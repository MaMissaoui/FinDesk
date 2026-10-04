package de.findesk.ui.screens

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
import androidx.compose.ui.unit.dp
import de.findesk.model.AccountItem
import de.findesk.model.BankAccessItem
import de.findesk.ui.components.*
import de.findesk.ui.navigation.NavigationArgs
import de.findesk.ui.navigation.Screen
import de.findesk.ui.theme.Error
import de.findesk.util.Formats
import de.findesk.viewmodel.AccountsViewModel
import java.math.BigDecimal

private val ACCOUNT_TYPES = listOf("checking" to "Checking", "savings" to "Savings", "credit_card" to "Credit Card", "depot" to "Depot")

private fun accountTypeLabel(type: String) = ACCOUNT_TYPES.firstOrNull { it.first == type }?.second ?: type

@Composable
fun AccountsScreen(onNavigate: (Screen, NavigationArgs) -> Unit = { _, _ -> }) {
    val vm = rememberViewModel { AccountsViewModel() }
    val state by vm.state.collectAsState()
    LaunchedEffect(Unit) { vm.loadAccounts() }

    var showAddBank by remember { mutableStateOf(false) }
    var addAccountFor by remember { mutableStateOf<BankAccessItem?>(null) }
    var editBalance by remember { mutableStateOf<AccountItem?>(null) }
    var confirmDeleteAccount by remember { mutableStateOf<AccountItem?>(null) }
    var confirmDeleteBank by remember { mutableStateOf<BankAccessItem?>(null) }

    Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column {
                Text("Bank Accounts", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text(
                    "Total ${Formats.money(state.totalBalance)} across ${state.accounts.size} account(s)",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Button(onClick = { showAddBank = true }) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Add Bank")
            }
        }
        Spacer(Modifier.height(16.dp))
        MessageBanner(state.message, state.error, vm::clearMessages)

        if (!state.isLoading && state.bankAccesses.isEmpty()) {
            EmptyState(
                Icons.Default.AccountBox,
                "No banks yet",
                "Add your bank, then add its accounts. Balances and transactions can be entered manually until online banking sync is set up.",
            ) { Button(onClick = { showAddBank = true }) { Text("Add Bank") } }
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            items(state.bankAccesses, key = { it.id }) { bank ->
                val accounts = state.accounts.filter { it.bankAccessId == bank.id }
                BankCard(
                    bank = bank,
                    accounts = accounts,
                    onAddAccount = { addAccountFor = bank },
                    onDeleteBank = { confirmDeleteBank = bank },
                    onTransactions = { onNavigate(Screen.TRANSACTIONS, NavigationArgs(accountId = it.id)) },
                    onEditBalance = { editBalance = it },
                    onDeleteAccount = { confirmDeleteAccount = it },
                )
            }
        }
    }

    if (showAddBank) {
        AddBankDialog(
            validate = vm::validateBankAccess,
            onDismiss = { showAddBank = false },
            onConfirm = { name, blz, bic, user ->
                vm.addBankAccess(name, blz, bic, user)
                showAddBank = false
            },
        )
    }
    addAccountFor?.let { bank ->
        AddAccountDialog(
            bank = bank,
            validate = vm::validateAccount,
            onDismiss = { addAccountFor = null },
            onConfirm = { iban, type, balance ->
                vm.addAccount(bank.id, iban, type, balance)
                addAccountFor = null
            },
        )
    }
    editBalance?.let { account ->
        BalanceDialog(
            account = account,
            onDismiss = { editBalance = null },
            onConfirm = {
                vm.updateBalance(account.id, it)
                editBalance = null
            },
        )
    }
    confirmDeleteAccount?.let { account ->
        ConfirmDialog(
            title = "Delete account?",
            text = "This deletes ${Formats.iban(account.iban)} and all of its transactions. Linked documents are kept but unlinked.",
            onConfirm = { vm.deleteAccount(account.id) },
            onDismiss = { confirmDeleteAccount = null },
        )
    }
    confirmDeleteBank?.let { bank ->
        ConfirmDialog(
            title = "Remove ${bank.bankName}?",
            text = "This deletes the bank, all of its accounts and their transactions.",
            onConfirm = { vm.deleteBankAccess(bank.id) },
            onDismiss = { confirmDeleteBank = null },
        )
    }
}

@Composable
private fun BankCard(
    bank: BankAccessItem,
    accounts: List<AccountItem>,
    onAddAccount: () -> Unit,
    onDeleteBank: () -> Unit,
    onTransactions: (AccountItem) -> Unit,
    onEditBalance: (AccountItem) -> Unit,
    onDeleteAccount: (AccountItem) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(bank.bankName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        "BLZ ${bank.blz}" + (bank.bic.takeIf { it.isNotBlank() }?.let { " · BIC $it" } ?: "") +
                            (bank.lastSync?.let { " · synced ${Formats.dateTime(it)}" } ?: ""),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = onAddAccount) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Account")
                }
                IconButton(onClick = onDeleteBank) { Icon(Icons.Default.Delete, contentDescription = "Remove bank") }
            }
            if (accounts.isEmpty()) {
                Text(
                    "No accounts yet",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            accounts.forEach { account ->
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(accountTypeLabel(account.accountType), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                        Text(Formats.iban(account.iban), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(
                        Formats.money(account.balance, account.currency),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = if (account.balance.signum() < 0) Error else MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.width(8.dp))
                    IconButton(onClick = { onTransactions(account) }) { Icon(Icons.Default.List, contentDescription = "Transactions") }
                    IconButton(onClick = { onEditBalance(account) }) { Icon(Icons.Default.Edit, contentDescription = "Edit balance") }
                    IconButton(onClick = { onDeleteAccount(account) }) { Icon(Icons.Default.Delete, contentDescription = "Delete account") }
                }
            }
        }
    }
}

@Composable
private fun AddBankDialog(
    validate: (String, String) -> String?,
    onDismiss: () -> Unit,
    onConfirm: (String, String, String, String) -> Unit,
) {
    var bankName by remember { mutableStateOf("") }
    var blz by remember { mutableStateOf("") }
    var bic by remember { mutableStateOf("") }
    var userId by remember { mutableStateOf("") }
    var touched by remember { mutableStateOf(false) }
    val error = validate(bankName, blz)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Bank") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(bankName, { bankName = it }, label = { Text("Bank name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(blz, { blz = it.filter(Char::isDigit).take(8) }, label = { Text("BLZ (8 digits)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(bic, { bic = it }, label = { Text("BIC (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(userId, { userId = it }, label = { Text("Online banking user ID (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                if (touched && error != null) Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            Button(onClick = {
                touched = true
                if (error == null) onConfirm(bankName, blz, bic, userId)
            }) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun AddAccountDialog(
    bank: BankAccessItem,
    validate: (String) -> String?,
    onDismiss: () -> Unit,
    onConfirm: (String, String, BigDecimal) -> Unit,
) {
    var iban by remember { mutableStateOf("") }
    var type by remember { mutableStateOf(ACCOUNT_TYPES.first().first) }
    var balance by remember { mutableStateOf("0,00") }
    var touched by remember { mutableStateOf(false) }
    val parsedBalance = Formats.parseAmount(balance)
    val error = validate(iban) ?: if (parsedBalance == null) "Balance is not a number" else null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add account at ${bank.bankName}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(iban, { iban = it.uppercase() }, label = { Text("IBAN") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                DropdownSelector(
                    label = accountTypeLabel(type),
                    options = ACCOUNT_TYPES,
                    optionLabel = { it.second },
                    onSelect = { type = it.first },
                )
                OutlinedTextField(balance, { balance = it }, label = { Text("Current balance (€)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                if (touched && error != null) Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            Button(onClick = {
                touched = true
                if (error == null) onConfirm(iban, type, parsedBalance!!)
            }) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun BalanceDialog(account: AccountItem, onDismiss: () -> Unit, onConfirm: (BigDecimal) -> Unit) {
    var text by remember { mutableStateOf(account.balance.toPlainString().replace('.', ',')) }
    val parsed = Formats.parseAmount(text)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Update balance") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(Formats.iban(account.iban), style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(text, { text = it }, label = { Text("Balance (€)") }, singleLine = true, isError = parsed == null)
            }
        },
        confirmButton = { Button(onClick = { parsed?.let(onConfirm) }, enabled = parsed != null) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
