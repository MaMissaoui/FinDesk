package de.findesk.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import de.findesk.document.ReportService
import de.findesk.i18n.StringKey
import de.findesk.i18n.strings
import de.findesk.ui.components.*
import de.findesk.ui.theme.*
import de.findesk.util.Formats
import de.findesk.viewmodel.ReportsViewModel
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.Month
import java.time.format.TextStyle

@Composable
fun ReportsScreen() {
    val vm = rememberViewModel { ReportsViewModel() }
    val state by vm.state.collectAsState()
    val s = strings()
    LaunchedEffect(Unit) {
        vm.loadTaxReport()
        vm.loadRecurring()
    }
    var selectedTab by remember { mutableStateOf(0) }
    val report = state.taxReport

    Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(s[StringKey.ReportsAndTax], style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            if (state.isExporting) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(12.dp))
            }
            OutlinedButton(
                enabled = !state.isExporting,
                onClick = { FileDialogs.saveFile(s[StringKey.ExportTransactions], "findesk-${fileSuffix(state.customRange, state.selectedYear)}.csv")?.let { vm.exportToCsv(it) } },
            ) {
                Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(s[StringKey.ExportCsv])
            }
            Spacer(Modifier.width(8.dp))
            Button(
                enabled = !state.isExporting,
                onClick = { FileDialogs.saveFile(s[StringKey.ExportReport], "report-${fileSuffix(state.customRange, state.selectedYear)}.pdf")?.let { vm.exportToPdf(it) } },
            ) {
                Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(8.dp))
                Spacer(Modifier.width(8.dp))
                Text(s[StringKey.ExportPdf])
            }
        }

        Spacer(Modifier.height(16.dp))
        MessageBanner(state.message, state.error, vm::clearMessages)

        var customMode by remember { mutableStateOf(state.customRange != null) }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            FilterChip(selected = !customMode, onClick = {
                customMode = false
                vm.updateYear(state.selectedYear)
            }, label = { Text(s[StringKey.TaxYear]) })
            FilterChip(selected = customMode, onClick = { customMode = true }, label = { Text(s[StringKey.CustomRange]) })
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

        Spacer(Modifier.height(16.dp))
        TabRow(selectedTabIndex = selectedTab) {
            listOf(StringKey.TabTaxSummary, StringKey.TabMonthlyBreakdown, StringKey.TabCategoryAnalysis, StringKey.TabRecurring).forEachIndexed { i, key ->
                Tab(selected = selectedTab == i, onClick = { selectedTab = i }, text = { Text(s[key]) })
            }
        }
        Spacer(Modifier.height(24.dp))

        if (selectedTab == 3) {
            RecurringTab(state.recurring)
            return@Column
        }
        if (report == null) return@Column
        if (report.transactionCount == 0) {
            EmptyState(Icons.Default.Info, s.format(StringKey.NoTransactionsIn, report.title), s[StringKey.ReportsBuiltFrom])
            return@Column
        }
        when (selectedTab) {
            0 -> TaxSummaryTab(report)
            1 -> MonthlyBreakdownTab(report)
            2 -> CategoryAnalysisTab(report)
        }
    }
}

@Composable
private fun TaxSummaryTab(report: ReportService.TaxReport) {
    val s = strings()
    LazyColumn(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                StatCard(s[StringKey.TotalIncome], Formats.money(report.totalIncome), Icons.Default.KeyboardArrowUp, Success, Modifier.weight(1f))
                StatCard(s[StringKey.TotalExpenses], Formats.money(report.totalExpenses), Icons.Default.KeyboardArrowDown, Error, Modifier.weight(1f))
                StatCard(s[StringKey.NetIncome], Formats.money(report.netIncome), Icons.Default.AccountBox, PrimaryBlue, Modifier.weight(1f))
                StatCard(s[StringKey.TaxRelevantAmount], Formats.money(report.taxRelevantTotal), Icons.Default.Info, Warning, Modifier.weight(1f))
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
                report.taxRelevantExpenses.forEach { (category, amount) -> AmountRow(category, amount) }
            }
        }
        item {
            SectionCard(s[StringKey.QuickStatistics], Modifier.fillMaxWidth()) {
                val activeMonths = report.monthlyBreakdown.filter { it.expenses.signum() > 0 }
                val avgMonthly = if (activeMonths.isEmpty()) {
                    BigDecimal.ZERO
                } else {
                    report.totalExpenses.divide(BigDecimal(activeMonths.size), 2, RoundingMode.HALF_UP)
                }
                val highest = report.monthlyBreakdown.maxByOrNull { it.expenses }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    StatItem(s[StringKey.StatTransactions], report.transactionCount.toString())
                    StatItem(s[StringKey.CategoriesUsed], report.categoriesUsed.toString())
                    StatItem(s[StringKey.AvgMonthlyExpenses], Formats.money(avgMonthly))
                    StatItem(
                        s[StringKey.HighestMonth],
                        highest?.takeIf { it.expenses.signum() > 0 }?.let { "${monthName(it.month, TextStyle.SHORT, s)} ${Formats.money(it.expenses)}" } ?: "—",
                    )
                }
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
                    Text(Formats.money(m.income), color = Success, modifier = Modifier.weight(1f), textAlign = TextAlign.End)
                    Text(Formats.money(m.expenses), color = Error, modifier = Modifier.weight(1f), textAlign = TextAlign.End)
                    Text(
                        Formats.money(m.net, signed = true),
                        fontWeight = FontWeight.SemiBold,
                        color = if (m.net.signum() < 0) Error else Success,
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
private fun CategoryAnalysisTab(report: ReportService.TaxReport) {
    val s = strings()
    LazyColumn(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            SectionCard(s[StringKey.SpendingByCategory], Modifier.fillMaxWidth()) {
                val total = report.totalExpenses.takeIf { it.signum() > 0 } ?: BigDecimal.ONE
                report.expensesByCategory.entries.forEachIndexed { i, (category, amount) ->
                    val share = amount.toFloat() / total.toFloat()
                    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                        AmountRow(category, amount)
                        LinearProgressIndicator(
                            progress = { share },
                            modifier = Modifier.fillMaxWidth(),
                            color = CategoryColors[i % CategoryColors.size],
                        )
                        Text("%.1f%%".format(share * 100), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        item {
            SectionCard(s[StringKey.TopVendors], Modifier.fillMaxWidth()) {
                report.topVendors.forEachIndexed { index, (vendor, amount) ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("${index + 1}.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(28.dp))
                        Text(vendor, modifier = Modifier.weight(1f))
                        Text(Formats.money(amount), fontWeight = FontWeight.SemiBold)
                    }
                    if (index < report.topVendors.size - 1) HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun AmountRow(label: String, amount: BigDecimal) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(Formats.money(amount), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun StatItem(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
    OutlinedTextField(fromText, { fromText = it }, label = { Text(s[StringKey.FromDate]) }, singleLine = true, isError = from == null, modifier = Modifier.width(150.dp))
    OutlinedTextField(toText, { toText = it }, label = { Text(s[StringKey.ToDate]) }, singleLine = true, isError = to == null, modifier = Modifier.width(150.dp))
    Button(enabled = from != null && to != null && !from.isAfter(to), onClick = { onApply(from!!, to!!) }) { Text(s[StringKey.Apply]) }
}

@Composable
private fun RecurringTab(recurring: List<de.findesk.document.RecurringDetector.RecurringPayment>) {
    if (recurring.isEmpty()) {
        EmptyState(Icons.Default.Refresh, strings()[StringKey.NoRecurringFound], strings()[StringKey.RecurringHint])
        return
    }
    val payments = recurring.filter { !it.isIncome }
    val yearlyCost = payments.fold(BigDecimal.ZERO) { a, r -> a + r.yearlyAmount.abs() }
    val s = strings()
    LazyColumn(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                StatCard(s[StringKey.RecurringPayments], payments.size.toString(), Icons.Default.Refresh, PrimaryBlue, Modifier.weight(1f))
                StatCard(s[StringKey.CostPerYear], Formats.money(yearlyCost), Icons.Default.KeyboardArrowDown, Error, Modifier.weight(1f))
                StatCard(s[StringKey.PerMonth], Formats.money(yearlyCost.divide(BigDecimal(12), 2, RoundingMode.HALF_UP)), Icons.Default.DateRange, Warning, Modifier.weight(1f))
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
                            Text(r.name, fontWeight = FontWeight.Medium)
                            Text(
                                s.format(StringKey.TimesSeen, r.occurrences) + (r.categoryName?.let { " · $it" } ?: ""),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(s.cadence(r.cadence), modifier = Modifier.weight(1f))
                        Text(Formats.date(r.nextDate), modifier = Modifier.weight(1f))
                        Text((if (r.fixedAmount) "" else "~") + Formats.money(r.typicalAmount, signed = true), color = if (r.isIncome) Success else Error, modifier = Modifier.weight(1f))
                        Text(Formats.money(r.yearlyAmount.abs()), fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}
