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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
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
import de.findesk.ui.theme.balanceToneColor
import de.findesk.util.ACCOUNT_TYPES
import de.findesk.util.AccountIds
import de.findesk.util.Currencies
import de.findesk.util.Formats
import de.findesk.util.LOAN_TYPE
import de.findesk.util.balanceTone
import de.findesk.viewmodel.AccountDetail
import de.findesk.viewmodel.AccountsViewModel
import java.math.BigDecimal
import java.time.LocalDate

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

    // The bank cards always show every bank and account: the selection is the panel's, which lives on Transactions
    val shownBanks = state.bankAccesses
    // The header keeps its figures while loading; the empty state owns the only "Add bank" button once no bank exists
    val showHeader = state.isLoading || state.bankAccesses.isNotEmpty()

    Row(modifier = Modifier.fillMaxSize().padding(24.dp)) {
        Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                val loans = state.accounts.filter { it.accountType == LOAN_TYPE }
                val totalText = Currencies.format(state.totals, state.primaryCurrency)
                val loanText = Currencies.format(Currencies.totals(loans, state.primaryCurrency, { it.currency }, { it.balance }), state.primaryCurrency)
                val loansLabel = s[StringKey.AccountsLoansLabel]
                // One sentence for screen readers instead of the sentence plus each figure
                val description = buildString {
                    append(s.format(StringKey.AccountsTotal, totalText, state.accounts.size))
                    if (loans.isNotEmpty()) append(", $loansLabel $loanText")
                }
                if (showHeader) {
                    // One currency: the total follows the balance tone; several: the combined line keeps plain text
                    val totalColor = state.totals.entries.singleOrNull()
                        ?.let { (currency, _) -> balanceToneColor(balanceTone(state.accounts, currency)) }
                        ?: MaterialTheme.colorScheme.onSurface
                    Row(
                        modifier = Modifier.weight(1f, fill = false).clearAndSetSemantics { contentDescription = description },
                        horizontalArrangement = Arrangement.spacedBy(32.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        FigureGroup(
                            label = s.format(if (loans.isEmpty()) StringKey.AccountsTotalLabel else StringKey.AccountsNetLabel, state.accounts.size),
                            value = totalText,
                            secondary = false,
                            valueColor = totalColor,
                        )
                        if (loans.isNotEmpty()) {
                            FigureGroup(label = loansLabel, value = loanText, secondary = true, valueColor = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                Spacer(Modifier.weight(1f))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    val progress = state.syncAll
                    if (progress != null) {
                        // A running "Sync all" stays visible whatever the number of online banks
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text(
                            s.format(StringKey.SyncAllProgress, progress.index, progress.total, progress.bankName),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    } else if (state.bankAccesses.count { !it.offline } >= 2) {
                        val anyIncluded = state.bankAccesses.any { it.includeInSyncAll }
                        DisabledReason(s[StringKey.SyncAllNothingIncluded].takeIf { !anyIncluded }) {
                            OutlinedButton(
                                onClick = { vm.syncAll() },
                                enabled = !state.isSyncing && anyIncluded,
                                colors = OutlinedButtonStyle.colors(),
                                border = OutlinedButtonStyle.border(enabled = !state.isSyncing && anyIncluded),
                            ) {
                                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(s[StringKey.SyncAll])
                            }
                        }
                    }
                    if (showHeader) {
                        Button(onClick = { showAddBank = true }) {
                            Icon(Icons.Default.Add, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(s[StringKey.AddBank])
                        }
                    }
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
                items(shownBanks, key = { it.id }) { bank ->
                    // Every card lists, counts and totals all of its accounts; the selection never filters this screen
                    val accounts = state.accounts.filter { it.bankAccessId == bank.id }
                    BankCard(
                        bank = bank,
                        accounts = accounts,
                        collapsed = bank.id in state.collapsedBanks,
                        primaryCurrency = state.primaryCurrency,
                        syncing = state.syncingBankId == bank.id,
                        syncStep = state.syncStep,
                        syncEnabled = !state.isSyncing,
                        onToggleCollapse = { vm.toggleBankCollapsed(bank.id) },
                        onSync = { vm.syncBankAccess(bank.id) },
                        onToggleIncludeInSyncAll = { vm.setIncludeInSyncAll(bank.id, !bank.includeInSyncAll) },
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
                        onForgetDeleted = { vm.forgetDeletedBookings(it.id) },
                    )
                }
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
            validateOffline = vm::validateOfflineBank,
            onDismiss = { showAddBank = false },
            onConfirm = { name, blz, bic, user ->
                vm.addBankAccess(name, blz, bic, user)
                showAddBank = false
            },
            onConfirmOffline = { name ->
                vm.addOfflineBank(name)
                showAddBank = false
            },
        )
    }
    addAccountFor?.let { bank ->
        AddAccountDialog(
            bank = bank,
            validate = { identifier -> vm.validateAccount(identifier, bank.offline) },
            onDismiss = { addAccountFor = null },
            onConfirm = { iban, type, balance, currency, name ->
                vm.addAccount(bank.id, iban, type, balance, currency, name)
                addAccountFor = null
            },
        )
    }
    editBalance?.let { account ->
        BalanceDialog(
            account = account,
            onDismiss = { editBalance = null },
            onConfirm = { newBalance, newType ->
                // Saving a balance also stamps the account as updated, so a type-only change must not touch it
                if (newBalance.compareTo(account.balance) != 0) vm.updateBalance(account.id, newBalance)
                if (newType != account.accountType) vm.setAccountType(account.id, newType)
                editBalance = null
            },
        )
    }
    confirmDeleteAccount?.let { account ->
        ConfirmDialog(
            title = s[StringKey.DeleteAccountTitle],
            text = s.format(StringKey.DeleteAccountText, AccountIds.display(account.iban)),
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

/** One labelled figure of the accounts header: a small label above a strong amount. */
@Composable
private fun FigureGroup(label: String, value: String, secondary: Boolean, valueColor: Color) {
    Column {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
        Text(
            value,
            style = if (secondary) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            color = valueColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun BankCard(
    bank: BankAccessItem,
    accounts: List<AccountItem>,
    collapsed: Boolean,
    primaryCurrency: String,
    syncing: Boolean,
    syncStep: String?,
    syncEnabled: Boolean,
    onToggleCollapse: () -> Unit,
    onSync: () -> Unit,
    onToggleIncludeInSyncAll: () -> Unit,
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
    onForgetDeleted: (AccountItem) -> Unit,
) {
    val s = strings()
    // The collapsed header shows the connection's total, coloured by the loan rule when it is a single currency
    val totals = Currencies.totals(accounts, primaryCurrency, { it.currency }, { it.balance })
    val totalColor = if (totals.size == 1) balanceToneColor(balanceTone(accounts, totals.keys.first())) else MaterialTheme.colorScheme.onSurfaceVariant
    FinDeskCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            RowContextMenu(
                // An offline bank never syncs, so its context menu has no sync or connection entries
                syncEnabled.takeIf { it && !syncing && !bank.offline }?.let { menuItem(s[StringKey.SyncNow], onSync) },
                syncEnabled.takeIf { it && !bank.offline }?.let { menuItem(s[StringKey.FetchHistory], onFetchHistory) },
                menuItem(s[StringKey.AddAccount], onAddAccount),
                menuItem(s[StringKey.RenameConnection], onRename),
                syncEnabled.takeIf { it && !bank.offline }?.let { menuItem(s[StringKey.ConnectionSettings], onEditConnection) },
                menuItem(s[StringKey.RemoveBank], onDeleteBank),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // The chevron only folds the account rows away; sync and every header action stay reachable
                    IconButton(onClick = onToggleCollapse) {
                        Icon(
                            if (collapsed) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowUp,
                            contentDescription = s[if (collapsed) StringKey.Expand else StringKey.Collapse],
                        )
                    }
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                bank.bankName,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                            if (collapsed) {
                                Spacer(Modifier.width(8.dp))
                                Text(s.format(StringKey.AccountCount, accounts.size), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(Modifier.width(8.dp))
                                Text(Currencies.format(totals, primaryCurrency), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium, color = totalColor)
                            }
                        }
                        Text(
                            if (bank.offline) {
                                s[StringKey.BankOfflineHint]
                            } else {
                                s.format(StringKey.BlzPrefix, bank.blz) +
                                    (bank.bic.takeIf { it.isNotBlank() }?.let { " · ${s.format(StringKey.BicPrefix, it)}" } ?: "") +
                                    (bank.lastSync?.let { " · ${s.format(StringKey.SyncedPrefix, Formats.dateTime(it))}" } ?: "") +
                                    (bank.syncFrom?.let { " · ${s.format(StringKey.NextSyncFrom, Formats.date(it))}" } ?: "") +
                                    (if (bank.includeInSyncAll) "" else " · ${s[StringKey.NotInSyncAll]}")
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        // A BIC lookup means a BankDirectory call; an offline bank has neither BLZ nor server
                        val centralBlz = if (bank.offline) bank.blz else BankDirectory.finTsBlz(bank.blz)
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
                    if (!bank.offline) {
                        if (syncing) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                            Text(syncStep ?: s[StringKey.Syncing], style = MaterialTheme.typography.bodySmall)
                            Spacer(Modifier.width(8.dp))
                        } else {
                            OutlinedButton(
                                onClick = onSync,
                                enabled = syncEnabled,
                                colors = OutlinedButtonStyle.colors(),
                                border = OutlinedButtonStyle.border(enabled = syncEnabled),
                            ) {
                                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(s[StringKey.Sync])
                            }
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
                            // Sync-only entries (history, include in "Sync all", connection settings) exist for online banks only
                            if (!bank.offline) {
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
                                    text = { Text(s[StringKey.IncludeInSyncAll]) },
                                    leadingIcon = {
                                        if (bank.includeInSyncAll) {
                                            Icon(Icons.Default.Check, contentDescription = null)
                                        } else {
                                            Spacer(Modifier.size(24.dp))
                                        }
                                    },
                                    enabled = syncEnabled,
                                    onClick = {
                                        bankMenu = false
                                        onToggleIncludeInSyncAll()
                                    },
                                    // The check mark alone says nothing to a screen reader
                                    modifier = Modifier.semantics { toggleableState = ToggleableState(bank.includeInSyncAll) },
                                )
                            }
                            DropdownMenuItem(
                                text = { Text(s[StringKey.RenameConnection]) },
                                leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                                onClick = {
                                    bankMenu = false
                                    onRename()
                                },
                            )
                            if (!bank.offline) {
                                DropdownMenuItem(
                                    text = { Text(s[StringKey.ConnectionSettings]) },
                                    leadingIcon = { Icon(Icons.Default.Settings, contentDescription = null) },
                                    onClick = {
                                        bankMenu = false
                                        onEditConnection()
                                    },
                                )
                            }
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
            if (!collapsed) {
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
                        menuItem(s[StringKey.ChangeAccountType]) { onEditBalance(account) },
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
                                val subtitle = account.name?.let { "${s.accountType(account.accountType)} · ${AccountIds.display(account.iban)}" } ?: AccountIds.display(account.iban)
                                Text(
                                    subtitle + (account.lastUpdate?.let { " · ${s.format(StringKey.LastFetched, Formats.date(it.toLocalDate()))}" } ?: ""),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Text(
                                Formats.money(account.balance, account.currency),
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                color = balanceToneColor(balanceTone(listOf(account), account.currency)),
                            )
                            Spacer(Modifier.width(8.dp))
                            IconButton(onClick = { onTransactions(account) }) {
                                Icon(Icons.Default.List, contentDescription = s[StringKey.Transactions], tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            AccountRowMenu(
                                onTransactions = { onTransactions(account) },
                                onDetails = { onOpenAccount(account) },
                                onRename = { onRenameAccount(account) },
                                onChangeType = { onEditBalance(account) },
                                onEditBalance = { onEditBalance(account) },
                                onDelete = { onDeleteAccount(account) },
                                onForgetDeleted = { onForgetDeleted(account) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun AccountRowMenu(
    onTransactions: () -> Unit,
    onDetails: () -> Unit,
    onRename: () -> Unit,
    onChangeType: () -> Unit,
    onEditBalance: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    /** "Allow deleted bookings again" from the account's menu; see [AccountsViewModel.forgetDeletedBookings]. */
    onForgetDeleted: () -> Unit = {},
) {
    val s = strings()
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        IconButton(onClick = { expanded = true }) { Icon(Icons.Default.MoreVert, contentDescription = s[StringKey.More], tint = MaterialTheme.colorScheme.onSurfaceVariant) }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(s[StringKey.ShowTransactions]) },
                leadingIcon = { Icon(Icons.Default.List, contentDescription = null) },
                onClick = {
                    expanded = false
                    onTransactions()
                },
            )
            DropdownMenuItem(
                text = { Text(s[StringKey.MenuDetails]) },
                leadingIcon = { Icon(Icons.Default.Info, contentDescription = null) },
                onClick = {
                    expanded = false
                    onDetails()
                },
            )
            DropdownMenuItem(
                text = { Text(s[StringKey.RenameAccount]) },
                leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                onClick = {
                    expanded = false
                    onRename()
                },
            )
            DropdownMenuItem(
                text = { Text(s[StringKey.ChangeAccountType]) },
                leadingIcon = { Icon(Icons.Default.Settings, contentDescription = null) },
                onClick = {
                    expanded = false
                    onChangeType()
                },
            )
            DropdownMenuItem(
                text = { Text(s[StringKey.EditBalance]) },
                leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                onClick = {
                    expanded = false
                    onEditBalance()
                },
            )
            DropdownMenuItem(
                text = { Text(s[StringKey.AllowDeletedAgain]) },
                leadingIcon = { Icon(Icons.Default.Refresh, contentDescription = null) },
                onClick = {
                    expanded = false
                    onForgetDeleted()
                },
            )
            DropdownMenuItem(
                text = { Text(s[StringKey.DeleteAccount], color = MaterialTheme.colorScheme.error) },
                leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                onClick = {
                    expanded = false
                    onDelete()
                },
            )
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
    /** For the offline choice: validates the bank's only field, its name. */
    validateOffline: (String) -> String? = { name -> if (name.isBlank()) validate(name, "") else null },
    onConfirmOffline: (String) -> Unit = {},
) {
    var offline by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var bankName by remember { mutableStateOf("") }
    var blz by remember { mutableStateOf("") }
    var bic by remember { mutableStateOf("") }
    var userId by remember { mutableStateOf("") }
    var touched by remember { mutableStateOf(false) }

    /** The branch BLZ a picked/typed bank had before it was mapped to the central FinTS BLZ, while that is shown. */
    var originalBlz by remember { mutableStateOf<String?>(null) }
    val error = if (offline) validateOffline(bankName) else validate(bankName, blz)
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
                SingleChoiceRow(
                    options = listOf(false, true),
                    selected = offline,
                    label = { if (it) s[StringKey.BankOfflineMode] else s[StringKey.BankOnlineMode] },
                    onSelect = { offline = it },
                )
                if (offline) {
                    // An offline bank has no BLZ, BIC, login or server: its name is everything
                    OutlinedTextField(bankName, { bankName = it }, label = { Text(s[StringKey.BankName]) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                } else {
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
                }
                if (touched && error != null) Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            Button(onClick = {
                touched = true
                if (error == null) {
                    if (offline) onConfirmOffline(bankName) else onConfirm(bankName, blz, bic, userId)
                }
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
internal fun AddAccountDialog(
    bank: BankAccessItem,
    validate: (String) -> String?,
    onDismiss: () -> Unit,
    onConfirm: (iban: String, type: String, balance: BigDecimal, currency: String, name: String?) -> Unit,
) {
    val form = remember(bank.id) { AddAccountForm(bank.offline) }
    val s = strings()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(s.format(StringKey.AddAccountAt, bank.bankName)) },
        text = { AddAccountDialogContent(form, validate) },
        confirmButton = {
            Button(onClick = {
                form.touched = true
                val parsed = form.parsedBalance
                if (validate(form.iban) == null && parsed != null) {
                    onConfirm(form.iban, form.type, parsed, form.currency, form.name.trim().takeIf { it.isNotBlank() })
                }
            }) { Text(s[StringKey.Add]) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(s[StringKey.Cancel]) } },
    )
}

/** The add-account dialog's fields, hoisted so the extracted content can be rendered by the screenshot test. */
internal class AddAccountForm(val offline: Boolean) {
    /** Optional display name; blank keeps the account unnamed. */
    var name by mutableStateOf("")

    var iban by mutableStateOf("")

    /** A card account is the common case for an offline bank (e.g. a credit card imported from statements). */
    var type by mutableStateOf(if (offline) "credit_card" else ACCOUNT_TYPES.first())

    var currency by mutableStateOf(Currencies.DEFAULT)

    var balance by mutableStateOf("0,00")

    var touched by mutableStateOf(false)

    val parsedBalance: BigDecimal? get() = Formats.parseAmount(balance)
}

/** The add-account dialog's body, extracted so the screenshot test can render it without a dialog window. */
@Composable
internal fun AddAccountDialogContent(form: AddAccountForm, validate: (String) -> String?) {
    val s = strings()
    val error = validate(form.iban) ?: if (form.parsedBalance == null) s[StringKey.BalanceNotANumber] else null
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(
            form.name,
            { form.name = it.take(de.findesk.repository.MAX_ACCOUNT_NAME_LENGTH) },
            label = { Text(s[StringKey.AccountName]) },
            supportingText = { Text(s.format(StringKey.AccountNameHint, s.accountType(form.type))) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            form.iban,
            { form.iban = it.uppercase() },
            label = { Text(if (form.offline) s[StringKey.IbanOrCardLast4] else s[StringKey.Iban]) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        DropdownSelector(
            label = s.accountType(form.type),
            options = ACCOUNT_TYPES,
            optionLabel = { s.accountType(it) },
            onSelect = { form.type = it },
        )
        DropdownSelector(
            label = s.format(StringKey.CurrencyIs, form.currency),
            options = Currencies.COMMON,
            optionLabel = { it },
            onSelect = { form.currency = it },
        )
        OutlinedTextField(form.balance, { form.balance = it }, label = { Text(s.format(StringKey.CurrentBalanceIn, form.currency)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        if (form.touched && error != null) Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
internal fun BalanceDialog(account: AccountItem, onDismiss: () -> Unit, onConfirm: (BigDecimal, String) -> Unit) {
    var text by remember { mutableStateOf(account.balance.toPlainString().replace('.', ',')) }
    var type by remember { mutableStateOf(account.accountType.takeIf { it in ACCOUNT_TYPES } ?: ACCOUNT_TYPES.first()) }
    val parsed = Formats.parseAmount(text)
    val s = strings()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(s[StringKey.UpdateBalance]) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(AccountIds.display(account.iban), style = MaterialTheme.typography.bodySmall)
                DropdownSelector(
                    label = "${s[StringKey.AccountTypeLabel]}: ${s.accountType(type)}",
                    options = ACCOUNT_TYPES,
                    optionLabel = { s.accountType(it) },
                    onSelect = { type = it },
                )
                OutlinedTextField(
                    text,
                    { text = it },
                    label = { Text(s.format(StringKey.CurrentBalanceIn, account.currency)) },
                    singleLine = true,
                    isError = parsed == null,
                    supportingText = { if (parsed == null) Text(s[StringKey.EnterValidBalance]) },
                )
                if (type == LOAN_TYPE) {
                    Text(
                        s[StringKey.LoanBalanceHint],
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = { Button(onClick = { parsed?.let { onConfirm(it, type) } }, enabled = parsed != null) { Text(s[StringKey.Save]) } },
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
    val nameField = remember { FocusRequester() }
    LaunchedEffect(focusName) { if (focusName) runCatching { nameField.requestFocus() } }
    val s = strings()
    // An offline bank has no BLZ, user id, server or PIN: the dialog is only a rename
    if (bank.offline) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(s[StringKey.RenameConnection]) },
            text = {
                Column(modifier = Modifier.width(460.dp)) {
                    OutlinedTextField(
                        name,
                        { name = it.take(de.findesk.repository.MAX_BANK_NAME_LENGTH) },
                        label = { Text(s[StringKey.ConnectionName]) },
                        supportingText = { Text(s[StringKey.ConnectionNameHint]) },
                        isError = name.isBlank(),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().focusRequester(nameField),
                    )
                }
            },
            confirmButton = { Button(enabled = name.isNotBlank(), onClick = { onConfirm(name, bank.blz, bank.userId, bank.serverUrl.orEmpty()) }) { Text(s[StringKey.Save]) } },
            dismissButton = { TextButton(onClick = onDismiss) { Text(s[StringKey.Cancel]) } },
        )
        return
    }
    var blzField by remember { mutableStateOf(initialBlz ?: bank.blz) }
    var userId by remember { mutableStateOf(bank.userId) }
    var url by remember { mutableStateOf(bank.serverUrl.orEmpty()) }
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
                        Text(AccountIds.display(a.iban), style = MaterialTheme.typography.bodySmall)
                        Text(
                            Formats.money(a.balance, a.currency),
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            color = balanceToneColor(balanceTone(listOf(a), a.currency)),
                        )
                    }
                    SingleChoiceRow(
                        options = listOf(30, 90, 365),
                        selected = detail.days,
                        label = { d -> if (d == 365) s[StringKey.OneYear] else s.format(StringKey.DaysCount, d) },
                        onSelect = onDays,
                    )
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
