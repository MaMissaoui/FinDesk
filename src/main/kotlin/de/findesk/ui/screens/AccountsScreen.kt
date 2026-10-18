package de.findesk.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import de.findesk.banking.BankDirectory
import de.findesk.banking.BankingService
import de.findesk.i18n.StringKey
import de.findesk.i18n.strings
import de.findesk.model.AccountItem
import de.findesk.model.BankAccessItem
import de.findesk.ui.components.*
import de.findesk.ui.navigation.NavigationArgs
import de.findesk.ui.navigation.Screen
import de.findesk.ui.theme.amountColor
import de.findesk.util.Currencies
import de.findesk.util.Formats
import de.findesk.viewmodel.AccountDetail
import de.findesk.viewmodel.AccountsViewModel
import java.math.BigDecimal
import java.time.LocalDate

private val ACCOUNT_TYPES = listOf("checking", "savings", "credit_card", "depot")

@Composable
fun AccountsScreen(onNavigate: (Screen, NavigationArgs) -> Unit = { _, _ -> }) {
    val vm = rememberViewModel { AccountsViewModel() }
    val state by vm.state.collectAsState()
    val s = strings()
    LaunchedEffect(Unit) { vm.loadAccounts() }

    var showAddBank by remember { mutableStateOf(false) }
    var addAccountFor by remember { mutableStateOf<BankAccessItem?>(null) }
    var editBalance by remember { mutableStateOf<AccountItem?>(null) }
    var confirmDeleteAccount by remember { mutableStateOf<AccountItem?>(null) }
    var confirmDeleteBank by remember { mutableStateOf<BankAccessItem?>(null) }
    var editConnection by remember { mutableStateOf<BankAccessItem?>(null) }
    var editConnectionBlz by remember { mutableStateOf<String?>(null) }
    var fetchHistoryFor by remember { mutableStateOf<BankAccessItem?>(null) }
    var renameAccount by remember { mutableStateOf<AccountItem?>(null) }

    // Rename opens the connection settings with the name field focused
    var focusName by remember { mutableStateOf(false) }

    BankingPromptHost(vm.bankingInteraction)

    Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column {
                Text(
                    s.format(StringKey.AccountsTotal, Currencies.format(state.totals, state.primaryCurrency), state.accounts.size),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Button(onClick = { showAddBank = true }) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(s[StringKey.AddBank])
            }
        }
        Spacer(Modifier.height(16.dp))
        MessageBanner(state.message, state.error, vm::clearMessages)

        if (!state.isLoading && state.bankAccesses.isEmpty()) {
            EmptyState(
                AppIcons.AccountBalance,
                s[StringKey.NoBanksYet],
                s[StringKey.NoBanksDescription],
            ) { Button(onClick = { showAddBank = true }) { Text(s[StringKey.AddBank]) } }
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            items(state.bankAccesses, key = { it.id }) { bank ->
                val accounts = state.accounts.filter { it.bankAccessId == bank.id }
                BankCard(
                    bank = bank,
                    accounts = accounts,
                    syncing = state.syncingBankId == bank.id,
                    syncStep = state.syncStep,
                    syncEnabled = state.syncingBankId == null,
                    onSync = { vm.syncBankAccess(bank.id) },
                    onEditConnection = {
                        focusName = false
                        editConnectionBlz = null
                        editConnection = bank
                    },
                    onRename = {
                        focusName = true
                        editConnectionBlz = null
                        editConnection = bank
                    },
                    onUseCentralBlz = { mapped ->
                        focusName = false
                        editConnectionBlz = mapped
                        editConnection = bank
                    },
                    onAddAccount = { addAccountFor = bank },
                    onDeleteBank = { confirmDeleteBank = bank },
                    onFetchHistory = { fetchHistoryFor = bank },
                    onTransactions = { onNavigate(Screen.TRANSACTIONS, NavigationArgs(accountId = it.id)) },
                    onEditBalance = { editBalance = it },
                    onDeleteAccount = { confirmDeleteAccount = it },
                    onOpenAccount = { vm.openDetail(it.id) },
                    onRenameAccount = { renameAccount = it },
                )
            }
        }
    }

    state.detail?.let { detail ->
        AccountDetailDialog(
            detail = detail,
            onDays = { vm.openDetail(detail.account.id, it) },
            onAllTransactions = {
                vm.closeDetail()
                onNavigate(Screen.TRANSACTIONS, NavigationArgs(accountId = detail.account.id))
            },
            onRename = { renameAccount = detail.account },
            onClose = vm::closeDetail,
        )
    }
    renameAccount?.let { account ->
        RenameAccountDialog(
            account = account,
            onDismiss = { renameAccount = null },
            onConfirm = {
                vm.renameAccount(account.id, it)
                renameAccount = null
            },
        )
    }
    editConnection?.let { bank ->
        ConnectionDialog(
            bank = bank,
            initialBlz = editConnectionBlz,
            lookup = vm::lookupBank,
            focusName = focusName,
            hasSavedPin = bank.id in state.savedPins,
            canRememberPin = state.canRememberPin,
            syncing = state.syncingBankId == bank.id,
            onForgetPin = { vm.forgetPin(bank.id) },
            onDismiss = { editConnection = null },
            onConfirm = { name, blz, userId, url ->
                vm.updateConnection(bank.id, name, blz, userId, url)
                editConnection = null
            },
        )
    }
    fetchHistoryFor?.let { bank ->
        FetchHistoryDialog(
            bank = bank,
            validate = vm::validateSyncFrom,
            onDismiss = { fetchHistoryFor = null },
            onFetch = { date ->
                vm.fetchHistoryFrom(bank.id, date)
                fetchHistoryFor = null
            },
            onReset = {
                vm.clearSyncFrom(bank.id)
                fetchHistoryFor = null
            },
        )
    }
    if (showAddBank) {
        AddBankDialog(
            search = vm::searchBanks,
            lookup = vm::lookupBank,
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
            onConfirm = { iban, type, balance, currency ->
                vm.addAccount(bank.id, iban, type, balance, currency)
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
            title = s[StringKey.DeleteAccountTitle],
            text = s.format(StringKey.DeleteAccountText, Formats.iban(account.iban)),
            onConfirm = { vm.deleteAccount(account.id) },
            onDismiss = { confirmDeleteAccount = null },
        )
    }
    confirmDeleteBank?.let { bank ->
        ConfirmDialog(
            title = s.format(StringKey.RemoveBankTitle, bank.bankName),
            text = s[StringKey.RemoveBankText],
            onConfirm = { vm.deleteBankAccess(bank.id) },
            onDismiss = { confirmDeleteBank = null },
        )
    }
}

@Composable
private fun BankCard(
    bank: BankAccessItem,
    accounts: List<AccountItem>,
    syncing: Boolean,
    syncStep: String?,
    syncEnabled: Boolean,
    onSync: () -> Unit,
    onEditConnection: () -> Unit,
    onRename: () -> Unit,
    onUseCentralBlz: (String) -> Unit,
    onAddAccount: () -> Unit,
    onDeleteBank: () -> Unit,
    onFetchHistory: () -> Unit,
    onTransactions: (AccountItem) -> Unit,
    onEditBalance: (AccountItem) -> Unit,
    onDeleteAccount: (AccountItem) -> Unit,
    onOpenAccount: (AccountItem) -> Unit,
    onRenameAccount: (AccountItem) -> Unit,
) {
    val s = strings()
    FinDeskCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            RowContextMenu(
                syncEnabled.takeIf { it && !syncing }?.let { menuItem(s[StringKey.SyncNow], onSync) },
                syncEnabled.takeIf { it }?.let { menuItem(s[StringKey.FetchHistory], onFetchHistory) },
                menuItem(s[StringKey.AddAccount], onAddAccount),
                menuItem(s[StringKey.RenameConnection], onRename),
                menuItem(s[StringKey.ConnectionSettings], onEditConnection),
                menuItem(s[StringKey.RemoveBank], onDeleteBank),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(bank.bankName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text(
                            s.format(StringKey.BlzPrefix, bank.blz) + (bank.bic.takeIf { it.isNotBlank() }?.let { " · ${s.format(StringKey.BicPrefix, it)}" } ?: "") +
                                (bank.lastSync?.let { " · ${s.format(StringKey.SyncedPrefix, Formats.dateTime(it))}" } ?: "") +
                                (bank.syncFrom?.let { " · ${s.format(StringKey.NextSyncFrom, Formats.date(it))}" } ?: ""),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        val centralBlz = BankDirectory.finTsBlz(bank.blz)
                        if (centralBlz != bank.blz) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    s.format(StringKey.CentralBlzHint, centralBlz),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                TextButton(onClick = { onUseCentralBlz(centralBlz) }) { Text(s.format(StringKey.UseBlz, centralBlz)) }
                            }
                        }
                    }
                    if (syncing) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text(syncStep ?: s[StringKey.Syncing], style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.width(8.dp))
                    } else {
                        OutlinedButton(onClick = onSync, enabled = syncEnabled) {
                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(s[StringKey.Sync])
                        }
                    }
                    TextButton(onClick = onAddAccount) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(s[StringKey.AddAccountShort])
                    }
                    // Bank-wide actions live in a menu: a trash icon here looked like the account rows' delete below it,
                    // yet removes the bank with all its accounts
                    var bankMenu by remember { mutableStateOf(false) }
                    Box {
                        IconButton(onClick = { bankMenu = true }) { Icon(Icons.Default.MoreVert, contentDescription = s[StringKey.More]) }
                        DropdownMenu(expanded = bankMenu, onDismissRequest = { bankMenu = false }) {
                            DropdownMenuItem(
                                text = { Text(s[StringKey.FetchHistory]) },
                                leadingIcon = { Icon(Icons.Default.DateRange, contentDescription = null) },
                                enabled = syncEnabled,
                                onClick = {
                                    bankMenu = false
                                    onFetchHistory()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(s[StringKey.RenameConnection]) },
                                leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                                onClick = {
                                    bankMenu = false
                                    onRename()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(s[StringKey.ConnectionSettings]) },
                                leadingIcon = { Icon(Icons.Default.Settings, contentDescription = null) },
                                onClick = {
                                    bankMenu = false
                                    onEditConnection()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(s[StringKey.RemoveBank], color = MaterialTheme.colorScheme.error) },
                                leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                                onClick = {
                                    bankMenu = false
                                    onDeleteBank()
                                },
                            )
                        }
                    }
                }
            }
            if (accounts.isEmpty()) {
                Text(
                    s[StringKey.NoAccountsYet],
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            accounts.forEach { account ->
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                RowContextMenu(
                    menuItem(s[StringKey.ShowTransactions]) { onTransactions(account) },
                    menuItem(s[StringKey.MenuDetails]) { onOpenAccount(account) },
                    menuItem(s[StringKey.RenameAccount]) { onRenameAccount(account) },
                    menuItem(s[StringKey.EditBalance]) { onEditBalance(account) },
                    menuItem(s[StringKey.DeleteAccount]) { onDeleteAccount(account) },
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clickable(
                                onClick = { onOpenAccount(account) },
                                onClickLabel = s[StringKey.OpenAccount],
                                role = Role.Button,
                            ),
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(s.accountName(account), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                            Text(
                                account.name?.let { "${s.accountType(account.accountType)} · ${Formats.iban(account.iban)}" } ?: Formats.iban(account.iban),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(
                            Formats.money(account.balance, account.currency),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = if (account.balance.signum() < 0) amountColor(true) else MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(Modifier.width(8.dp))
                        IconButton(onClick = { onTransactions(account) }) { Icon(Icons.Default.List, contentDescription = s[StringKey.Transactions]) }
                        IconButton(onClick = { onEditBalance(account) }) { Icon(Icons.Default.Edit, contentDescription = s[StringKey.EditBalance]) }
                        IconButton(onClick = { onDeleteAccount(account) }) { Icon(Icons.Default.Delete, contentDescription = s[StringKey.DeleteAccount]) }
                    }
                }
            }
        }
    }
}

@Composable
internal fun AddBankDialog(
    search: (String) -> List<BankDirectory.Entry>,
    lookup: (String) -> BankDirectory.Entry?,
    validate: (String, String) -> String?,
    onDismiss: () -> Unit,
    onConfirm: (String, String, String, String) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var bankName by remember { mutableStateOf("") }
    var blz by remember { mutableStateOf("") }
    var bic by remember { mutableStateOf("") }
    var userId by remember { mutableStateOf("") }
    var touched by remember { mutableStateOf(false) }

    /** The branch BLZ a picked/typed bank had before it was mapped to the central FinTS BLZ, while that is shown. */
    var originalBlz by remember { mutableStateOf<String?>(null) }
    val error = validate(bankName, blz)
    val s = strings()
    // Recompute per keystroke; it is an in-memory scan over the bundled bank directory.
    val suggestions = remember(query) { if (query.isBlank()) emptyList() else search(query) }
    // The bank the entered BLZ resolves to, used to propose a FinTS connection.
    val knownBank = remember(blz) { if (blz.length == 8) lookup(blz) else null }

    fun applySuggestion(entry: BankDirectory.Entry) {
        val mapped = BankDirectory.finTsBlz(entry.blz)
        blz = mapped
        originalBlz = entry.blz.takeIf { it != mapped }
        bankName = entry.name
        bic = entry.bic
        query = ""
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(s[StringKey.AddBank]) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.widthIn(max = 480.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text(s[StringKey.BankSearchHint]) },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                when {
                    query.isBlank() -> Unit

                    suggestions.isEmpty() -> Text(
                        s[StringKey.BankSearchNoMatch],
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    else -> Column(
                        modifier = Modifier.fillMaxWidth().heightIn(max = 220.dp).verticalScroll(rememberScrollState()),
                    ) {
                        suggestions.forEach { entry ->
                            BankSuggestionRow(entry, onSelect = { applySuggestion(entry) })
                        }
                    }
                }
                Text(s[StringKey.BankManualEntry], style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(bankName, { bankName = it }, label = { Text(s[StringKey.BankName]) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    blz,
                    { input ->
                        blz = input.filter(Char::isDigit).take(8)
                        if (blz.length == 8) {
                            lookup(blz)?.let { entry ->
                                val mapped = BankDirectory.finTsBlz(entry.blz)
                                originalBlz = entry.blz.takeIf { it != mapped }
                                if (mapped != entry.blz) blz = mapped
                                if (bankName.isBlank()) bankName = entry.name
                                if (bic.isBlank()) bic = entry.bic
                            }
                        }
                    },
                    label = { Text(s[StringKey.BlzDigits]) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                originalBlz?.takeIf { BankDirectory.finTsBlz(it) == blz }?.let { original ->
                    Text(
                        s.format(StringKey.CentralBlzNote, blz, original),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                OutlinedTextField(bic, { bic = it.uppercase() }, label = { Text(s[StringKey.BicOptional]) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(userId, { userId = it }, label = { Text(s[StringKey.OnlineUserIdOptional]) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                ConnectionProposal(knownBank, blz)
                if (touched && error != null) Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            Button(onClick = {
                touched = true
                if (error == null) onConfirm(bankName, blz, bic, userId)
            }) { Text(s[StringKey.Add]) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(s[StringKey.Cancel]) } },
    )
}

/** One clickable bank suggestion, with the BLZ/city/BIC that disambiguate branches of the same bank. */
@Composable
private fun BankSuggestionRow(entry: BankDirectory.Entry, onSelect: () -> Unit) {
    val s = strings()
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .clickable(onClickLabel = s[StringKey.BankUseSuggestion], role = Role.Button, onClick = onSelect),
    ) {
        Row(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(entry.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                Text(
                    listOf(entry.city, entry.blz, entry.bic).filter { it.isNotBlank() }.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                if (entry.hasOnlineBanking) s[StringKey.OnlineSyncAvailable] else s[StringKey.NoFintsServer],
                style = MaterialTheme.typography.labelSmall,
                color = if (entry.hasOnlineBanking) amountColor(false) else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Shows which FinTS server the entered BLZ resolves to, so the connection is known before saving. */
@Composable
private fun ConnectionProposal(bank: BankDirectory.Entry?, blz: String) {
    val s = strings()
    when {
        bank?.hasOnlineBanking == true -> Text(
            s.format(StringKey.BankFintsProposed, bank.pinTanUrl.orEmpty()),
            style = MaterialTheme.typography.bodySmall,
            color = amountColor(false),
        )

        bank != null -> Text(s[StringKey.NoFintsServer], style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

        blz.length == 8 -> Text(
            s.format(StringKey.BankUnknownBlz, blz),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun AddAccountDialog(
    bank: BankAccessItem,
    validate: (String) -> String?,
    onDismiss: () -> Unit,
    onConfirm: (String, String, BigDecimal, String) -> Unit,
) {
    var iban by remember { mutableStateOf("") }
    var type by remember { mutableStateOf(ACCOUNT_TYPES.first()) }
    var currency by remember { mutableStateOf(Currencies.DEFAULT) }
    var balance by remember { mutableStateOf("0,00") }
    var touched by remember { mutableStateOf(false) }
    val parsedBalance = Formats.parseAmount(balance)
    val s = strings()
    val error = validate(iban) ?: if (parsedBalance == null) s[StringKey.BalanceNotANumber] else null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(s.format(StringKey.AddAccountAt, bank.bankName)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(iban, { iban = it.uppercase() }, label = { Text(s[StringKey.Iban]) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                DropdownSelector(
                    label = s.accountType(type),
                    options = ACCOUNT_TYPES,
                    optionLabel = { s.accountType(it) },
                    onSelect = { type = it },
                )
                DropdownSelector(
                    label = s.format(StringKey.CurrencyIs, currency),
                    options = Currencies.COMMON,
                    optionLabel = { it },
                    onSelect = { currency = it },
                )
                OutlinedTextField(balance, { balance = it }, label = { Text(s.format(StringKey.CurrentBalanceIn, currency)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                if (touched && error != null) Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            Button(onClick = {
                touched = true
                if (error == null) onConfirm(iban, type, parsedBalance!!, currency)
            }) { Text(s[StringKey.Add]) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(s[StringKey.Cancel]) } },
    )
}

@Composable
private fun BalanceDialog(account: AccountItem, onDismiss: () -> Unit, onConfirm: (BigDecimal) -> Unit) {
    var text by remember { mutableStateOf(account.balance.toPlainString().replace('.', ',')) }
    val parsed = Formats.parseAmount(text)
    val s = strings()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(s[StringKey.UpdateBalance]) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(Formats.iban(account.iban), style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    text,
                    { text = it },
                    label = { Text(s.format(StringKey.CurrentBalanceIn, account.currency)) },
                    singleLine = true,
                    isError = parsed == null,
                    supportingText = { if (parsed == null) Text(s[StringKey.EnterValidBalance]) },
                )
            }
        },
        confirmButton = { Button(onClick = { parsed?.let(onConfirm) }, enabled = parsed != null) { Text(s[StringKey.Save]) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(s[StringKey.Cancel]) } },
    )
}

@Composable
private fun RenameAccountDialog(account: AccountItem, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text by remember { mutableStateOf(account.name.orEmpty()) }
    val field = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { field.requestFocus() } }
    val s = strings()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(s[StringKey.RenameAccount]) },
        text = {
            Column(modifier = Modifier.width(460.dp)) {
                OutlinedTextField(
                    text,
                    { text = it.take(de.findesk.repository.MAX_ACCOUNT_NAME_LENGTH) },
                    label = { Text(s[StringKey.AccountName]) },
                    placeholder = { Text(s.accountType(account.accountType)) },
                    supportingText = { Text(s.format(StringKey.AccountNameHint, s.accountType(account.accountType))) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { onConfirm(text) }),
                    modifier = Modifier.fillMaxWidth().focusRequester(field),
                )
            }
        },
        confirmButton = { Button(onClick = { onConfirm(text) }) { Text(s[StringKey.Save]) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(s[StringKey.Cancel]) } },
    )
}

@Composable
private fun ConnectionDialog(
    bank: BankAccessItem,
    initialBlz: String?,
    lookup: (String) -> BankDirectory.Entry?,
    focusName: Boolean,
    hasSavedPin: Boolean,
    canRememberPin: Boolean,
    syncing: Boolean,
    onForgetPin: () -> Unit,
    onDismiss: () -> Unit,
    onConfirm: (name: String, blz: String, userId: String, url: String) -> Unit,
) {
    var name by remember { mutableStateOf(bank.bankName) }
    var blzField by remember { mutableStateOf(initialBlz ?: bank.blz) }
    var userId by remember { mutableStateOf(bank.userId) }
    var url by remember { mutableStateOf(bank.serverUrl.orEmpty()) }
    val nameField = remember { FocusRequester() }
    LaunchedEffect(focusName) { if (focusName) runCatching { nameField.requestFocus() } }
    val s = strings()
    val knownField = if (blzField.length == 8) lookup(blzField) else null
    // The endpoint follows what the field says now, not the stored BLZ, so a correction updates the proposal too.
    val defaultUrl = knownField?.pinTanUrl
    val oldBicPrefix = bank.bic.take(6)
    val otherBank = knownField != null && oldBicPrefix.isNotBlank() && !knownField.bic.take(6).equals(oldBicPrefix, ignoreCase = true)
    val blzHint = when {
        otherBank -> s.format(StringKey.BlzOtherBank, knownField!!.name)
        knownField != null -> listOfNotNull(knownField.name, knownField.city.takeIf { it.isNotBlank() }).joinToString(", ")
        blzField.length == 8 -> s[StringKey.BlzNotInDirectory]
        else -> null
    }
    val loginChanged = blzField.trim() != bank.blz || userId.trim() != bank.userId || url.trim() != bank.serverUrl.orEmpty()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(s.format(StringKey.OnlineBankingFor, bank.bankName)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.width(460.dp)) {
                OutlinedTextField(
                    name,
                    { name = it.take(de.findesk.repository.MAX_BANK_NAME_LENGTH) },
                    label = { Text(s[StringKey.ConnectionName]) },
                    supportingText = { Text(s[StringKey.ConnectionNameHint]) },
                    isError = name.isBlank(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().focusRequester(nameField),
                )
                OutlinedTextField(
                    blzField,
                    { blzField = it.filter(Char::isDigit).take(8) },
                    label = { Text(s[StringKey.BlzDigits]) },
                    supportingText = blzHint?.let { hint -> { Text(hint, color = if (otherBank) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant) } },
                    isError = otherBank,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(userId, { userId = it }, label = { Text(s[StringKey.UserIdLogin]) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    url,
                    { url = it },
                    label = { Text(s[StringKey.FintsServerUrl]) },
                    placeholder = { Text(defaultUrl ?: "https://…") },
                    supportingText = { Text(if (defaultUrl != null) s.format(StringKey.LeaveEmptyToUse, defaultUrl) else s[StringKey.AskBankForFints]) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (loginChanged) {
                    Text(
                        s[StringKey.LoginResetNote],
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                bank.tanMethod?.let { Text(s.format(StringKey.RememberedTan, it), style = MaterialTheme.typography.bodySmall) }
                when {
                    hasSavedPin -> Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(s[StringKey.PinSavedForSyncs], style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(4.dp))
                        TextButton(onClick = onForgetPin) { Text(s[StringKey.ForgetPin]) }
                    }

                    canRememberPin -> Text(
                        s[StringKey.PinAskedEverySync],
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    else -> Text(
                        s[StringKey.PinNeverStored],
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            // A warning (unknown BLZ, another bank) never blocks saving; a wrong login is still confirmed with Save
            Button(
                enabled = name.isNotBlank() && userId.isNotBlank() && blzField.length == 8 && !syncing && (url.isBlank() || url.trim().startsWith("https://")),
                onClick = { onConfirm(name, blzField, userId, url) },
            ) { Text(s[StringKey.Save]) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(s[StringKey.Cancel]) } },
    )
}

/** Asks the bank for bookings from a chosen date on (defaults to the initial window), or resets a stored one. */
@Composable
private fun FetchHistoryDialog(
    bank: BankAccessItem,
    validate: (String) -> String?,
    onDismiss: () -> Unit,
    onFetch: (LocalDate) -> Unit,
    onReset: () -> Unit,
) {
    var input by remember { mutableStateOf(Formats.date(bank.syncFrom ?: LocalDate.now().minusDays(BankingService.INITIAL_SYNC_DAYS))) }
    val date = Formats.parseDate(input)
    val error = validate(input)
    val s = strings()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(s.format(StringKey.FetchHistoryFor, bank.bankName)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.width(460.dp)) {
                OutlinedTextField(
                    input,
                    { input = it },
                    label = { Text(s[StringKey.FetchHistoryDate]) },
                    singleLine = true,
                    isError = error != null,
                    supportingText = { Text(error ?: s[StringKey.FetchHistoryHint]) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(enabled = date != null && error == null, onClick = { date?.let(onFetch) }) { Text(s[StringKey.Fetch]) }
        },
        dismissButton = {
            Row {
                if (bank.syncFrom != null) {
                    TextButton(onClick = onReset) { Text(s[StringKey.Reset]) }
                }
                TextButton(onClick = onDismiss) { Text(s[StringKey.Cancel]) }
            }
        },
    )
}

@Composable
private fun AccountDetailDialog(detail: AccountDetail, onDays: (Int) -> Unit, onAllTransactions: () -> Unit, onRename: () -> Unit, onClose: () -> Unit) {
    val a = detail.account
    val s = strings()
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("${a.bankName} · ${s.accountName(a)}") },
        text = {
            Column(Modifier.width(760.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(Formats.iban(a.iban), style = MaterialTheme.typography.bodySmall)
                        Text(Formats.money(a.balance, a.currency), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    }
                    listOf(30, 90, 365).forEach { d ->
                        FilterChip(selected = detail.days == d, onClick = { onDays(d) }, label = { Text(if (d == 365) s[StringKey.OneYear] else s.format(StringKey.DaysCount, d)) })
                        Spacer(Modifier.width(6.dp))
                    }
                }
                Text(
                    s.format(StringKey.LastDaysSummary, detail.days, Formats.money(detail.incomeInPeriod, a.currency), Formats.money(detail.expensesInPeriod, a.currency)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                BalanceLineChart(detail.history, modifier = Modifier.fillMaxWidth().height(220.dp), currency = a.currency)
                Text(s[StringKey.RecentBookings], style = MaterialTheme.typography.labelLarge)
                if (detail.recent.isEmpty()) Text(s[StringKey.NoneInPeriod], color = MaterialTheme.colorScheme.onSurfaceVariant)
                detail.recent.forEach { t ->
                    Row(Modifier.fillMaxWidth()) {
                        Text(Formats.date(t.date), style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(90.dp))
                        Text(t.description, style = MaterialTheme.typography.bodySmall, maxLines = 1, modifier = Modifier.weight(1f))
                        Text(
                            Formats.money(t.amount, t.currency, signed = true),
                            style = MaterialTheme.typography.bodySmall,
                            color = amountColor(t.isExpense),
                        )
                    }
                }
                Text(
                    s[StringKey.HistoryReconstructed],
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = onAllTransactions) { Text(s[StringKey.AllTransactions]) } },
        dismissButton = {
            Row {
                TextButton(onClick = onRename) { Text(s[StringKey.RenameAccount]) }
                TextButton(onClick = onClose) { Text(s[StringKey.Close]) }
            }
        },
    )
}
