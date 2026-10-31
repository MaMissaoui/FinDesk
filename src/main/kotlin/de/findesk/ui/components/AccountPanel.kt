package de.findesk.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import de.findesk.i18n.StringKey
import de.findesk.i18n.Strings
import de.findesk.i18n.strings
import de.findesk.model.AccountGroupItem
import de.findesk.model.AccountItem
import de.findesk.repository.MAX_ACCOUNT_GROUP_NAME_LENGTH
import de.findesk.ui.theme.ErrorOnSelectionDark
import de.findesk.ui.theme.ErrorOnSelectionLight
import de.findesk.ui.theme.balanceColor
import de.findesk.util.AccountGroupView
import de.findesk.util.Formats
import de.findesk.util.GroupKey
import de.findesk.util.dropTarget
import de.findesk.util.groupIdFor
import de.findesk.util.isWarningBalance
import de.findesk.viewmodel.AccountSelection
import java.math.BigDecimal
import kotlin.math.roundToInt

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
    onMarkRead: (AccountSelection) -> Unit,
    modifier: Modifier = Modifier,
) {
    val s = strings()
    var creating by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<AccountGroupItem?>(null) }
    var deleting by remember { mutableStateOf<AccountGroupItem?>(null) }
    var moving by remember { mutableStateOf<AccountItem?>(null) }

    // Mouse drag of an account row onto a group header. Header bounds and the pointer live in the same (root)
    // coordinate space, so a drop target can be hit-tested as a pure function (see dropTarget).
    var drag by remember { mutableStateOf<AccountDrag?>(null) }
    val headers = remember { mutableStateMapOf<GroupKey, Rect>() }
    var panelTopLeft by remember { mutableStateOf(Offset.Zero) }
    val dragFocus = remember { FocusRequester() }
    val target = drag?.let { dropTarget(it.pointer, headers, it.account, it.currentKey) }

    fun drop() {
        val current = drag
        if (current != null) {
            dropTarget(current.pointer, headers, current.account, current.currentKey)?.let { key ->
                onMoveAccount(current.account.id, groupIdFor(key))
            }
        }
        drag = null
    }

    val query = filterText.trim()
    fun matches(account: AccountItem): Boolean = query.isEmpty() ||
        s.accountName(account).contains(query, ignoreCase = true) ||
        account.iban.contains(query, ignoreCase = true) ||
        account.bankName.contains(query, ignoreCase = true)

    val shown = accounts.count { matches(it) }
    val totalNew = accounts.sumOf { it.newCount }

    FinDeskCard(modifier = modifier) {
        Box(Modifier.fillMaxSize().onGloballyPositioned { panelTopLeft = it.positionInRoot() }) {
            Column(Modifier.fillMaxSize()) {
                Column(Modifier.padding(start = 8.dp, top = 8.dp, end = 8.dp)) {
                    CompactSearchField(
                        value = filterText,
                        onValueChange = onFilterText,
                        placeholder = s[StringKey.FilterAccounts],
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (filterText.isNotEmpty()) {
                        Text(
                            s.format(StringKey.AccountsShown, shown, accounts.size),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 12.dp, top = 4.dp),
                        )
                    }
                }
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(vertical = 4.dp)) {
                    RowContextMenu(
                        if (totalNew > 0) menuItem(s[StringKey.MarkAsRead]) { onMarkRead(AccountSelection.All) } else null,
                    ) {
                        SelectableRow(selected = selection == AccountSelection.All, onClick = { onSelect(AccountSelection.All) }) {
                            Text(s[StringKey.AllAccounts], style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                            NewCountBadge(totalNew, selected = selection == AccountSelection.All)
                            Spacer(Modifier.width(8.dp))
                            Column(horizontalAlignment = Alignment.End) {
                                grandTotal.forEach { (currency, amount) ->
                                    Text(Formats.money(amount, currency), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = panelAmountColor(isWarningBalance(accounts, currency), selection == AccountSelection.All))
                                }
                            }
                        }
                    }

                    groups.forEach { view ->
                        val visible = view.accounts.filter { matches(it) }
                        // A group with no matching account is hidden while a filter is set.
                        if (query.isNotEmpty() && visible.isEmpty()) return@forEach
                        val custom = (view.key as? GroupKey.Custom)?.let { key -> customGroups.firstOrNull { it.id == key.groupId } }
                        // A deleted group must not stay a drop target
                        DisposableEffect(view.key) { onDispose { headers.remove(view.key) } }
                        RowContextMenu(
                            if (view.newCount > 0) menuItem(s[StringKey.MarkAsRead]) { onMarkRead(AccountSelection.Group(view.key)) } else null,
                            custom?.let { menuItem(s[StringKey.RenameGroup]) { renaming = it } },
                            custom?.let { menuItem(s[StringKey.MoveUp]) { onMoveGroup(it.id, true) } },
                            custom?.let { menuItem(s[StringKey.MoveDown]) { onMoveGroup(it.id, false) } },
                            custom?.let { menuItem(s[StringKey.Delete]) { deleting = it } },
                        ) {
                            GroupHeaderRow(
                                view = view,
                                collapsed = view.key.id in collapsed,
                                selected = selection is AccountSelection.Group && selection.key.id == view.key.id,
                                highlighted = target == view.key,
                                primaryCurrency = primaryCurrency,
                                onSelect = { onSelect(AccountSelection.Group(view.key)) },
                                onToggle = { onToggleCollapsed(view.key) },
                                onBounds = { headers[view.key] = it },
                            )
                        }
                        if (view.key.id !in collapsed) {
                            visible.forEach { account ->
                                RowContextMenu(
                                    if (account.newCount > 0) menuItem(s[StringKey.MarkAsRead]) { onMarkRead(AccountSelection.Account(account.id)) } else null,
                                    menuItem(s[StringKey.MoveToGroup]) { moving = account },
                                ) {
                                    AccountRow(
                                        account = account,
                                        selected = selection is AccountSelection.Account && selection.id == account.id,
                                        dragging = drag?.account?.id == account.id,
                                        onSelect = { onSelect(AccountSelection.Account(account.id)) },
                                        onDragStart = { drag = AccountDrag(account, view.key, it) },
                                        onDragBy = { delta -> drag = drag?.let { current -> current.copy(pointer = current.pointer + delta) } },
                                        onDragEnd = { drop() },
                                        onDragCancel = { drag = null },
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
                }
            }

            // The floating label follows the pointer inside the panel while a row is dragged
            drag?.let { d ->
                val position = d.pointer - panelTopLeft
                Surface(
                    tonalElevation = 6.dp,
                    shadowElevation = 6.dp,
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier
                        .offset { IntOffset(position.x.roundToInt() + 12, position.y.roundToInt() + 12) }
                        .zIndex(1f)
                        .semantics { contentDescription = s[StringKey.DragToGroupHint] },
                ) {
                    Text(
                        s.accountName(d.account),
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }

            // Esc cancels the drag (a mouse drag has no other key to drop out); focus is only taken while dragging
            if (drag != null) {
                Box(
                    Modifier
                        .size(0.dp)
                        .focusRequester(dragFocus)
                        .focusable()
                        .onPreviewKeyEvent { event ->
                            if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                                drag = null
                                true
                            } else {
                                false
                            }
                        },
                )
                LaunchedEffect(Unit) { runCatching { dragFocus.requestFocus() } }
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

/**
 * Red when the loan rule warns (see [balanceColor]), even on the selection highlight: there the selection-safe red
 * keeps 4.5:1 where the plain one falls below (see ContrastTest), so a checking account's red stays red everywhere.
 */
@Composable
private fun panelAmountColor(warning: Boolean, selected: Boolean): Color = when {
    !selected -> balanceColor(warning)
    warning -> if (MaterialTheme.colorScheme.surface.luminance() > 0.5f) ErrorOnSelectionLight else ErrorOnSelectionDark
    else -> LocalContentColor.current
}

/** The grey of secondary lines, except on the selection highlight, where the row keeps the container's content colour. */
@Composable
private fun panelSecondaryColor(selected: Boolean): Color = if (selected) LocalContentColor.current else MaterialTheme.colorScheme.onSurfaceVariant

/** One account being dragged: its row, the group it is listed under and the pointer in root coordinates. */
private data class AccountDrag(val account: AccountItem, val currentKey: GroupKey, val pointer: Offset)

/**
 * The unread-booking count for an account, a group or "All accounts": a small pill shown only when it is above zero.
 * On the selection highlight a filled pill would be unreadable in dark mode, so there it turns into an outline in the
 * row's own content colour (same reason as [panelAmountColor]).
 */
@Composable
private fun NewCountBadge(count: Int, selected: Boolean) {
    if (count <= 0) return
    val s = strings()
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (selected) Color.Transparent else MaterialTheme.colorScheme.primary,
        border = if (selected) BorderStroke(1.dp, LocalContentColor.current) else null,
        modifier = Modifier.semantics { contentDescription = s.format(StringKey.NewBookingsCount, count) },
    ) {
        Text(
            text = count.toString(),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
            color = if (selected) LocalContentColor.current else MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
        )
    }
}

/** One clickable entry that is highlighted (and announced) while selected, and outlined while a dragged account may be dropped on it. */
@Composable
private fun SelectableRow(
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    alpha: Float = 1f,
    highlighted: Boolean = false,
    content: @Composable RowScope.() -> Unit,
) {
    // A plain 1f row keeps its exact previous rendering: no extra graphics layer
    val surfaceModifier = if (alpha < 1f) Modifier.fillMaxWidth().alpha(alpha) else Modifier.fillMaxWidth()
    val barColor = MaterialTheme.colorScheme.primary
    val selectionBar = if (selected) {
        Modifier.drawBehind { drawRect(color = barColor, size = Size(3.dp.toPx(), size.height)) }
    } else {
        Modifier
    }
    Surface(
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
        border = if (highlighted) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
        modifier = surfaceModifier,
    ) {
        Row(
            modifier = selectionBar
                .then(modifier)
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
    highlighted: Boolean,
    primaryCurrency: String,
    onSelect: () -> Unit,
    onToggle: () -> Unit,
    onBounds: (Rect) -> Unit,
) {
    val s = strings()
    val label = view.name ?: (view.key as? GroupKey.Type)?.type?.let { s.accountType(it) }.orEmpty()
    SelectableRow(
        selected = selected,
        onClick = onSelect,
        modifier = Modifier.onGloballyPositioned { onBounds(it.boundsInRoot()) },
        highlighted = highlighted,
    ) {
        IconButton(onClick = onToggle, modifier = Modifier.size(28.dp)) {
            Icon(
                if (collapsed) Icons.Default.KeyboardArrowRight else Icons.Default.KeyboardArrowDown,
                contentDescription = s[if (collapsed) StringKey.Expand else StringKey.Collapse],
                modifier = Modifier.size(18.dp),
            )
        }
        Text(label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        NewCountBadge(view.newCount, selected = selected)
        Spacer(Modifier.width(8.dp))
        // One line per currency, each coloured by its own currency's loan rule
        Column(horizontalAlignment = Alignment.End) {
            view.totals.ifEmpty { mapOf(primaryCurrency to BigDecimal.ZERO) }.forEach { (currency, amount) ->
                Text(
                    Formats.money(amount, currency),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.End,
                    // Each currency's own loan rule, so a negative-only-because-of-loans total stays plain
                    color = panelAmountColor(isWarningBalance(view.accounts, currency), selected),
                )
            }
        }
    }
}

/**
 * One account. It is both clickable (a press without movement still selects it) and draggable: the drag detector only
 * starts after the touch slop, so a click never becomes a drag, and after the slop it consumes the pointer so the
 * click is cancelled. The row position is read in root coordinates so the pointer can be hit-tested against the
 * group headers (see [dropTarget]).
 */
@Composable
private fun AccountRow(
    account: AccountItem,
    selected: Boolean,
    dragging: Boolean,
    onSelect: () -> Unit,
    onDragStart: (Offset) -> Unit,
    onDragBy: (Offset) -> Unit,
    onDragEnd: () -> Unit,
    onDragCancel: () -> Unit,
) {
    val s = strings()
    var topLeft by remember { mutableStateOf(Offset.Zero) }
    val latestTopLeft by rememberUpdatedState(topLeft)
    val dragStart by rememberUpdatedState(onDragStart)
    val dragBy by rememberUpdatedState(onDragBy)
    val dragEnd by rememberUpdatedState(onDragEnd)
    val dragCancel by rememberUpdatedState(onDragCancel)
    SelectableRow(
        selected = selected,
        onClick = onSelect,
        alpha = if (dragging) 0.4f else 1f,
        modifier = Modifier
            .padding(start = 12.dp)
            .onGloballyPositioned { topLeft = it.positionInRoot() }
            .pointerInput(account.id) {
                detectDragGestures(
                    onDragStart = { offset -> dragStart(latestTopLeft + offset) },
                    onDrag = { change, amount ->
                        change.consume()
                        dragBy(amount)
                    },
                    onDragEnd = { dragEnd() },
                    onDragCancel = { dragCancel() },
                )
            },
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                s.accountName(account),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (account.newCount > 0) FontWeight.Bold else FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // Only with a user-set name: the default name already ends in the same 4 characters
            if (account.name != null) {
                Text("…${account.iban.takeLast(4)}", style = MaterialTheme.typography.bodySmall, color = panelSecondaryColor(selected))
            }
            account.lastUpdate?.let {
                Text(
                    s.format(StringKey.LastFetched, Formats.date(it.toLocalDate())),
                    style = MaterialTheme.typography.bodySmall,
                    color = panelSecondaryColor(selected),
                )
            }
        }
        NewCountBadge(account.newCount, selected = selected)
        Spacer(Modifier.width(8.dp))
        Text(
            Formats.money(account.balance, account.currency),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.End,
            color = panelAmountColor(isWarningBalance(listOf(account), account.currency), selected),
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

/** A group's label: its custom name, or the localized account type. */
private fun groupLabel(view: AccountGroupView, s: Strings): String = view.name ?: (view.key as? GroupKey.Type)?.type?.let { s.accountType(it) }.orEmpty()

/** The compact selector's button text for the current selection. */
private fun compactSelectionLabel(selection: AccountSelection, groups: List<AccountGroupView>, accounts: List<AccountItem>, s: Strings): String = when (selection) {
    AccountSelection.All -> s[StringKey.AllAccounts]
    is AccountSelection.Group -> groups.firstOrNull { it.key == selection.key }?.let { groupLabel(it, s) } ?: s[StringKey.AllAccounts]
    is AccountSelection.Account -> accounts.firstOrNull { it.id == selection.id }?.let { accountLabel(it) } ?: s[StringKey.AllAccounts]
}

/** Menu text for the compact selector: groups as they are, accounts indented under them, each with its new count. */
private fun compactOptionLabel(selection: AccountSelection, groups: List<AccountGroupView>, accounts: List<AccountItem>, s: Strings): String {
    fun withCount(label: String, count: Int) = if (count > 0) "$label ($count)" else label
    return when (selection) {
        AccountSelection.All -> withCount(s[StringKey.AllAccounts], accounts.sumOf { it.newCount })
        is AccountSelection.Group -> groups.firstOrNull { it.key == selection.key }?.let { withCount(groupLabel(it, s), it.newCount) } ?: s[StringKey.AllAccounts]
        is AccountSelection.Account -> accounts.firstOrNull { it.id == selection.id }?.let { withCount("  " + accountLabel(it), it.newCount) } ?: s[StringKey.AllAccounts]
    }
}

/**
 * The account picker for windows too narrow for the panel: one dropdown with "All accounts", every group and every
 * account, standing in for the panel on Transactions and Accounts. Group management (new, rename, move, delete,
 * move account) stays in the panel.
 */
@Composable
fun CompactAccountSelector(
    accounts: List<AccountItem>,
    groups: List<AccountGroupView>,
    selection: AccountSelection,
    onSelect: (AccountSelection) -> Unit,
) {
    val s = strings()
    val options = listOf<AccountSelection>(AccountSelection.All) + groups.map { AccountSelection.Group(it.key) } + accounts.map { AccountSelection.Account(it.id) }
    DropdownSelector(
        label = compactSelectionLabel(selection, groups, accounts, s),
        options = options,
        optionLabel = { compactOptionLabel(it, groups, accounts, s) },
        onSelect = onSelect,
    )
}
