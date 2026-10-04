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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import de.findesk.model.TransactionItem
import de.findesk.ui.components.*
import de.findesk.ui.navigation.NavigationArgs
import de.findesk.ui.navigation.Screen
import de.findesk.ui.theme.*
import de.findesk.util.Formats
import de.findesk.viewmodel.BudgetStatus
import de.findesk.viewmodel.DashboardViewModel

@Composable
fun DashboardScreen(onNavigate: (Screen, NavigationArgs) -> Unit = { _, _ -> }) {
    val vm = rememberViewModel { DashboardViewModel() }
    val state by vm.state.collectAsState()
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
                        title = "Welcome to FinDesk",
                        description = "Add a bank account to start tracking balances, transactions and tax-relevant expenses.",
                    ) {
                        Button(onClick = { onNavigate(Screen.ACCOUNTS, NavigationArgs()) }) { Text("Add account") }
                    }
                }
            }
        }

        item {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                StatCard("Total Balance", Formats.money(state.totalBalance), Icons.Default.AccountBox, PrimaryBlue, Modifier.weight(1f))
                StatCard("Income this month", Formats.money(state.monthlyIncome), Icons.Default.KeyboardArrowUp, Success, Modifier.weight(1f))
                StatCard("Expenses this month", Formats.money(state.monthlyExpenses), Icons.Default.KeyboardArrowDown, Error, Modifier.weight(1f))
                StatCard(
                    "Pending Tasks",
                    state.pendingTasks.toString(),
                    Icons.Default.Warning,
                    Warning,
                    Modifier.weight(1f).clickable(enabled = state.uncategorizedCount > 0) {
                        onNavigate(Screen.TRANSACTIONS, NavigationArgs(uncategorizedOnly = true))
                    },
                    subtitle = "${state.uncategorizedCount} uncategorized · ${state.unlinkedDocumentCount} unlinked docs",
                )
            }
        }

        item {
            Row(modifier = Modifier.fillMaxWidth().height(300.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                SectionCard("Expenses by Category (this month)", Modifier.weight(1f).fillMaxHeight()) {
                    if (state.expensesByCategory.isEmpty()) {
                        Text("No expenses this month", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        DonutChart(state.expensesByCategory)
                    }
                }
                SectionCard("Income vs Expenses (6 months)", Modifier.weight(1f).fillMaxHeight()) {
                    IncomeExpenseBars(state.lastMonths, Modifier.fillMaxSize())
                }
            }
        }

        if (state.upcoming.isNotEmpty()) {
            item {
                SectionCard("Upcoming in the next 30 days", Modifier.fillMaxWidth()) {
                    state.upcoming.forEach { r ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(Formats.date(r.nextDate), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(100.dp))
                            Column(Modifier.weight(1f)) {
                                Text(r.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                                Text(
                                    r.cadence.label + (r.categoryName?.let { " · $it" } ?: "") + if (r.fixedAmount) "" else " · amount varies",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Text(
                                (if (r.fixedAmount) "" else "~") + Formats.money(r.typicalAmount, signed = true),
                                fontWeight = FontWeight.SemiBold,
                                color = if (r.isIncome) Success else Error,
                            )
                        }
                    }
                }
            }
        }

        if (state.budgets.isNotEmpty()) {
            item {
                SectionCard("Budgets this month", Modifier.fillMaxWidth()) {
                    state.budgets.forEach { b -> BudgetRow(b, state.monthProgress) }
                }
            }
        }

        item {
            SectionCard("Tax-Deductible Expenses (year to date)", Modifier.fillMaxWidth()) {
                if (state.taxDeductionsByCategory.isEmpty()) {
                    Text(
                        "Mark categories as tax-relevant in Settings to track deductible expenses.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    DonutChart(state.taxDeductionsByCategory)
                }
            }
        }

        item {
            SectionCard(
                "Recent Transactions",
                Modifier.fillMaxWidth(),
                actions = { TextButton(onClick = { onNavigate(Screen.TRANSACTIONS, NavigationArgs()) }) { Text("View All") } },
            ) {
                if (state.recentTransactions.isEmpty()) {
                    Text("No transactions yet", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                state.recentTransactions.forEach { RecentTransactionRow(it) }
            }
        }
    }
}

@Composable
private fun RecentTransactionRow(txn: TransactionItem) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(txn.description, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, maxLines = 1)
            Text(
                "${Formats.date(txn.date)} • ${txn.categoryName ?: "Uncategorized"}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            Formats.money(txn.amount, txn.currency, signed = true),
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.SemiBold,
            color = if (txn.isExpense) Error else Success,
        )
    }
    HorizontalDivider()
}

@Composable
private fun BudgetRow(b: BudgetStatus, monthProgress: Float) {
    // Over budget: red. Spending faster than the month is passing: amber.
    val color = when {
        b.isOver -> Error
        b.ratio > monthProgress + 0.1f -> Warning
        else -> categoryColor(b.color, b.categoryName)
    }
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(Modifier.fillMaxWidth()) {
            Text(b.categoryName, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(
                "${Formats.money(b.spent)} of ${Formats.money(b.limit)}",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (b.isOver) Error else MaterialTheme.colorScheme.onSurface,
            )
        }
        Spacer(Modifier.height(4.dp))
        LinearProgressIndicator(progress = { b.ratio.coerceIn(0f, 1f) }, color = color, modifier = Modifier.fillMaxWidth())
        Text(
            if (b.isOver) "${Formats.money(b.spent - b.limit)} over budget" else "${Formats.money(b.remaining)} left",
            style = MaterialTheme.typography.bodySmall,
            color = if (b.isOver) Error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
