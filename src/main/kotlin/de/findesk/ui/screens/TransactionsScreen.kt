package de.findesk.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import de.findesk.i18n.StringKey
import de.findesk.i18n.Strings
import de.findesk.i18n.strings
import de.findesk.importer.CsvMapping
import de.findesk.importer.StatementImportService
import de.findesk.model.AccountItem
import de.findesk.model.CategoryItem
import de.findesk.model.Direction
import de.findesk.model.TransactionItem
import de.findesk.ui.components.*
import de.findesk.ui.navigation.NavigationArgs
import de.findesk.ui.navigation.ScreenRequest
import de.findesk.ui.theme.amountColor
import de.findesk.ui.theme.balanceColor
import de.findesk.util.AccountGroupView
import de.findesk.util.Currencies
import de.findesk.util.Formats
import de.findesk.util.GroupKey
import de.findesk.viewmodel.AccountSelection
import de.findesk.viewmodel.CategoryFilter
import de.findesk.viewmodel.ImportPreview
import de.findesk.viewmodel.Period
import de.findesk.viewmodel.TransactionsViewModel
import java.math.BigDecimal
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TransactionsScreen(args: NavigationArgs = NavigationArgs(), createViewModel: () -> TransactionsViewModel = { TransactionsViewModel() }) {
    val vm = rememberViewModel(createViewModel)
    val state by vm.state.collectAsState()
    val s = strings()
    LaunchedEffect(args) { vm.start(args.accountId, args.uncategorizedOnly, args.partner) }

    var showAdd by remember { mutableStateOf(false) }
    val attachReceipt: (Int) -> Unit = { transactionId ->
        val files = FileDialogs.openFiles(s[StringKey.AttachReceipt], de.findesk.document.DocumentService.SUPPORTED_EXTENSIONS)
        if (files.isNotEmpty()) vm.attachDocuments(transactionId, files)
    }
    val openImport = {
        FileDialogs.openFiles(s[StringKey.ImportBankStatement], StatementImportService.EXTENSIONS).firstOrNull()?.let { vm.openImport(it) }
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
                val headerBalanceColor = if (headerBalance != null) balanceColor(headerBalance) else MaterialTheme.colorScheme.onSurfaceVariant
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // The top bar names the screen; this row holds its summary and actions
                    Text(
                        buildAnnotatedString {
                            append(
                                s.format(
                                    StringKey.TransactionsSummary,
                                    state.transactions.size,
                                    Currencies.format(state.totalIncome, Currencies.DEFAULT),
                                    Currencies.format(state.totalExpenses, Currencies.DEFAULT),
                                ),
                            )
                            if (headerBalance != null) {
                                withStyle(SpanStyle(color = headerBalanceColor)) {
                                    append(s.format(StringKey.TransactionsBalance, Formats.money(headerBalance, state.balanceCurrency ?: "EUR")))
                                }
                            }
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    OverflowMenu(
                        items = listOf(
                            OverflowItem(
                                label = s[StringKey.Import],
                                icon = Icons.Default.Add,
                                disabledReason = s[StringKey.HintNeedsAccount].takeIf { state.accounts.isEmpty() },
                                onClick = { openImport() },
                            ),
                            OverflowItem(
                                label = s[StringKey.AutoCategorize],
                                icon = Icons.Default.Refresh,
                                onClick = { vm.autoCategorizeTransactions() },
                            ),
                            OverflowItem(
                                label = s[StringKey.ExportCsv],
                                icon = AppIcons.FileDownload,
                                disabledReason = s[StringKey.HintNothingToExport].takeIf { state.transactions.isEmpty() },
                                onClick = { FileDialogs.saveFile(s[StringKey.ExportTransactions], "transactions-${LocalDate.now()}.csv")?.let { vm.exportCsv(it) } },
                            ),
                        ),
                    )
                    DisabledReason(s[StringKey.HintNeedsAccount].takeIf { state.accounts.isEmpty() }) {
                        Button(onClick = { showAdd = true }, enabled = state.accounts.isNotEmpty()) {
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
                        modifier = Modifier.weight(1f).widthIn(min = 240.dp).align(Alignment.CenterVertically),
                    )
                    DropdownSelector(s.period(state.period), Period.entries, { s.period(it) }, { vm.updatePeriod(it) }, leadingIcon = Icons.Default.DateRange)
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
                    SingleChoiceRow(
                        options = Direction.entries,
                        selected = state.direction,
                        label = { s.direction(it) },
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
                FinDeskCard(modifier = if (state.balanceChart != null) Modifier.fillMaxWidth().weight(1f) else Modifier.fillMaxWidth()) {
                    Column {
                        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                            HeaderCell(s[StringKey.ColDate], 1f)
                            HeaderCell(s[StringKey.ColDescription], 3f)
                            HeaderCell(s[StringKey.ColCategory], 1.4f)
                            HeaderCell(s[StringKey.ColAmount], 1.2f, TextAlign.End)
                            if (state.balances != null) HeaderCell(s[StringKey.ColBalance], 1.2f, TextAlign.End)
                            Spacer(Modifier.width(40.dp))
                        }
                        HorizontalDivider()
                        if (!state.isLoading && state.transactions.isEmpty()) {
                            EmptyState(
                                Icons.Default.List,
                                s[StringKey.NoTransactions],
                                if (state.accounts.isEmpty()) s[StringKey.AddAccountFirstThen] else s[StringKey.NothingMatches],
                            )
                        }
                        LazyColumn {
                            items(state.transactions, key = { it.id }) { txn ->
                                RowContextMenu(
                                    menuItem(s[StringKey.OpenDetails]) { vm.selectTransaction(txn) },
                                    menuItem(s[StringKey.AttachReceipt]) { attachReceipt(txn.id) },
                                    txn.partnerName?.let { partner -> menuItem(s.format(StringKey.ShowAllFromPartner, partner)) { vm.showPartner(partner) } },
                                    menuItem(s[StringKey.Delete]) { confirmDelete = txn },
                                ) {
                                    TransactionRow(
                                        txn = txn,
                                        categories = state.categories,
                                        balance = state.balances?.get(txn.id),
                                        balanceCurrency = state.balanceCurrency,
                                        isNew = txn.id in state.boldIds,
                                        onCategory = { vm.updateTransactionCategory(txn.id, it) },
                                        onDelete = { confirmDelete = txn },
                                        onOpen = { vm.selectTransaction(txn) },
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

    state.selected?.let { txn ->
        TransactionDetailDialog(
            txn = txn,
            documents = state.selectedDocuments,
            categories = state.categories,
            account = state.accounts.firstOrNull { it.id == txn.accountId },
            suggestedPattern = vm.rulePatternFor(txn),
            onCategory = { vm.updateTransactionCategory(txn.id, it) },
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
            text = "${Formats.date(txn.date)} ${txn.description} ${Formats.money(txn.amount, txn.currency)}",
            onConfirm = { vm.deleteTransaction(txn.id) },
            onDismiss = { confirmDelete = null },
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
    isNew: Boolean,
    onCategory: (Int?) -> Unit,
    onDelete: () -> Unit,
    onOpen: () -> Unit,
) {
    var categoryMenu by remember { mutableStateOf(false) }
    var rowMenu by remember { mutableStateOf(false) }
    val s = strings()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                onClick = onOpen,
                onClickLabel = s[StringKey.OpenDetails],
                role = Role.Button,
            )
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The dot sits inside the date cell, so a new row's columns stay aligned with the header
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
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
        Column(Modifier.weight(3f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(txn.description, style = MaterialTheme.typography.bodyMedium, fontWeight = if (isNew) FontWeight.Bold else FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (txn.documentCount > 0) {
                    Spacer(Modifier.width(6.dp))
                    Icon(AppIcons.AttachFile, contentDescription = s.format(StringKey.DocumentsCount, txn.documentCount), modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
                }
            }
            val secondary = if (txn.partnerName != null) txn.purpose else null
            if (!secondary.isNullOrBlank()) {
                Text(secondary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Box(Modifier.weight(1.4f)) {
            CategoryBadge(
                txn.categoryName,
                txn.categoryColor,
                Modifier.clickable(
                    onClick = { categoryMenu = true },
                    onClickLabel = s.format(StringKey.ChangeCategory, txn.categoryName ?: s[StringKey.Uncategorized]),
                    role = Role.Button,
                ),
            )
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
            modifier = Modifier.weight(1.2f),
            textAlign = TextAlign.End,
        )
        if (balance != null) {
            val currency = balanceCurrency ?: txn.currency
            Row(
                modifier = Modifier.weight(1.2f),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (balance.bankDifference != null) {
                    val message = s.format(StringKey.BalanceBankMismatch, Formats.money(balance.balance, currency))
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
                    color = balanceColor(balance.balance),
                    textAlign = TextAlign.End,
                )
            }
        }
        Box(Modifier.width(40.dp), contentAlignment = Alignment.CenterEnd) {
            IconButton(onClick = { rowMenu = true }, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.MoreVert, contentDescription = s[StringKey.More], modifier = Modifier.size(18.dp))
            }
            DropdownMenu(expanded = rowMenu, onDismissRequest = { rowMenu = false }) {
                DropdownMenuItem(text = { Text(s[StringKey.Delete]) }, onClick = {
                    rowMenu = false
                    onDelete()
                })
            }
        }
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
private fun ImportDialog(
    preview: ImportPreview,
    accounts: List<AccountItem>,
    importing: Boolean,
    onAccount: (Int) -> Unit,
    onRemap: (CsvMapping) -> Unit,
    onCancel: () -> Unit,
    onImport: (Boolean) -> Unit,
) {
    val st = preview.statement
    val hasBalances = st.bookings.any { it.balanceAfter != null }
    var updateBalance by remember(preview.file, preview.accountId, preview.balanceUpdateAllowed) { mutableStateOf(preview.balanceUpdateAllowed) }
    val account = accounts.firstOrNull { it.id == preview.accountId }
    val s = strings()

    AlertDialog(
        onDismissRequest = { if (!importing) onCancel() },
        title = { Text(s.format(StringKey.ImportFile, preview.file.name)) },
        text = {
            Column(Modifier.width(720.dp).heightIn(max = 560.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                val csv = st.csv
                val delimLabel = csv?.let { if (it.delimiter == '\t') s[StringKey.TabDelimiter] else "'${it.delimiter}'" } ?: ""
                Text(
                    "${st.format.label}" + (csv?.let { " · ${it.charsetName} · delimiter $delimLabel" } ?: "") +
                        " · ${s.format(StringKey.BookingCount, st.bookings.size)}" +
                        (if (st.pendingSkipped > 0) " · ${s.format(StringKey.PendingLeftOut, st.pendingSkipped)}" else "") +
                        (if (st.skipped.isNotEmpty()) " · ${s.format(StringKey.UnreadableCount, st.skipped.size)}" else ""),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(s[StringKey.IntoAccount], fontWeight = FontWeight.Medium)
                    DropdownSelector(
                        label = account?.let { accountLabel(it) } ?: s[StringKey.ChooseAccount],
                        options = accounts,
                        optionLabel = { accountLabel(it) },
                        onSelect = { onAccount(it.id) },
                    )
                }
                if (st.accountIban != null && account != null && st.accountIban != account.iban) {
                    Text(
                        s.format(StringKey.FileBelongsTo, Formats.iban(st.accountIban)),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                val wrongCurrency = account?.let { a -> st.bookings.count { it.currency != null && it.currency != a.currency } } ?: 0
                if (wrongCurrency > 0) {
                    Text(
                        s.format(StringKey.WrongCurrencySkipped, wrongCurrency, account!!.currency),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                if (csv != null) {
                    Text(s[StringKey.Columns], style = MaterialTheme.typography.labelLarge)
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

                if (st.bookings.isNotEmpty()) {
                    Text(s[StringKey.Preview], style = MaterialTheme.typography.labelLarge)
                    st.bookings.take(8).forEach { b ->
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
                    if (st.bookings.size > 8) Text(s.format(StringKey.AndMore, st.bookings.size - 8), style = MaterialTheme.typography.bodySmall)
                }
                st.skipped.take(5).forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }

                if (hasBalances && preview.balanceUpdateAllowed) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = updateBalance, onCheckedChange = { updateBalance = it })
                        Text(s[StringKey.SetAccountBalance])
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
        },
        confirmButton = {
            Button(enabled = !importing && account != null && st.bookings.isNotEmpty(), onClick = { onImport(updateBalance) }) {
                Text(if (importing) s[StringKey.Importing] else s.format(StringKey.ImportCount, st.bookings.size))
            }
        },
        dismissButton = { TextButton(enabled = !importing, onClick = onCancel) { Text(s[StringKey.Cancel]) } },
    )
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
        OutlinedButton(onClick = { expanded = true }, shape = RoundedCornerShape(50)) {
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
private fun TransactionDetailDialog(
    txn: TransactionItem,
    documents: List<de.findesk.model.DocumentItem>,
    categories: List<CategoryItem>,
    account: AccountItem?,
    suggestedPattern: String,
    onCategory: (Int?) -> Unit,
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
        title = { Text(txn.description) },
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
