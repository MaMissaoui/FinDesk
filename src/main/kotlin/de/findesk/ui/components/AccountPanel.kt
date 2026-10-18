package de.findesk.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.findesk.i18n.StringKey
import de.findesk.i18n.strings
import de.findesk.model.AccountGroupItem
import de.findesk.model.AccountItem
import de.findesk.repository.MAX_ACCOUNT_GROUP_NAME_LENGTH
import de.findesk.ui.theme.amountColor
import de.findesk.util.AccountGroupView
import de.findesk.util.Currencies
import de.findesk.util.Formats
import de.findesk.util.GroupKey
import de.findesk.viewmodel.AccountSelection
import java.math.BigDecimal

/**
 * Banking4-style account list on the left of the Transactions screen: "All accounts" with a grand total, one
 * collapsible entry per group with its totals, and the accounts inside. Clicking an entry filters the bookings;
 * custom groups can be renamed, reordered, deleted, and accounts moved into them.
 */
@Composable
fun AccountPanel(
    accounts: List<AccountItem>,
    groups: List<AccountGroupView>,
    customGroups: List<AccountGroupItem>,
    grandTotal: Map<String, BigDecimal>,
    primaryCurrency: String,
    selection: AccountSelection,
    collapsed: Set<String>,
    filterText: String,
    onSelect: (AccountSelection) -> Unit,
    onToggleCollapsed: (GroupKey) -> Unit,
    onFilterText: (String) -> Unit,
    onCreateGroup: (String) -> Unit,
    onRenameGroup: (Int, String) -> Unit,
    onDeleteGroup: (Int) -> Unit,
    onMoveGroup: (Int, Boolean) -> Unit,
    onMoveAccount: (Int, Int?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val s = strings()
    var creating by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<AccountGroupItem?>(null) }
    var deleting by remember { mutableStateOf<AccountGroupItem?>(null) }
    var moving by remember { mutableStateOf<AccountItem?>(null) }

    val query = filterText.trim()
    fun matches(account: AccountItem): Boolean = query.isEmpty() ||
        s.accountName(account).contains(query, ignoreCase = true) ||
        account.iban.contains(query, ignoreCase = true) ||
        account.bankName.contains(query, ignoreCase = true)

    val shown = accounts.count { matches(it) }

    FinDeskCard(modifier = modifier) {
        Column(Modifier.fillMaxSize()) {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(vertical = 4.dp)) {
                SelectableRow(selected = selection == AccountSelection.All, onClick = { onSelect(AccountSelection.All) }) {
                    Text(s[StringKey.AllAccounts], style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    Column(horizontalAlignment = Alignment.End) {
                        grandTotal.forEach { (currency, amount) ->
                            Text(Formats.money(amount, currency), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
                        }
                    }
                }

                groups.forEach { view ->
                    val visible = view.accounts.filter { matches(it) }
                    // A group with no matching account is hidden while a filter is set.
                    if (query.isNotEmpty() && visible.isEmpty()) return@forEach
                    val custom = (view.key as? GroupKey.Custom)?.let { key -> customGroups.firstOrNull { it.id == key.groupId } }
                    RowContextMenu(
                        custom?.let { menuItem(s[StringKey.RenameGroup]) { renaming = it } },
                        custom?.let { menuItem(s[StringKey.MoveUp]) { onMoveGroup(it.id, true) } },
                        custom?.let { menuItem(s[StringKey.MoveDown]) { onMoveGroup(it.id, false) } },
                        custom?.let { menuItem(s[StringKey.Delete]) { deleting = it } },
                    ) {
                        GroupHeaderRow(
                            view = view,
                            collapsed = view.key.id in collapsed,
                            selected = selection is AccountSelection.Group && selection.key.id == view.key.id,
                            primaryCurrency = primaryCurrency,
                            onSelect = { onSelect(AccountSelection.Group(view.key)) },
                            onToggle = { onToggleCollapsed(view.key) },
                        )
                    }
                    if (view.key.id !in collapsed) {
                        visible.forEach { account ->
                            RowContextMenu(menuItem(s[StringKey.MoveToGroup]) { moving = account }) {
                                AccountRow(
                                    account = account,
                                    selected = selection is AccountSelection.Account && selection.id == account.id,
                                    onSelect = { onSelect(AccountSelection.Account(account.id)) },
                                )
                            }
                        }
                    }
                }
            }

            HorizontalDivider()
            Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { creating = true }) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(s[StringKey.NewGroup])
                }
                OutlinedTextField(
                    value = filterText,
                    onValueChange = onFilterText,
                    label = { Text(s[StringKey.FilterAccounts]) },
                    singleLine = true,
                    supportingText = { Text(s.format(StringKey.AccountsShown, shown, accounts.size)) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }

    if (creating) {
        GroupNameDialog(
            title = s[StringKey.NewGroup],
            initial = "",
            onDismiss = { creating = false },
            onConfirm = {
                onCreateGroup(it)
                creating = false
            },
        )
    }
    renaming?.let { group ->
        GroupNameDialog(
            title = s[StringKey.RenameGroup],
            initial = group.name,
            onDismiss = { renaming = null },
            onConfirm = {
                onRenameGroup(group.id, it)
                renaming = null
            },
        )
    }
    deleting?.let { group ->
        ConfirmDialog(
            title = s[StringKey.DeleteGroup],
            text = s.format(StringKey.DeleteGroupText, group.name),
            onConfirm = { onDeleteGroup(group.id) },
            onDismiss = { deleting = null },
        )
    }
    moving?.let { account ->
        MoveToGroupDialog(
            account = account,
            customGroups = customGroups,
            onDismiss = { moving = null },
            onConfirm = {
                onMoveAccount(account.id, it)
                moving = null
            },
        )
    }
}

/** One clickable entry that is highlighted (and announced) while selected. */
@Composable
private fun SelectableRow(
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    Surface(color = if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = modifier
                .clickable(onClick = onClick, role = Role.Button)
                .semantics { this.selected = selected }
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
    }
}

@Composable
private fun GroupHeaderRow(
    view: AccountGroupView,
    collapsed: Boolean,
    selected: Boolean,
    primaryCurrency: String,
    onSelect: () -> Unit,
    onToggle: () -> Unit,
) {
    val s = strings()
    val label = view.name ?: (view.key as? GroupKey.Type)?.type?.let { s.accountType(it) }.orEmpty()
    SelectableRow(selected = selected, onClick = onSelect) {
        IconButton(onClick = onToggle, modifier = Modifier.size(28.dp)) {
            Icon(
                if (collapsed) Icons.Default.KeyboardArrowRight else Icons.Default.KeyboardArrowDown,
                contentDescription = s[if (collapsed) StringKey.Expand else StringKey.Collapse],
                modifier = Modifier.size(18.dp),
            )
        }
        Text(label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Text(Currencies.format(view.totals, primaryCurrency), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium, textAlign = TextAlign.End)
    }
}

@Composable
private fun AccountRow(account: AccountItem, selected: Boolean, onSelect: () -> Unit) {
    val s = strings()
    SelectableRow(selected = selected, onClick = onSelect, modifier = Modifier.padding(start = 12.dp)) {
        Column(Modifier.weight(1f)) {
            Text(s.accountName(account), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("…${account.iban.takeLast(4)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            account.lastUpdate?.let {
                Text(
                    s.format(StringKey.LastFetched, Formats.date(it.toLocalDate())),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Text(
            Formats.money(account.balance, account.currency),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.End,
            color = if (account.balance.signum() < 0) amountColor(true) else MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** One small name dialog for both a new and a renamed group; Save stays disabled while blank. */
@Composable
private fun GroupNameDialog(title: String, initial: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    val field = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { field.requestFocus() } }
    val s = strings()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.width(400.dp)) {
                OutlinedTextField(
                    text,
                    { text = it.take(MAX_ACCOUNT_GROUP_NAME_LENGTH) },
                    label = { Text(s[StringKey.GroupName]) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { if (text.isNotBlank()) onConfirm(text) }),
                    modifier = Modifier.fillMaxWidth().focusRequester(field),
                )
            }
        },
        confirmButton = { Button(enabled = text.isNotBlank(), onClick = { onConfirm(text) }) { Text(s[StringKey.Save]) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(s[StringKey.Cancel]) } },
    )
}

@Composable
private fun MoveToGroupDialog(account: AccountItem, customGroups: List<AccountGroupItem>, onDismiss: () -> Unit, onConfirm: (Int?) -> Unit) {
    val s = strings()
    var chosen by remember(account.id) { mutableStateOf(account.groupId) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(s[StringKey.MoveToGroup]) },
        text = {
            Column(Modifier.width(360.dp)) {
                RadioRow(selected = chosen == null, label = s[StringKey.DefaultGroup]) { chosen = null }
                customGroups.forEach { group ->
                    RadioRow(selected = chosen == group.id, label = group.name) { chosen = group.id }
                }
            }
        },
        confirmButton = { Button(onClick = { onConfirm(chosen) }) { Text(s[StringKey.Save]) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(s[StringKey.Cancel]) } },
    )
}

@Composable
private fun RadioRow(selected: Boolean, label: String, onSelect: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onSelect, role = Role.RadioButton).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}
