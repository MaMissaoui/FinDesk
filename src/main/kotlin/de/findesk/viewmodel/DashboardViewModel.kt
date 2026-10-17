package de.findesk.viewmodel

import de.findesk.document.ReportService
import de.findesk.model.TransactionFilter
import de.findesk.model.TransactionItem
import de.findesk.repository.*
import de.findesk.util.Currencies
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.math.BigDecimal
import java.time.LocalDate

data class ChartSlice(
    val label: String,
    val amount: BigDecimal,
    val color: String?,
)

data class MonthBar(
    val year: Int,
    val month: Int,
    val income: BigDecimal,
    val expenses: BigDecimal,
)

data class BudgetStatus(
    val categoryId: Int,
    val categoryName: String,
    val color: String?,
    val limit: BigDecimal,
    val spent: BigDecimal,
) {
    val ratio: Float get() = if (limit.signum() > 0) spent.toFloat() / limit.toFloat() else 0f
    val remaining: BigDecimal get() = limit - spent
    val isOver: Boolean get() = spent > limit
}

data class DashboardState(
    /** Currency of every total and chart below; other currencies are only listed in [otherBalances]. */
    val currency: String = Currencies.DEFAULT,
    val totalBalance: BigDecimal = BigDecimal.ZERO,
    /** Balances of accounts in other currencies, per currency (never added to [totalBalance]). */
    val otherBalances: Map<String, BigDecimal> = emptyMap(),
    val monthlyIncome: BigDecimal = BigDecimal.ZERO,
    val monthlyExpenses: BigDecimal = BigDecimal.ZERO,
    val uncategorizedCount: Int = 0,
    val unlinkedDocumentCount: Int = 0,
    val recentTransactions: List<TransactionItem> = emptyList(),
    val expensesByCategory: List<ChartSlice> = emptyList(),
    val taxDeductionsByCategory: List<ChartSlice> = emptyList(),
    val lastMonths: List<MonthBar> = emptyList(),
    val budgets: List<BudgetStatus> = emptyList(),
    /** Recurring bookings expected in the next 30 days (or slightly overdue). */
    val upcoming: List<de.findesk.document.RecurringDetector.RecurringPayment> = emptyList(),
    /** How far through the month we are (0..1), to judge budget pace. */
    val monthProgress: Float = 0f,
    val hasAccounts: Boolean = false,
    val isLoading: Boolean = false,
    val error: String? = null,
) {
    val pendingTasks: Int get() = uncategorizedCount + unlinkedDocumentCount
}

class DashboardViewModel(
    private val accountRepository: AccountRepository = AccountRepositoryImpl(),
    private val transactionRepository: TransactionRepository = TransactionRepositoryImpl(),
    private val documentRepository: DocumentRepository = DocumentRepositoryImpl(),
    private val categoryRepository: CategoryRepository = CategoryRepositoryImpl(),
    private val settingsRepository: SettingsRepository = SettingsRepositoryImpl(),
    private val today: () -> LocalDate = { LocalDate.now() },
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) : BaseViewModel(scope) {
    private val _state = MutableStateFlow(DashboardState())
    val state: StateFlow<DashboardState> = _state.asStateFlow()

    fun loadDashboard(): Job {
        _state.update { it.copy(isLoading = true, error = null) }
        return launchSafely({ e -> _state.update { it.copy(isLoading = false, error = e.userMessage()) } }) {
            val now = today()
            val monthStart = now.withDayOfMonth(1)
            val monthEnd = monthStart.plusMonths(1).minusDays(1)
            val sixMonthsStart = monthStart.minusMonths(5)
            val yearStart = now.withDayOfYear(1)

            val currency = Currencies.primary(settingsRepository)
            val accounts = accountRepository.findAllItems()
            val balances = Currencies.totals(accounts, currency, { it.currency }, { it.balance })
            val recent = transactionRepository.findItems(limit = 8)
            // One query covering both the 6-month chart and the year-to-date tax chart
            val window = transactionRepository.findItems(
                TransactionFilter(startDate = minOf(sixMonthsStart, yearStart), endDate = monthEnd, currency = currency),
            )
            val thisMonth = window.filter { !it.date.isBefore(monthStart) && !it.date.isAfter(monthEnd) }
            val thisMonthExpenses = thisMonth.filter { it.isExpense }

            val lastMonths = (0 until 6).map { i ->
                val m = sixMonthsStart.plusMonths(i.toLong())
                val txns = window.filter { it.date.year == m.year && it.date.monthValue == m.monthValue }
                MonthBar(
                    year = m.year,
                    month = m.monthValue,
                    income = txns.filter { it.amount.signum() > 0 }.sumOf { it.amount },
                    expenses = txns.filter { it.isExpense }.sumOf { it.amount.abs() },
                )
            }

            _state.value = DashboardState(
                currency = currency,
                totalBalance = balances[currency] ?: BigDecimal.ZERO,
                otherBalances = balances - currency,
                monthlyIncome = thisMonth.filter { it.amount.signum() > 0 }.sumOf { it.amount },
                monthlyExpenses = thisMonthExpenses.sumOf { it.amount.abs() },
                // All currencies: the card opens the uncategorized list, which isn't limited to one currency
                uncategorizedCount = transactionRepository.findItems(TransactionFilter(startDate = monthStart, endDate = monthEnd, uncategorizedOnly = true)).size,
                unlinkedDocumentCount = documentRepository.findItems(unlinkedOnly = true).size,
                recentTransactions = recent,
                expensesByCategory = slices(thisMonthExpenses),
                taxDeductionsByCategory = slices(window.filter { it.isExpense && it.isTaxRelevant && !it.date.isBefore(yearStart) }),
                lastMonths = lastMonths,
                budgets = categoryRepository.findAllItems().mapNotNull { c ->
                    val limit = c.budgetLimit?.takeIf { it.signum() > 0 } ?: return@mapNotNull null
                    BudgetStatus(c.id, c.name, c.color, limit, thisMonthExpenses.filter { it.categoryId == c.id }.sumOf { it.amount.abs() })
                }.sortedByDescending { it.ratio },
                monthProgress = now.dayOfMonth.toFloat() / now.lengthOfMonth(),
                upcoming = de.findesk.document.RecurringDetector.upcoming(
                    de.findesk.document.RecurringDetector.detect(transactionRepository.findItems(TransactionFilter(startDate = now.minusDays(400), endDate = now)), now),
                    now,
                ),
                hasAccounts = accounts.isNotEmpty(),
                isLoading = false,
            )
        }
    }

    private fun slices(expenses: List<TransactionItem>): List<ChartSlice> = expenses
        .groupBy { it.categoryName ?: ReportService.UNCATEGORIZED }
        .map { (name, txns) ->
            val color = if (txns.first().categoryId == null) UNCATEGORIZED_COLOR else txns.first().categoryColor
            ChartSlice(name, txns.sumOf { it.amount.abs() }, color)
        }
        .sortedByDescending { it.amount }

    companion object {
        /**
         * Neutral grey for bookings without a category, as their badge is grey. Left to the palette fallback, it took
         * whatever palette colour its label hashed to, e.g. the same purple as a "Donations" category.
         */
        const val UNCATEGORIZED_COLOR = "#757575"
    }
}
