package de.findesk.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerKeyboardModifiers
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.findesk.i18n.StringKey
import de.findesk.i18n.Strings
import de.findesk.i18n.strings
import de.findesk.importer.CsvMapping
import de.findesk.importer.StatementFormat
import de.findesk.importer.StatementImportService
import de.findesk.model.AccountItem
import de.findesk.model.CategoryItem
import de.findesk.model.Direction
import de.findesk.model.PendingBookingItem
import de.findesk.model.TaxSource
import de.findesk.model.TransactionItem
import de.findesk.ui.components.*
import de.findesk.ui.navigation.NavigationArgs
import de.findesk.ui.navigation.Screen
import de.findesk.ui.navigation.ScreenRequest
import de.findesk.ui.platform.Platform
import de.findesk.ui.theme.amountColor
import de.findesk.ui.theme.balanceToneColor
import de.findesk.util.AccountGroupView
import de.findesk.util.BankText
import de.findesk.util.Currencies
import de.findesk.util.Formats
import de.findesk.util.GroupKey
import de.findesk.util.Iban
import de.findesk.util.PaymentProviders
import de.findesk.util.balanceTone
import de.findesk.viewmodel.AccountSelection
import de.findesk.viewmodel.CategoryFilter
import de.findesk.viewmodel.ImportPreview
import de.findesk.viewmodel.Period
import de.findesk.viewmodel.ReapplyPreview
import de.findesk.viewmodel.TransactionsViewModel
import java.math.BigDecimal
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TransactionsScreen(
    args: NavigationArgs = NavigationArgs(),
    createViewModel: () -> TransactionsViewModel = { TransactionsViewModel() },
    onNavigate: (Screen, NavigationArgs) -> Unit = { _, _ -> },
) {
    val vm = rememberViewModel(createViewModel)
    val state by vm.state.collectAsState()
    val s = strings()
    LaunchedEffect(args) { vm.start(args.accountId, args.uncategorizedOnly, args.partner, args.categoryId, args.period, args.month) }

    var showAdd by remember { mutableStateOf(false) }
    val attachReceipt: (Int) -> Unit = { transactionId ->
        val files = FileDialogs.openFiles(s[StringKey.AttachReceipt], de.findesk.document.DocumentService.SUPPORTED_EXTENSIONS)
        if (files.isNotEmpty()) vm.attachDocuments(transactionId, files)
    }
    val openImport = {
        FileDialogs.openFiles(s[StringKey.ImportBankStatement], StatementImportService.EXTENSIONS).takeIf { it.isNotEmpty() }?.let { vm.openImport(it) }
    }
    // A menu request (File → New Transaction / Import) runs once the accounts it needs have loaded
    var pendingRequest by remember(args) { mutableStateOf(args.request) }
    LaunchedEffect(pendingRequest, state.accounts.isNotEmpty()) {
        val request = pendingRequest ?: return@LaunchedEffect
        if (state.accounts.isEmpty()) return@LaunchedEffect
        pendingRequest = null
        when (request) {
            ScreenRequest.NEW_TRANSACTION -> showAdd = true
            ScreenRequest.IMPORT_STATEMENT -> openImport()
        }
    }
    var confirmDelete by remember { mutableStateOf<TransactionItem?>(null) }
    // The "Custom range…" entry of the period menu opens this From/To dialog
    var showCustomRangeDialog by remember { mutableStateOf(false) }
    // The batch-delete confirmation, captured with the exact id set and bookings it showed when it opened
    var confirmMultiDelete by remember { mutableStateOf<MultiDeleteConfirmation?>(null) }
    // A row click focuses the list, so Cmd/Ctrl+A, Delete and Escape reach its key handler afterwards
    val listFocus = remember { FocusRequester() }
    val focusList: () -> Unit = { runCatching { listFocus.requestFocus() } }
    val openMultiDelete: () -> Unit = {
        if (state.selectedIds.isNotEmpty()) {
            confirmMultiDelete = MultiDeleteConfirmation(state.selectedIds, state.selectedShown)
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        // The panel needs the room; below this the screen falls back to the compact account selector
        val showPanel = maxWidth >= 1000.dp
        Row(modifier = Modifier.fillMaxSize().padding(24.dp)) {
            if (showPanel && state.accounts.isNotEmpty()) {
                AccountPanel(
                    accounts = state.accounts,
                    groups = state.groups,
                    customGroups = state.customGroups,
                    grandTotal = state.grandTotal,
                    primaryCurrency = state.grandTotal.keys.firstOrNull() ?: Currencies.DEFAULT,
                    selection = state.selection,
                    collapsed = state.collapsed,
                    filterText = state.accountFilterText,
                    onSelect = { vm.selectAccounts(it) },
                    onToggleCollapsed = { vm.toggleCollapsed(it) },
                    onFilterText = { vm.updateAccountFilterText(it) },
                    onCreateGroup = { vm.createGroup(it) },
                    onRenameGroup = { id, name -> vm.renameGroup(id, name) },
                    onDeleteGroup = { vm.deleteGroup(it) },
                    onMoveGroup = { id, up -> vm.moveGroup(id, up) },
                    onMoveAccount = { id, groupId -> vm.moveAccountToGroup(id, groupId) },
                    onMarkRead = { vm.markRead(it) },
                    modifier = Modifier.width(260.dp).fillMaxHeight(),
                )
                Spacer(Modifier.width(16.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                // The summary shares one line with the actions; it ellipsizes rather than wrapping below them
                val headerBalance = state.currentBalance
                // The header's and the running column's balances both belong to [state.accountId]'s account,
                // so a loan's negative balance there never warns red (see currentBalanceIsLoan)
                val headerBalanceColor = if (headerBalance != null) balanceToneColor(balanceTone(headerBalance, state.currentBalanceIsLoan)) else MaterialTheme.colorScheme.onSurfaceVariant
                // The sentence the strip replaces, so a screen reader hears one summary instead of every figure twice
                val summaryText = buildString {
                    append(
                        s.format(
                            StringKey.TransactionsSummary,
                            state.transactions.size,
                            Currencies.format(state.totalIncome, Currencies.DEFAULT),
                            Currencies.format(state.totalExpenses, Currencies.DEFAULT),
                        ),
                    )
                    if (headerBalance != null) {
                        append(s.format(StringKey.TransactionsBalance, Formats.money(headerBalance, state.balanceCurrency ?: "EUR")))
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // The top bar names the screen; this row holds its summary and actions
                    Row(
                        modifier = Modifier.weight(1f).clearAndSetSemantics { contentDescription = summaryText },
                        horizontalArrangement = Arrangement.spacedBy(24.dp),
                        verticalAlignment = Alignment.Bottom,
                    ) {
                        SummaryFigure(
                            label = s[StringKey.SummaryCount],
                            value = state.transactions.size.toString(),
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        SummaryFigure(
                            label = s[StringKey.SummaryIn],
                            value = Currencies.format(state.totalIncome, Currencies.DEFAULT),
                            color = amountColor(false),
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        SummaryFigure(
                            label = s[StringKey.SummaryOut],
                            value = Currencies.format(state.totalExpenses, Currencies.DEFAULT),
                            color = amountColor(true),
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        if (headerBalance != null) {
                            SummaryFigure(
                                label = s[StringKey.SummaryBalance],
                                value = Formats.money(headerBalance, state.balanceCurrency ?: "EUR"),
                                color = headerBalanceColor,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                        }
                    }
                    OverflowMenu(
                        items = buildList {
                            add(
                                OverflowItem(
                                    label = s[StringKey.Import],
                                    icon = Icons.Default.Add,
                                    disabledReason = s[StringKey.HintNeedsAccount].takeIf { state.accounts.isEmpty() },
                                    onClick = { openImport() },
                                ),
                            )
                            add(
                                OverflowItem(
                                    label = s[StringKey.AutoCategorize],
                                    icon = Icons.Default.Refresh,
                                    onClick = { vm.autoCategorizeTransactions() },
                                ),
                            )
                            add(
                                OverflowItem(
                                    label = s[StringKey.ReapplyRules],
                                    icon = Icons.Default.Done,
                                    disabledReason = s[StringKey.HintNothingShown].takeIf { state.transactions.isEmpty() },
                                    onClick = { vm.previewReapplyRules() },
                                ),
                            )
                            // Only while this session still remembers the last re-apply's previous categories
                            if (state.canUndoReapply) {
                                add(
                                    OverflowItem(
                                        label = s[StringKey.UndoReapplyRules],
                                        icon = Icons.Default.ArrowBack,
                                        onClick = { vm.undoReapplyRules() },
                                    ),
                                )
                            }
                            add(
                                OverflowItem(
                                    label = s[StringKey.ExportCsv],
                                    icon = AppIcons.FileDownload,
                                    disabledReason = s[StringKey.HintNothingToExport].takeIf { state.transactions.isEmpty() },
                                    onClick = { FileDialogs.saveFile(s[StringKey.ExportTransactions], "transactions-${LocalDate.now()}.csv")?.let { vm.exportCsv(it) } },
                                ),
                            )
                        },
                    )
                    // Without accounts the empty state's button is the only way to add a bank: no disabled "+ Add" here
                    if (state.accounts.isNotEmpty()) {
                        Button(onClick = { showAdd = true }) {
                            Icon(Icons.Default.Add, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text(s[StringKey.Add], maxLines = 1, softWrap = false)
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))
                MessageBanner(state.message, state.error, vm::clearMessages)

                // One row of 40 dp controls; it wraps instead of crushing the search field, which keeps a usable minimum
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    CompactSearchField(
                        value = state.searchQuery,
                        onValueChange = { vm.updateSearchQuery(it) },
                        placeholder = s[StringKey.SearchPartnerPurpose],
                        // The pills keep a 48 dp touch area around their 40 dp outline, so centre the field on them
                        modifier = Modifier.weight(1f).widthIn(min = 200.dp).align(Alignment.CenterVertically),
                    )
                    // While a single month is navigated (from the dashboard), its label wins over the period's;
                    // otherwise a custom From/To shows as "from – to" and only then the period's own label
                    DropdownSelector(
                        label = state.month?.let { s.month(it) }
                            ?: state.customRange?.let { (from, to) -> "${Formats.date(from)} – ${Formats.date(to)}" }
                            ?: s.period(state.period),
                        options = Period.entries + listOf<Period?>(null),
                        optionLabel = { it?.let { p -> s.period(p) } ?: s[StringKey.PeriodCustomRange] },
                        onSelect = { if (it == null) showCustomRangeDialog = true else vm.updatePeriod(it) },
                        leadingIcon = Icons.Default.DateRange,
                    )
                    DropdownSelector(
                        label = categoryFilterLabel(state.categoryFilter, state.categories, s),
                        options = listOf<CategoryFilter>(CategoryFilter.Any, CategoryFilter.None) + state.categories.map { CategoryFilter.Only(it.id) },
                        optionLabel = { categoryFilterLabel(it, state.categories, s) },
                        onSelect = { vm.updateCategoryFilter(it) },
                    )
                    // Only without the panel: the compact account picker takes the old account dropdown's place
                    if (!showPanel && state.accounts.isNotEmpty()) {
                        CompactAccountSelector(
                            accounts = state.accounts,
                            groups = state.groups,
                            selection = state.selection,
                            onSelect = { vm.selectAccounts(it) },
                        )
                    }
                    DropdownSelector(
                        label = if (state.direction == Direction.ALL) s[StringKey.DirectionAnyLabel] else s.direction(state.direction),
                        options = Direction.entries,
                        optionLabel = { if (it == Direction.ALL) s[StringKey.DirectionAnyLabel] else s.direction(it) },
                        onSelect = { vm.updateDirection(it) },
                    )
                    // Labelled in the primary currency, which the grand total lists first
                    AmountFilter(state.minAmount, state.maxAmount, state.grandTotal.keys.firstOrNull() ?: Currencies.DEFAULT) { min, max ->
                        vm.updateAmountRange(min, max)
                    }
                }

                Spacer(Modifier.height(16.dp))

                // With the chart below, the list takes the remaining height so the chart stays in view; otherwise
                // the card wraps its rows as before
                FinDeskCard(modifier = if (state.balanceChart != null) Modifier.fillMaxWidth().weight(1f, fill = false) else Modifier.fillMaxWidth()) {
                    Column {
                        if (state.selectedIds.isNotEmpty()) {
                            SelectionBar(
                                count = state.selectedIds.size,
                                totals = state.selectedTotals,
                                categories = state.categories,
                                onCategory = { vm.setCategoryForSelection(it) },
                                onTax = { vm.setTaxOverrideForSelection(it) },
                                onDelete = openMultiDelete,
                                onClear = vm::clearSelection,
                            )
                        }
                        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                            HeaderCell(s[StringKey.ColDate], COL_DATE_WEIGHT)
                            HeaderCell(s[StringKey.ColDescription], COL_DESCRIPTION_WEIGHT)
                            HeaderCell(s[StringKey.ColCategory], COL_CATEGORY_WEIGHT)
                            HeaderCell(s[StringKey.ColAmount], COL_AMOUNT_WEIGHT, TextAlign.End)
                            if (state.balances != null) HeaderCell(s[StringKey.ColBalance], COL_BALANCE_WEIGHT, TextAlign.End)
                            Spacer(Modifier.width(COL_ACTION_WIDTH))
                        }
                        HorizontalDivider()
                        // The pending block above renders its own rows, so the empty state only shows
                        // without them: a filter can hide every booking while pending lines exist, and a
                        // "nothing matches" message would contradict the pending rows still rendered
                        if (!state.isLoading && state.transactions.isEmpty() && state.pendingBookings.isEmpty()) {
                            EmptyState(
                                Icons.Default.List,
                                s[StringKey.NoTransactions],
                                if (state.accounts.isEmpty()) s[StringKey.NoTransactionsAddBank] else s[StringKey.NothingMatches],
                                action = if (state.accounts.isEmpty()) {
                                    { Button(onClick = { onNavigate(Screen.ACCOUNTS, NavigationArgs()) }) { Text(s[StringKey.AddBank]) } }
                                } else {
                                    null
                                },
                            )
                        }
                        LazyColumn(
                            // The keys act only while the list itself has focus: this wrapper holds the list only,
                            // never the search field or the filter pills, so their keys are always their own
                            modifier = Modifier
                                .focusRequester(listFocus)
                                .focusable()
                                .onPreviewKeyEvent { event ->
                                    if (event.type != KeyEventType.KeyDown) {
                                        return@onPreviewKeyEvent false
                                    }
                                    when {
                                        (event.isCtrlPressed || event.isMetaPressed) && event.key == Key.A -> {
                                            vm.selectAllShown()
                                            true
                                        }

                                        event.key == Key.Delete || event.key == Key.Backspace -> {
                                            if (state.selectedIds.isNotEmpty()) {
                                                openMultiDelete()
                                                true
                                            } else {
                                                false
                                            }
                                        }

                                        event.key == Key.Escape && state.selectedIds.isNotEmpty() -> {
                                            vm.clearSelection()
                                            true
                                        }

                                        else -> false
                                    }
                                },
                        ) {
                            // Pending (vorgemerkte) bank lines: greyed out above the list, with the fetch time.
                            // Not bookings, so no balance, category, menu or selection, and never clickable.
                            if (state.pendingBookings.isNotEmpty()) {
                                item(key = "pending-header") {
                                    Text(
                                        s.format(StringKey.PendingHeader, Formats.dateTime(state.pendingBookings.first().fetchedAt)),
                                        style = MaterialTheme.typography.titleSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)).padding(horizontal = 16.dp, vertical = 10.dp),
                                    )
                                }
                            }
                            itemsIndexed(state.pendingBookings, key = { i, _ -> "pending-$i" }) { _, line ->
                                PendingRow(line, showBalanceColumn = state.balances != null)
                            }
                            if (state.pendingBookings.isNotEmpty()) {
                                item(key = "pending-divider") { HorizontalDivider() }
                            }
                            items(state.transactions, key = { it.id }) { txn ->
                                val inMultiSelection = state.selectedIds.size >= 2 && txn.id in state.selectedIds
                                val deleteLabel = if (inMultiSelection) s.format(StringKey.DeleteSelectedCount, state.selectedIds.size) else s[StringKey.Delete]
                                RowContextMenu(
                                    menuItem(s[StringKey.OpenDetails]) { vm.selectTransaction(txn) },
                                    menuItem(s[StringKey.AttachReceipt]) { attachReceipt(txn.id) },
                                    txn.partnerName?.let { partner -> menuItem(s.format(StringKey.ShowAllFromPartner, partner)) { vm.showPartner(partner) } },
                                    menuItem(deleteLabel) {
                                        if (inMultiSelection) openMultiDelete() else confirmDelete = txn
                                    },
                                ) {
                                    TransactionRow(
                                        txn = txn,
                                        categories = state.categories,
                                        balance = state.balances?.get(txn.id),
                                        balanceCurrency = state.balanceCurrency,
                                        balanceIsLoan = state.currentBalanceIsLoan,
                                        isNew = txn.id in state.boldIds,
                                        isSelected = txn.id in state.selectedIds,
                                        deleteLabel = deleteLabel,
                                        onCategory = { vm.updateTransactionCategory(txn.id, it) },
                                        onDelete = { if (inMultiSelection) openMultiDelete() else confirmDelete = txn },
                                        onOpen = {
                                            vm.selectTransaction(txn)
                                            vm.clearSelection()
                                        },
                                        onToggleSelect = {
                                            vm.toggleSelection(txn.id)
                                            focusList()
                                        },
                                        onSelectRange = {
                                            vm.selectRange(txn.id)
                                            focusList()
                                        },
                                    )
                                }
                                HorizontalDivider()
                            }
                        }
                    }
                }

                // Same balances as the column, so the two can never disagree; only a single non-depot account shows it
                state.balanceChart?.let { chart ->
                    Spacer(Modifier.height(12.dp))
                    BalanceHistoryCard(
                        points = chart,
                        collapsed = state.chartCollapsed,
                        currency = state.balanceCurrency ?: Currencies.DEFAULT,
                        onToggle = { vm.toggleChart() },
                    )
                }
            }
        }
    }

    if (showCustomRangeDialog) {
        val today = LocalDate.now()
        val initial = state.customRange
            ?: state.month?.let { it.atDay(1) to it.atEndOfMonth() }
            ?: state.period.range(today).takeIf { (from, to) -> from != null && to != null }?.let { (from, to) -> from!! to to!! }
            ?: (today.withDayOfYear(1) to today)
        CustomRangeDialog(
            initial = initial,
            onApply = { from, to ->
                vm.setCustomRange(from, to)
                showCustomRangeDialog = false
            },
            onDismiss = { showCustomRangeDialog = false },
        )
    }
    state.selected?.let { txn ->
        TransactionDetailDialog(
            txn = txn,
            documents = state.selectedDocuments,
            categories = state.categories,
            account = state.accounts.firstOrNull { it.id == txn.accountId },
            suggestedPattern = vm.rulePatternFor(txn),
            onCategory = { vm.updateTransactionCategory(txn.id, it) },
            onTaxOverride = { vm.setTaxOverride(txn.id, it) },
            onAttach = { attachReceipt(txn.id) },
            onCreateRule = { pattern, categoryId -> vm.createRule(pattern, categoryId) },
            onClose = { vm.selectTransaction(null) },
        )
    }
    state.importPreview?.let { preview ->
        ImportDialog(
            preview = preview,
            accounts = state.accounts,
            importing = state.isImporting,
            onAccount = vm::selectImportAccount,
            onRemap = { vm.remapImport(it) },
            onCancel = vm::cancelImport,
            onImport = { vm.confirmImport(it && preview.balanceUpdateAllowed) },
        )
    }
    state.reapplyPreview?.let { preview ->
        ReapplyRulesDialog(
            preview = preview,
            onCancel = vm::cancelReapplyRules,
            onConfirm = { excluded -> vm.confirmReapplyRules(excluded) },
        )
    }
    if (showAdd) {
        AddTransactionDialog(
            accounts = state.accounts,
            categories = state.categories,
            initialAccountId = state.accountId,
            onDismiss = { showAdd = false },
            onConfirm = { accountId, date, amount, partner, purpose, categoryId ->
                vm.addManualTransaction(accountId, date, amount, partner, purpose, categoryId)
                showAdd = false
            },
        )
    }
    state.pendingDuplicate?.let { entry ->
        ConfirmDialog(
            title = s[StringKey.DuplicateTransactionTitle],
            text = s.format(StringKey.DuplicateTransactionText, Formats.date(entry.date), Formats.money(entry.amount, entry.currency)),
            confirmLabel = s[StringKey.AddAnyway],
            onConfirm = { vm.confirmDuplicate() },
            onDismiss = vm::dismissDuplicate,
        )
    }
    confirmDelete?.let { txn ->
        ConfirmDialog(
            title = s[StringKey.DeleteTransactionTitle],
            text = "${Formats.date(txn.date)} ${txn.displayTitle} ${Formats.money(txn.amount, txn.currency)}",
            onConfirm = { vm.deleteTransaction(txn.id) },
            onDismiss = { confirmDelete = null },
        )
    }
    confirmMultiDelete?.let { confirmation ->
        MultiDeleteDialog(
            confirmation = confirmation,
            onConfirm = {
                vm.deleteSelected(confirmation.ids)
                confirmMultiDelete = null
            },
            onDismiss = { confirmMultiDelete = null },
        )
    }
}

/** The batch-delete confirmation as captured when it opened: its id set never changes afterwards. */
internal data class MultiDeleteConfirmation(val ids: Set<Int>, val bookings: List<TransactionItem>)

/**
 * Confirmation of a batch delete: date range, per-currency total, the first bookings like the single delete's
 * text, and the warning about bookings a later bank sync can bring back. The initial focus is on Cancel, so a
 * reflexive Enter cannot confirm; the confirm button keeps the destructive style of [ConfirmDialog].
 */
@Composable
internal fun MultiDeleteDialog(confirmation: MultiDeleteConfirmation, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val s = strings()
    val cancelFocus = remember { FocusRequester() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(s.format(StringKey.DeleteTransactionsTitle, confirmation.ids.size)) },
        text = { MultiDeleteDialogContent(confirmation.bookings) },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
            ) { Text(s[StringKey.Delete]) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.focusRequester(cancelFocus)) { Text(s[StringKey.Cancel]) }
        },
    )
    LaunchedEffect(confirmation) { runCatching { cancelFocus.requestFocus() } }
}

/** The multi-delete dialog's body, extracted so the screenshot test can render the confirmation offscreen. */
@Composable
internal fun MultiDeleteDialogContent(bookings: List<TransactionItem>) {
    val s = strings()
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val from = bookings.minOf { it.date }
        val to = bookings.maxOf { it.date }
        Text(if (from == to) Formats.date(from) else "${Formats.date(from)}–${Formats.date(to)}")
        Text(
            Currencies.totals(bookings, Currencies.DEFAULT, { t -> t.currency }, { t -> t.amount })
                .entries.joinToString(" · ") { (currency, value) -> Formats.money(value, currency, signed = true) },
        )
        bookings.take(5).forEach { txn ->
            Text("${Formats.date(txn.date)} ${txn.displayTitle} ${Formats.money(txn.amount, txn.currency)}")
        }
        if (bookings.size > 5) Text(s.format(StringKey.AndMore, bookings.size - 5))
        Text(
            s[StringKey.DeleteSyncedHint],
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The period menu's custom From/To dialog: both dates in `dd.MM.yyyy`, validated like the Reports screen's
 * date-range inputs. Apply applies only a valid, non-reversed range; Cancel closes without changes.
 */
@Composable
private fun CustomRangeDialog(initial: Pair<LocalDate, LocalDate>, onApply: (LocalDate, LocalDate) -> Unit, onDismiss: () -> Unit) {
    val fmt = remember { DateTimeFormatter.ofPattern("dd.MM.yyyy") }
    var fromText by remember { mutableStateOf(initial.first.format(fmt)) }
    var toText by remember { mutableStateOf(initial.second.format(fmt)) }
    fun parse(text: String) = runCatching { LocalDate.parse(text.trim(), fmt) }.getOrNull()
    val from = parse(fromText)
    val to = parse(toText)
    val s = strings()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(s[StringKey.CustomRange]) },
        text = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.width(320.dp)) {
                OutlinedTextField(
                    fromText,
                    { fromText = it },
                    label = { Text(s[StringKey.FromDate]) },
                    singleLine = true,
                    isError = from == null,
                    supportingText = { if (from == null) Text(s[StringKey.EnterValidDate]) },
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    toText,
                    { toText = it },
                    label = { Text(s[StringKey.ToDate]) },
                    singleLine = true,
                    isError = to == null || (from != null && to != null && from.isAfter(to)),
                    supportingText = {
                        if (to == null) {
                            Text(s[StringKey.EnterValidDate])
                        } else if (from != null && to != null && from.isAfter(to)) {
                            Text(s[StringKey.InvalidDateRange])
                        }
                    },
                    modifier = Modifier.weight(1f),
                )
            }
        },
        confirmButton = {
            Button(enabled = from != null && to != null && !from.isAfter(to), onClick = { onApply(from!!, to!!) }) { Text(s[StringKey.Apply]) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(s[StringKey.Cancel]) } },
    )
}

/**
 * Confirm dialog of the re-apply preview: one checkbox row per (from, to) group, all ticked by default, so the
 * user unticks whole groups to leave their bookings out. Confirm hands the excluded (from, to) pairs back.
 */
@Composable
private fun ReapplyRulesDialog(
    preview: ReapplyPreview,
    onCancel: () -> Unit,
    onConfirm: (Set<Pair<Int?, Int>>) -> Unit,
) {
    val s = strings()
    var ticked by remember(preview) { mutableStateOf(preview.groups.associate { (it.fromCategoryId to it.toCategoryId) to true }) }
    val tickedCount = preview.groups.filter { group -> ticked[group.fromCategoryId to group.toCategoryId] ?: true }.sumOf { it.count }

    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(s[StringKey.ReapplyRulesTitle]) },
        text = {
            Column(
                modifier = Modifier.width(560.dp).heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(s.format(StringKey.ReapplyRulesSummary, preview.changes.size, preview.shownCount))
                Text(
                    s[StringKey.ReapplyRulesWarning],
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                preview.groups.forEach { group ->
                    val pair = group.fromCategoryId to group.toCategoryId
                    val sumText = group.sum?.let { Formats.money(it, group.currency) }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = ticked[pair] ?: true,
                            onCheckedChange = { checked -> ticked = ticked + (pair to checked) },
                        )
                        Column(Modifier.weight(1f)) {
                            Text("${group.fromName} → ${group.toName}", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                listOfNotNull("${group.count}", sumText).joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(enabled = tickedCount > 0, onClick = { onConfirm(ticked.filterValues { !it }.keys) }) {
                Text(s.format(StringKey.ReapplyRulesConfirm, tickedCount))
            }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text(s[StringKey.Cancel]) } },
    )
}

/**
 * The bar above the list while a multi-selection is active: how many bookings are selected, their totals per
 * currency (currencies are never added together), the Category menu, the Tax menu, the batch delete and the
 * clear action.
 */
@Composable
private fun SelectionBar(
    count: Int,
    totals: Map<String, BigDecimal>,
    categories: List<CategoryItem>,
    onCategory: (Int?) -> Unit,
    onTax: (Boolean?) -> Unit,
    onDelete: () -> Unit,
    onClear: () -> Unit,
) {
    val s = strings()
    var taxMenu by remember { mutableStateOf(false) }
    var categoryMenu by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            s.format(StringKey.SelectedCount, count),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            totals.entries.joinToString(" · ") { (currency, value) -> Formats.money(value, currency, signed = true) },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.weight(1f))
        Box {
            Button(onClick = { categoryMenu = true }) {
                Text(s[StringKey.CategoryMenu], maxLines = 1, softWrap = false)
                Icon(Icons.Default.ArrowDropDown, contentDescription = null)
            }
            DropdownMenu(expanded = categoryMenu, onDismissRequest = { categoryMenu = false }) {
                DropdownMenuItem(text = { Text(s[StringKey.Uncategorized]) }, onClick = {
                    categoryMenu = false
                    onCategory(null)
                })
                categories.forEach { c ->
                    DropdownMenuItem(
                        text = { Text(c.name + if (c.isTaxRelevant) "  (${s[StringKey.Tax]})" else "") },
                        onClick = {
                            categoryMenu = false
                            onCategory(c.id)
                        },
                    )
                }
            }
        }
        Box {
            OutlinedButton(onClick = { taxMenu = true }) {
                Text(s[StringKey.TaxMenu], maxLines = 1, softWrap = false)
                Icon(Icons.Default.ArrowDropDown, contentDescription = null)
            }
            DropdownMenu(expanded = taxMenu, onDismissRequest = { taxMenu = false }) {
                DropdownMenuItem(text = { Text(s[StringKey.MarkTaxRelevant]) }, onClick = {
                    taxMenu = false
                    onTax(true)
                })
                DropdownMenuItem(text = { Text(s[StringKey.MarkNotTaxRelevant]) }, onClick = {
                    taxMenu = false
                    onTax(false)
                })
                DropdownMenuItem(text = { Text(s[StringKey.ResetTaxAutomatic]) }, onClick = {
                    taxMenu = false
                    onTax(null)
                })
            }
        }
        OutlinedButton(onClick = onClear) { Text(s[StringKey.ClearSelection]) }
        OutlinedButton(
            onClick = onDelete,
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.error),
        ) { Text(s[StringKey.DeleteSelected]) }
    }
}

/** One labelled figure of the transactions summary strip: a small label above a strong value. */
@Composable
private fun SummaryFigure(label: String, value: String, color: Color, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
        Text(
            value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun categoryFilterLabel(filter: CategoryFilter, categories: List<CategoryItem>, s: Strings): String = when (filter) {
    CategoryFilter.Any -> s[StringKey.AllCategories]
    CategoryFilter.None -> s[StringKey.Uncategorized]
    is CategoryFilter.Only -> categories.firstOrNull { it.id == filter.categoryId }?.name ?: s[StringKey.NoTransactions]
}

@Composable
private fun RowScope.HeaderCell(text: String, weight: Float, align: TextAlign = TextAlign.Start) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.weight(weight),
        textAlign = align,
    )
}

/**
 * The booking list's shared column weights: header, [TransactionRow] and [PendingRow] must stay in one copy,
 * so a change to one cannot desynchronise the others.
 */
private const val COL_DATE_WEIGHT = 1f
private const val COL_DESCRIPTION_WEIGHT = 3f
private const val COL_CATEGORY_WEIGHT = 1.4f
private const val COL_AMOUNT_WEIGHT = 1.2f

/** The Balance column is the amount's width and only present for one account's running balance. */
private const val COL_BALANCE_WEIGHT = 1.2f

/** The ⋮ menu's cell at the row end: 48 dp touch area around the 32 dp button. */
private val COL_ACTION_WIDTH = 40.dp

/** The balance chart below the booking list: a foldable card over the running-balance column's own points. */
@Composable
private fun BalanceHistoryCard(
    points: List<Pair<LocalDate, BigDecimal>>,
    collapsed: Boolean,
    currency: String,
    onToggle: () -> Unit,
) {
    val s = strings()
    FinDeskCard(modifier = Modifier.fillMaxWidth()) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onToggle, modifier = Modifier.size(32.dp)) {
                    Icon(
                        if (collapsed) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowUp,
                        contentDescription = s[if (collapsed) StringKey.Expand else StringKey.Collapse],
                        modifier = Modifier.size(20.dp),
                    )
                }
                Text(
                    s[StringKey.BalanceHistory],
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                // The chart's axis is chosen from the points; say when it leaves zero out, where money looks like height alone
                if (!collapsed && points.size >= 2) {
                    val values = points.map { it.second.toDouble() }
                    val ticks = niceTicks(values.min(), values.max())
                    if (ticks.first() > 0.0 || ticks.last() < 0.0) {
                        Text(
                            s[StringKey.AxisNotFromZero],
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(end = 12.dp),
                        )
                    }
                }
            }
            if (!collapsed) {
                BalanceLineChart(
                    points = points,
                    title = s[StringKey.BalanceHistory],
                    modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 12.dp).height(160.dp),
                    currency = currency,
                )
            }
        }
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun TransactionRow(
    txn: TransactionItem,
    categories: List<CategoryItem>,
    balance: de.findesk.util.RunningBalance.Entry?,
    balanceCurrency: String?,
    /** Whether the running balance belongs to a loan: it is negative by nature, so it never warns red. */
    balanceIsLoan: Boolean,
    isNew: Boolean,
    isSelected: Boolean,
    deleteLabel: String,
    onCategory: (Int?) -> Unit,
    onDelete: () -> Unit,
    onOpen: () -> Unit,
    onToggleSelect: () -> Unit,
    onSelectRange: () -> Unit,
) {
    var categoryMenu by remember { mutableStateOf(false) }
    var rowMenu by remember { mutableStateOf(false) }
    // Not state: only the click handler reads it, so a press must not recompose the row
    val pressModifiers = remember {
        object {
            var value: PointerKeyboardModifiers? = null
        }
    }
    val s = strings()
    // Display only: the list shows cleaned bank text, the stored purpose and partner stay raw (see BankText)
    val title = remember(txn) { txn.displayTitle }
    val secondary = remember(txn) { BankText.secondaryLine(txn) }
    // The bar repeats the selection in a colour that reads in both themes; the tinted background alone is faint in dark mode
    val selectionBarColor = MaterialTheme.colorScheme.primary

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (isSelected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
            .drawBehind { if (isSelected) drawRect(selectionBarColor, size = Size(3.dp.toPx(), size.height)) }
            .semantics {
                contentDescription = listOfNotNull(title, secondary).joinToString(" · ")
                selected = isSelected
            }
            // Remembers the keyboard modifiers of the press without consuming it, so the one `clickable` below
            // decides: Cmd/Ctrl-click (Platform decides which) toggles, Shift-click extends, a plain click opens.
            // Separate `onClick(keyboardModifiers = …)` handlers never saw the click: `clickable` took it first.
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (event.type == PointerEventType.Press) pressModifiers.value = event.keyboardModifiers
                    }
                }
            }
            .clickable(
                onClick = {
                    val mods = pressModifiers.value
                    // A keyboard activation (Enter) has no press of its own, so it never reuses an old one
                    pressModifiers.value = null
                    when {
                        mods != null && (if (Platform.isMac) mods.isMetaPressed else mods.isCtrlPressed) -> onToggleSelect()
                        mods != null && mods.isShiftPressed -> onSelectRange()
                        else -> onOpen()
                    }
                },
                onClickLabel = s[StringKey.OpenDetails],
                role = Role.Button,
            )
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The dot sits inside the date cell, so a new row's columns stay aligned with the header
        Row(Modifier.weight(COL_DATE_WEIGHT), verticalAlignment = Alignment.CenterVertically) {
            if (isNew) {
                // A booking a bank sync brought in and the user hasn't opened yet: a dot plus bold type
                Box(
                    Modifier
                        .size(6.dp)
                        .background(MaterialTheme.colorScheme.primary, CircleShape)
                        .semantics { contentDescription = s[StringKey.NewBooking] },
                )
                Spacer(Modifier.width(6.dp))
            }
            Text(
                Formats.date(txn.date),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (isNew) FontWeight.Bold else FontWeight.Normal,
            )
        }
        Column(Modifier.weight(COL_DESCRIPTION_WEIGHT)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = if (isNew) FontWeight.Bold else FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (txn.documentCount > 0) {
                    Spacer(Modifier.width(6.dp))
                    Icon(AppIcons.AttachFile, contentDescription = s.format(StringKey.DocumentsCount, txn.documentCount), modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
                }
            }
            if (!secondary.isNullOrBlank()) {
                Text(secondary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Box(Modifier.weight(COL_CATEGORY_WEIGHT)) {
            val categoryClick = Modifier.clickable(
                onClick = { categoryMenu = true },
                onClickLabel = s.format(StringKey.ChangeCategory, txn.categoryName ?: s[StringKey.Uncategorized]),
                role = Role.Button,
            )
            if (txn.categoryName == null) {
                // Quiet, background-free text with the badge's text padding, so the columns still line up
                Text(
                    s[StringKey.Uncategorized],
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    modifier = categoryClick.padding(horizontal = 8.dp, vertical = 4.dp),
                )
            } else {
                CategoryBadge(txn.categoryName, txn.categoryColor, categoryClick)
            }
            DropdownMenu(expanded = categoryMenu, onDismissRequest = { categoryMenu = false }) {
                DropdownMenuItem(text = { Text(s[StringKey.Uncategorized]) }, onClick = {
                    categoryMenu = false
                    onCategory(null)
                })
                categories.forEach { c ->
                    DropdownMenuItem(
                        text = { Text(c.name + if (c.isTaxRelevant) "  (${s[StringKey.Tax]})" else "") },
                        onClick = {
                            categoryMenu = false
                            onCategory(c.id)
                        },
                    )
                }
            }
        }
        Text(
            Formats.money(txn.amount, txn.currency, signed = true),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (isNew) FontWeight.Bold else FontWeight.SemiBold,
            color = amountColor(txn.isExpense),
            modifier = Modifier.weight(COL_AMOUNT_WEIGHT),
            textAlign = TextAlign.End,
        )
        if (balance != null) {
            val currency = balanceCurrency ?: txn.currency
            Row(
                modifier = Modifier.weight(COL_BALANCE_WEIGHT),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val bankDifference = balance.bankDifference
                if (bankDifference != null) {
                    val message = balanceMismatchText(s, balance.balance, bankDifference, currency)
                    TooltipArea(
                        tooltip = {
                            Surface(shape = RoundedCornerShape(4.dp), color = MaterialTheme.colorScheme.inverseSurface, shadowElevation = 2.dp) {
                                Text(
                                    message,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.inverseOnSurface,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                )
                            }
                        },
                        delayMillis = 300,
                    ) {
                        Icon(
                            Icons.Default.Warning,
                            contentDescription = message,
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                    Spacer(Modifier.width(4.dp))
                }
                Text(
                    Formats.money(balance.balance, currency),
                    style = MaterialTheme.typography.bodyMedium,
                    color = balanceToneColor(balanceTone(balance.balance, balanceIsLoan)),
                    textAlign = TextAlign.End,
                )
            }
        }
        Box(Modifier.width(COL_ACTION_WIDTH), contentAlignment = Alignment.CenterEnd) {
            IconButton(onClick = { rowMenu = true }, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.MoreVert, contentDescription = s[StringKey.More], modifier = Modifier.size(18.dp))
            }
            DropdownMenu(expanded = rowMenu, onDismissRequest = { rowMenu = false }) {
                DropdownMenuItem(text = { Text(deleteLabel) }, onClick = {
                    rowMenu = false
                    onDelete()
                })
            }
        }
    }
}

/**
 * One pending (vorgemerkte) bank line, greyed out like the booking rows around it and in their columns: date,
 * partner and purpose, the amount in italics, and empty Category and Balance cells plus the ⋮ menu's spacer, so
 * everything aligns with [TransactionRow] (the Balance cell follows its column's visibility). Not a booking, so
 * no category, balance, menu, selection or click.
 */
@Composable
private fun PendingRow(line: PendingBookingItem, showBalanceColumn: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            Formats.date(line.date),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(COL_DATE_WEIGHT),
        )
        Column(Modifier.weight(COL_DESCRIPTION_WEIGHT)) {
            Text(
                BankText.displayPartner(line.partnerName) ?: BankText.displayPurpose(line.purpose) ?: "—",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (line.partnerName != null) {
                BankText.displayPurpose(line.purpose)?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        // Empty cells, so the pending amount sits under Amount and not under Balance
        Spacer(Modifier.weight(COL_CATEGORY_WEIGHT))
        Text(
            Formats.money(line.amount, line.currency, signed = true),
            style = MaterialTheme.typography.bodyMedium,
            fontStyle = FontStyle.Italic,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(COL_AMOUNT_WEIGHT),
            textAlign = TextAlign.End,
        )
        if (showBalanceColumn) Spacer(Modifier.weight(COL_BALANCE_WEIGHT))
        Spacer(Modifier.width(COL_ACTION_WIDTH))
    }
}

@Composable
private fun AddTransactionDialog(
    accounts: List<AccountItem>,
    categories: List<CategoryItem>,
    initialAccountId: Int?,
    onDismiss: () -> Unit,
    onConfirm: (Int, LocalDate, BigDecimal, String?, String?, Int?) -> Unit,
) {
    val dateFormat = remember { DateTimeFormatter.ofPattern("dd.MM.yyyy") }
    var account by remember { mutableStateOf(accounts.firstOrNull { it.id == initialAccountId } ?: accounts.first()) }
    var dateText by remember { mutableStateOf(LocalDate.now().format(dateFormat)) }
    var amountText by remember { mutableStateOf("") }
    var isExpense by remember { mutableStateOf(true) }
    var partner by remember { mutableStateOf("") }
    var purpose by remember { mutableStateOf("") }
    var category by remember { mutableStateOf<CategoryItem?>(null) }
    val s = strings()

    val date = try {
        LocalDate.parse(dateText.trim(), dateFormat)
    } catch (e: DateTimeParseException) {
        null
    }
    val amount = Formats.parseAmount(amountText)?.abs()?.takeIf { it.signum() > 0 }
    val valid = date != null && amount != null && (partner.isNotBlank() || purpose.isNotBlank())

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(s[StringKey.AddTransaction]) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.width(420.dp)) {
                DropdownSelector(
                    label = accountLabel(account),
                    options = accounts,
                    optionLabel = { accountLabel(it) },
                    onSelect = { account = it },
                )
                SingleChoiceRow(
                    options = listOf(true, false),
                    selected = isExpense,
                    label = { if (it) s[StringKey.Expense] else s[StringKey.Income] },
                    onSelect = { isExpense = it },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        dateText,
                        { dateText = it },
                        label = { Text(s[StringKey.DateFormat]) },
                        singleLine = true,
                        isError = date == null,
                        supportingText = { if (date == null) Text(s[StringKey.EnterValidDate]) },
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        amountText,
                        { amountText = it },
                        label = { Text(s[StringKey.AmountEuro]) },
                        singleLine = true,
                        isError = amountText.isNotEmpty() && amount == null,
                        supportingText = { if (amountText.isNotEmpty() && amount == null) Text(s[StringKey.EnterValidAmount]) },
                        modifier = Modifier.weight(1f),
                    )
                }
                OutlinedTextField(partner, { partner = it }, label = { Text(s[StringKey.PartnerPayee]) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(purpose, { purpose = it }, label = { Text(s[StringKey.Purpose]) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                DropdownSelector(
                    label = category?.name ?: s[StringKey.CategoryAuto],
                    options = listOf<CategoryItem?>(null) + categories,
                    optionLabel = { it?.name ?: s[StringKey.AutoRules] },
                    onSelect = { category = it },
                )
            }
        },
        confirmButton = {
            Button(enabled = valid, onClick = {
                val signed = if (isExpense) amount!!.negate() else amount!!
                onConfirm(account.id, date!!, signed, partner, purpose, category?.id)
            }) { Text(s[StringKey.Add]) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(s[StringKey.Cancel]) } },
    )
}

@Composable
internal fun ImportDialog(
    preview: ImportPreview,
    accounts: List<AccountItem>,
    importing: Boolean,
    onAccount: (Int) -> Unit,
    onRemap: (CsvMapping) -> Unit,
    onCancel: () -> Unit,
    onImport: (Boolean) -> Unit,
) {
    var updateBalance by remember(preview.files, preview.accountId, preview.balanceUpdateAllowed) { mutableStateOf(preview.balanceUpdateAllowed) }
    val s = strings()

    AlertDialog(
        onDismissRequest = { if (!importing) onCancel() },
        title = {
            Text(
                if (preview.files.size == 1) {
                    s.format(StringKey.ImportFile, preview.files.single().name)
                } else {
                    s.format(StringKey.ImportFiles, preview.files.size)
                },
            )
        },
        text = { ImportDialogContent(preview, accounts, updateBalance, onUpdateBalance = { updateBalance = it }, onAccount = onAccount, onRemap = onRemap) },
        confirmButton = {
            Button(enabled = !importing && preview.accountId != null && preview.bookingCount > 0, onClick = { onImport(updateBalance) }) {
                Text(if (importing) s[StringKey.Importing] else s.format(StringKey.ImportCount, preview.bookingCount))
            }
        },
        dismissButton = { TextButton(enabled = !importing, onClick = onCancel) { Text(s[StringKey.Cancel]) } },
    )
}

/**
 * The import dialog's body, extracted so the screenshot test can render it without a platform [AlertDialog]
 * window. [updateBalance] lives in [ImportDialog]; the composable renders and changes it.
 */
@Composable
internal fun ImportDialogContent(
    preview: ImportPreview,
    accounts: List<AccountItem>,
    updateBalance: Boolean,
    onUpdateBalance: (Boolean) -> Unit,
    onAccount: (Int) -> Unit,
    onRemap: (CsvMapping) -> Unit,
) {
    val multi = preview.files.size > 1
    val account = accounts.firstOrNull { it.id == preview.accountId }
    val newest = preview.newest.statement
    // Closing balances only apply to the selected account when their currencies match, or the file doesn't say
    val closingBalance = newest.closingBalance?.takeIf { account == null || it.currency == null || it.currency == account.currency }
    val hasBalances = newest.bookings.any { it.balanceAfter != null } || closingBalance != null
    val s = strings()

    Column(Modifier.width(720.dp).heightIn(max = 560.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (!multi) {
            // One file: exactly as before
            val st = newest
            val csv = st.csv
            val delimLabel = csv?.let { if (it.delimiter == '\t') s[StringKey.TabDelimiter] else "'${it.delimiter}'" } ?: ""
            Text(
                "${st.formatLabel}" +
                    (if (st.format == StatementFormat.XLSX) "" else csv?.let { " · ${it.charsetName} · delimiter $delimLabel" } ?: "") +
                    " · ${s.format(StringKey.BookingCount, st.bookings.size)}" +
                    (if (st.pendingSkipped > 0) " · ${s.format(StringKey.PendingLeftOut, st.pendingSkipped)}" else "") +
                    (if (st.skipped.isNotEmpty()) " · ${s.format(StringKey.UnreadableCount, st.skipped.size)}" else ""),
                style = MaterialTheme.typography.bodyMedium,
            )
        } else {
            // In the summary line the total count only
            Text(s.format(StringKey.BookingCount, preview.bookingCount), style = MaterialTheme.typography.bodyMedium)
            preview.entries.forEach { entry ->
                val bookings = entry.statement.bookings
                val from = bookings.minOfOrNull { it.date }
                val to = bookings.maxOfOrNull { it.date }
                val range = if (from != null && to != null) {
                    "${Formats.date(from)}–${Formats.date(to)}"
                } else {
                    null
                }
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        entry.file.name,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(2f),
                    )
                    Text(
                        s.format(StringKey.BookingCount, bookings.size),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                        textAlign = TextAlign.End,
                    )
                    Text(
                        range ?: "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1.4f),
                        textAlign = TextAlign.End,
                    )
                    listOfNotNull(
                        entry.statement.pendingSkipped.takeIf { it > 0 }?.let { s.format(StringKey.PendingLeftOut, it) },
                        entry.statement.skipped.takeIf { it.isNotEmpty() }?.let { s.format(StringKey.UnreadableCount, it.size) },
                    ).joinToString(" · ").takeIf { it.isNotEmpty() }?.let { message ->
                        Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                }
            }
            preview.failures.forEach { failure ->
                Text(
                    "${failure.fileName}: ${failure.message}",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (preview.failures.isNotEmpty()) {
                Text(
                    s.format(StringKey.ImportFilesSkipped, preview.failures.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(s[StringKey.IntoAccount], fontWeight = FontWeight.Medium)
            DropdownSelector(
                label = account?.let { accountLabel(it) } ?: s[StringKey.ChooseAccount],
                options = accounts,
                optionLabel = { accountLabel(it) },
                onSelect = { onAccount(it.id) },
            )
        }
        if (multi) {
            preview.entries.forEach { entry ->
                val iban = entry.statement.accountIban
                if (iban != null && account != null && Iban.normalize(iban) != account.iban) {
                    Text(
                        s.format(StringKey.FileBelongsToNamed, entry.file.name, Formats.iban(iban)),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        } else if (newest.accountIban != null && account != null && newest.accountIban != account.iban) {
            Text(
                s.format(StringKey.FileBelongsTo, Formats.iban(newest.accountIban)),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        val wrongCurrency = account?.let { a -> preview.entries.sumOf { e -> e.statement.bookings.count { it.currency != null && it.currency != a.currency } } } ?: 0
        if (wrongCurrency > 0) {
            Text(
                s.format(StringKey.WrongCurrencySkipped, wrongCurrency, account!!.currency),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }

        val firstCsvEntry = preview.entries.firstOrNull { it.statement.csv != null }
        val csv = firstCsvEntry?.statement?.csv
        if (csv != null) {
            Text(s[StringKey.Columns], style = MaterialTheme.typography.labelLarge)
            if (multi) {
                // Column indexes only mean something for files with the same header row; the hint says how many are covered
                val same = preview.entries.count { it.statement.csv?.headers == csv.headers }
                Text(
                    s.format(StringKey.ColumnsApplyToSame, same),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            val m = csv.mapping
            val fields: List<Triple<StringKey, Int?, (Int?) -> CsvMapping>> = listOf(
                Triple(StringKey.ColDate, m.date) { i -> m.copy(date = i) },
                Triple(StringKey.ColAmount, m.amount) { i -> m.copy(amount = i, debit = if (i != null) null else m.debit, credit = if (i != null) null else m.credit) },
                Triple(StringKey.ColDebit, m.debit) { i -> m.copy(debit = i, amount = if (i != null) null else m.amount) },
                Triple(StringKey.ColCredit, m.credit) { i -> m.copy(credit = i, amount = if (i != null) null else m.amount) },
                Triple(StringKey.ColPartner, m.partner) { i -> m.copy(partner = i) },
                Triple(StringKey.ColPurpose, m.purpose) { i -> m.copy(purpose = i) },
                Triple(StringKey.ColBalance, m.balance) { i -> m.copy(balance = i) },
            )
            fields.chunked(4).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { (labelKey, index, update) ->
                        DropdownSelector(
                            label = "${s[labelKey]}: ${index?.let { csv.headers.getOrNull(it) } ?: "—"}",
                            options = listOf<Int?>(null) + csv.headers.indices.toList(),
                            optionLabel = { i -> i?.let { csv.headers[it].ifBlank { s.format(StringKey.ColumnN, it + 1) } } ?: s[StringKey.NoneDash] },
                            onSelect = { onRemap(update(it)) },
                        )
                    }
                }
            }
        }

        val allBookings = preview.entries.flatMap { it.statement.bookings }
        if (allBookings.isNotEmpty()) {
            Text(s[StringKey.Preview], style = MaterialTheme.typography.labelLarge)
            allBookings.take(8).forEach { b ->
                Row(Modifier.fillMaxWidth()) {
                    Text(Formats.date(b.date), style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(90.dp))
                    Text(
                        listOfNotNull(b.partnerName, b.purpose).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        Formats.money(b.amount, b.currency ?: account?.currency ?: "EUR", signed = true),
                        style = MaterialTheme.typography.bodySmall,
                        color = amountColor(b.amount.signum() < 0),
                    )
                }
            }
            if (allBookings.size > 8) Text(s.format(StringKey.AndMore, allBookings.size - 8), style = MaterialTheme.typography.bodySmall)
        }
        preview.entries.flatMap { it.statement.skipped }.take(5).forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }

        if (hasBalances && preview.balanceUpdateAllowed) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = updateBalance, onCheckedChange = onUpdateBalance)
                val runningBalances = newest.bookings.any { it.balanceAfter != null }
                val closing = closingBalance
                Text(
                    if (runningBalances) {
                        s[StringKey.SetAccountBalance]
                    } else {
                        val money = Formats.money(closing!!.amount, closing.currency ?: account?.currency ?: "EUR")
                        if (closing.pendingExcluded > 0) {
                            s.format(StringKey.SetAccountBalanceClosingPending, money, closing.pendingExcluded)
                        } else {
                            s.format(StringKey.SetAccountBalanceClosing, money)
                        }
                    },
                )
            }
        } else if (hasBalances && account != null) {
            Text(
                s[StringKey.NewerBookingsUnchanged],
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            s[StringKey.BookingsSkippedHint],
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The amount bound as one pill: "Any amount" until a bound is set, then "≥ €10.00", "≤ €50.00" or "€10.00–€50.00".
 * Its menu keeps the old live parsing: blank or parseable input applies (absolute values), anything else waits.
 */
@Composable
internal fun AmountFilter(min: BigDecimal?, max: BigDecimal?, currency: String = Currencies.DEFAULT, onChange: (BigDecimal?, BigDecimal?) -> Unit) {
    val s = strings()
    var expanded by remember { mutableStateOf(false) }
    var minText by remember { mutableStateOf(min?.toPlainString().orEmpty()) }
    var maxText by remember { mutableStateOf(max?.toPlainString().orEmpty()) }
    fun apply() = onChange(Formats.parseAmount(minText)?.abs(), Formats.parseAmount(maxText)?.abs())

    val labelText = when {
        min != null && max != null -> "${Formats.money(min, currency)}–${Formats.money(max, currency)}"
        min != null -> "≥ ${Formats.money(min, currency)}"
        max != null -> "≤ ${Formats.money(max, currency)}"
        else -> s[StringKey.AmountFilterAny]
    }
    Box {
        OutlinedButton(
            onClick = { expanded = true },
            shape = RoundedCornerShape(50),
            colors = OutlinedButtonStyle.colors(),
            border = OutlinedButtonStyle.border(),
        ) {
            Text(labelText, maxLines = 1)
            Icon(Icons.Default.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            Column(Modifier.width(240.dp).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CompactAmountField(
                        value = minText,
                        onValueChange = {
                            minText = it
                            if (it.isBlank() || Formats.parseAmount(it) != null) apply()
                        },
                        placeholder = s[StringKey.MinEuro],
                        modifier = Modifier.weight(1f),
                    )
                    CompactAmountField(
                        value = maxText,
                        onValueChange = {
                            maxText = it
                            if (it.isBlank() || Formats.parseAmount(it) != null) apply()
                        },
                        placeholder = s[StringKey.MaxEuro],
                        modifier = Modifier.weight(1f),
                    )
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = {
                        minText = ""
                        maxText = ""
                        onChange(null, null)
                        expanded = false
                    }) { Text(s[StringKey.Reset]) }
                }
            }
        }
    }
}

@Composable
private fun CompactAmountField(value: String, onValueChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier) {
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        modifier = modifier.height(40.dp),
        decorationBox = { innerTextField ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
                    .padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.weight(1f)) {
                    if (value.isEmpty()) {
                        Text(
                            placeholder,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    innerTextField()
                }
            }
        },
    )
}

@Composable
internal fun TransactionDetailDialog(
    txn: TransactionItem,
    documents: List<de.findesk.model.DocumentItem>,
    categories: List<CategoryItem>,
    account: AccountItem?,
    suggestedPattern: String,
    onCategory: (Int?) -> Unit,
    onTaxOverride: (Boolean?) -> Unit,
    onAttach: () -> Unit,
    onCreateRule: (String, Int) -> Unit,
    onClose: () -> Unit,
) {
    var pattern by remember(txn.id) { mutableStateOf(suggestedPattern) }
    var ruleCategory by remember(txn.id) { mutableStateOf(categories.firstOrNull { it.id == txn.categoryId }) }
    val patternValid = pattern.isNotBlank() && runCatching { Regex(pattern) }.isSuccess
    val s = strings()

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(txn.displayTitle) },
        text = {
            Column(Modifier.width(560.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    Formats.money(txn.amount, txn.currency, signed = true),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = amountColor(txn.isExpense),
                )
                DetailRow(s[StringKey.DetailDate], Formats.date(txn.date))
                DetailRow(s[StringKey.DetailAccount], account?.let { accountLabel(it) } ?: Formats.iban(txn.accountIban))
                txn.partnerName?.let { DetailRow(s[StringKey.DetailPartner], it) }
                PaymentProviders.merchantOf(txn.purpose, txn.partnerName)?.let { DetailRow(s[StringKey.DetailShop], it) }
                txn.purpose?.let { DetailRow(s[StringKey.DetailPurpose], it) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(s[StringKey.DetailCategory], fontWeight = FontWeight.Medium, modifier = Modifier.width(90.dp))
                    DropdownSelector(
                        label = txn.categoryName ?: s[StringKey.Uncategorized],
                        options = listOf<CategoryItem?>(null) + categories,
                        optionLabel = { it?.let { c -> c.name + if (c.isTaxRelevant) " (${s[StringKey.Tax]})" else "" } ?: s[StringKey.Uncategorized] },
                        onSelect = { onCategory(it?.id) },
                    )
                    if (txn.isTaxRelevant) {
                        Spacer(Modifier.width(8.dp))
                        CategoryBadge(s[StringKey.TaxRelevant], "#F59E0B")
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(s[StringKey.TaxMenu], fontWeight = FontWeight.Medium, modifier = Modifier.width(90.dp))
                    Text(
                        taxStateLine(txn, s),
                        modifier = Modifier.weight(1f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    DropdownSelector(
                        label = taxChoiceLabel(txn.taxOverride, s),
                        options = listOf<Boolean?>(null, true, false),
                        optionLabel = { taxChoiceLabel(it, s) },
                        onSelect = { onTaxOverride(it) },
                    )
                }

                HorizontalDivider()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(s[StringKey.LinkedTransaction], style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                    TextButton(onClick = onAttach) { Text(s[StringKey.AttachReceiptEllipsis]) }
                }
                if (documents.isEmpty()) Text(s[StringKey.NoDocumentsLinked], style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                documents.forEach { d ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(d.fileName, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        TextButton(onClick = {
                            val f = java.io.File(d.filePath)
                            if (f.exists() && java.awt.Desktop.isDesktopSupported()) Thread { java.awt.Desktop.getDesktop().open(f) }.start()
                        }) { Text(s[StringKey.Open]) }
                    }
                }

                if (categories.isNotEmpty()) {
                    HorizontalDivider()
                    Text(s[StringKey.CategorizeSimilar], style = MaterialTheme.typography.labelLarge)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            pattern,
                            { pattern = it },
                            label = { Text(s[StringKey.PatternRegex]) },
                            singleLine = true,
                            isError = !patternValid,
                            supportingText = { if (!patternValid) Text(s[StringKey.PatternInvalid]) },
                            modifier = Modifier.weight(1f),
                        )
                        DropdownSelector(ruleCategory?.name ?: s[StringKey.DetailCategory], categories, { it.name }, { ruleCategory = it })
                        Button(enabled = patternValid && ruleCategory != null, onClick = { onCreateRule(pattern, ruleCategory!!.id) }) { Text(s[StringKey.CreateRule]) }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text(s[StringKey.Close]) } },
    )
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row {
        Text(label, fontWeight = FontWeight.Medium, modifier = Modifier.width(90.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

/** The effective tax state and its deciding layer as one line, for the details dialog's Tax row. */
internal fun taxStateLine(txn: TransactionItem, s: Strings): String {
    val base = if (txn.isTaxRelevant) s[StringKey.TaxRelevantChoice] else s[StringKey.TaxNotRelevantChoice]
    return when (txn.taxSource) {
        TaxSource.MANUAL -> s.format(StringKey.TaxSetByYou, base)
        TaxSource.RULE -> s.format(StringKey.TaxFromRule, base, txn.taxRulePattern.orEmpty())
        TaxSource.CATEGORY -> s.format(StringKey.TaxFromCategory, base, txn.categoryName ?: s[StringKey.Uncategorized])
        TaxSource.NONE -> base
    }
}

/** The override dropdown's label: the stored choice, or Automatic when none is stored. */
internal fun taxChoiceLabel(override: Boolean?, s: Strings): String = when (override) {
    null -> s[StringKey.TaxAutomatic]
    true -> s[StringKey.TaxRelevantChoice]
    false -> s[StringKey.TaxNotRelevantChoice]
}

/**
 * Tooltip and content description on a row whose bank balance differs from the reconstructed one: the bank's balance
 * after the booking and the missing amount (computed − bank, so a bank above the computed balance reads as a loss).
 */
internal fun balanceMismatchText(s: Strings, bank: BigDecimal, bankDifference: BigDecimal, currency: String): String = s.format(
    StringKey.BalanceBankMismatch,
    Formats.money(bank, currency),
    Formats.money(bankDifference.negate(), currency, signed = true),
)
