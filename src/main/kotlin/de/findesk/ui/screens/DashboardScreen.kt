package de.findesk.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import de.findesk.i18n.StringKey
import de.findesk.i18n.strings
import de.findesk.model.CategoryShare
import de.findesk.ui.components.*
import de.findesk.ui.navigation.NavigationArgs
import de.findesk.ui.navigation.Screen
import de.findesk.ui.navigation.SettingsSection
import de.findesk.ui.theme.Error
import de.findesk.ui.theme.PrimaryBlue
import de.findesk.ui.theme.Success
import de.findesk.ui.theme.Warning
import de.findesk.ui.theme.amountColor
import de.findesk.util.BankText
import de.findesk.util.Currencies
import de.findesk.util.Formats
import de.findesk.viewmodel.BudgetStatus
import de.findesk.viewmodel.DashboardViewModel
import de.findesk.viewmodel.Period

@Composable
fun DashboardScreen(onNavigate: (Screen, NavigationArgs) -> Unit = { _, _ -> }) {
    val vm = rememberViewModel { DashboardViewModel() }
    val state by vm.state.collectAsState()
    val s = strings()
    LaunchedEffect(Unit) { vm.loadDashboard() }

    // A category row opens this month's bookings of that category; uncategorized without a category filter
    val openTransactionsCategory: (Int?) -> Unit = { categoryId ->
        onNavigate(
            Screen.TRANSACTIONS,
            if (categoryId == null) {
                NavigationArgs(uncategorizedOnly = true, period = Period.THIS_MONTH)
            } else {
                NavigationArgs(categoryId = categoryId, period = Period.THIS_MONTH)
            },
        )
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        // Not keyed: a keyed list keeps its first visible item in place when one is inserted above it, so the welcome
        // card, added once loading ends, opened scrolled out of view above the stat cards
        if (state.error != null) {
            item { MessageBanner(null, state.error) { vm.loadDashboard() } }
        }

        if (!state.isLoading && !state.hasAccounts) {
            item {
                GettingStartedCard(
                    onAddBank = { onNavigate(Screen.ACCOUNTS, NavigationArgs()) },
                    onOpenSettings = { onNavigate(Screen.SETTINGS, NavigationArgs(settingsSection = SettingsSection.CATEGORIES)) },
                )
            }
            // First run: the setup card is the whole dashboard, so no empty totals or charts suggest false data
            return@LazyColumn
        }

        item {
            // Equal heights even when a subtitle wraps in a narrow window
            Row(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                // Loans are money owed: the first card shows what is available, and squeezes the loans in as its subtitle
                StatCard(
                    s[StringKey.AvailableBalance],
                    Formats.money(state.availableBalance, state.currency),
                    AppIcons.AccountBalance,
                    PrimaryBlue,
                    Modifier.weight(1f).fillMaxHeight(),
                    // Loans and other currencies can both apply; neither line hides the other
                    subtitle = listOfNotNull(
                        state.hasLoans.takeIf { it }?.let {
                            s.format(
                                StringKey.LoansSubtitle,
                                Formats.money(state.loanBalance, state.currency),
                                Formats.money(state.totalBalance, state.currency),
                            )
                        },
                        state.otherBalances.takeIf { it.isNotEmpty() }?.let { s.format(StringKey.PlusOtherCurrencies, Currencies.format(it, state.currency)) },
                    ).joinToString("\n").ifEmpty { null },
                )
                StatCard(s[StringKey.IncomeThisMonth], Formats.money(state.monthlyIncome, state.currency), AppIcons.ArrowDownward, Success, Modifier.weight(1f).fillMaxHeight())
                StatCard(s[StringKey.ExpensesThisMonth], Formats.money(state.monthlyExpenses, state.currency), AppIcons.ArrowUpward, Error, Modifier.weight(1f).fillMaxHeight())
                PendingTasksCard(
                    pendingTasks = state.pendingTasks,
                    uncategorizedCount = state.uncategorizedCount,
                    unlinkedDocumentCount = state.unlinkedDocumentCount,
                    onOpenTasks = { onNavigate(Screen.TRANSACTIONS, NavigationArgs(uncategorizedOnly = true)) },
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                )
            }
        }

        item {
            // Tall enough for every split row (up to 8, the "more" row and the transfers line): the chart beside it
            // measures with BoxWithConstraints, so the row can't take its height from the content's intrinsics
            val splitRows = minOf(state.expensesByCategory.size, 8) +
                (if (state.expensesByCategory.size > 8) 1 else 0) +
                (if (state.transfersThisMonth.signum() > 0) 1 else 0)
            Row(modifier = Modifier.fillMaxWidth().height(maxOf(300.dp, 104.dp + 34.dp * splitRows)), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                SectionCard(s[StringKey.ExpensesByCategory], Modifier.weight(1f).fillMaxHeight()) {
                    if (state.expensesByCategory.isEmpty()) {
                        Text(s[StringKey.NoExpensesThisMonth], color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        CategorySplit(
                            slices = state.expensesByCategory.map { CategoryShare(it.categoryId, it.label, it.amount, it.color ?: "") },
                            currency = state.currency,
                            excludedTransfers = state.transfersThisMonth,
                            onSelect = { share -> openTransactionsCategory(share.categoryId) },
                        )
                    }
                }
                SectionCard(s[StringKey.IncomeVsExpenses], Modifier.weight(1f).fillMaxHeight()) {
                    if (state.lastMonths.all { it.income.signum() == 0 && it.expenses.signum() == 0 }) {
                        Text(s[StringKey.NoBookingsSixMonths], color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        IncomeExpenseBars(state.lastMonths, s[StringKey.IncomeVsExpenses], Modifier.fillMaxSize(), currency = state.currency)
                    }
                }
            }
        }

        if (state.upcoming.isNotEmpty()) {
            item {
                SectionCard(s[StringKey.Upcoming30Days], Modifier.fillMaxWidth()) {
                    state.upcoming.forEach { r ->
                        LedgerRow(
                            date = Formats.date(r.nextDate),
                            title = BankText.displayPartner(r.name) ?: r.name,
                            meta = s.cadence(r.cadence) + (r.categoryName?.let { " · $it" } ?: "") + if (r.fixedAmount) "" else " · ${s[StringKey.AmountVaries]}",
                            amount = upcomingAmount(r),
                            isExpense = !r.isIncome,
                        )
                    }
                }
            }
        }

        item {
            // Budgets (when any) and tax-deductible expenses side by side and equal height, like the stat row
            Row(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                if (state.budgets.isNotEmpty()) {
                    SectionCard(s[StringKey.BudgetsThisMonth], Modifier.weight(1f).fillMaxHeight()) {
                        state.budgets.forEach { b -> BudgetRow(b, state.monthProgress, state.currency) }
                    }
                }
                SectionCard(s[StringKey.TaxDeductibleYtd], Modifier.weight(1f).fillMaxHeight()) {
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
                state.recentTransactions.forEach { txn ->
                    RowContextMenu(
                        txn.partnerName?.let { partner ->
                            menuItem(s.format(StringKey.ShowAllFromPartner, partner)) { onNavigate(Screen.TRANSACTIONS, NavigationArgs(partner = partner)) }
                        },
                    ) {
                        LedgerRow(
                            date = Formats.date(txn.date),
                            title = BankText.displayDescription(txn),
                            meta = txn.categoryName ?: s[StringKey.Uncategorized],
                            amount = Formats.money(txn.amount, txn.currency, signed = true),
                            isExpense = txn.isExpense,
                        )
                    }
                }
            }
        }
    }
}

/**
 * The amount column for one upcoming payment. Varying amounts carry no "~" prefix: their meta line already says
 * "amount varies", and a squiggle only ever referred to the median shown.
 */
internal fun upcomingAmount(payment: de.findesk.document.RecurringDetector.RecurringPayment): String = Formats.money(payment.typicalAmount, payment.currency, signed = true)

@Composable
internal fun GettingStartedCard(onAddBank: () -> Unit, onOpenSettings: () -> Unit, modifier: Modifier = Modifier) {
    val s = strings()
    Card(modifier = modifier.widthIn(max = 640.dp).fillMaxWidth()) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(s[StringKey.WelcomeTitle], style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text(
                s[StringKey.GettingStartedIntro],
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            GettingStartedStep(1, s[StringKey.StepAddBank], s[StringKey.StepAddBankHint]) {
                Button(onClick = onAddBank) { Text(s[StringKey.AddBank]) }
            }
            GettingStartedStep(2, s[StringKey.StepSync], s[StringKey.StepSyncHint])
            GettingStartedStep(3, s[StringKey.StepCategorize], s[StringKey.StepCategorizeHint]) {
                // No inner padding, so the label lines up with the step text above it
                TextButton(onClick = onOpenSettings, contentPadding = PaddingValues(horizontal = 0.dp)) { Text(s[StringKey.OpenSettings]) }
            }
        }
    }
}

/** One numbered step; the number is a real sequence, so the circle carries 1, 2, 3 rather than an icon. */
@Composable
private fun GettingStartedStep(number: Int, title: String, description: String, action: (@Composable () -> Unit)? = null) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(
            modifier = Modifier.size(28.dp).background(MaterialTheme.colorScheme.secondaryContainer, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                number.toString(),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (action != null) action()
        }
    }
}

@Composable
internal fun PendingTasksCard(
    pendingTasks: Int,
    uncategorizedCount: Int,
    unlinkedDocumentCount: Int,
    onOpenTasks: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val s = strings()
    val done = pendingTasks == 0
    StatCard(
        s[StringKey.PendingTasks],
        pendingTasks.toString(),
        if (done) Icons.Default.CheckCircle else Icons.Default.Warning,
        if (done) amountColor(false) else Warning,
        modifier.clickable(
            enabled = uncategorizedCount > 0,
            onClick = onOpenTasks,
            onClickLabel = s[StringKey.ViewPendingTasks],
            role = Role.Button,
        ),
        subtitle = if (done) s[StringKey.NothingPending] else s.format(StringKey.PendingSubtitle, uncategorizedCount, unlinkedDocumentCount),
    )
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
        LinearProgressIndicator(
            progress = { b.ratio.coerceIn(0f, 1f) },
            color = color,
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
            strokeCap = StrokeCap.Round,
            // One continuous bar: no gap between fill and track, no dot at its end
            gapSize = 0.dp,
            drawStopIndicator = {},
            modifier = Modifier.fillMaxWidth().height(6.dp),
        )
        Text(
            if (b.isOver) s.format(StringKey.OverBudget, Formats.money(b.spent - b.limit, currency)) else s.format(StringKey.LeftBudget, Formats.money(b.remaining, currency)),
            style = MaterialTheme.typography.bodySmall,
            color = if (b.isOver) overBudgetTextColor else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
