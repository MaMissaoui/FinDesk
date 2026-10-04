package de.findesk.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import de.findesk.i18n.StringKey
import de.findesk.i18n.strings
import de.findesk.model.TransactionItem
import de.findesk.ui.components.*
import de.findesk.ui.navigation.NavigationArgs
import de.findesk.ui.navigation.Screen
import de.findesk.ui.theme.Error
import de.findesk.ui.theme.PrimaryBlue
import de.findesk.ui.theme.Success
import de.findesk.ui.theme.Warning
import de.findesk.ui.theme.amountColor
import de.findesk.util.Currencies
import de.findesk.util.Formats
import de.findesk.viewmodel.BudgetStatus
import de.findesk.viewmodel.DashboardViewModel

@Composable
fun DashboardScreen(onNavigate: (Screen, NavigationArgs) -> Unit = { _, _ -> }) {
    val vm = rememberViewModel { DashboardViewModel() }
    val state by vm.state.collectAsState()
    val s = strings()
    LaunchedEffect(Unit) { vm.loadDashboard() }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        if (state.error != null) {
            item { MessageBanner(null, state.error) { vm.loadDashboard() } }
        }

        if (!state.isLoading && !state.hasAccounts) {
            item {
                Card {
                    EmptyState(
                        icon = Icons.Default.AccountBox,
                        title = s[StringKey.WelcomeTitle],
                        description = s[StringKey.WelcomeDescription],
                    ) {
                        Button(onClick = { onNavigate(Screen.ACCOUNTS, NavigationArgs()) }) { Text(s[StringKey.AddAccount]) }
                    }
                }
            }
        }

        item {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                StatCard(
                    s[StringKey.TotalBalance],
                    Formats.money(state.totalBalance, state.currency),
                    Icons.Default.AccountBox,
                    PrimaryBlue,
                    Modifier.weight(1f),
                    subtitle = state.otherBalances.takeIf { it.isNotEmpty() }?.let { s.format(StringKey.PlusOtherCurrencies, Currencies.format(it, state.currency)) },
                )
                StatCard(s[StringKey.IncomeThisMonth], Formats.money(state.monthlyIncome, state.currency), Icons.Default.KeyboardArrowUp, Success, Modifier.weight(1f))
                StatCard(s[StringKey.ExpensesThisMonth], Formats.money(state.monthlyExpenses, state.currency), Icons.Default.KeyboardArrowDown, Error, Modifier.weight(1f))
                StatCard(
                    s[StringKey.PendingTasks],
                    state.pendingTasks.toString(),
                    Icons.Default.Warning,
                    Warning,
                    Modifier
                        .weight(1f)
                        .clickable(
                            enabled = state.uncategorizedCount > 0,
                            onClick = {
                                onNavigate(Screen.TRANSACTIONS, NavigationArgs(uncategorizedOnly = true))
                            },
                            onClickLabel = s[StringKey.ViewPendingTasks],
                            role = Role.Button,
                        ),
                    subtitle = s.format(StringKey.PendingSubtitle, state.uncategorizedCount, state.unlinkedDocumentCount),
                )
            }
        }

        item {
            Row(modifier = Modifier.fillMaxWidth().height(300.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                SectionCard(s[StringKey.ExpensesByCategory], Modifier.weight(1f).fillMaxHeight()) {
                    if (state.expensesByCategory.isEmpty()) {
                        Text(s[StringKey.NoExpensesThisMonth], color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        DonutChart(state.expensesByCategory, s[StringKey.ExpensesByCategory], currency = state.currency)
                    }
                }
                SectionCard(s[StringKey.IncomeVsExpenses], Modifier.weight(1f).fillMaxHeight()) {
                    IncomeExpenseBars(state.lastMonths, s[StringKey.IncomeVsExpenses], Modifier.fillMaxSize(), currency = state.currency)
                }
            }
        }

        if (state.upcoming.isNotEmpty()) {
            item {
                SectionCard(s[StringKey.Upcoming30Days], Modifier.fillMaxWidth()) {
                    state.upcoming.forEach { r ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(Formats.date(r.nextDate), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(100.dp))
                            Column(Modifier.weight(1f)) {
                                Text(r.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                                Text(
                                    s.cadence(r.cadence) + (r.categoryName?.let { " · $it" } ?: "") + if (r.fixedAmount) "" else " · ${s[StringKey.AmountVaries]}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Text(
                                (if (r.fixedAmount) "" else "~") + Formats.money(r.typicalAmount, r.currency, signed = true),
                                fontWeight = FontWeight.SemiBold,
                                color = amountColor(!r.isIncome),
                            )
                        }
                    }
                }
            }
        }

        if (state.budgets.isNotEmpty()) {
            item {
                SectionCard(s[StringKey.BudgetsThisMonth], Modifier.fillMaxWidth()) {
                    state.budgets.forEach { b -> BudgetRow(b, state.monthProgress, state.currency) }
                }
            }
        }

        item {
            SectionCard(s[StringKey.TaxDeductibleYtd], Modifier.fillMaxWidth()) {
                if (state.taxDeductionsByCategory.isEmpty()) {
                    Text(
                        s[StringKey.TaxDeductibleHint],
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    DonutChart(state.taxDeductionsByCategory, s[StringKey.TaxDeductibleYtd], currency = state.currency)
                }
            }
        }

        item {
            SectionCard(
                s[StringKey.RecentTransactions],
                Modifier.fillMaxWidth(),
                actions = { TextButton(onClick = { onNavigate(Screen.TRANSACTIONS, NavigationArgs()) }) { Text(s[StringKey.ViewAll]) } },
            ) {
                if (state.recentTransactions.isEmpty()) {
                    Text(s[StringKey.NoTransactionsYet], color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                state.recentTransactions.forEach { RecentTransactionRow(it) }
            }
        }
    }
}

@Composable
private fun RecentTransactionRow(txn: TransactionItem) {
    val s = strings()
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(txn.description, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, maxLines = 1)
            Text(
                "${Formats.date(txn.date)} • ${txn.categoryName ?: s[StringKey.Uncategorized]}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            Formats.money(txn.amount, txn.currency, signed = true),
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.SemiBold,
            color = amountColor(txn.isExpense),
        )
    }
    HorizontalDivider()
}

@Composable
private fun BudgetRow(b: BudgetStatus, monthProgress: Float, currency: String) {
    val s = strings()
    // Over budget: red. Spending faster than the month is passing: amber.
    val color = when {
        b.isOver -> Error
        b.ratio > monthProgress + 0.1f -> Warning
        else -> categoryColor(b.color, b.categoryName)
    }
    val overBudgetTextColor = amountColor(true)
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(Modifier.fillMaxWidth()) {
            Text(b.categoryName, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(
                s.format(StringKey.SpentOfLimit, Formats.money(b.spent, currency), Formats.money(b.limit, currency)),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (b.isOver) overBudgetTextColor else MaterialTheme.colorScheme.onSurface,
            )
        }
        Spacer(Modifier.height(4.dp))
        LinearProgressIndicator(progress = { b.ratio.coerceIn(0f, 1f) }, color = color, modifier = Modifier.fillMaxWidth())
        Text(
            if (b.isOver) s.format(StringKey.OverBudget, Formats.money(b.spent - b.limit, currency)) else s.format(StringKey.LeftBudget, Formats.money(b.remaining, currency)),
            style = MaterialTheme.typography.bodySmall,
            color = if (b.isOver) overBudgetTextColor else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
