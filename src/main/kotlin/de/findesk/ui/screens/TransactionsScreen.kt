package de.findesk.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.findesk.model.AccountItem
import de.findesk.model.CategoryItem
import de.findesk.model.Direction
import de.findesk.model.TransactionItem
import de.findesk.ui.components.*
import de.findesk.ui.navigation.NavigationArgs
import de.findesk.ui.theme.Error
import de.findesk.ui.theme.Success
import de.findesk.util.Formats
import de.findesk.viewmodel.CategoryFilter
import de.findesk.viewmodel.Period
import de.findesk.viewmodel.TransactionsViewModel
import java.math.BigDecimal
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

@Composable
fun TransactionsScreen(args: NavigationArgs = NavigationArgs()) {
    val vm = rememberViewModel { TransactionsViewModel() }
    val state by vm.state.collectAsState()
    LaunchedEffect(args) { vm.start(args.accountId, args.uncategorizedOnly) }

    var showAdd by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf<TransactionItem?>(null) }

    Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Transactions", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            OutlinedButton(onClick = { vm.autoCategorizeTransactions() }) {
                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Auto-categorize")
            }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(
                onClick = { FileDialogs.saveFile("Export transactions", "transactions-${LocalDate.now()}.csv")?.let { vm.exportCsv(it) } },
                enabled = state.transactions.isNotEmpty(),
            ) {
                Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Export CSV")
            }
            Spacer(Modifier.width(8.dp))
            Button(onClick = { showAdd = true }, enabled = state.accounts.isNotEmpty()) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("Add")
            }
        }

        Spacer(Modifier.height(16.dp))
        MessageBanner(state.message, state.error, vm::clearMessages)

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = state.searchQuery,
                onValueChange = { vm.updateSearchQuery(it) },
                placeholder = { Text("Search partner or purpose…") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                modifier = Modifier.weight(1f),
                singleLine = true,
            )
            DropdownSelector(state.period.label, Period.entries, { it.label }, { vm.updatePeriod(it) }, leadingIcon = Icons.Default.DateRange)
            DropdownSelector(
                label = categoryFilterLabel(state.categoryFilter, state.categories),
                options = listOf<CategoryFilter>(CategoryFilter.Any, CategoryFilter.None) + state.categories.map { CategoryFilter.Only(it.id) },
                optionLabel = { categoryFilterLabel(it, state.categories) },
                onSelect = { vm.updateCategoryFilter(it) },
            )
            if (state.accounts.size > 1 || state.accountId != null) {
                DropdownSelector(
                    label = state.accounts.firstOrNull { it.id == state.accountId }?.let { "…" + it.iban.takeLast(4) } ?: "All accounts",
                    options = listOf<AccountItem?>(null) + state.accounts,
                    optionLabel = { it?.let { a -> "${a.bankName} ${Formats.iban(a.iban)}" } ?: "All accounts" },
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
                    label = { Text(d.name.lowercase().replaceFirstChar { it.uppercase() }) },
                )
            }
            Spacer(Modifier.weight(1f))
            Text(
                "${state.transactions.size} transactions · in ${Formats.money(state.totalIncome)} · out ${Formats.money(state.totalExpenses)}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(16.dp))

        Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
            Column {
                Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                    HeaderCell("Date", 1f)
                    HeaderCell("Description", 3f)
                    HeaderCell("Category", 1.4f)
                    HeaderCell("Amount", 1.2f, TextAlign.End)
                    Spacer(Modifier.width(40.dp))
                }
                HorizontalDivider()
                if (!state.isLoading && state.transactions.isEmpty()) {
                    EmptyState(
                        Icons.Default.List,
                        "No transactions",
                        if (state.accounts.isEmpty()) "Add an account first, then add transactions." else "Nothing matches the current filters.",
                    )
                }
                LazyColumn {
                    items(state.transactions, key = { it.id }) { txn ->
                        TransactionRow(
                            txn = txn,
                            categories = state.categories,
                            onCategory = { vm.updateTransactionCategory(txn.id, it) },
                            onDelete = { confirmDelete = txn },
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
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
    confirmDelete?.let { txn ->
        ConfirmDialog(
            title = "Delete transaction?",
            text = "${Formats.date(txn.date)} ${txn.description} ${Formats.money(txn.amount, txn.currency)}",
            onConfirm = { vm.deleteTransaction(txn.id) },
            onDismiss = { confirmDelete = null },
        )
    }
}

private fun categoryFilterLabel(filter: CategoryFilter, categories: List<CategoryItem>): String = when (filter) {
    CategoryFilter.Any -> "All categories"
    CategoryFilter.None -> "Uncategorized"
    is CategoryFilter.Only -> categories.firstOrNull { it.id == filter.categoryId }?.name ?: "Category"
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
) {
    var categoryMenu by remember { mutableStateOf(false) }
    var rowMenu by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(Formats.date(txn.date), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Column(Modifier.weight(3f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(txn.description, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (txn.documentCount > 0) {
                    Spacer(Modifier.width(6.dp))
                    Icon(Icons.Default.Email, contentDescription = "${txn.documentCount} document(s)", modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
                }
            }
            val secondary = if (txn.partnerName != null) txn.purpose else null
            if (!secondary.isNullOrBlank()) {
                Text(secondary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Box(Modifier.weight(1.4f)) {
            CategoryBadge(txn.categoryName, txn.categoryColor, Modifier.clickable { categoryMenu = true })
            DropdownMenu(expanded = categoryMenu, onDismissRequest = { categoryMenu = false }) {
                DropdownMenuItem(text = { Text("Uncategorized") }, onClick = {
                    categoryMenu = false
                    onCategory(null)
                })
                categories.forEach { c ->
                    DropdownMenuItem(
                        text = { Text(c.name + if (c.isTaxRelevant) "  (tax)" else "") },
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
            color = if (txn.isExpense) Error else Success,
            modifier = Modifier.weight(1.2f),
            textAlign = TextAlign.End,
        )
        Box(Modifier.width(40.dp), contentAlignment = Alignment.CenterEnd) {
            IconButton(onClick = { rowMenu = true }, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.MoreVert, contentDescription = "More", modifier = Modifier.size(18.dp))
            }
            DropdownMenu(expanded = rowMenu, onDismissRequest = { rowMenu = false }) {
                DropdownMenuItem(text = { Text("Delete") }, onClick = {
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

    val date = try {
        LocalDate.parse(dateText.trim(), dateFormat)
    } catch (e: DateTimeParseException) {
        null
    }
    val amount = Formats.parseAmount(amountText)?.abs()?.takeIf { it.signum() > 0 }
    val valid = date != null && amount != null && (partner.isNotBlank() || purpose.isNotBlank())

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add transaction") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.width(420.dp)) {
                DropdownSelector(
                    label = "${account.bankName} ${Formats.iban(account.iban)}",
                    options = accounts,
                    optionLabel = { "${it.bankName} ${Formats.iban(it.iban)}" },
                    onSelect = { account = it },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = isExpense, onClick = { isExpense = true }, label = { Text("Expense") })
                    FilterChip(selected = !isExpense, onClick = { isExpense = false }, label = { Text("Income") })
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(dateText, { dateText = it }, label = { Text("Date (dd.mm.yyyy)") }, singleLine = true, isError = date == null, modifier = Modifier.weight(1f))
                    OutlinedTextField(amountText, { amountText = it }, label = { Text("Amount (€)") }, singleLine = true, isError = amountText.isNotEmpty() && amount == null, modifier = Modifier.weight(1f))
                }
                OutlinedTextField(partner, { partner = it }, label = { Text("Partner / payee") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(purpose, { purpose = it }, label = { Text("Purpose") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                DropdownSelector(
                    label = category?.name ?: "Category: auto (rules)",
                    options = listOf<CategoryItem?>(null) + categories,
                    optionLabel = { it?.name ?: "Auto (rules)" },
                    onSelect = { category = it },
                )
            }
        },
        confirmButton = {
            Button(enabled = valid, onClick = {
                val signed = if (isExpense) amount!!.negate() else amount!!
                onConfirm(account.id, date!!, signed, partner, purpose, category?.id)
            }) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
