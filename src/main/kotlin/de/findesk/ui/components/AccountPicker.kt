package de.findesk.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.findesk.i18n.StringKey
import de.findesk.i18n.strings
import de.findesk.model.AccountItem
import de.findesk.ui.theme.balanceToneColor
import de.findesk.util.Formats
import de.findesk.util.balanceTone

/**
 * The Payments screen's "From" picker: an outlined field that opens a readable menu instead of a list of one-line
 * strings. The menu groups the accounts by bank with a header per bank, shows every account on two lines (name, then
 * the grouped IBAN) and keeps the balance right-aligned and coloured like everywhere else.
 */
@Composable
fun AccountPicker(
    selected: AccountItem?,
    accounts: List<AccountItem>,
    onSelect: (AccountItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    val s = strings()
    var expanded by remember { mutableStateOf(false) }
    val description = selected?.let { "${s[StringKey.From]}: ${s.accountName(it)}, ${it.bankName}, ${Formats.money(it.balance, it.currency)}" }
        ?: s[StringKey.ChooseAccount]
    Box(modifier) {
        Surface(
            onClick = { expanded = true },
            shape = RoundedCornerShape(4.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).semantics { contentDescription = description },
        ) {
            // The Surface's description already reads the selection; the visible text must not be read a second time
            Row(
                Modifier.padding(horizontal = 16.dp).clearAndSetSemantics {},
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (selected != null) {
                    Text(
                        s.accountName(selected),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        " · ${selected.bankName}",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        Formats.money(selected.balance, selected.currency),
                        style = MaterialTheme.typography.bodyLarge,
                        color = balanceToneColor(balanceTone(listOf(selected), selected.currency)),
                        maxLines = 1,
                        modifier = Modifier.padding(start = 12.dp),
                    )
                } else {
                    Text(
                        s[StringKey.ChooseAccount],
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }
                Icon(Icons.Default.ArrowDropDown, contentDescription = null)
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }, modifier = Modifier.widthIn(min = 440.dp)) {
            // The list order is the view model's bank-then-type order, so a bank change starts a new group
            var previousBank: String? = null
            accounts.forEach { account ->
                if (account.bankName != previousBank) {
                    if (previousBank != null) HorizontalDivider()
                    Text(
                        account.bankName,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                    previousBank = account.bankName
                }
                AccountPickerItem(account, isSelected = account.id == selected?.id) {
                    expanded = false
                    onSelect(account)
                }
            }
        }
    }
}

/** One account's menu entry: the two readable lines plus a leading check mark on the selected one. */
@Composable
private fun AccountPickerItem(account: AccountItem, isSelected: Boolean, onSelect: () -> Unit) {
    val s = strings()
    val iban = Formats.iban(account.iban)
    val balance = Formats.money(account.balance, account.currency)
    // A screen reader reads name, bank, IBAN and balance; the merged visual lines alone read oddly
    val description = "${s.accountName(account)}, ${account.bankName}, $iban, $balance"
    DropdownMenuItem(
        text = {
            Column(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        s.accountName(account),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        balance,
                        color = balanceToneColor(balanceTone(listOf(account), account.currency)),
                    )
                }
                // The type prefix tells named accounts apart; an unnamed one already says its type
                Text(
                    account.name?.let { "${s.accountType(account.accountType)} · $iban" } ?: iban,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
        leadingIcon = {
            // The spacer keeps the text of the unselected items lined up with the selected one
            if (isSelected) {
                Icon(
                    Icons.Default.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp),
                )
            } else {
                Spacer(Modifier.width(24.dp))
            }
        },
        modifier = Modifier.semantics(mergeDescendants = true) { contentDescription = description },
        onClick = onSelect,
    )
}
