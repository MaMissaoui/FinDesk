package de.findesk.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import de.findesk.i18n.StringKey
import de.findesk.i18n.Strings
import de.findesk.i18n.strings
import de.findesk.ui.components.FinDeskCard
import de.findesk.ui.theme.balanceToneColor
import de.findesk.ui.theme.figure
import de.findesk.ui.theme.inflowColor
import de.findesk.util.BalanceTone
import de.findesk.util.Currencies
import de.findesk.util.Formats
import de.findesk.viewmodel.DashboardState
import java.math.BigDecimal
import java.time.YearMonth

/** The role one month-statement line plays, which decides its label and amount colours. */
internal enum class StatementKind { CARRY, IN, OUT, MOVEMENT, RESULT }

/** One line of the month statement; [amountText] is already formatted and parsed by the unit tests. */
internal data class StatementLine(val label: String, val amountText: String, val kind: StatementKind)

/**
 * The month statement in the order of a German bank statement: what was carried over, what came in, what went out,
 * what moved between the user's own accounts or to loans, and the resulting available balance. A missing carry-forward
 * or a zero movement simply has no line; the remaining amounts still add up to the result.
 */
internal fun statementLines(state: DashboardState, loadedMonth: YearMonth, s: Strings): List<StatementLine> = buildList {
    state.carryForward?.let { carried ->
        add(
            StatementLine(
                label = s.format(StringKey.CarryForwardLabel, s.month(loadedMonth.minusMonths(1))),
                amountText = Formats.money(carried, state.currency),
                kind = StatementKind.CARRY,
            ),
        )
    }
    add(
        StatementLine(
            label = s[StringKey.DirectionIncome],
            amountText = incomeAmount(state.monthlyIncome, state.currency),
            kind = StatementKind.IN,
        ),
    )
    add(
        StatementLine(
            label = s[StringKey.DirectionExpenses],
            // Expenses are stored positive, but a statement line that takes money away reads with a minus
            amountText = Formats.money(state.monthlyExpenses.negate(), state.currency),
            kind = StatementKind.OUT,
        ),
    )
    state.otherMovements?.takeIf { it.signum() != 0 }?.let { moved ->
        add(
            StatementLine(
                label = s[StringKey.StatementMovementsLabel],
                amountText = Formats.money(moved, state.currency, signed = true),
                kind = StatementKind.MOVEMENT,
            ),
        )
    }
    add(
        StatementLine(
            label = state.balanceDate?.let { s.format(StringKey.AvailableAsOf, Formats.date(it)) } ?: s[StringKey.AvailableBalance],
            amountText = Formats.money(state.availableBalance, state.currency),
            kind = StatementKind.RESULT,
        ),
    )
}

/** Income reads with a plus even at zero ("+0,00 €"), which [Formats.money] doesn't add for zero. */
private fun incomeAmount(amount: BigDecimal, currency: String): String {
    val text = Formats.money(amount, currency, signed = true)
    return if (amount.signum() == 0) "+$text" else text
}

/** One card for the month: the statement on the left and the open tasks on the right, or below when narrow. */
@Composable
internal fun MonthStatementCard(
    state: DashboardState,
    loadedMonth: YearMonth,
    onOpenTasks: () -> Unit,
    onOpenDocuments: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val stacked = maxWidth < 900.dp
        FinDeskCard(Modifier.fillMaxWidth()) {
            if (stacked) {
                Column(Modifier.padding(20.dp)) {
                    StatementSide(state, loadedMonth)
                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 16.dp),
                        thickness = 1.dp,
                        color = MaterialTheme.colorScheme.outlineVariant,
                    )
                    TasksSide(state, onOpenTasks, onOpenDocuments)
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min).padding(20.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Box(Modifier.weight(3f)) { StatementSide(state, loadedMonth) }
                    Spacer(Modifier.width(20.dp))
                    VerticalDivider(Modifier.fillMaxHeight(), thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
                    Spacer(Modifier.width(20.dp))
                    Box(Modifier.weight(2f)) { TasksSide(state, onOpenTasks, onOpenDocuments) }
                }
            }
        }
    }
}

/** The month title, its statement lines and, below them, the loan/net and other-currency note. */
@Composable
private fun StatementSide(state: DashboardState, loadedMonth: YearMonth) {
    val s = strings()
    Column(Modifier.widthIn(max = 560.dp)) {
        Text(s.month(loadedMonth), style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(12.dp))
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            statementLines(state, loadedMonth, s).forEach { line ->
                if (line.kind == StatementKind.RESULT) {
                    // The rule spans only the amount column, like the line above a statement's final balance
                    HorizontalDivider(
                        modifier = Modifier.width(AmountColumnWidth).align(Alignment.End),
                        thickness = 1.dp,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                StatementRow(line, state.availableBalance.signum() < 0)
            }
        }
        BalanceNote(state)
    }
}

/** Wide enough for "-1.234.567,89 €" even at the result's 24 sp, and the same for every line so decimals align. */
private val AmountColumnWidth = 208.dp

@Composable
private fun StatementRow(line: StatementLine, resultNegative: Boolean) {
    val result = line.kind == StatementKind.RESULT
    val labelColor = when (line.kind) {
        StatementKind.CARRY, StatementKind.MOVEMENT -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> MaterialTheme.colorScheme.onSurface
    }
    val amountColor = when (line.kind) {
        StatementKind.IN -> inflowColor()
        StatementKind.RESULT -> if (resultNegative) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
        else -> MaterialTheme.colorScheme.onSurface
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            line.label,
            style = if (result) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium,
            color = labelColor,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            line.amountText,
            style = if (result) MaterialTheme.typography.figure else MaterialTheme.typography.bodyMedium,
            color = amountColor,
            textAlign = TextAlign.End,
            maxLines = 1,
            modifier = Modifier.width(AmountColumnWidth),
        )
    }
}

/** Loans and net in bodySmall slate, plus other currencies as a note: never mixed into the statement. */
@Composable
private fun BalanceNote(state: DashboardState) {
    val s = strings()
    val loanAmountText = Formats.money(state.loanBalance, state.currency)
    val netAmountText = Formats.money(state.totalBalance, state.currency)
    val loansText = state.hasLoans.takeIf { it }?.let {
        s.format(StringKey.LoansSubtitle, loanAmountText, netAmountText)
    }
    val otherText = state.otherBalances.takeIf { it.isNotEmpty() }
        ?.let { s.format(StringKey.PlusOtherCurrencies, Currencies.format(it, state.currency)) }
    val netTone = when {
        state.totalBalance.signum() > 0 -> BalanceTone.POSITIVE
        state.totalBalance.signum() == 0 -> BalanceTone.ZERO
        state.availableBalance.signum() < 0 -> BalanceTone.NEGATIVE
        else -> BalanceTone.LOAN
    }
    val loanColor = MaterialTheme.colorScheme.onSurfaceVariant
    val netColor = balanceToneColor(netTone)
    val note = buildAnnotatedString {
        if (loansText != null) {
            val base = length
            append(loansText)
            val loanStart = loansText.indexOf(loanAmountText)
            if (loanStart >= 0) addStyle(SpanStyle(color = loanColor), base + loanStart, base + loanStart + loanAmountText.length)
            val netStart = loansText.indexOf(netAmountText)
            if (netStart >= 0) addStyle(SpanStyle(color = netColor), base + netStart, base + netStart + netAmountText.length)
        }
        if (otherText != null) {
            if (length > 0) append("\n")
            append(otherText)
        }
    }
    if (note.isNotEmpty()) {
        Spacer(Modifier.height(12.dp))
        Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** The open tasks: the number first, then one line per kind with a non-zero count, or a quiet all-clear. */
@Composable
private fun TasksSide(state: DashboardState, onOpenTasks: () -> Unit, onOpenDocuments: (() -> Unit)?) {
    val s = strings()
    val countColor = if (state.pendingTasks > 0) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant
    Column(Modifier.fillMaxWidth()) {
        Text(s[StringKey.PendingTasks], style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(state.pendingTasks.toString(), style = MaterialTheme.typography.figure, color = countColor)
        Spacer(Modifier.height(12.dp))
        if (state.pendingTasks == 0) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(s[StringKey.NothingPending], style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            if (state.uncategorizedCount > 0) {
                TaskLine(s.format(StringKey.PendingUncategorizedLine, state.uncategorizedCount), s[StringKey.ViewPendingTasks], onOpenTasks)
            }
            if (state.unlinkedDocumentCount > 0) {
                TaskLine(s.format(StringKey.PendingUnlinkedDocumentsLine, state.unlinkedDocumentCount), s[StringKey.ViewPendingTasks], onOpenDocuments)
            }
        }
    }
}

/** One task kind, with the same button role and click label as the pending-tasks card had. */
@Composable
private fun TaskLine(label: String, onClickLabel: String, onClick: (() -> Unit)?) {
    val base = Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small)
    val interactive = if (onClick == null) {
        base
    } else {
        base.clickable(onClickLabel = onClickLabel, role = Role.Button, onClick = onClick)
    }
    Row(
        modifier = interactive.padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        if (onClick != null) {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}
