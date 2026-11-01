package de.findesk.viewmodel

import de.findesk.document.ReportService
import de.findesk.model.AccountItem
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
import java.time.YearMonth
import java.util.concurrent.atomic.AtomicLong

data class ChartSlice(
    val label: String,
    val amount: BigDecimal,
    val color: String?,
    /** The category's id, or null (uncategorized); the dashboard passes it on when a chart row is clicked. */
    val categoryId: Int? = null,
)

data class MonthBar(
    val year: Int,
    val month: Int,
    val income: BigDecimal,
    val expenses: BigDecimal,
    /** The month is still running (today's month); its totals are so far, not final. */
    val partial: Boolean = false,
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
    /**
     * Money on checking, savings and credit-card accounts (a card's negative balance is money owed and reduces
     * it): what is available to spend, without the loans' balances that [totalBalance] adds.
     */
    val availableBalance: BigDecimal = BigDecimal.ZERO,
    /** Sum of the loan accounts' balances; only shown when [hasLoans] says there are loans at all. */
    val loanBalance: BigDecimal = BigDecimal.ZERO,
    /**
     * Money available at the end of the previous month, reconstructed from today's balances and the bookings after
     * that month. Null when the available accounts' booking history doesn't reach back before the selected month.
     */
    val carryForward: BigDecimal? = null,
    /**
     * What the available accounts moved outside income and expenses (transfers to loans or depots, interest on a
     * loan), so [carryForward] and the monthly figures add up to [availableBalance]. Null when [carryForward] is null.
     */
    val otherMovements: BigDecimal? = null,
    /** The day the available balance is stated for: a past month's last day; null in the current month. */
    val balanceDate: LocalDate? = null,
    /** Whether the accounts include loans, so the balance card can show what they do to [totalBalance]. */
    val hasLoans: Boolean = false,
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
    /** Money moved between the user's own accounts this month: excluded from income and spending above. */
    val transfersThisMonth: BigDecimal = BigDecimal.ZERO,
    /** The month the cards and charts show (never in the future); the balances and the lists below stay "now". */
    val selectedMonth: YearMonth = YearMonth.now(),
    /** The month of the oldest booking in the primary currency; the current month when there are none. */
    val earliestMonth: YearMonth = YearMonth.now(),
    /** The calendar month of today when the dashboard last loaded ([isCurrentMonth] compares against it). */
    val currentMonth: YearMonth = YearMonth.now(),
    val hasAccounts: Boolean = false,
    val isLoading: Boolean = false,
    val error: String? = null,
) {
    val pendingTasks: Int get() = uncategorizedCount + unlinkedDocumentCount
    val isCurrentMonth: Boolean get() = selectedMonth == currentMonth
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
    private val _state = MutableStateFlow(
        DashboardState(
            selectedMonth = YearMonth.from(today()),
            currentMonth = YearMonth.from(today()),
            earliestMonth = YearMonth.from(today()),
        ),
    )
    val state: StateFlow<DashboardState> = _state.asStateFlow()

    /** The latest started load; a load publishes only while its generation is still the latest. */
    private val loadGeneration = AtomicLong(0)

    fun loadDashboard(): Job {
        val generation = loadGeneration.incrementAndGet()
        // The month this load is for, before any suspension: a later click must not change what this one computes
        val requestedMonth = _state.value.selectedMonth
        _state.update { it.copy(isLoading = true, error = null) }
        return launchSafely({ e ->
            if (loadGeneration.get() == generation) _state.update { it.copy(isLoading = false, error = e.userMessage()) }
        }) {
            val now = today()
            val nowMonth = YearMonth.from(now)
            val currency = Currencies.primary(settingsRepository)
            // Never a future month: the selection is clamped here too, in case data moved between loads
            // A future-dated oldest booking must not push the range past today (coerceIn would throw)
            val earliestMonth = transactionRepository.oldestDate(currency)?.let(YearMonth::from)?.coerceAtMost(nowMonth) ?: nowMonth
            val month = requestedMonth.coerceAtMost(nowMonth).coerceAtLeast(earliestMonth)
            if (month != _state.value.selectedMonth && loadGeneration.get() == generation) {
                _state.update { it.copy(selectedMonth = month) }
            }
            val monthStart = month.atDay(1)
            val monthEnd = month.atEndOfMonth()
            val sixMonthsStart = monthStart.minusMonths(5)
            val yearStart = LocalDate.of(month.year, 1, 1)
            val accounts = accountRepository.findAllItems()
            // One query for every month-aware balance: from the selected month on, in every currency (the other
            // currencies' totals are reconstructed the same way). Pending bookings are not bookings and stay out.
            val monthBookings = transactionRepository.findItems(TransactionFilter(startDate = monthStart)).groupBy { it.accountId }
            val currentMonth = month == nowMonth

            // A depot's balance is a market value, not the sum of its bookings; the current month uses the bank's
            // balance as is (future-dated bookings included), so only a past month is reconstructed backwards.
            fun balanceAt(account: AccountItem): BigDecimal = when {
                account.accountType == DEPOT_TYPE || currentMonth -> account.balance
                else -> account.balance - monthBookings[account.id].orEmpty().filter { it.date.isAfter(monthEnd) }.sumOf { it.amount }
            }

            // The current month's movement includes future-dated bookings, matching the balance the bank reports.
            fun movement(account: AccountItem): BigDecimal = monthBookings[account.id].orEmpty()
                .filter { currentMonth || !it.date.isAfter(monthEnd) }
                .sumOf { it.amount }
            val balances = Currencies.totals(accounts, currency, { it.currency }, { balanceAt(it) })
            // Loans are money owed and depots are neither, so only the other account types are money available to spend
            val availableAccounts = accounts.filter { it.accountType != LOAN_TYPE && it.accountType != DEPOT_TYPE && it.currency == currency }
            val availableBalance = Currencies.totals(availableAccounts, currency, { it.currency }, { balanceAt(it) })[currency] ?: BigDecimal.ZERO
            val loanBalance = Currencies.totals(accounts.filter { it.accountType == LOAN_TYPE }, currency, { it.currency }, { balanceAt(it) })[currency] ?: BigDecimal.ZERO
            val recent = transactionRepository.findItems(limit = 8)
            // One query covering both the 6-month chart and the year-to-date tax chart
            val window = transactionRepository.findItems(
                TransactionFilter(startDate = minOf(sixMonthsStart, yearStart), endDate = monthEnd, currency = currency),
            )
            val thisMonth = window.filter { !it.date.isBefore(monthStart) && !it.date.isAfter(monthEnd) }
            val thisMonthExpenses = thisMonth.filter { it.countsAsSpending }
            val monthlyIncome = thisMonth.filter { it.countsAsIncome }.sumOf { it.amount }
            val monthlyExpenses = thisMonthExpenses.sumOf { it.amount.abs() }

            // The carry-forward is only reliable when the booking history reaches back before the month: otherwise
            // money from earlier bookings is missing from today's balance and would look like a movement.
            val oldestByAccount = transactionRepository.oldestDateByAccount()
            val carryForwardKnown = availableAccounts.all { account ->
                oldestByAccount[account.id]?.isBefore(monthStart) ?: true
            }
            val carryForward = if (carryForwardKnown) availableAccounts.sumOf { balanceAt(it) - movement(it) } else null
            // By construction carryForward + monthlyIncome - monthlyExpenses + otherMovements == availableBalance
            val otherMovements = carryForward?.let { availableAccounts.sumOf { movement(it) } - (monthlyIncome - monthlyExpenses) }

            val lastMonths = (0 until 6).map { i ->
                val m = sixMonthsStart.plusMonths(i.toLong())
                val txns = window.filter { it.date.year == m.year && it.date.monthValue == m.monthValue }
                MonthBar(
                    year = m.year,
                    month = m.monthValue,
                    income = txns.filter { it.countsAsIncome }.sumOf { it.amount },
                    expenses = txns.filter { it.countsAsSpending }.sumOf { it.amount.abs() },
                    // Only the running month is so far: a past selected month's window can never include it
                    partial = m.year == nowMonth.year && m.monthValue == nowMonth.monthValue,
                )
            }

            // A load that is no longer the latest drops its whole result, including its month and figures
            if (loadGeneration.get() != generation) return@launchSafely
            _state.value = DashboardState(
                currency = currency,
                totalBalance = balances[currency] ?: BigDecimal.ZERO,
                availableBalance = availableBalance,
                carryForward = carryForward,
                otherMovements = otherMovements,
                balanceDate = if (currentMonth) null else monthEnd,
                loanBalance = loanBalance,
                hasLoans = accounts.any { it.accountType == LOAN_TYPE },
                otherBalances = balances - currency,
                monthlyIncome = monthlyIncome,
                monthlyExpenses = monthlyExpenses,
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
                transfersThisMonth = thisMonth
                    .filter { it.isTransfer && it.amount.signum() < 0 }
                    .sumOf { it.amount.abs() },
                monthProgress = if (month == nowMonth) now.dayOfMonth.toFloat() / now.lengthOfMonth() else 1f,
                upcoming = de.findesk.document.RecurringDetector.upcoming(
                    de.findesk.document.RecurringDetector.detect(transactionRepository.findItems(TransactionFilter(startDate = now.minusDays(400), endDate = now)), now),
                    now,
                ),
                selectedMonth = month,
                currentMonth = nowMonth,
                earliestMonth = earliestMonth,
                hasAccounts = accounts.isNotEmpty(),
                isLoading = false,
            )
        }
    }

    /**
     * Selects [month] and fully reloads. Only the future is cut off here: the oldest month comes from the data,
     * which [loadDashboard] reads and clamps against. Clamping to the state's [DashboardState.earliestMonth] here
     * turned a month selected before the first load (the dashboard reopened by Back) into the current month,
     * because that field still holds its initial value then.
     */
    fun selectMonth(month: YearMonth): Job {
        val nowMonth = YearMonth.from(today())
        _state.update { it.copy(selectedMonth = month.coerceAtMost(nowMonth)) }
        return loadDashboard()
    }

    fun previousMonth(): Job = selectMonth(_state.value.selectedMonth.minusMonths(1))

    fun nextMonth(): Job = selectMonth(_state.value.selectedMonth.plusMonths(1))

    fun currentMonth(): Job = selectMonth(YearMonth.from(today()))

    private fun slices(expenses: List<TransactionItem>): List<ChartSlice> = expenses
        .groupBy { it.categoryName ?: ReportService.UNCATEGORIZED }
        .map { (name, txns) ->
            val color = if (txns.first().categoryId == null) UNCATEGORIZED_COLOR else txns.first().categoryColor
            ChartSlice(name, txns.sumOf { it.amount.abs() }, color, txns.first().categoryId)
        }
        .sortedByDescending { it.amount }

    companion object {
        /**
         * Neutral grey for bookings without a category, as their badge is grey. Left to the palette fallback, it took
         * whatever palette colour its label hashed to, e.g. the same purple as a "Donations" category.
         */
        const val UNCATEGORIZED_COLOR = "#757575"

        // Loans are money owed and depots money invested: every other account type counts as available, so an
        // account of an unexpected type still shows up somewhere
        private const val LOAN_TYPE = "loan"

        private const val DEPOT_TYPE = "depot"
    }
}
