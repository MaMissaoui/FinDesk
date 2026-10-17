package de.findesk.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
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
import de.findesk.ui.theme.amountColor
import de.findesk.util.Currencies
import de.findesk.util.Formats
import de.findesk.viewmodel.CategoryFilter
import de.findesk.viewmodel.ImportPreview
import de.findesk.viewmodel.Period
import de.findesk.viewmodel.TransactionsViewModel
import java.math.BigDecimal
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

@Composable
fun TransactionsScreen(args: NavigationArgs = NavigationArgs(), createViewModel: () -> TransactionsViewModel = { TransactionsViewModel() }) {
    val vm = rememberViewModel(createViewModel)
    val state by vm.state.collectAsState()
    val s = strings()
    LaunchedEffect(args) { vm.start(args.accountId, args.uncategorizedOnly) }

    var showAdd by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf<TransactionItem?>(null) }

    Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            // The top bar names the screen; this row holds its actions
            Spacer(Modifier.weight(1f))
            DisabledReason(s[StringKey.HintNeedsAccount].takeIf { state.accounts.isEmpty() }) {
                OutlinedButton(
                    enabled = state.accounts.isNotEmpty(),
                    onClick = {
                        FileDialogs.openFiles(s[StringKey.ImportBankStatement], StatementImportService.EXTENSIONS).firstOrNull()?.let { vm.openImport(it) }
                    },
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(s[StringKey.Import])
                }
            }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = { vm.autoCategorizeTransactions() }) {
                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(s[StringKey.AutoCategorize])
            }
            Spacer(Modifier.width(8.dp))
            DisabledReason(s[StringKey.HintNothingToExport].takeIf { state.transactions.isEmpty() }) {
                OutlinedButton(
                    onClick = { FileDialogs.saveFile(s[StringKey.ExportTransactions], "transactions-${LocalDate.now()}.csv")?.let { vm.exportCsv(it) } },
                    enabled = state.transactions.isNotEmpty(),
                ) {
                    Icon(AppIcons.FileDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(s[StringKey.ExportCsv])
                }
            }
            Spacer(Modifier.width(8.dp))
            DisabledReason(s[StringKey.HintNeedsAccount].takeIf { state.accounts.isEmpty() }) {
                Button(onClick = { showAdd = true }, enabled = state.accounts.isNotEmpty()) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text(s[StringKey.Add])
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        MessageBanner(state.message, state.error, vm::clearMessages)

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = state.searchQuery,
                onValueChange = { vm.updateSearchQuery(it) },
                placeholder = { Text(s[StringKey.SearchPartnerPurpose]) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                modifier = Modifier.weight(1f),
                singleLine = true,
            )
            DropdownSelector(s.period(state.period), Period.entries, { s.period(it) }, { vm.updatePeriod(it) }, leadingIcon = Icons.Default.DateRange)
            DropdownSelector(
                label = categoryFilterLabel(state.categoryFilter, state.categories, s),
                options = listOf<CategoryFilter>(CategoryFilter.Any, CategoryFilter.None) + state.categories.map { CategoryFilter.Only(it.id) },
                optionLabel = { categoryFilterLabel(it, state.categories, s) },
                onSelect = { vm.updateCategoryFilter(it) },
            )
            if (state.accounts.size > 1 || state.accountId != null) {
                DropdownSelector(
                    label = state.accounts.firstOrNull { it.id == state.accountId }?.let { "…" + it.iban.takeLast(4) } ?: s[StringKey.AllAccounts],
                    options = listOf<AccountItem?>(null) + state.accounts,
                    optionLabel = { it?.let { a -> "${a.bankName} ${Formats.iban(a.iban)}" } ?: s[StringKey.AllAccounts] },
                    onSelect = { vm.updateAccountFilter(it?.id) },
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Direction.entries.forEach { d ->
                FilterChip(
                    selected = state.direction == d,
                    onClick = { vm.updateDirection(d) },
                    label = { Text(s.direction(d)) },
                )
            }
            Spacer(Modifier.width(8.dp))
            AmountRangeInputs(state.minAmount, state.maxAmount, s) { min, max -> vm.updateAmountRange(min, max) }
            Spacer(Modifier.weight(1f))
            Text(
                s.format(StringKey.TransactionsSummary, state.transactions.size, Currencies.format(state.totalIncome, Currencies.DEFAULT), Currencies.format(state.totalExpenses, Currencies.DEFAULT)),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(16.dp))

        FinDeskCard(modifier = Modifier.fillMaxWidth()) {
            Column {
                Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                    HeaderCell(s[StringKey.ColDate], 1f)
                    HeaderCell(s[StringKey.ColDescription], 3f)
                    HeaderCell(s[StringKey.ColCategory], 1.4f)
                    HeaderCell(s[StringKey.ColAmount], 1.2f, TextAlign.End)
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
                        TransactionRow(
                            txn = txn,
                            categories = state.categories,
                            onCategory = { vm.updateTransactionCategory(txn.id, it) },
                            onDelete = { confirmDelete = txn },
                            onOpen = { vm.selectTransaction(txn) },
                        )
                        HorizontalDivider()
                    }
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
            onAttach = {
                val files = FileDialogs.openFiles(s[StringKey.AttachReceipt], de.findesk.document.DocumentService.SUPPORTED_EXTENSIONS)
                if (files.isNotEmpty()) vm.attachDocuments(txn.id, files)
            },
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

@Composable
private fun TransactionRow(
    txn: TransactionItem,
    categories: List<CategoryItem>,
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
        Text(Formats.date(txn.date), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Column(Modifier.weight(3f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(txn.description, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (txn.documentCount > 0) {
                    Spacer(Modifier.width(6.dp))
                    Icon(Icons.Default.Email, contentDescription = s.format(StringKey.DocumentsCount, txn.documentCount), modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
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
            fontWeight = FontWeight.SemiBold,
            color = amountColor(txn.isExpense),
            modifier = Modifier.weight(1.2f),
            textAlign = TextAlign.End,
        )
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
                    label = "${account.bankName} ${Formats.iban(account.iban)}",
                    options = accounts,
                    optionLabel = { "${it.bankName} ${Formats.iban(it.iban)}" },
                    onSelect = { account = it },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = isExpense, onClick = { isExpense = true }, label = { Text(s[StringKey.Expense]) })
                    FilterChip(selected = !isExpense, onClick = { isExpense = false }, label = { Text(s[StringKey.Income]) })
                }
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
                        label = account?.let { "${it.bankName} ${Formats.iban(it.iban)}" } ?: s[StringKey.ChooseAccount],
                        options = accounts,
                        optionLabel = { "${it.bankName} ${Formats.iban(it.iban)}" },
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

@Composable
private fun AmountRangeInputs(min: BigDecimal?, max: BigDecimal?, s: Strings, onChange: (BigDecimal?, BigDecimal?) -> Unit) {
    var minText by remember { mutableStateOf(min?.toPlainString().orEmpty()) }
    var maxText by remember { mutableStateOf(max?.toPlainString().orEmpty()) }
    fun apply() = onChange(Formats.parseAmount(minText)?.abs(), Formats.parseAmount(maxText)?.abs())
    OutlinedTextField(
        minText,
        {
            minText = it
            if (it.isBlank() || Formats.parseAmount(it) != null) apply()
        },
        placeholder = { Text(s[StringKey.MinEuro]) },
        singleLine = true,
        modifier = Modifier.width(100.dp),
    )
    Text("–")
    OutlinedTextField(
        maxText,
        {
            maxText = it
            if (it.isBlank() || Formats.parseAmount(it) != null) apply()
        },
        placeholder = { Text(s[StringKey.MaxEuro]) },
        singleLine = true,
        modifier = Modifier.width(100.dp),
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
                DetailRow(s[StringKey.DetailAccount], account?.let { "${it.bankName} ${Formats.iban(it.iban)}" } ?: Formats.iban(txn.accountIban))
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
