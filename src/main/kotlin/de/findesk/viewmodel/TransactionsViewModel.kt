package de.findesk.viewmodel

import de.findesk.banking.FinTSClient
import de.findesk.document.ReportService
import de.findesk.i18n.AppStrings
import de.findesk.i18n.StringKey
import de.findesk.importer.CsvMapping
import de.findesk.importer.ParsedStatement
import de.findesk.importer.StatementImportService
import de.findesk.model.AccountItem
import de.findesk.model.CategoryItem
import de.findesk.model.Direction
import de.findesk.model.TransactionFilter
import de.findesk.model.TransactionItem
import de.findesk.repository.*
import de.findesk.util.Currencies
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
    /** The file's balance may replace the account balance (file not older than the account's newest booking). */
    val balanceUpdateAllowed: Boolean = false,
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
    /** Per currency: the list may mix accounts in different currencies, whose amounts can't be added. */
    val totalIncome: Map<String, BigDecimal> = emptyMap(),
    val totalExpenses: Map<String, BigDecimal> = emptyMap(),
    val importPreview: ImportPreview? = null,
    val minAmount: BigDecimal? = null,
    val maxAmount: BigDecimal? = null,
    val selected: TransactionItem? = null,
    val selectedDocuments: List<de.findesk.model.DocumentItem> = emptyList(),
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
    private val documentService: de.findesk.document.DocumentService = de.findesk.document.DocumentService(),
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
            minAmount = s.minAmount,
            maxAmount = s.maxAmount,
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
        // Keep the open detail panel in sync with edits
        _state.value.selected?.let { sel -> _state.update { it.copy(selected = transactions.firstOrNull { t -> t.id == sel.id } ?: sel) } }
        val categories = categoryRepository.findAllItems()
        val accounts = accountRepository.findAllItems()
        _state.update {
            it.copy(
                transactions = transactions,
                categories = categories,
                accounts = accounts,
                totalIncome = Currencies.totals(transactions.filter { t -> t.amount.signum() > 0 }, Currencies.DEFAULT, { t -> t.currency }, { t -> t.amount }),
                totalExpenses = Currencies.totals(transactions.filter { t -> t.isExpense }, Currencies.DEFAULT, { t -> t.currency }, { t -> t.amount.abs() }),
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

    fun updateAmountRange(min: BigDecimal?, max: BigDecimal?): Job {
        _state.update { it.copy(minAmount = min, maxAmount = max) }
        return loadTransactions()
    }

    /** Opens the detail panel with the transaction's documents. */
    fun selectTransaction(txn: TransactionItem?): Job {
        _state.update { it.copy(selected = txn, selectedDocuments = emptyList()) }
        if (txn == null) return Job().apply { complete() }
        return launchSafely(onError) {
            val docs = documentService.getDocumentsForTransaction(txn.id)
            _state.update { if (it.selected?.id == txn.id) it.copy(selectedDocuments = docs) else it }
        }
    }

    fun attachDocuments(transactionId: Int, files: List<File>): Job = launchSafely(onError) {
        val failures = mutableListOf<String>()
        files.forEach { f ->
            try {
                documentService.uploadDocument(f, transactionId)
            } catch (e: Exception) {
                failures += "${f.name}: ${e.userMessage()}"
            }
        }
        val docs = documentService.getDocumentsForTransaction(transactionId)
        _state.update { it.copy(selectedDocuments = docs, error = failures.takeIf { f -> f.isNotEmpty() }?.joinToString("\n")) }
        refresh()
    }

    /**
     * Suggested rule pattern for a transaction: the first words of the partner name, regex-escaped, without
     * reference numbers ("NETFLIX.COM 1234" -> "NETFLIX\\.COM") so the rule also matches future bookings.
     */
    fun rulePatternFor(txn: TransactionItem): String = (txn.partnerName ?: txn.purpose ?: "").trim()
        .split(Regex("\\s+"))
        .filter { w -> w.isNotEmpty() && w.count(Char::isDigit) < 3 }
        .take(3)
        .joinToString(" ")
        .replace(Regex("[\\\\.^$|?*+()\\[\\]{}]")) { "\\" + it.value }

    /** Creates an auto-categorization rule and applies it to all uncategorized transactions. */
    fun createRule(pattern: String, categoryId: Int): Job = launchSafely(onError) {
        ruleRepository.create(pattern, categoryId, priority = 5)
        val uncategorized = transactionRepository.findItems(TransactionFilter(uncategorizedOnly = true))
        val matcher = RuleMatcher(listOf(pattern to categoryId))
        var applied = 0
        uncategorized.forEach { t ->
            if (matcher.categoryFor(t.purpose, t.partnerName) != null) {
                transactionRepository.updateCategory(t.id, categoryId)
                applied++
            }
        }
        auditLogRepository.log("SAVE_RULE", "user", "Rule '$pattern' created from a transaction, applied to $applied")
        refresh()
        _state.update { it.copy(message = AppStrings.format(StringKey.MessageRuleCreated, applied)) }
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
        require(amount.signum() != 0) { AppStrings[StringKey.ErrorAmountNotZero] }
        // The booking is in the account's currency; defaulting to EUR would mix currencies.
        val account = accountRepository.findAllItems().firstOrNull { it.id == accountId }
            ?: throw IllegalArgumentException("Account $accountId not found")
        val partner = partnerName?.trim()?.ifEmpty { null }
        val text = purpose?.trim()?.ifEmpty { null }
        // Guards against entering the same booking twice on this account, whatever its source; the same booking on
        // another account is a different one
        val duplicate = transactionRepository.findItems(TransactionFilter(startDate = date, endDate = date, accountId = accountId))
            .any { it.amount.compareTo(amount) == 0 && it.partnerName == partner && it.purpose == text }
        if (duplicate) {
            _state.update { it.copy(error = AppStrings[StringKey.ErrorDuplicateTransaction]) }
            return@launchSafely
        }
        val hash = FinTSClient.transactionHash(date, amount, partner, "${text.orEmpty()}#${account.iban}#manual")
        val resolvedCategory = categoryId ?: ruleRepository.matchCategoryId(purpose, partnerName)
        transactionRepository.create(
            accountId = accountId,
            date = date,
            amount = amount,
            currency = account.currency,
            partnerName = partner,
            purpose = text,
            categoryId = resolvedCategory,
            hash = hash,
        )
        auditLogRepository.log("ADD_TRANSACTION", "user", "Added manual transaction on account $accountId")
        refresh()
        _state.update { it.copy(message = AppStrings[StringKey.MessageTransactionAdded]) }
    }

    fun deleteTransaction(transactionId: Int): Job = launchSafely(onError) {
        transactionRepository.delete(transactionId)
        auditLogRepository.log("DELETE_TRANSACTION", "user", "Deleted transaction $transactionId")
        refresh()
    }

    fun autoCategorizeTransactions(): Job = launchSafely(onError) {
        val uncategorized = transactionRepository.findItems(TransactionFilter(uncategorizedOnly = true))
        var updated = 0
        val rules = ruleRepository.matcher()
        uncategorized.forEach { txn ->
            val matched = rules.categoryFor(txn.purpose, txn.partnerName)
            if (matched != null) {
                transactionRepository.updateCategory(txn.id, matched)
                updated++
            }
        }
        auditLogRepository.log("AUTO_CATEGORIZE", "user", "Auto-categorized $updated of ${uncategorized.size} transactions")
        refresh()
        _state.update { it.copy(message = AppStrings.format(StringKey.MessageAutoCategorized, updated, uncategorized.size)) }
    }

    /** Exports exactly what is currently shown. */
    fun exportCsv(file: File): Job = launchSafely(onError) {
        val ok = reportService.exportToCsv(_state.value.transactions, file)
        _state.update {
            if (ok) it.copy(message = AppStrings.format(StringKey.MessageExportedCount, it.transactions.size, file.name)) else it.copy(error = AppStrings[StringKey.ErrorExportFailed])
        }
    }

    /** Reads a statement file and opens the import preview (nothing is stored yet). */
    fun openImport(file: File, mapping: CsvMapping? = null): Job = launchSafely(onError) {
        val statement = importService.read(file, mapping)
        val accountId = importService.matchAccount(statement) ?: _state.value.importPreview?.accountId
            ?: _state.value.accountId ?: _state.value.accounts.singleOrNull()?.id
        val allowed = accountId?.let { importService.balanceUpdateAllowed(it, statement) } ?: false
        _state.update { it.copy(importPreview = ImportPreview(file, statement, accountId, allowed), error = null) }
    }

    /** Re-reads the CSV with a user-adjusted column mapping. */
    fun remapImport(mapping: CsvMapping): Job = _state.value.importPreview?.let { openImport(it.file, mapping) } ?: Job().apply { complete() }

    fun selectImportAccount(accountId: Int): Job {
        _state.update { s -> s.copy(importPreview = s.importPreview?.copy(accountId = accountId, balanceUpdateAllowed = false)) }
        return launchSafely(onError) {
            val preview = _state.value.importPreview ?: return@launchSafely
            val allowed = importService.balanceUpdateAllowed(accountId, preview.statement)
            _state.update { s -> s.copy(importPreview = s.importPreview?.takeIf { it.accountId == accountId }?.copy(balanceUpdateAllowed = allowed) ?: s.importPreview) }
        }
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
