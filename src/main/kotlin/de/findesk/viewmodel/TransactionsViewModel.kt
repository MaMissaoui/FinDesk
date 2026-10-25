package de.findesk.viewmodel

import de.findesk.banking.FinTSClient
import de.findesk.document.ReportService
import de.findesk.i18n.AppStrings
import de.findesk.i18n.StringKey
import de.findesk.importer.CsvMapping
import de.findesk.importer.ParsedStatement
import de.findesk.importer.StatementImportService
import de.findesk.model.AccountGroupItem
import de.findesk.model.AccountItem
import de.findesk.model.CategoryItem
import de.findesk.model.Direction
import de.findesk.model.TransactionFilter
import de.findesk.model.TransactionItem
import de.findesk.repository.*
import de.findesk.util.AccountGroupView
import de.findesk.util.BalanceHistory
import de.findesk.util.Currencies
import de.findesk.util.GroupKey
import de.findesk.util.Iban
import de.findesk.util.RunningBalance
import de.findesk.util.grandTotal
import de.findesk.util.group
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
import java.time.YearMonth

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

/** What the account panel filters the booking list by. */
sealed interface AccountSelection {
    data object All : AccountSelection

    /** One built-in or custom group; its accounts become an `accountIds` filter. */
    data class Group(val key: GroupKey) : AccountSelection

    data class Account(val id: Int) : AccountSelection
}

/** One selected statement file that was read successfully. */
data class ImportEntry(val file: File, val statement: ParsedStatement)

/** A selected file that could not be read, with the reason shown to the user. */
data class ImportFailure(val fileName: String, val message: String)

/** The selected statement files, read and waiting for the user to pick the account and confirm. */
data class ImportPreview(
    /** Every selected file, in selection order: a column remap re-reads all of them. */
    val files: List<File>,
    /** The readable files in import order (see [TransactionsViewModel.openImport]). Never empty. */
    val entries: List<ImportEntry>,
    val failures: List<ImportFailure> = emptyList(),
    val accountId: Int?,
    /** The newest entry's balance may replace the account balance (same rule as before, for that entry). */
    val balanceUpdateAllowed: Boolean = false,
) {
    val newest: ImportEntry get() = entries.last()
    val bookingCount: Int get() = entries.sumOf { it.statement.bookings.size }
}

/** One booking the rules would recategorize (see [TransactionsViewModel.previewReapplyRules]). */
data class ReapplyChange(
    val transactionId: Int,
    /** The booking's category at preview time; null when it was uncategorized. */
    val fromCategoryId: Int?,
    /** The category the highest-priority matching rule assigns. */
    val toCategoryId: Int,
)

/** All bookings a re-apply would move from one category to another: one checkbox row of the preview dialog. */
data class ReapplyGroup(
    val fromCategoryId: Int?,
    val fromName: String,
    val toCategoryId: Int,
    val toName: String,
    val count: Int,
    /** Sum of the bookings' amounts in [currency], but null when they do not all use the primary currency. */
    val sum: BigDecimal?,
    val currency: String,
)

/** The exact change list a re-apply preview computed; [TransactionsViewModel.confirmReapplyRules] applies it as is. */
data class ReapplyPreview(
    val changes: List<ReapplyChange>,
    val groups: List<ReapplyGroup>,
    /** The bookings the preview checked (exactly what the list showed at action time). */
    val shownCount: Int,
)

data class TransactionsState(
    val transactions: List<TransactionItem> = emptyList(),
    val categories: List<CategoryItem> = emptyList(),
    val accounts: List<AccountItem> = emptyList(),
    val searchQuery: String = "",
    val period: Period = Period.THIS_MONTH,
    /** One exact month (e.g. navigated from the dashboard's past-month category): wins over [period]. */
    val month: YearMonth? = null,
    /** One arbitrary From/To range (period menu's "Custom range…"), inclusive: after an exact [month] but before [period]. */
    val customRange: Pair<LocalDate, LocalDate>? = null,
    val direction: Direction = Direction.ALL,
    val categoryFilter: CategoryFilter = CategoryFilter.Any,
    /** Derived from [selection] (the selected single account), kept for the running balance, import and CSV export. */
    val accountId: Int? = null,
    /** What the account panel filters by. */
    val selection: AccountSelection = AccountSelection.All,
    /** The account panel's groups, in display order. */
    val groups: List<AccountGroupView> = emptyList(),
    /** The grand total of every account, per currency, primary first. */
    val grandTotal: Map<String, BigDecimal> = emptyMap(),
    /** Ids of the collapsed groups (see [GroupKey.id]). */
    val collapsed: Set<String> = emptySet(),
    /** The ids rendered bold: bookings a sync brought in, kept until the selection changes. */
    val boldIds: Set<Int> = emptySet(),
    /** Free-text filter over the panel's account rows. */
    val accountFilterText: String = "",
    /** The custom groups as loaded, for rename, move and delete. */
    val customGroups: List<AccountGroupItem> = emptyList(),
    /** Balance after each shown booking's id, when one non-depot account is selected; null hides the column. */
    val balances: Map<Int, RunningBalance.Entry>? = null,
    /** The selected account's balance per day over the shown period, oldest first; null hides the chart. */
    val balanceChart: List<Pair<LocalDate, BigDecimal>>? = null,
    /** Whether the balance-chart card's body is folded away; persisted per profile, view state only. */
    val chartCollapsed: Boolean = false,
    /** The selected account's balance and currency, for the header. */
    val currentBalance: BigDecimal? = null,
    val balanceCurrency: String? = null,
    /** Per currency: the list may mix accounts in different currencies, whose amounts can't be added. */
    val totalIncome: Map<String, BigDecimal> = emptyMap(),
    val totalExpenses: Map<String, BigDecimal> = emptyMap(),
    val importPreview: ImportPreview? = null,
    val minAmount: BigDecimal? = null,
    val maxAmount: BigDecimal? = null,
    val selected: TransactionItem? = null,
    val selectedDocuments: List<de.findesk.model.DocumentItem> = emptyList(),
    /** A manual entry identical to one already booked that day, waiting for the user to confirm it (see [TransactionsViewModel.confirmDuplicate]). */
    val pendingDuplicate: ManualEntry? = null,
    val isImporting: Boolean = false,
    /** The rules' preview of the shown bookings, waiting for the user's confirmation in the dialog. */
    val reapplyPreview: ReapplyPreview? = null,
    /** Whether [TransactionsViewModel.undoReapplyRules] still has the last re-apply's previous categories (memory only). */
    val canUndoReapply: Boolean = false,
    /** Ids of the bookings the user selected for a batch delete; only bookings the shown list can hold. */
    val selectedIds: Set<Int> = emptySet(),
    /** The last row toggled or clicked with a modifier: the start of the next Shift range. */
    val selectionAnchorId: Int? = null,
    val isLoading: Boolean = false,
    val error: String? = null,
    val message: String? = null,
) {
    /** The selected bookings among the currently shown list, in list order. */
    val selectedShown: List<TransactionItem> get() = transactions.filter { it.id in selectedIds }

    /** Totals of the selected bookings per currency; currencies are never added together. */
    val selectedTotals: Map<String, BigDecimal> get() = Currencies.totals(selectedShown, Currencies.DEFAULT, { t -> t.currency }, { t -> t.amount })
}

data class ManualEntry(
    val accountId: Int,
    val date: LocalDate,
    val amount: BigDecimal,
    val currency: String,
    val partnerName: String?,
    val purpose: String?,
    val categoryId: Int?,
)

class TransactionsViewModel(
    private val transactionRepository: TransactionRepository = TransactionRepositoryImpl(),
    private val categoryRepository: CategoryRepository = CategoryRepositoryImpl(),
    private val accountRepository: AccountRepository = AccountRepositoryImpl(),
    private val accountGroupRepository: AccountGroupRepository = AccountGroupRepositoryImpl(),
    private val ruleRepository: RuleRepository = RuleRepositoryImpl(),
    private val auditLogRepository: AuditLogRepository = AuditLogRepositoryImpl(),
    private val settingsRepository: SettingsRepository = SettingsRepositoryImpl(),
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

    /** Set by [start] without an account: the next refresh restores the selection stored for this profile. */
    private var restoreSelection = false

    private var collapsedLoaded = false

    private var chartCollapsedLoaded = false

    private val groupActions = AccountGroupActions(accountGroupRepository, accountRepository, transactionRepository, auditLogRepository, settingsRepository)

    /** The selection the last [refresh] resolved, so a changed selection can reset [TransactionsState.boldIds]. */
    private var lastRefreshedSelection: AccountSelection? = null

    fun currentFilter(s: TransactionsState = _state.value): TransactionFilter {
        val (start, end) = dateRange(s)
        val accountId: Int?
        val accountIds: Set<Int>?
        when (val selection = s.selection) {
            is AccountSelection.Account -> {
                accountId = selection.id
                accountIds = null
            }

            is AccountSelection.Group -> {
                accountId = null
                // Empty for an empty custom group, so it shows no bookings rather than all of them.
                accountIds = s.groups.firstOrNull { it.key.id == selection.key.id }?.accounts?.map { it.id }?.toSet() ?: emptySet()
            }

            AccountSelection.All -> {
                accountId = null
                accountIds = null
            }
        }
        return TransactionFilter(
            startDate = start,
            endDate = end,
            accountId = accountId,
            accountIds = accountIds,
            categoryId = (s.categoryFilter as? CategoryFilter.Only)?.categoryId,
            uncategorizedOnly = s.categoryFilter == CategoryFilter.None,
            direction = s.direction,
            text = s.searchQuery,
            minAmount = s.minAmount,
            maxAmount = s.maxAmount,
        )
    }

    /**
     * The inclusive date range behind [month]/[customRange]/[period]: exactly [month] when it is set (the dashboard's
     * past-month click-through), else [TransactionsState.customRange] (the period menu's custom From/To), else what
     * [Period.range] resolves on today. Every filter that turns the period into dates goes through this one, so list
     * and chart always agree.
     */
    private fun dateRange(s: TransactionsState): Pair<LocalDate?, LocalDate?> = when {
        s.month != null -> s.month.let { it.atDay(1) to it.atEndOfMonth() }
        s.customRange != null -> s.customRange.first to s.customRange.second
        else -> s.period.range(today())
    }

    /** The selection only ever covers what the list shows, so a change of the filters starts over with an empty one. */
    private fun TransactionsState.clearingSelection(): TransactionsState = copy(selectedIds = emptySet(), selectionAnchorId = null)

    /** Adds or removes [id] from the selection and makes it the new anchor. */
    private fun TransactionsState.toggled(id: Int): TransactionsState = copy(
        selectedIds = if (id in selectedIds) selectedIds - id else selectedIds + id,
        selectionAnchorId = id,
    )

    /** Applies navigation context (e.g. "transactions of this account") and loads. */
    fun start(
        accountId: Int? = null,
        uncategorizedOnly: Boolean = false,
        partner: String? = null,
        categoryId: Int? = null,
        period: Period? = null,
        month: YearMonth? = null,
    ): Job {
        if (partner != null) return showPartner(partner)
        if (categoryId != null) return showCategory(categoryId, period, month)
        restoreSelection = accountId == null
        // Any navigation (a period, an exact month, an account, the uncategorized list) resets to its own period;
        // only a plain start() keeps a custom From/To that was set from the period menu
        val navigated = period != null || month != null || accountId != null || uncategorizedOnly
        _state.update {
            // A deep link into an account or the uncategorized list shouldn't be limited to this month,
            // unless the navigation asks for a period itself
            val effectivePeriod = when {
                period != null -> period
                accountId != null || uncategorizedOnly -> Period.ALL
                else -> it.period
            }
            it.copy(
                accountId = accountId,
                selection = accountId?.let { id -> AccountSelection.Account(id) } ?: it.selection,
                categoryFilter = if (uncategorizedOnly) CategoryFilter.None else CategoryFilter.Any,
                period = effectivePeriod,
                // Only an explicit month navigation keeps one; any other start clears it
                month = month,
                customRange = if (navigated) null else it.customRange,
            ).clearingSelection()
        }
        return loadTransactions()
    }

    /**
     * Every booking of one category over [period] (default all time) or exactly [month]; search, direction,
     * amount and account selection are cleared, like [showPartner] does.
     */
    private fun showCategory(categoryId: Int, period: Period?, month: YearMonth?): Job {
        searchJob?.cancel()
        restoreSelection = false
        _state.update {
            it.copy(
                searchQuery = "",
                period = period ?: Period.ALL,
                month = month,
                direction = Direction.ALL,
                categoryFilter = CategoryFilter.Only(categoryId),
                selection = AccountSelection.All,
                accountId = null,
                minAmount = null,
                maxAmount = null,
                customRange = null,
            ).clearingSelection()
        }
        return loadTransactions()
    }

    /** Every booking from [partner], any time, account, category, direction or amount: the search alone narrows the list. */
    fun showPartner(partner: String): Job {
        searchJob?.cancel()
        restoreSelection = false
        _state.update {
            it.copy(
                searchQuery = partner,
                period = Period.ALL,
                month = null,
                direction = Direction.ALL,
                categoryFilter = CategoryFilter.Any,
                selection = AccountSelection.All,
                accountId = null,
                minAmount = null,
                maxAmount = null,
                customRange = null,
            ).clearingSelection()
        }
        return loadTransactions()
    }

    fun loadTransactions(): Job {
        _state.update { it.copy(isLoading = true, error = null) }
        return launchSafely(onError) { refresh() }
    }

    private fun refresh() {
        // Compared against the last refresh, not the current state: selectAccounts already wrote the new selection
        // into the state before calling refresh, so the two would always be equal and a change would never reset boldIds
        val previousSelection = lastRefreshedSelection
        var accounts = accountRepository.findAllItems()
        val customGroups = accountGroupRepository.findAllItems()
        val primary = Currencies.primary(settingsRepository)
        var groups = group(accounts, customGroups, primary)
        val selection = AccountPanelSettings.resolve(restoreSelectionOrCurrent(groups, accounts), groups, accounts)
        restoreSelection = false
        lastRefreshedSelection = selection
        _state.update {
            it.copy(
                accounts = accounts,
                customGroups = customGroups,
                groups = groups,
                grandTotal = grandTotal(accounts, primary),
                // Read once; afterwards the in-memory set is the truth, so a refresh can't undo a toggle still being saved
                collapsed = if (collapsedLoaded) it.collapsed else AccountPanelSettings.loadCollapsed(settingsRepository).also { collapsedLoaded = true },
                chartCollapsed = if (chartCollapsedLoaded) it.chartCollapsed else loadChartCollapsed().also { chartCollapsedLoaded = true },
                selection = selection,
                accountId = (selection as? AccountSelection.Account)?.id,
            )
        }
        val transactions = transactionRepository.findItems(currentFilter())
        val shownNew = transactions.filter { it.isNew }.map { it.id }.toSet()
        // Bold rows survive a refresh so clearing a counter can't unbold them, but a selection change replaces them
        var boldIds = if (selection == previousSelection) _state.value.boldIds + shownNew else shownNew

        // Opening a single account marks its bookings read, however it was selected. View state only: no audit entry.
        if (selection is AccountSelection.Account && (_state.value.accounts.firstOrNull { it.id == selection.id }?.newCount ?: 0) > 0) {
            // The clear covers the whole account, so capture its new bookings outside the period on screen too:
            // a late booking dated last month stays bold when the user widens the period
            boldIds = boldIds + transactionRepository.findItems(TransactionFilter(accountId = selection.id)).filter { it.isNew }.map { it.id }
            transactionRepository.markRead(setOf(selection.id))
            accounts = accountRepository.findAllItems()
            groups = group(accounts, customGroups, primary)
        }

        // Keep the open detail panel in sync with edits; a booking deleted elsewhere (e.g. with the account)
        // closes it, while one merely hidden by the filters keeps the stale snapshot as before
        _state.value.selected?.let { sel ->
            val stillShown = transactions.firstOrNull { t -> t.id == sel.id }
            if (stillShown != null) {
                _state.update { if (it.selected?.id == sel.id) it.copy(selected = stillShown) else it }
            } else if (transactionRepository.findById(sel.id) == null) {
                _state.update { if (it.selected?.id == sel.id) it.copy(selected = null, selectedDocuments = emptyList()) else it }
            }
        }
        val categories = categoryRepository.findAllItems()
        // The running balance needs every booking of the account, not just the filtered list, so a text or period
        // filter hides rows but never changes their balances. A depot's balance is a market value, not the sum of
        // its bookings, so the column only makes sense for a single non-depot account.
        val balanceAccount = accounts.firstOrNull { it.id == _state.value.accountId }?.takeIf { it.accountType != "depot" }
        // One query feeds both the column and the chart, so the two can never disagree.
        val accountBookings = balanceAccount?.let { transactionRepository.findItems(TransactionFilter(accountId = it.id)).toList() }
        val balances = if (balanceAccount != null && accountBookings != null) RunningBalance.compute(balanceAccount.balance, accountBookings) else null
        val balanceChart = if (balanceAccount != null && accountBookings != null && balances != null) {
            balanceChartPoints(accountBookings, balances, _state.value)
        } else {
            null
        }
        _state.update {
            it.copy(
                transactions = transactions,
                categories = categories,
                accounts = accounts,
                groups = groups,
                grandTotal = grandTotal(accounts, primary),
                boldIds = boldIds,
                balances = balances,
                balanceChart = balanceChart,
                currentBalance = balanceAccount?.balance,
                balanceCurrency = balanceAccount?.currency,
                totalIncome = Currencies.totals(transactions.filter { t -> t.amount.signum() > 0 }, Currencies.DEFAULT, { t -> t.currency }, { t -> t.amount }),
                totalExpenses = Currencies.totals(transactions.filter { t -> t.isExpense }, Currencies.DEFAULT, { t -> t.currency }, { t -> t.amount.abs() }),
                // A booking deleted elsewhere (e.g. by a sync re-run or an import) falls out of the selection
                selectedIds = it.selectedIds intersect transactions.mapTo(mutableSetOf()) { t -> t.id },
                isLoading = false,
            )
        }
    }

    /** Reads the stored selection once (when [start] asked for it), otherwise keeps the current one. */
    private fun restoreSelectionOrCurrent(groups: List<AccountGroupView>, accounts: List<AccountItem>): AccountSelection = if (restoreSelection) AccountPanelSettings.loadSelection(settingsRepository, groups, accounts) else _state.value.selection

    private fun loadChartCollapsed(): Boolean = settingsRepository.getValue(KEY_CHART_COLLAPSED)?.toBooleanStrictOrNull() ?: false

    /**
     * One point per day over the shown period, clamped to [now]: `Period.ALL` starts at the account's oldest
     * booking. The chart follows the running-balance entries, so it shows the account's balance whatever the
     * search, direction, category or amount filters do to the list.
     */
    private fun balanceChartPoints(
        bookings: List<TransactionItem>,
        running: Map<Int, RunningBalance.Entry>,
        s: TransactionsState,
    ): List<Pair<LocalDate, BigDecimal>> {
        val (start, end) = dateRange(s)
        val now = today()
        val from = start ?: bookings.minOfOrNull { it.date } ?: return emptyList()
        // The period's end, but never past today: the balance of a future day is not known
        val to = if (end == null || end.isAfter(now)) now else end
        return BalanceHistory.fromRunning(bookings, running, from, to)
    }

    /** Debounced so typing doesn't fire a query per keystroke. */
    fun updateSearchQuery(query: String): Job {
        _state.update { it.copy(searchQuery = query).clearingSelection() }
        searchJob?.cancel()
        return launchSafely(onError) {
            delay(SEARCH_DEBOUNCE_MS)
            refresh()
        }.also { searchJob = it }
    }

    fun updatePeriod(period: Period): Job {
        // Picking a period from the list ends the exact-month navigation and a custom From/To
        _state.update { it.copy(period = period, month = null, customRange = null).clearingSelection() }
        return loadTransactions()
    }

    /**
     * Filters the list by an arbitrary From/To range (the period menu's "Custom range…"): it wins over [Period]
     * beneath an exact month, clears the exact-month navigation and the selection, and reloads. A reversed range
     * changes nothing and completes without loading anything.
     */
    fun setCustomRange(from: LocalDate, to: LocalDate): Job {
        if (from.isAfter(to)) return Job().apply { complete() }
        _state.update { it.copy(customRange = from to to, month = null).clearingSelection() }
        return loadTransactions()
    }

    fun updateDirection(direction: Direction): Job {
        _state.update { it.copy(direction = direction).clearingSelection() }
        return loadTransactions()
    }

    fun updateCategoryFilter(filter: CategoryFilter): Job {
        _state.update { it.copy(categoryFilter = filter).clearingSelection() }
        return loadTransactions()
    }

    /** Selects an account, group or all accounts, persists the choice for this profile and reloads. */
    fun selectAccounts(selection: AccountSelection): Job {
        _state.update { it.copy(selection = selection, accountId = (selection as? AccountSelection.Account)?.id).clearingSelection() }
        _state.update { it.copy(isLoading = true, error = null) }
        // The write runs in the same job as the reload, off the UI thread, so stored and shown selection can't diverge
        return launchSafely(onError) {
            AccountPanelSettings.saveSelection(settingsRepository, selection)
            refresh()
        }
    }

    fun updateAccountFilter(accountId: Int?): Job = selectAccounts(accountId?.let { AccountSelection.Account(it) } ?: AccountSelection.All)

    /**
     * Marks the bookings behind [selection] as read on the user's request: All clears everything, a group its
     * accounts, an account that one. View state only, so no audit entry. [TransactionsState.boldIds] stays as it is.
     */
    fun markRead(selection: AccountSelection): Job {
        _state.update { it.copy(isLoading = true, error = null) }
        return launchSafely(onError) {
            groupActions.markRead(selection, _state.value.groups)
            refresh()
        }
    }

    /** Collapses or expands a group; view state only, so it is persisted but not audited. */
    fun toggleCollapsed(key: GroupKey): Job {
        val collapsed = _state.value.collapsed
        val next = if (key.id in collapsed) collapsed - key.id else collapsed + key.id
        _state.update { it.copy(collapsed = next) }
        return launchSafely(onError) { AccountPanelSettings.saveCollapsed(settingsRepository, next) }
    }

    /** Collapses or expands the balance chart; view state only, so it is persisted but not audited. */
    fun toggleChart(): Job {
        val next = !_state.value.chartCollapsed
        _state.update { it.copy(chartCollapsed = next) }
        return launchSafely(onError) { settingsRepository.setValue(KEY_CHART_COLLAPSED, next.toString()) }
    }

    /** Filters the panel's account rows by name, IBAN or bank; the booking list is unaffected. */
    fun updateAccountFilterText(text: String) {
        _state.update { it.copy(accountFilterText = text) }
    }

    fun createGroup(name: String): Job = launchSafely(onError) {
        groupActions.create(name)?.let { msg -> _state.update { it.copy(error = msg) } } ?: refresh()
    }

    fun renameGroup(id: Int, name: String): Job = launchSafely(onError) {
        groupActions.rename(id, name)?.let { msg -> _state.update { it.copy(error = msg) } } ?: refresh()
    }

    fun deleteGroup(id: Int): Job = launchSafely(onError) {
        if (groupActions.delete(id, _state.value.selection)) _state.update { it.copy(selection = AccountSelection.All, accountId = null) }
        refresh()
    }

    fun moveGroup(id: Int, up: Boolean): Job = launchSafely(onError) {
        groupActions.move(id, up)
        refresh()
    }

    fun moveAccountToGroup(accountId: Int, groupId: Int?): Job = launchSafely(onError) {
        groupActions.moveAccount(accountId, groupId)
        refresh()
    }

    fun updateAmountRange(min: BigDecimal?, max: BigDecimal?): Job {
        _state.update { it.copy(minAmount = min, maxAmount = max).clearingSelection() }
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
        val matcher = RuleMatcher.of(listOf(pattern to categoryId))
        var applied = 0
        uncategorized.forEach { t ->
            if (matcher.categoryFor(t.purpose, t.partnerName, t.amount) != null) {
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
        confirmedDuplicate: Boolean = false,
    ): Job = launchSafely(onError) {
        require(amount.signum() != 0) { AppStrings[StringKey.ErrorAmountNotZero] }
        // The booking is in the account's currency; defaulting to EUR would mix currencies.
        val account = accountRepository.findAllItems().firstOrNull { it.id == accountId }
            ?: throw IllegalArgumentException("Account $accountId not found")
        val partner = partnerName?.trim()?.ifEmpty { null }
        val text = purpose?.trim()?.ifEmpty { null }
        // The same booking twice on this account, whatever its source, is usually a slip, but two coffees on one day
        // are real: ask instead of refusing. The same booking on another account is a different one.
        val identical = transactionRepository.findItems(TransactionFilter(startDate = date, endDate = date, accountId = accountId))
            .count { it.amount.compareTo(amount) == 0 && it.partnerName == partner && it.purpose == text }
        if (identical > 0 && !confirmedDuplicate) {
            _state.update { it.copy(pendingDuplicate = ManualEntry(accountId, date, amount, account.currency, partner, text, categoryId)) }
            return@launchSafely
        }
        // A repeat gets its own hash, so each entry stays distinguishable
        val repeat = if (identical > 0) "#$identical" else ""
        val hash = FinTSClient.transactionHash(date, amount, partner, "${text.orEmpty()}#${account.iban}#manual$repeat")
        val resolvedCategory = categoryId ?: ruleRepository.matchCategoryId(purpose, partnerName, amount)
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
        val note = if (identical > 0) " (confirmed repeat of an identical booking)" else ""
        auditLogRepository.log("ADD_TRANSACTION", "user", "Added manual transaction on account $accountId$note")
        refresh()
        _state.update { it.copy(message = AppStrings[StringKey.MessageTransactionAdded]) }
    }

    /** Adds the pending identical entry after the user confirmed it is a second, real booking. */
    fun confirmDuplicate(): Job? {
        val entry = _state.value.pendingDuplicate ?: return null
        _state.update { it.copy(pendingDuplicate = null) }
        return with(entry) { addManualTransaction(accountId, date, amount, partnerName, purpose, categoryId, confirmedDuplicate = true) }
    }

    fun dismissDuplicate() {
        _state.update { it.copy(pendingDuplicate = null) }
    }

    fun deleteTransaction(transactionId: Int): Job = launchSafely(onError) {
        transactionRepository.delete(transactionId)
        auditLogRepository.log("DELETE_TRANSACTION", "user", "Deleted transaction $transactionId")
        refresh()
    }

    /** Toggles [id] in the multi-selection (Cmd/Ctrl-click) and makes it the anchor for the next Shift range. */
    fun toggleSelection(id: Int) {
        _state.update { it.toggled(id) }
    }

    /**
     * Shift-click: selects every booking between the anchor and [id] (both inclusive, in list order) in addition
     * to the existing selection, and moves the anchor to [id]. Without an anchor — or when either row is no
     * longer shown — it behaves like [toggleSelection].
     */
    fun selectRange(id: Int) {
        _state.update { s ->
            val anchor = s.selectionAnchorId ?: return@update s.toggled(id)
            val shown = s.transactions
            val from = shown.indexOfFirst { it.id == anchor }
            val to = shown.indexOfFirst { it.id == id }
            if (from < 0 || to < 0) return@update s.toggled(id)
            val range = shown.subList(minOf(from, to), maxOf(from, to) + 1).mapTo(mutableSetOf()) { it.id }
            s.copy(selectedIds = s.selectedIds + range, selectionAnchorId = id)
        }
    }

    /** Selects every booking the current filters show. */
    fun selectAllShown() {
        _state.update { s -> s.copy(selectedIds = s.transactions.mapTo(mutableSetOf()) { t -> t.id }) }
    }

    /** Empties the multi-selection. */
    fun clearSelection() {
        _state.update { it.copy(selectedIds = emptySet(), selectionAnchorId = null) }
    }

    /**
     * Deletes exactly the bookings of [ids] — the set the confirmation dialog displayed — that are still part
     * of the selection and still shown, so it can delete fewer than shown but never more, and never a booking
     * the list does not show. One audit entry names the count only (no amounts, names or purposes). The
     * selection is cleared and the list refreshed afterwards.
     */
    fun deleteSelected(ids: Set<Int>): Job = launchSafely(onError) {
        if (ids.isEmpty()) return@launchSafely
        val s = _state.value
        val targets = ids intersect s.selectedIds intersect s.transactions.mapTo(mutableSetOf()) { t -> t.id }
        if (targets.isNotEmpty()) {
            val deleted = transactionRepository.deleteAll(targets)
            if (deleted > 0) {
                auditLogRepository.log("DELETE_TRANSACTIONS", "user", "Deleted $deleted transactions")
                _state.update { it.copy(message = AppStrings.format(StringKey.MessageTransactionsDeleted, deleted)) }
            }
        }
        _state.update { it.copy(selectedIds = emptySet(), selectionAnchorId = null) }
        refresh()
    }

    /**
     * Sets the manual tax override of the selected bookings that are still shown (the same intersection idea as
     * [deleteSelected]); `value = null` resets them to automatic. One `SET_TAX_OVERRIDE` audit entry names the
     * count and the value word only — no amounts, names or purposes. The selection is kept and the list refreshed.
     */
    fun setTaxOverrideForSelection(value: Boolean?): Job = launchSafely(onError) {
        val s = _state.value
        val targets = s.selectedIds intersect s.transactions.mapTo(mutableSetOf()) { t -> t.id }
        val changed = transactionRepository.setTaxOverride(targets, value)
        if (changed > 0) {
            auditLogRepository.log("SET_TAX_OVERRIDE", "user", "Set tax override for $changed bookings: ${taxOverrideWord(value)}")
            _state.update { it.copy(message = AppStrings.format(StringKey.MessageTaxOverrideSet, changed)) }
        }
        refresh()
    }

    /**
     * Sets the category of the selected bookings that are still shown (the same intersection idea as
     * [deleteSelected]); `categoryId = null` means "Uncategorized". The batch write skips bookings already on the
     * target, and one `UPDATE_CATEGORIES` audit entry names the count only — no amounts, names or purposes. The
     * selection is kept and the list refreshed.
     */
    fun setCategoryForSelection(categoryId: Int?): Job = launchSafely(onError) {
        val s = _state.value
        val targets = s.selectedIds intersect s.transactions.mapTo(mutableSetOf()) { t -> t.id }
        val changed = transactionRepository.updateCategories(targets.associateWith { categoryId })
        if (changed > 0) {
            auditLogRepository.log("UPDATE_CATEGORIES", "user", "Set category for $changed bookings to category ${categoryId ?: "uncategorized"}")
            _state.update { it.copy(message = AppStrings.format(StringKey.MessageCategorySet, changed)) }
        }
        refresh()
    }

    /**
     * Sets the manual tax override of one booking from the details dialog (same audit entry as the selection,
     * with count 1); `value = null` resets it to automatic. Refreshes so the open details show the new state.
     */
    fun setTaxOverride(transactionId: Int, value: Boolean?): Job = launchSafely(onError) {
        val changed = transactionRepository.setTaxOverride(listOf(transactionId), value)
        if (changed > 0) {
            auditLogRepository.log("SET_TAX_OVERRIDE", "user", "Set tax override for $changed bookings: ${taxOverrideWord(value)}")
            _state.update { it.copy(message = AppStrings.format(StringKey.MessageTaxOverrideSet, changed)) }
        }
        refresh()
    }

    /** The override's word for the `SET_TAX_OVERRIDE` audit entry (records stay English; count only, no booking text). */
    private fun taxOverrideWord(value: Boolean?): String = when (value) {
        true -> "relevant"
        false -> "not relevant"
        null -> "automatic"
    }

    fun autoCategorizeTransactions(): Job = launchSafely(onError) {
        val uncategorized = transactionRepository.findItems(TransactionFilter(uncategorizedOnly = true))
        var updated = 0
        val rules = ruleRepository.matcher()
        uncategorized.forEach { txn ->
            val matched = rules.categoryFor(txn.purpose, txn.partnerName, txn.amount)
            if (matched != null) {
                transactionRepository.updateCategory(txn.id, matched)
                updated++
            }
        }
        auditLogRepository.log("AUTO_CATEGORIZE", "user", "Auto-categorized $updated of ${uncategorized.size} transactions")
        refresh()
        _state.update { it.copy(message = AppStrings.format(StringKey.MessageAutoCategorized, updated, uncategorized.size)) }
    }

    /** Every booking the last [confirmReapplyRules] changed, with its previous and new category; it lives only in memory. */
    private var reapplyUndo: List<ReapplyChange> = emptyList()

    /**
     * Computes the re-apply preview for exactly the bookings the list shows with the current filters ("Auto-categorize"
     * in reverse): for each booking, the highest-priority matching rule's category wins, and only a category other
     * than the booking's own counts as a change. No match leaves a booking alone; a category is never set to null.
     */
    fun previewReapplyRules(): Job = launchSafely(onError) {
        val items = transactionRepository.findItems(currentFilter())
        val matcher = ruleRepository.matcher()
        val changes = items.mapNotNull { txn ->
            val matched = matcher.categoryFor(txn.purpose, txn.partnerName, txn.amount)
            if (matched != null && matched != txn.categoryId) ReapplyChange(txn.id, txn.categoryId, matched) else null
        }
        if (changes.isEmpty()) {
            _state.update { it.copy(reapplyPreview = null, message = AppStrings[StringKey.MessageReapplyNothing]) }
            return@launchSafely
        }
        val primary = Currencies.primary(settingsRepository)
        val names = categoryRepository.findAllItems().associate { it.id to it.name }
        val uncategorizedName = AppStrings[StringKey.Uncategorized]
        val byId = items.associateBy { it.id }
        val groups = changes
            .groupBy { it.fromCategoryId to it.toCategoryId }
            .map { (pair, same) ->
                val bookings = same.mapNotNull { byId[it.transactionId] }
                val sum = if (bookings.all { it.currency == primary }) bookings.fold(BigDecimal.ZERO) { acc, b -> acc + b.amount } else null
                ReapplyGroup(
                    fromCategoryId = pair.first,
                    fromName = pair.first?.let { names[it] ?: it.toString() } ?: uncategorizedName,
                    toCategoryId = pair.second,
                    toName = names[pair.second] ?: pair.second.toString(),
                    count = same.size,
                    sum = sum,
                    currency = primary,
                )
            }
            .sortedWith(compareByDescending<ReapplyGroup> { it.count }.thenBy { it.fromName }.thenBy { it.toName })
        _state.update { it.copy(reapplyPreview = ReapplyPreview(changes, groups, items.size)) }
    }

    /** Closes the preview dialog without changing anything. */
    fun cancelReapplyRules() {
        _state.update { it.copy(reapplyPreview = null) }
    }

    /**
     * Applies exactly the stored preview's changes minus the [excluded] groups; the matches are not recomputed, so a
     * rule added after the preview changes nothing here. A booking whose category changed since the preview (by hand)
     * is skipped, not overwritten; the old categories of the rows that did change are kept for [undoReapplyRules].
     */
    fun confirmReapplyRules(excluded: Set<Pair<Int?, Int>> = emptySet()): Job = launchSafely(onError) {
        val preview = _state.value.reapplyPreview ?: return@launchSafely
        _state.update { it.copy(reapplyPreview = null) }
        val current = transactionRepository.findItems(TransactionFilter()).associate { it.id to it.categoryId }
        val changes: List<ReapplyChange> = preview.changes
            .filter { change -> (change.fromCategoryId to change.toCategoryId) !in excluded }
            // A booking categorized by hand since the preview keeps its category; everything else moves as previewed
            .filter { change -> current[change.transactionId] == change.fromCategoryId }
        val count = if (changes.isEmpty()) 0 else transactionRepository.updateCategories(changes.associate { it.transactionId to it.toCategoryId })
        if (count > 0) {
            reapplyUndo = changes
            auditLogRepository.log("REAPPLY_RULES", "user", "Re-applied rules: $count bookings recategorized")
        }
        refresh()
        _state.update { it.copy(message = AppStrings.format(StringKey.MessageReapplied, count), canUndoReapply = reapplyUndo.isNotEmpty()) }
    }

    /**
     * Restores the previous categories of the last re-apply, including uncategorized bookings back to null. A booking
     * moved again since (by hand) is no longer on the re-apply's category and keeps the newer choice.
     */
    fun undoReapplyRules(): Job = launchSafely(onError) {
        if (reapplyUndo.isEmpty()) return@launchSafely
        val current = transactionRepository.findItems(TransactionFilter()).associate { it.id to it.categoryId }
        val previous = reapplyUndo.filter { current[it.transactionId] == it.toCategoryId }.associate { it.transactionId to it.fromCategoryId }
        val count = if (previous.isEmpty()) 0 else transactionRepository.updateCategories(previous)
        reapplyUndo = emptyList()
        auditLogRepository.log("UNDO_REAPPLY_RULES", "user", "Undid re-applied rules: $count bookings")
        refresh()
        _state.update { it.copy(message = AppStrings.format(StringKey.MessageReapplyUndone, count), canUndoReapply = false) }
    }

    /** Exports exactly what is currently shown. */
    fun exportCsv(file: File): Job = launchSafely(onError) {
        val ok = reportService.exportToCsv(_state.value.transactions, file, _state.value.balances?.mapValues { it.value.balance })
        _state.update {
            if (ok) it.copy(message = AppStrings.format(StringKey.MessageExportedCount, it.transactions.size, file.name)) else it.copy(error = AppStrings[StringKey.ErrorExportFailed])
        }
    }

    /**
     * Reads the selected statement files and opens the import preview (nothing is stored yet). All files must
     * belong to one account; the readable files are read one after another, unreadable ones become failures and
     * the rest is still offered. The `entries` order is the import order: files without bookings first, then by
     * each file's latest booking date ascending (ties by earliest date, then selection order), so the newest
     * entry — [ImportPreview.newest] — is the file with the greatest latest-booking date; only it may set the
     * balance, and it is imported last.
     */
    fun openImport(files: List<File>, mapping: CsvMapping? = null): Job = readImportFiles(files) { mapping }

    /** Reads one statement file and opens the import preview. */
    fun openImport(file: File, mapping: CsvMapping? = null): Job = openImport(listOf(file), mapping)

    /**
     * The shared body of [openImport] and [remapImport]: the limits are checked before any file is read, then
     * each file is read with [mappingFor]'s mapping for that file.
     */
    private fun readImportFiles(files: List<File>, mappingFor: (File) -> CsvMapping?): Job = launchSafely(onError) {
        if (files.isEmpty()) return@launchSafely
        // Limits before reading anything, so a bad selection fails fast and cheap
        if (files.size > StatementImportService.MAX_FILES) {
            _state.update { it.copy(error = AppStrings.format(StringKey.ErrorImportTooManyFiles, StatementImportService.MAX_FILES)) }
            return@launchSafely
        }
        val totalBytes = files.sumOf { f -> runCatching { f.length() }.getOrDefault(0L) }
        if (totalBytes > StatementImportService.MAX_TOTAL_BYTES) {
            _state.update { it.copy(error = AppStrings.format(StringKey.ErrorImportFilesTooLarge, StatementImportService.MAX_TOTAL_MB)) }
            return@launchSafely
        }
        val entries = mutableListOf<ImportEntry>()
        val failures = mutableListOf<ImportFailure>()
        files.forEach { file ->
            try {
                entries += ImportEntry(file, importService.read(file, mappingFor(file)))
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                failures += ImportFailure(file.name, e.userMessage())
            }
        }
        if (entries.isEmpty()) {
            // One unreadable file reports its own message, as before; several name every one
            _state.update {
                it.copy(
                    importPreview = null,
                    error = if (failures.size == 1) {
                        failures[0].message
                    } else {
                        AppStrings.format(StringKey.ErrorImportNoReadableFile, failures.joinToString(", ") { f -> "${f.fileName}: ${f.message}" })
                    },
                )
            }
            return@launchSafely
        }
        val ordered = entries
            .mapIndexed { index, entry -> Triple(entry, index, entry.statement.bookings) }
            .sortedWith(
                compareBy(
                    { t: Triple<ImportEntry, Int, List<de.findesk.importer.ImportedBooking>> -> t.third.isNotEmpty() }, // empty files first: they import nothing
                    { t: Triple<ImportEntry, Int, List<de.findesk.importer.ImportedBooking>> -> t.third.maxOfOrNull { it.date } ?: LocalDate.MIN },
                    { t: Triple<ImportEntry, Int, List<de.findesk.importer.ImportedBooking>> -> t.third.minOfOrNull { it.date } ?: LocalDate.MIN },
                    { t: Triple<ImportEntry, Int, List<de.findesk.importer.ImportedBooking>> -> t.second },
                ),
            )
            .map { it.first }
        // One account only: several IBANs among the files are refused instead of guessing
        val ibans = ordered.mapNotNull { it.statement.accountIban?.let(Iban::normalize) }.distinct()
        if (ibans.size > 1) {
            _state.update {
                it.copy(error = AppStrings.format(StringKey.ErrorImportDifferentAccounts, ibans.joinToString(", ") { "…${it.takeLast(4)}" }))
            }
            return@launchSafely
        }
        val statement = ordered.firstOrNull { it.statement.accountIban != null }?.statement ?: ordered.last().statement
        val accountId = importService.matchAccount(statement) ?: _state.value.importPreview?.accountId
            ?: _state.value.accountId ?: _state.value.accounts.singleOrNull()?.id
        val newest = ordered.last().statement
        val allowed = accountId?.let { importService.balanceUpdateAllowed(it, newest) } ?: false
        _state.update { it.copy(importPreview = ImportPreview(files, ordered, failures, accountId, allowed), error = null) }
    }

    /**
     * Re-reads the CSV with a user-adjusted column mapping. Column indexes only make sense for files with the
     * same header row: the new mapping goes to the CSV/XLSX files whose headers match the first CSV/XLSX entry's
     * headers; every other file keeps its auto-detected mapping.
     */
    fun remapImport(mapping: CsvMapping): Job {
        val preview = _state.value.importPreview ?: return Job().apply { complete() }
        val firstCsv = preview.entries.firstOrNull { it.statement.csv != null }?.statement?.csv
        return readImportFiles(preview.files) { file ->
            val entry = preview.entries.firstOrNull { it.file === file }
            if (firstCsv != null && entry?.statement?.csv?.headers == firstCsv.headers) mapping else null
        }
    }

    fun selectImportAccount(accountId: Int): Job {
        _state.update { s -> s.copy(importPreview = s.importPreview?.copy(accountId = accountId, balanceUpdateAllowed = false)) }
        return launchSafely(onError) {
            val preview = _state.value.importPreview ?: return@launchSafely
            val allowed = importService.balanceUpdateAllowed(accountId, preview.newest.statement)
            _state.update { s -> s.copy(importPreview = s.importPreview?.takeIf { it.accountId == accountId }?.copy(balanceUpdateAllowed = allowed) ?: s.importPreview) }
        }
    }

    fun cancelImport() {
        _state.update { it.copy(importPreview = null) }
    }

    /**
     * Imports the previewed entries in order, each with its own dedup hashing and audit-log entry — the per-file
     * occurrence counter would differ from importing the files one by one if the bookings were merged. Only the
     * newest file may set the balance. The first failure stops the import; files before it stay stored and the
     * list refreshes to show them.
     */
    fun confirmImport(updateBalance: Boolean): Job {
        val preview = _state.value.importPreview ?: return Job().apply { complete() }
        val accountId = preview.accountId ?: return Job().apply { complete() }
        _state.update { it.copy(isImporting = true) }
        return launchSafely({ e -> _state.update { it.copy(isImporting = false, error = e.userMessage()) } }) {
            var report: StatementImportService.ImportReport? = null
            var importedFiles = 0
            var failedName: String? = null
            var failedMessage: String? = null
            for (entry in preview.entries) {
                try {
                    val fileReport = Busy.track {
                        importService.import(accountId, entry.statement, updateBalance = updateBalance && entry === preview.newest)
                    }
                    report = report?.plus(fileReport) ?: fileReport
                    importedFiles++
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    failedName = entry.file.name
                    failedMessage = e.userMessage()
                    break
                }
            }
            if (failedName != null) {
                _state.update {
                    it.copy(
                        importPreview = null,
                        isImporting = false,
                        error = AppStrings.format(StringKey.ErrorImportStopped, failedName, failedMessage, importedFiles),
                    )
                }
                refresh()
                return@launchSafely
            }
            val finalReport = report!!
            // Show everything that was imported, not just this month; select the imported account.
            _state.update {
                it.copy(importPreview = null, isImporting = false, period = Period.ALL, selection = AccountSelection.Account(accountId), accountId = accountId, message = finalReport.summary(importedFiles)).clearingSelection()
            }
            refresh()
        }
    }

    fun clearMessages() {
        _state.update { it.copy(error = null, message = null) }
    }

    companion object {
        const val SEARCH_DEBOUNCE_MS = 250L

        /** Settings keys holding the account panel's selection and collapsed groups, per profile (shared with Accounts). */
        const val KEY_SELECTION = AccountPanelSettings.KEY_SELECTION
        const val KEY_COLLAPSED = AccountPanelSettings.KEY_COLLAPSED

        /** Whether the balance chart is folded away, per profile. */
        const val KEY_CHART_COLLAPSED = "transactions.chartCollapsed"
    }
}
