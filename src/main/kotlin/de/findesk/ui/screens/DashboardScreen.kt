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
import androidx.compose.ui.unit.dp
import de.findesk.i18n.StringKey
import de.findesk.i18n.strings
import de.findesk.model.CategoryShare
import de.findesk.ui.components.*
import de.findesk.ui.navigation.NavigationArgs
import de.findesk.ui.navigation.Screen
import de.findesk.ui.navigation.SettingsSection
import de.findesk.ui.theme.figure
import de.findesk.ui.theme.rowAmount
import de.findesk.util.BankText
import de.findesk.util.Formats
import de.findesk.viewmodel.BudgetStatus
import de.findesk.viewmodel.ChartSlice
import de.findesk.viewmodel.DashboardViewModel
import de.findesk.viewmodel.Period
import java.math.BigDecimal

@Composable
fun DashboardScreen(
    args: NavigationArgs = NavigationArgs(),
    onNavigate: (Screen, NavigationArgs) -> Unit = { _, _ -> },
    createViewModel: () -> DashboardViewModel = { DashboardViewModel() },
) {
    val vm = rememberViewModel(createViewModel)
    val state by vm.state.collectAsState()
    val s = strings()
    // Arriving via a link (e.g. Back) restores the month the dashboard was left on; otherwise load the default
    LaunchedEffect(Unit) {
        if (args.month != null) vm.selectMonth(args.month) else vm.loadDashboard()
    }

    // Every link out of the dashboard: Back reopens it on the month it left, unless that was the current month
    val leaveWithBackArgs: (NavigationArgs) -> NavigationArgs = { args ->
        args.copy(backArgs = state.selectedMonth.takeUnless { state.isCurrentMonth }?.let { NavigationArgs(month = it) })
    }

    // A category row opens the selected month's bookings of that category; uncategorized without a category filter.
    // The current month keeps the plain period (as before); a past month passes the exact month.
    // The pending-tasks card uses this too (with a null category), so it opens the bookings its count counts.
    val openTransactionsCategory: (Int?) -> Unit = { categoryId ->
        val month = if (state.isCurrentMonth) null else state.selectedMonth
        onNavigate(
            Screen.TRANSACTIONS,
            leaveWithBackArgs(
                NavigationArgs(
                    uncategorizedOnly = categoryId == null,
                    categoryId = categoryId,
                    period = if (month == null) Period.THIS_MONTH else null,
                    month = month,
                ),
            ),
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
                    onAddBank = { onNavigate(Screen.ACCOUNTS, leaveWithBackArgs(NavigationArgs())) },
                    onOpenSettings = { onNavigate(Screen.SETTINGS, leaveWithBackArgs(NavigationArgs(settingsSection = SettingsSection.CATEGORIES))) },
                )
            }
            // First run: the setup card is the whole dashboard, so no empty totals or charts suggest false data
            return@LazyColumn
        }

        // Nothing has loaded yet: never show zeros or another month's figures under a month's title
        val loadedMonth = state.loadedMonth
        if (loadedMonth == null) {
            if (state.error == null) {
                item {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
            }
            return@LazyColumn
        }
        // These titles describe the loaded figures, not the month the user just clicked while they still load
        val isCurrentLoadedMonth = loadedMonth == state.currentMonth

        // Hidden with the other cards on the first-run dashboard (its block above returns early)
        if (state.hasAccounts) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    PeriodNavigator(
                        label = s.month(state.selectedMonth),
                        // Every month from the current one back to the oldest booking, newest first
                        options = generateSequence(state.currentMonth) { it.minusMonths(1) }
                            .takeWhile { it >= state.earliestMonth }
                            .toList(),
                        optionLabel = { s.month(it) },
                        onSelect = { vm.selectMonth(it) },
                        onPrevious = { vm.previousMonth() },
                        onNext = { vm.nextMonth() },
                        previousEnabled = state.selectedMonth > state.earliestMonth,
                        nextEnabled = !state.isCurrentMonth,
                        previousContentDescription = s[StringKey.PreviousMonth],
                        nextContentDescription = s[StringKey.NextMonth],
                    )
                    // A past month offers the way back to today's month, as the old month header did
                    if (!state.isCurrentMonth) {
                        AppTextButton(onClick = { vm.currentMonth() }) { Text(s[StringKey.CurrentMonth]) }
                    }
                }
            }
        }
        // The figures below are still the loaded month's until the new load publishes
        if (state.isLoading) {
            item {
                LinearProgressIndicator(Modifier.fillMaxWidth().height(4.dp))
            }
        }

        item {
            MonthStatementCard(
                state = state,
                loadedMonth = loadedMonth,
                // The uncategorized line keeps opening the bookings its count counts
                onOpenTasks = { openTransactionsCategory(null) },
                onOpenDocuments = { onNavigate(Screen.DOCUMENTS, leaveWithBackArgs(NavigationArgs())) },
            )
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.Top,
            ) {
                SectionCard(
                    if (isCurrentLoadedMonth) s[StringKey.ExpensesByCategory] else s.format(StringKey.ExpensesByCategoryInMonth, s.month(loadedMonth)),
                    Modifier.weight(3f),
                ) {
                    if (state.expensesByCategory.isEmpty()) {
                        Text(
                            if (isCurrentLoadedMonth) s[StringKey.NoExpensesThisMonth] else s.format(StringKey.NoExpensesInMonth, s.month(loadedMonth)),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        CategorySplit(
                            slices = state.expensesByCategory.map { CategoryShare(it.categoryId, it.label, it.amount, it.color ?: "") },
                            currency = state.currency,
                            excludedTransfers = state.transfersThisMonth,
                            onSelect = { share -> openTransactionsCategory(share.categoryId) },
                        )
                    }
                }
                SectionCard(s[StringKey.IncomeVsExpenses], Modifier.weight(2f)) {
                    if (state.lastMonths.all { it.income.signum() == 0 && it.expenses.signum() == 0 }) {
                        Text(
                            if (isCurrentLoadedMonth) {
                                s[StringKey.NoBookingsSixMonths]
                            } else {
                                s.format(StringKey.NoBookingsSixMonthsTo, s.month(loadedMonth))
                            },
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        // The chart keeps its own minimum height, so its legend never spills outside the card
                        IncomeExpenseBars(state.lastMonths, s[StringKey.IncomeVsExpenses], Modifier.fillMaxWidth().height(260.dp), currency = state.currency)
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
                    SectionCard(
                        if (isCurrentLoadedMonth) s[StringKey.BudgetsThisMonth] else s.format(StringKey.BudgetsInMonth, s.month(loadedMonth)),
                        Modifier.weight(1f).fillMaxHeight(),
                    ) {
                        state.budgets.forEach { b -> BudgetRow(b, state.monthProgress, state.currency) }
                    }
                }
                // The whole card opens the Tax bookings tab for the selected month's year
                SectionCard(
                    s[StringKey.TaxDeductibleYtd],
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clickable(
                            onClickLabel = s[StringKey.OpenTaxBookings],
                            role = Role.Button,
                        ) {
                            onNavigate(Screen.REPORTS, leaveWithBackArgs(NavigationArgs(taxYear = loadedMonth.year)))
                        },
                ) {
                    if (state.taxDeductionsByCategory.isEmpty()) {
                        Text(
                            s[StringKey.TaxDeductibleHint],
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        val shares = taxCategoryShares(state.taxDeductionsByCategory)
                        Text(
                            Formats.money(shares.fold(BigDecimal.ZERO) { acc, share -> acc + share.amount }, state.currency),
                            style = MaterialTheme.typography.figure,
                        )
                        Spacer(Modifier.height(8.dp))
                        CategorySplit(
                            slices = shares,
                            currency = state.currency,
                            excludedTransfers = BigDecimal.ZERO,
                            onSelect = null,
                        )
                    }
                }
            }
        }

        item {
            SectionCard(
                s[StringKey.RecentTransactions],
                Modifier.fillMaxWidth(),
                actions = { AppTextButton(onClick = { onNavigate(Screen.TRANSACTIONS, leaveWithBackArgs(NavigationArgs())) }) { Text(s[StringKey.ViewAll]) } },
            ) {
                if (state.recentTransactions.isEmpty()) {
                    Text(s[StringKey.NoTransactionsYet], color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                state.recentTransactions.forEach { txn ->
                    RowContextMenu(
                        txn.partnerName?.let { partner ->
                            menuItem(s.format(StringKey.ShowAllFromPartner, partner)) {
                                onNavigate(Screen.TRANSACTIONS, leaveWithBackArgs(NavigationArgs(partner = partner)))
                            }
                        },
                    ) {
                        LedgerRow(
                            date = Formats.date(txn.date),
                            title = txn.displayTitle,
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
 * The tax card's slices in the shape [CategorySplit] takes: each category keeps its own name and colour, and an
 * uncategorized slice keeps its null id so the split shows the shared neutral label.
 */
internal fun taxCategoryShares(slices: List<ChartSlice>): List<CategoryShare> = slices.map { CategoryShare(it.categoryId, it.label, it.amount, it.color ?: "") }

/**
 * The amount column for one upcoming payment. Varying amounts carry no "~" prefix: their meta line already says
 * "amount varies", and a squiggle only ever referred to the median shown.
 */
internal fun upcomingAmount(payment: de.findesk.document.RecurringDetector.RecurringPayment): String = Formats.money(payment.typicalAmount, payment.currency, signed = true)

@Composable
internal fun GettingStartedCard(onAddBank: () -> Unit, onOpenSettings: () -> Unit, modifier: Modifier = Modifier) {
    val s = strings()
    FinDeskCard(modifier = modifier.widthIn(max = 640.dp).fillMaxWidth()) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            // The card title other Dashboard cards use (SectionCard), not a page title
            Text(s[StringKey.WelcomeTitle], style = MaterialTheme.typography.titleMedium)
            Text(
                s[StringKey.GettingStartedIntro],
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            GettingStartedStep(1, s[StringKey.StepAddBank], s[StringKey.StepAddBankHint]) {
                AppButton(onClick = onAddBank) { Text(s[StringKey.AddBank]) }
            }
            GettingStartedStep(2, s[StringKey.StepSync], s[StringKey.StepSyncHint])
            GettingStartedStep(3, s[StringKey.StepCategorize], s[StringKey.StepCategorizeHint]) {
                // No inner padding, so the label lines up with the step text above it
                AppTextButton(onClick = onOpenSettings, contentPadding = PaddingValues(horizontal = 0.dp)) { Text(s[StringKey.OpenSettings]) }
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
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.labelLarge)
            Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (action != null) action()
        }
    }
}

/** The pending tasks as their own stat card; the dashboard's statement now carries the same list inline. */
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
        if (done) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.tertiary,
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
    // Over budget or spending faster than the month is passing: caution, not the error red reserved for real problems.
    val color = when {
        b.isOver -> MaterialTheme.colorScheme.tertiary
        b.ratio > monthProgress + 0.1f -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.primary
    }
    val overBudgetTextColor = MaterialTheme.colorScheme.tertiary
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(Modifier.fillMaxWidth()) {
            Text(b.categoryName, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            Text(
                s.format(StringKey.SpentOfLimit, Formats.money(b.spent, currency), Formats.money(b.limit, currency)),
                style = MaterialTheme.typography.rowAmount,
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
