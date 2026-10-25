package de.findesk.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.findesk.document.ReportService
import de.findesk.document.TaxSuggestion
import de.findesk.i18n.StringKey
import de.findesk.i18n.strings
import de.findesk.model.CategoryShare
import de.findesk.model.TaxSource
import de.findesk.model.TransactionItem
import de.findesk.ui.components.*
import de.findesk.ui.navigation.NavigationArgs
import de.findesk.ui.navigation.Screen
import de.findesk.ui.theme.Error
import de.findesk.ui.theme.PrimaryBlue
import de.findesk.ui.theme.Success
import de.findesk.ui.theme.Warning
import de.findesk.ui.theme.amountColor
import de.findesk.util.BankText
import de.findesk.util.Currencies
import de.findesk.util.Formats
import de.findesk.viewmodel.Period
import de.findesk.viewmodel.ReportsState
import de.findesk.viewmodel.ReportsViewModel
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.Month
import java.time.format.TextStyle

@Composable
fun ReportsScreen(
    args: NavigationArgs = NavigationArgs(),
    onNavigate: (Screen, NavigationArgs) -> Unit = { _, _ -> },
    createViewModel: () -> ReportsViewModel = { ReportsViewModel() },
) {
    val vm = rememberViewModel(createViewModel)
    val state by vm.state.collectAsState()
    val s = strings()
    // A navigation arg opens the Tax bookings tab for its year (the dashboard's tax card); the screen is
    // recreated per navigation, so the effect below runs once per arrival
    var selectedTab by remember { mutableStateOf(if (args.taxYear != null) ReportsTab.TAX_BOOKINGS else ReportsTab.CATEGORY_ANALYSIS) }
    var exportMenu by remember { mutableStateOf(false) }
    val report = state.taxReport
    LaunchedEffect(Unit) {
        val taxYear = args.taxYear
        if (taxYear == null) {
            vm.loadTaxReport()
        } else {
            vm.updateYear(taxYear)
        }
        vm.loadRecurring()
    }

    Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
        var customMode by remember { mutableStateOf(state.customRange != null) }
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            // The top bar names the screen; this row pairs the period controls with the export actions. While the
            // Recurring tab is selected the period controls hide: the tab always covers the last 13 months.
            if (selectedTab != ReportsTab.RECURRING) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    SingleChoiceRow(
                        options = listOf(false, true),
                        selected = customMode,
                        label = { if (it) s[StringKey.CustomRange] else s[StringKey.TaxYear] },
                        onSelect = { custom ->
                            customMode = custom
                            if (!custom) vm.updateYear(state.selectedYear)
                        },
                    )
                    if (!customMode) {
                        IconButton(onClick = { vm.updateYear(state.selectedYear - 1) }) { Icon(Icons.Default.ArrowBack, contentDescription = s[StringKey.PreviousYear]) }
                        Text(state.selectedYear.toString(), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        IconButton(onClick = { vm.updateYear(state.selectedYear + 1) }) { Icon(Icons.Default.ArrowForward, contentDescription = s[StringKey.NextYear]) }
                    } else {
                        DateRangeInputs(
                            initial = state.customRange ?: (LocalDate.now().withDayOfMonth(1).minusMonths(2) to LocalDate.now()),
                            onApply = { from, to -> vm.setCustomRange(from, to) },
                        )
                    }
                    if (state.isLoading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                }
            }
            Spacer(Modifier.weight(1f))
            if (state.isExporting) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(12.dp))
            }
            Box {
                Button(enabled = !state.isExporting, onClick = { exportMenu = true }) {
                    Icon(AppIcons.FileDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(s[StringKey.Export])
                }
                DropdownMenu(expanded = exportMenu, onDismissRequest = { exportMenu = false }) {
                    DropdownMenuItem(
                        text = { Text(s[StringKey.ExportPdf]) },
                        onClick = {
                            exportMenu = false
                            FileDialogs.saveFile(s[StringKey.ExportReport], "report-${fileSuffix(state.customRange, state.selectedYear)}.pdf")?.let { vm.exportToPdf(it) }
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(s[StringKey.ExportCsv]) },
                        onClick = {
                            exportMenu = false
                            FileDialogs.saveFile(s[StringKey.ExportTransactions], "findesk-${fileSuffix(state.customRange, state.selectedYear)}.csv")?.let { vm.exportToCsv(it) }
                        },
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        MessageBanner(state.message, state.error, vm::clearMessages)

        Spacer(Modifier.height(16.dp))
        TabRow(selectedTabIndex = selectedTab.ordinal) {
            listOf(
                ReportsTab.TAX_SUMMARY to StringKey.TabTaxSummary,
                ReportsTab.TAX_BOOKINGS to StringKey.TabTaxBookings,
                ReportsTab.MONTHLY_BREAKDOWN to StringKey.TabMonthlyBreakdown,
                ReportsTab.CATEGORY_ANALYSIS to StringKey.TabCategoryAnalysis,
                ReportsTab.RECURRING to StringKey.TabRecurring,
            ).forEach { (tab, key) ->
                Tab(selected = selectedTab == tab, onClick = { selectedTab = tab }, text = { Text(s[key]) }, unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(24.dp))

        if (selectedTab == ReportsTab.RECURRING) {
            RecurringTab(state.recurring)
            return@Column
        }
        if (report == null) return@Column
        if (report.transactionCount == 0) {
            val hint = if (report.otherCurrencyBookings > 0) s.format(StringKey.OtherCurrenciesExcluded, report.currency, report.otherCurrencyBookings) else s[StringKey.ReportsBuiltFrom]
            EmptyState(AppIcons.BarChart, s.format(StringKey.NoTransactionsIn, report.title), hint)
            return@Column
        }
        when (selectedTab) {
            ReportsTab.TAX_SUMMARY -> TaxSummaryTab(report)

            ReportsTab.TAX_BOOKINGS -> state.taxBookings?.let { TaxBookingsTab(it, state, vm) }

            ReportsTab.MONTHLY_BREAKDOWN -> MonthlyBreakdownTab(report)

            ReportsTab.CATEGORY_ANALYSIS -> CategoryAnalysisTab(report, linkablePeriod(report)) { share ->
                // A report over exactly a Period deep-links into that period's bookings
                when (val period = linkablePeriod(report)) {
                    null -> {}

                    else -> if (share.categoryId == null) {
                        onNavigate(Screen.TRANSACTIONS, NavigationArgs(uncategorizedOnly = true, period = period))
                    } else {
                        onNavigate(Screen.TRANSACTIONS, NavigationArgs(categoryId = share.categoryId, period = period))
                    }
                }
            }

            ReportsTab.RECURRING -> {}
        }
    }
}

/** The Reports tabs; an enum keeps the `when` branches stable when a tab is inserted. */
private enum class ReportsTab { TAX_SUMMARY, TAX_BOOKINGS, MONTHLY_BREAKDOWN, CATEGORY_ANALYSIS, RECURRING }

/**
 * The [Period] the report's category rows can deep-link into the transactions screen with, only when the report
 * covers exactly that period: the current calendar year or the previous one. Any other range links nowhere.
 */
private fun linkablePeriod(report: ReportService.TaxReport): Period? {
    val today = LocalDate.now()
    val range = report.from to report.to
    return when (range) {
        LocalDate.of(today.year, 1, 1) to LocalDate.of(today.year, 12, 31) -> Period.THIS_YEAR
        LocalDate.of(today.year - 1, 1, 1) to LocalDate.of(today.year - 1, 12, 31) -> Period.LAST_YEAR
        else -> null
    }
}

@Composable
private fun TaxSummaryTab(report: ReportService.TaxReport) {
    val s = strings()
    LazyColumn(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (report.otherCurrencyBookings > 0) {
            item {
                Text(
                    s.format(StringKey.OtherCurrenciesExcluded, report.currency, report.otherCurrencyBookings),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                StatCard(s[StringKey.TotalIncome], Formats.money(report.totalIncome, report.currency), Icons.Default.KeyboardArrowUp, Success, Modifier.weight(1f))
                StatCard(s[StringKey.TotalExpenses], Formats.money(report.totalExpenses, report.currency), Icons.Default.KeyboardArrowDown, Error, Modifier.weight(1f))
                StatCard(s[StringKey.NetIncome], Formats.money(report.netIncome, report.currency), AppIcons.BarChart, PrimaryBlue, Modifier.weight(1f))
                StatCard(s[StringKey.TaxRelevantAmount], Formats.money(report.taxRelevantTotal, report.currency), Icons.Default.Info, Warning, Modifier.weight(1f))
            }
        }
        item {
            SectionCard(s[StringKey.TaxRelevantByCategory], Modifier.fillMaxWidth()) {
                if (report.taxRelevantExpenses.isEmpty()) {
                    Text(
                        s[StringKey.NoTaxRelevantExpenses],
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                report.taxRelevantExpenses.forEach { (category, amount) -> AmountRow(category, amount, report.currency) }
            }
        }
        item {
            // Quick statistics as plain stat cards, matching the dashboard's row
            val activeMonths = report.monthlyBreakdown.filter { it.expenses.signum() > 0 }
            val avgMonthly = if (activeMonths.isEmpty()) {
                BigDecimal.ZERO
            } else {
                report.totalExpenses.divide(BigDecimal(activeMonths.size), 2, RoundingMode.HALF_UP)
            }
            val highest = report.monthlyBreakdown.maxByOrNull { it.expenses }
            Row(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                StatCard(s[StringKey.StatTransactions], report.transactionCount.toString(), null, PrimaryBlue, Modifier.weight(1f).fillMaxHeight())
                StatCard(s[StringKey.CategoriesUsed], report.categoriesUsed.toString(), null, PrimaryBlue, Modifier.weight(1f).fillMaxHeight())
                StatCard(s[StringKey.AvgMonthlyExpenses], Formats.money(avgMonthly, report.currency), null, PrimaryBlue, Modifier.weight(1f).fillMaxHeight())
                StatCard(
                    s[StringKey.HighestMonth],
                    highest?.takeIf { it.expenses.signum() > 0 }?.let { "${monthName(it.month, TextStyle.SHORT, s)} ${Formats.money(it.expenses, report.currency)}" } ?: "—",
                    null,
                    PrimaryBlue,
                    Modifier.weight(1f).fillMaxHeight(),
                )
            }
        }
    }
}

@Composable
private fun MonthlyBreakdownTab(report: ReportService.TaxReport) {
    val s = strings()
    SectionCard(s.format(StringKey.MonthlyIncomeExpenses, report.title), Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth()) {
            listOf(s[StringKey.ColMonth], s[StringKey.ColIncome], s[StringKey.ColExpenses], s[StringKey.ColNet]).forEachIndexed { i, h ->
                Text(
                    h,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                    textAlign = if (i == 0) TextAlign.Start else TextAlign.End,
                )
            }
        }
        HorizontalDivider()
        LazyColumn {
            itemsIndexed(report.monthlyBreakdown) { _, m ->
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                    Text(monthName(m.month, TextStyle.FULL, s) + if (report.isCalendarYear) "" else " ${m.year}", modifier = Modifier.weight(1f))
                    Text(Formats.money(m.income, report.currency), color = amountColor(false), modifier = Modifier.weight(1f), textAlign = TextAlign.End)
                    Text(Formats.money(m.expenses, report.currency), color = amountColor(true), modifier = Modifier.weight(1f), textAlign = TextAlign.End)
                    Text(
                        Formats.money(m.net, report.currency, signed = true),
                        fontWeight = FontWeight.SemiBold,
                        color = amountColor(m.net.signum() < 0),
                        modifier = Modifier.weight(1f),
                        textAlign = TextAlign.End,
                    )
                }
                HorizontalDivider()
            }
            item {
                // The whole period's totals, bold like a table footer
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                    Text(s[StringKey.ReportTotal], fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    Text(Formats.money(report.totalIncome, report.currency), color = amountColor(false), fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), textAlign = TextAlign.End)
                    Text(Formats.money(report.totalExpenses, report.currency), color = amountColor(true), fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), textAlign = TextAlign.End)
                    Text(
                        Formats.money(report.netIncome, report.currency, signed = true),
                        fontWeight = FontWeight.Bold,
                        color = amountColor(report.netIncome.signum() < 0),
                        modifier = Modifier.weight(1f),
                        textAlign = TextAlign.End,
                    )
                }
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun CategoryAnalysisTab(report: ReportService.TaxReport, linkablePeriod: Period?, onSelect: ((CategoryShare) -> Unit)) {
    val s = strings()
    LazyColumn(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            SectionCard(s[StringKey.SpendingByCategory], Modifier.fillMaxWidth()) {
                CategorySplit(
                    slices = report.categoryShares,
                    currency = report.currency,
                    excludedTransfers = report.transferTotal,
                    onSelect = if (linkablePeriod != null) onSelect else null,
                    maxRows = 12,
                )
            }
        }
        item {
            SectionCard(s[StringKey.TopVendors], Modifier.fillMaxWidth()) {
                report.topVendors.forEachIndexed { index, (vendor, amount) ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("${index + 1}.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(28.dp))
                        Text(BankText.displayPartner(vendor) ?: vendor, modifier = Modifier.weight(1f))
                        Text(Formats.money(amount, report.currency), fontWeight = FontWeight.SemiBold)
                    }
                    if (index < report.topVendors.size - 1) HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun AmountRow(label: String, amount: BigDecimal, currency: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(Formats.money(amount, currency), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
    }
}

private fun monthName(month: Int, style: TextStyle, s: de.findesk.i18n.Strings): String = Month.of(month).getDisplayName(style, s.language.locale)

private fun fileSuffix(range: Pair<LocalDate, LocalDate>?, year: Int) = range?.let { "${it.first}_${it.second}" } ?: year.toString()

@Composable
private fun DateRangeInputs(initial: Pair<LocalDate, LocalDate>, onApply: (LocalDate, LocalDate) -> Unit) {
    val fmt = remember { java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy") }
    var fromText by remember { mutableStateOf(initial.first.format(fmt)) }
    var toText by remember { mutableStateOf(initial.second.format(fmt)) }
    fun parse(s: String) = runCatching { LocalDate.parse(s.trim(), fmt) }.getOrNull()
    val from = parse(fromText)
    val to = parse(toText)
    val s = strings()
    OutlinedTextField(
        fromText,
        { fromText = it },
        label = { Text(s[StringKey.FromDate]) },
        singleLine = true,
        isError = from == null,
        supportingText = { if (from == null) Text(s[StringKey.EnterValidDate]) },
        modifier = Modifier.width(150.dp),
    )
    OutlinedTextField(
        toText,
        { toText = it },
        label = { Text(s[StringKey.ToDate]) },
        singleLine = true,
        isError = to == null || (from != null && to != null && from.isAfter(to)),
        supportingText = {
            if (to == null) {
                Text(s[StringKey.EnterValidDate])
            } else if (from != null && to != null && from.isAfter(to)) {
                Text(s[StringKey.InvalidDateRange])
            }
        },
        modifier = Modifier.width(150.dp),
    )
    Button(enabled = from != null && to != null && !from.isAfter(to), onClick = { onApply(from!!, to!!) }) { Text(s[StringKey.Apply]) }
}

@Composable
private fun RecurringTab(recurring: List<de.findesk.document.RecurringDetector.RecurringPayment>) {
    if (recurring.isEmpty()) {
        EmptyState(Icons.Default.Refresh, strings()[StringKey.NoRecurringFound], strings()[StringKey.RecurringHint])
        return
    }
    val payments = recurring.filter { !it.isIncome }
    val yearlyCost = recurringCost(recurring)
    val s = strings()
    LazyColumn(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                StatCard(s[StringKey.RecurringPayments], payments.size.toString(), Icons.Default.Refresh, PrimaryBlue, Modifier.weight(1f))
                StatCard(s[StringKey.CostPerYear], Currencies.format(yearlyCost, Currencies.DEFAULT), Icons.Default.KeyboardArrowDown, Error, Modifier.weight(1f))
                StatCard(s[StringKey.PerMonth], Currencies.format(yearlyCost.mapValues { it.value.divide(BigDecimal(12), 2, RoundingMode.HALF_UP) }, Currencies.DEFAULT), Icons.Default.DateRange, Warning, Modifier.weight(1f))
            }
        }
        item {
            SectionCard(s[StringKey.SubscriptionsTitle], Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth()) {
                    listOf(s[StringKey.ColName] to 3f, s[StringKey.ColEvery] to 1f, s[StringKey.ColNext] to 1f, s[StringKey.ColAmount] to 1f, s[StringKey.ColPerYear] to 1f).forEach { (h, w) ->
                        Text(h, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(w))
                    }
                }
                HorizontalDivider()
                recurring.sortedByDescending { it.yearlyAmount.abs() }.forEach { r ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(3f)) {
                            Text(r.name, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                s.format(StringKey.TimesSeen, r.occurrences) + (r.categoryName?.let { " · $it" } ?: ""),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(s.cadence(r.cadence), modifier = Modifier.weight(1f))
                        Text(Formats.date(r.nextDate), modifier = Modifier.weight(1f))
                        Text((if (r.fixedAmount) "" else "≈ ") + Formats.money(r.typicalAmount, r.currency, signed = true), color = amountColor(!r.isIncome), modifier = Modifier.weight(1f))
                        Text(Formats.money(r.yearlyAmount.abs(), r.currency), fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}

/**
 * The recurring payments' cost per year for the tab's cards, per currency: no income and no own-account transfers
 * (the rows still list them).
 */
internal fun recurringCost(payments: List<de.findesk.document.RecurringDetector.RecurringPayment>): Map<String, BigDecimal> = Currencies.totals(payments.filter { !it.isIncome && !it.isTransfer }, Currencies.DEFAULT, { it.currency }, { it.yearlyAmount.abs() })

/** Imports count as money in, cards never for tax: the tax view splits persistence by the category's net instead. */
@Composable
private fun TaxBookingsTab(info: ReportService.TaxBookings, state: ReportsState, vm: ReportsViewModel) {
    val s = strings()
    LazyColumn(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            // The tab's actions, right-aligned above its cards; disabled while exporting or without bookings
            val enabled = !state.isExporting && info.bookingCount > 0
            val suffix = fileSuffix(state.customRange, state.selectedYear)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                if (state.isExporting) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                }
                OutlinedButton(
                    enabled = enabled,
                    onClick = { FileDialogs.saveFile(s[StringKey.ExportExcel], "tax-bookings-$suffix.xlsx")?.let(vm::exportTaxXlsx) },
                ) {
                    Text(s[StringKey.ExportExcel])
                }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(
                    enabled = enabled,
                    onClick = { FileDialogs.saveFile(s[StringKey.ExportPdf], "tax-bookings-$suffix.pdf")?.let(vm::exportTaxPdf) },
                ) {
                    Text(s[StringKey.ExportPdf])
                }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(enabled = enabled, onClick = { vm.printTax() }) {
                    Text(s[StringKey.Print])
                }
            }
        }
        item {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                StatCard(s[StringKey.TaxIncomeTotal], Formats.money(info.incomeTotal, info.currency), Icons.Default.KeyboardArrowUp, Success, Modifier.weight(1f))
                StatCard(s[StringKey.TaxDeductionTotal], Formats.money(info.deductionTotal, info.currency), Icons.Default.KeyboardArrowDown, Error, Modifier.weight(1f))
                StatCard(s[StringKey.NetIncome], Formats.money(info.netTotal, info.currency), AppIcons.BarChart, PrimaryBlue, Modifier.weight(1f))
                StatCard(s[StringKey.TaxReceipts], s.format(StringKey.TaxReceiptsOf, info.withReceipt, info.bookingCount), Icons.Default.Info, Warning, Modifier.weight(1f))
            }
        }
        if (info.otherCurrencyBookings > 0) {
            item { TaxOtherCurrenciesHint(info) }
        }
        if (state.suggestions.isNotEmpty()) {
            item { TaxSuggestionsCard(state.suggestions, vm) }
        }
        item {
            Row(
                modifier = Modifier.fillMaxWidth().clickable { vm.setMissingReceiptsOnly(!state.missingReceiptsOnly) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = state.missingReceiptsOnly, onCheckedChange = { vm.setMissingReceiptsOnly(it) })
                Text(s[StringKey.MissingReceiptsOnly])
            }
        }
        if (info.bookingCount == 0) {
            item {
                EmptyState(AppIcons.Description, s.format(StringKey.NoTaxBookingsIn, info.title), s[StringKey.TaxBookingsHowTo])
            }
        }
        // Missing-receipts filter removes only rows, never a group's share of the totals (they stay whole-period)
        val rowVisible: (TransactionItem) -> Boolean = { !state.missingReceiptsOnly || it.documentCount == 0 }
        if (info.incomeGroups.any { it.bookings.any(rowVisible) }) {
            item { TaxSectionHeader(s[StringKey.TaxSectionIncome]) }
        }
        info.incomeGroups.forEach { group ->
            if (group.bookings.any(rowVisible)) {
                item { TaxGroupCard(group, group.bookings.filter(rowVisible), info.currency, vm) }
            }
        }
        if (info.deductionGroups.any { it.bookings.any(rowVisible) }) {
            item { TaxSectionHeader(s[StringKey.TaxSectionDeductions]) }
        }
        info.deductionGroups.forEach { group ->
            if (group.bookings.any(rowVisible)) {
                item { TaxGroupCard(group, group.bookings.filter(rowVisible), info.currency, vm) }
            }
        }
    }
}

/** The "[Bookings] in another currency are not included" hint, styled like the Tax summary's one. */
@Composable
private fun TaxOtherCurrenciesHint(info: ReportService.TaxBookings) {
    Text(
        strings().format(StringKey.OtherCurrenciesExcluded, info.currency, info.otherCurrencyBookings),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** A bold, muted section label above the groups of one net sign. */
@Composable
private fun TaxSectionHeader(title: String) {
    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
}

@Composable
private fun TaxGroupCard(group: ReportService.TaxGroup, rows: List<TransactionItem>, currency: String, vm: ReportsViewModel) {
    FinDeskCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(12.dp).background(categoryColor(group.color, group.name), CircleShape))
                Spacer(Modifier.width(12.dp))
                Text(group.name, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Text(rows.size.toString() + " · " + Formats.money(group.net, currency, signed = true), style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(Modifier.height(8.dp))
            rows.forEachIndexed { index, txn ->
                TaxBookingRow(txn, currency, vm)
                if (index < rows.lastIndex) HorizontalDivider()
            }
        }
    }
}

/** One tax booking: date, partner-text display, account, amount, receipt state and the deciding tax source. */
@Composable
private fun TaxBookingRow(txn: TransactionItem, currency: String, vm: ReportsViewModel) {
    val s = strings()
    RowContextMenu(
        menuItem(s[StringKey.MarkNotTaxRelevant]) { vm.markNotTaxRelevant(txn.id) },
        // Only a stored override can be reset; rule- and category-decided rows have nothing to reset
        if (txn.taxOverride != null) menuItem(s[StringKey.ResetTaxAutomatic]) { vm.resetTaxAutomatic(txn.id) } else null,
    ) {
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(Formats.date(txn.date), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(88.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        BankText.displayPartner(txn.description) ?: txn.description,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        "…" + txn.accountIban.takeLast(4),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (txn.taxSource != TaxSource.CATEGORY) {
                        Text(
                            taxStateLine(txn, s),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                    } else {
                        // A category-derived reason repeats the group header, so only the receipt state stays
                        Spacer(Modifier.weight(1f))
                    }
                    if (txn.documentCount > 0) {
                        Icon(AppIcons.AttachFile, contentDescription = null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
                    } else {
                        Text(
                            s[StringKey.NoReceipt],
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                }
            }
            Text(
                Formats.money(txn.amount, currency, signed = true),
                color = amountColor(txn.amount.signum() < 0),
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.End,
                modifier = Modifier.width(110.dp),
            )
        }
    }
}

/** The keyword suggestions above the booking groups; accepting or dismissing stores a manual override. */
@Composable
private fun TaxSuggestionsCard(suggestions: List<TaxSuggestion>, vm: ReportsViewModel) {
    val s = strings()
    var showAll by remember { mutableStateOf(false) }
    SectionCard(s.format(StringKey.TaxSuggestionsTitle, suggestions.size), Modifier.fillMaxWidth()) {
        Text(
            s[StringKey.TaxSuggestionsHint],
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        val shown = if (showAll) suggestions else suggestions.take(20)
        shown.forEachIndexed { index, suggestion ->
            TaxSuggestionRow(suggestion, vm)
            if (index < shown.lastIndex) HorizontalDivider()
        }
        if (!showAll && suggestions.size > 20) {
            TextButton(onClick = { showAll = true }) { Text(s.format(StringKey.ShowAllCount, suggestions.size)) }
        }
    }
}

/** One suggestion: date, partner-text display, the matched keyword as a chip, amount and the two actions. */
@Composable
private fun TaxSuggestionRow(suggestion: TaxSuggestion, vm: ReportsViewModel) {
    val s = strings()
    val txn = suggestion.booking
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(Formats.date(txn.date), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(88.dp))
        Column(Modifier.weight(1f)) {
            Text(
                BankText.displayPartner(txn.description) ?: txn.description,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                taxStateLine(txn, s),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        KeywordChip(suggestion.keyword)
        Text(
            Formats.money(txn.amount, txn.currency, signed = true),
            color = amountColor(txn.amount.signum() < 0),
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.End,
            modifier = Modifier.width(110.dp),
        )
        TextButton(onClick = { vm.acceptSuggestion(txn.id) }) { Text(s[StringKey.Accept]) }
        TextButton(onClick = { vm.dismissSuggestion(txn.id) }) { Text(s[StringKey.DismissSuggestion]) }
    }
}

/** The matched keyword as a small badge. */
@Composable
private fun KeywordChip(keyword: String) {
    Surface(shape = RoundedCornerShape(4.dp), color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.padding(horizontal = 8.dp)) {
        Text(
            keyword,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}
