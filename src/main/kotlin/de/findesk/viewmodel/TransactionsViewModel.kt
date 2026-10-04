package de.findesk.viewmodel

import de.findesk.banking.FinTSClient
import de.findesk.document.ReportService
import de.findesk.importer.CsvMapping
import de.findesk.importer.ParsedStatement
import de.findesk.importer.StatementImportService
import de.findesk.model.AccountItem
import de.findesk.model.CategoryItem
import de.findesk.model.Direction
import de.findesk.model.TransactionFilter
import de.findesk.model.TransactionItem
import de.findesk.repository.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.math.BigDecimal
import java.time.LocalDate

enum class Period(val label: String) {
    THIS_MONTH("This month"),
    LAST_MONTH("Last month"),
    LAST_3_MONTHS("Last 3 months"),
    THIS_YEAR("This year"),
    LAST_YEAR("Last year"),
    ALL("All time"),
    ;

    /** Inclusive date range, or nulls for "all time". */
    fun range(today: LocalDate): Pair<LocalDate?, LocalDate?> {
        val monthStart = today.withDayOfMonth(1)
        return when (this) {
            THIS_MONTH -> monthStart to monthStart.plusMonths(1).minusDays(1)
            LAST_MONTH -> monthStart.minusMonths(1) to monthStart.minusDays(1)
            LAST_3_MONTHS -> monthStart.minusMonths(2) to monthStart.plusMonths(1).minusDays(1)
            THIS_YEAR -> today.withDayOfYear(1) to LocalDate.of(today.year, 12, 31)
            LAST_YEAR -> LocalDate.of(today.year - 1, 1, 1) to LocalDate.of(today.year - 1, 12, 31)
            ALL -> null to null
        }
    }
}

/** Category filter: everything, only uncategorized, or one category id. */
sealed interface CategoryFilter {
    data object Any : CategoryFilter
    data object None : CategoryFilter
    data class Only(val categoryId: Int) : CategoryFilter
}

/** A statement file read and waiting for the user to pick the account and confirm. */
data class ImportPreview(
    val file: File,
    val statement: ParsedStatement,
    val accountId: Int?,
)

data class TransactionsState(
    val transactions: List<TransactionItem> = emptyList(),
    val categories: List<CategoryItem> = emptyList(),
    val accounts: List<AccountItem> = emptyList(),
    val searchQuery: String = "",
    val period: Period = Period.THIS_MONTH,
    val direction: Direction = Direction.ALL,
    val categoryFilter: CategoryFilter = CategoryFilter.Any,
    val accountId: Int? = null,
    val totalIncome: BigDecimal = BigDecimal.ZERO,
    val totalExpenses: BigDecimal = BigDecimal.ZERO,
    val importPreview: ImportPreview? = null,
    val isImporting: Boolean = false,
    val isLoading: Boolean = false,
    val error: String? = null,
    val message: String? = null,
)

class TransactionsViewModel(
    private val transactionRepository: TransactionRepository = TransactionRepositoryImpl(),
    private val categoryRepository: CategoryRepository = CategoryRepositoryImpl(),
    private val accountRepository: AccountRepository = AccountRepositoryImpl(),
    private val ruleRepository: RuleRepository = RuleRepositoryImpl(),
    private val auditLogRepository: AuditLogRepository = AuditLogRepositoryImpl(),
    private val reportService: ReportService = ReportService(),
    private val importService: StatementImportService = StatementImportService(),
    private val today: () -> LocalDate = { LocalDate.now() },
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) : BaseViewModel(scope) {
    private val _state = MutableStateFlow(TransactionsState())
    val state: StateFlow<TransactionsState> = _state.asStateFlow()

    private val onError: (Exception) -> Unit = { e -> _state.update { it.copy(isLoading = false, error = e.userMessage()) } }
    private var searchJob: Job? = null

    fun currentFilter(s: TransactionsState = _state.value): TransactionFilter {
        val (start, end) = s.period.range(today())
        return TransactionFilter(
            startDate = start,
            endDate = end,
            accountId = s.accountId,
            categoryId = (s.categoryFilter as? CategoryFilter.Only)?.categoryId,
            uncategorizedOnly = s.categoryFilter == CategoryFilter.None,
            direction = s.direction,
            text = s.searchQuery,
        )
    }

    /** Applies navigation context (e.g. "transactions of this account") and loads. */
    fun start(accountId: Int? = null, uncategorizedOnly: Boolean = false): Job {
        _state.update {
            it.copy(
                accountId = accountId,
                categoryFilter = if (uncategorizedOnly) CategoryFilter.None else CategoryFilter.Any,
                // A deep link into an account or the uncategorized list shouldn't be limited to this month
                period = if (accountId != null || uncategorizedOnly) Period.ALL else it.period,
            )
        }
        return loadTransactions()
    }

    fun loadTransactions(): Job {
        _state.update { it.copy(isLoading = true, error = null) }
        return launchSafely(onError) { refresh() }
    }

    private fun refresh() {
        val transactions = transactionRepository.findItems(currentFilter())
        val categories = categoryRepository.findAllItems()
        val accounts = accountRepository.findAllItems()
        _state.update {
            it.copy(
                transactions = transactions,
                categories = categories,
                accounts = accounts,
                totalIncome = transactions.filter { t -> t.amount.signum() > 0 }.sumOf { t -> t.amount },
                totalExpenses = transactions.filter { t -> t.isExpense }.sumOf { t -> t.amount.abs() },
                isLoading = false,
            )
        }
    }

    /** Debounced so typing doesn't fire a query per keystroke. */
    fun updateSearchQuery(query: String): Job {
        _state.update { it.copy(searchQuery = query) }
        searchJob?.cancel()
        return launchSafely(onError) {
            delay(SEARCH_DEBOUNCE_MS)
            refresh()
        }.also { searchJob = it }
    }

    fun updatePeriod(period: Period): Job {
        _state.update { it.copy(period = period) }
        return loadTransactions()
    }

    fun updateDirection(direction: Direction): Job {
        _state.update { it.copy(direction = direction) }
        return loadTransactions()
    }

    fun updateCategoryFilter(filter: CategoryFilter): Job {
        _state.update { it.copy(categoryFilter = filter) }
        return loadTransactions()
    }

    fun updateAccountFilter(accountId: Int?): Job {
        _state.update { it.copy(accountId = accountId) }
        return loadTransactions()
    }

    fun updateTransactionCategory(transactionId: Int, categoryId: Int?): Job = launchSafely(onError) {
        transactionRepository.updateCategory(transactionId, categoryId)
        auditLogRepository.log("UPDATE_CATEGORY", "user", "Updated transaction $transactionId category")
        refresh()
    }

    fun addManualTransaction(
        accountId: Int,
        date: LocalDate,
        amount: BigDecimal,
        partnerName: String?,
        purpose: String?,
        categoryId: Int?,
    ): Job = launchSafely(onError) {
        require(amount.signum() != 0) { "Amount must not be zero" }
        val hash = FinTSClient.transactionHash(date, amount, partnerName, purpose)
        if (transactionRepository.findByHash(hash) != null) {
            _state.update { it.copy(error = "An identical transaction already exists") }
            return@launchSafely
        }
        val resolvedCategory = categoryId ?: ruleRepository.matchTransaction(purpose, partnerName)?.id?.value
        transactionRepository.create(
            accountId = accountId,
            date = date,
            amount = amount,
            partnerName = partnerName?.trim()?.ifEmpty { null },
            purpose = purpose?.trim()?.ifEmpty { null },
            categoryId = resolvedCategory,
            hash = hash,
        )
        auditLogRepository.log("ADD_TRANSACTION", "user", "Added manual transaction on account $accountId")
        refresh()
        _state.update { it.copy(message = "Transaction added") }
    }

    fun deleteTransaction(transactionId: Int): Job = launchSafely(onError) {
        transactionRepository.delete(transactionId)
        auditLogRepository.log("DELETE_TRANSACTION", "user", "Deleted transaction $transactionId")
        refresh()
    }

    fun autoCategorizeTransactions(): Job = launchSafely(onError) {
        val uncategorized = transactionRepository.findItems(TransactionFilter(uncategorizedOnly = true))
        var updated = 0
        uncategorized.forEach { txn ->
            val matched = ruleRepository.matchTransaction(txn.purpose, txn.partnerName)
            if (matched != null) {
                transactionRepository.updateCategory(txn.id, matched.id.value)
                updated++
            }
        }
        auditLogRepository.log("AUTO_CATEGORIZE", "user", "Auto-categorized $updated of ${uncategorized.size} transactions")
        refresh()
        _state.update { it.copy(message = "Categorized $updated of ${uncategorized.size} uncategorized transactions") }
    }

    /** Exports exactly what is currently shown. */
    fun exportCsv(file: File): Job = launchSafely(onError) {
        val ok = reportService.exportToCsv(_state.value.transactions, file)
        _state.update {
            if (ok) it.copy(message = "Exported ${it.transactions.size} transactions to ${file.name}") else it.copy(error = "Export failed")
        }
    }

    /** Reads a statement file and opens the import preview (nothing is stored yet). */
    fun openImport(file: File, mapping: CsvMapping? = null): Job = launchSafely(onError) {
        val statement = importService.read(file, mapping)
        val accountId = importService.matchAccount(statement) ?: _state.value.importPreview?.accountId
            ?: _state.value.accountId ?: _state.value.accounts.singleOrNull()?.id
        _state.update { it.copy(importPreview = ImportPreview(file, statement, accountId), error = null) }
    }

    /** Re-reads the CSV with a user-adjusted column mapping. */
    fun remapImport(mapping: CsvMapping): Job = _state.value.importPreview?.let { openImport(it.file, mapping) } ?: Job().apply { complete() }

    fun selectImportAccount(accountId: Int) {
        _state.update { s -> s.copy(importPreview = s.importPreview?.copy(accountId = accountId)) }
    }

    fun cancelImport() {
        _state.update { it.copy(importPreview = null) }
    }

    fun confirmImport(updateBalance: Boolean): Job {
        val preview = _state.value.importPreview ?: return Job().apply { complete() }
        val accountId = preview.accountId ?: return Job().apply { complete() }
        _state.update { it.copy(isImporting = true) }
        return launchSafely({ e -> _state.update { it.copy(isImporting = false, error = e.userMessage()) } }) {
            val report = importService.import(accountId, preview.statement, updateBalance)
            // Show everything that was imported, not just this month
            _state.update { it.copy(importPreview = null, isImporting = false, period = Period.ALL, accountId = accountId, message = report.summary()) }
            refresh()
        }
    }

    fun clearMessages() {
        _state.update { it.copy(error = null, message = null) }
    }

    companion object {
        const val SEARCH_DEBOUNCE_MS = 250L
    }
}
