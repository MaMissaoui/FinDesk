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
import de.findesk.ui.components.*
import de.findesk.ui.theme.*
import de.findesk.util.Formats
import de.findesk.viewmodel.ReportsViewModel
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.Month
import java.time.format.TextStyle
import java.util.Locale

@Composable
fun ReportsScreen() {
    val vm = rememberViewModel { ReportsViewModel() }
    val state by vm.state.collectAsState()
    LaunchedEffect(Unit) {
        vm.loadTaxReport()
        vm.loadRecurring()
    }
    var selectedTab by remember { mutableStateOf(0) }
    val report = state.taxReport

    Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Reports & Tax", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            if (state.isExporting) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(12.dp))
            }
            OutlinedButton(
                enabled = !state.isExporting,
                onClick = { FileDialogs.saveFile("Export transactions", "findesk-${fileSuffix(state.customRange, state.selectedYear)}.csv")?.let { vm.exportToCsv(it) } },
            ) {
                Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Export CSV")
            }
            Spacer(Modifier.width(8.dp))
            Button(
                enabled = !state.isExporting,
                onClick = { FileDialogs.saveFile("Export report", "report-${fileSuffix(state.customRange, state.selectedYear)}.pdf")?.let { vm.exportToPdf(it) } },
            ) {
                Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Export PDF")
            }
        }

        Spacer(Modifier.height(16.dp))
        MessageBanner(state.message, state.error, vm::clearMessages)

        var customMode by remember { mutableStateOf(state.customRange != null) }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            FilterChip(selected = !customMode, onClick = {
                customMode = false
                vm.updateYear(state.selectedYear)
            }, label = { Text("Tax year") })
            FilterChip(selected = customMode, onClick = { customMode = true }, label = { Text("Custom range") })
            if (!customMode) {
                IconButton(onClick = { vm.updateYear(state.selectedYear - 1) }) { Icon(Icons.Default.ArrowBack, contentDescription = "Previous year") }
                Text(state.selectedYear.toString(), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                IconButton(onClick = { vm.updateYear(state.selectedYear + 1) }) { Icon(Icons.Default.ArrowForward, contentDescription = "Next year") }
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
            listOf("Tax Summary", "Monthly Breakdown", "Category Analysis", "Recurring").forEachIndexed { i, label ->
                Tab(selected = selectedTab == i, onClick = { selectedTab = i }, text = { Text(label) })
            }
        }
        Spacer(Modifier.height(24.dp))

        if (selectedTab == 3) {
            RecurringTab(state.recurring)
            return@Column
        }
        if (report == null) return@Column
        if (report.transactionCount == 0) {
            EmptyState(Icons.Default.Info, "No transactions in ${report.title}", "Reports are built from the transactions of the selected period.")
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
    LazyColumn(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                StatCard("Total Income", Formats.money(report.totalIncome), Icons.Default.KeyboardArrowUp, Success, Modifier.weight(1f))
                StatCard("Total Expenses", Formats.money(report.totalExpenses), Icons.Default.KeyboardArrowDown, Error, Modifier.weight(1f))
                StatCard("Net Income", Formats.money(report.netIncome), Icons.Default.AccountBox, PrimaryBlue, Modifier.weight(1f))
                StatCard("Tax-Relevant", Formats.money(report.taxRelevantTotal), Icons.Default.Info, Warning, Modifier.weight(1f))
            }
        }
        item {
            SectionCard("Tax-Relevant Expenses by Category", Modifier.fillMaxWidth()) {
                if (report.taxRelevantExpenses.isEmpty()) {
                    Text(
                        "No tax-relevant expenses. Mark categories as tax-relevant in Settings (e.g. Arbeitsmittel, Spenden, Versicherungen).",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                report.taxRelevantExpenses.forEach { (category, amount) -> AmountRow(category, amount) }
            }
        }
        item {
            SectionCard("Quick Statistics", Modifier.fillMaxWidth()) {
                val activeMonths = report.monthlyBreakdown.filter { it.expenses.signum() > 0 }
                val avgMonthly = if (activeMonths.isEmpty()) {
                    BigDecimal.ZERO
                } else {
                    report.totalExpenses.divide(BigDecimal(activeMonths.size), 2, RoundingMode.HALF_UP)
                }
                val highest = report.monthlyBreakdown.maxByOrNull { it.expenses }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    StatItem("Transactions", report.transactionCount.toString())
                    StatItem("Categories Used", report.categoriesUsed.toString())
                    StatItem("Avg Monthly Expenses", Formats.money(avgMonthly))
                    StatItem(
                        "Highest Month",
                        highest?.takeIf { it.expenses.signum() > 0 }?.let { "${monthName(it.month, TextStyle.SHORT)} ${Formats.money(it.expenses)}" } ?: "—",
                    )
                }
            }
        }
    }
}

@Composable
private fun MonthlyBreakdownTab(report: ReportService.TaxReport) {
    SectionCard("Monthly Income & Expenses - ${report.title}", Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth()) {
            listOf("Month", "Income", "Expenses", "Net").forEachIndexed { i, h ->
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
                    Text(monthName(m.month, TextStyle.FULL) + if (report.isCalendarYear) "" else " ${m.year}", modifier = Modifier.weight(1f))
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
    LazyColumn(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            SectionCard("Spending by Category", Modifier.fillMaxWidth()) {
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
            SectionCard("Top Vendors", Modifier.fillMaxWidth()) {
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

private fun monthName(month: Int, style: TextStyle) = Month.of(month).getDisplayName(style, Locale.ENGLISH)

private fun fileSuffix(range: Pair<LocalDate, LocalDate>?, year: Int) = range?.let { "${it.first}_${it.second}" } ?: year.toString()

@Composable
private fun DateRangeInputs(initial: Pair<LocalDate, LocalDate>, onApply: (LocalDate, LocalDate) -> Unit) {
    val fmt = remember { java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy") }
    var fromText by remember { mutableStateOf(initial.first.format(fmt)) }
    var toText by remember { mutableStateOf(initial.second.format(fmt)) }
    fun parse(s: String) = runCatching { LocalDate.parse(s.trim(), fmt) }.getOrNull()
    val from = parse(fromText)
    val to = parse(toText)
    OutlinedTextField(fromText, { fromText = it }, label = { Text("From") }, singleLine = true, isError = from == null, modifier = Modifier.width(150.dp))
    OutlinedTextField(toText, { toText = it }, label = { Text("To") }, singleLine = true, isError = to == null, modifier = Modifier.width(150.dp))
    Button(enabled = from != null && to != null && !from.isAfter(to), onClick = { onApply(from!!, to!!) }) { Text("Apply") }
}

@Composable
private fun RecurringTab(recurring: List<de.findesk.document.RecurringDetector.RecurringPayment>) {
    if (recurring.isEmpty()) {
        EmptyState(Icons.Default.Refresh, "No recurring bookings found", "Detected from the last 13 months once something repeats at least three times (yearly: twice).")
        return
    }
    val payments = recurring.filter { !it.isIncome }
    val yearlyCost = payments.fold(BigDecimal.ZERO) { a, r -> a + r.yearlyAmount.abs() }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                StatCard("Recurring payments", payments.size.toString(), Icons.Default.Refresh, PrimaryBlue, Modifier.weight(1f))
                StatCard("Cost per year", Formats.money(yearlyCost), Icons.Default.KeyboardArrowDown, Error, Modifier.weight(1f))
                StatCard("Per month", Formats.money(yearlyCost.divide(BigDecimal(12), 2, RoundingMode.HALF_UP)), Icons.Default.DateRange, Warning, Modifier.weight(1f))
            }
        }
        item {
            SectionCard("Subscriptions, contracts and regular income", Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth()) {
                    listOf("Name" to 3f, "Every" to 1f, "Next" to 1f, "Amount" to 1f, "Per year" to 1f).forEach { (h, w) ->
                        Text(h, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(w))
                    }
                }
                HorizontalDivider()
                recurring.sortedByDescending { it.yearlyAmount.abs() }.forEach { r ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(3f)) {
                            Text(r.name, fontWeight = FontWeight.Medium)
                            Text(
                                "${r.occurrences}x seen" + (r.categoryName?.let { " · $it" } ?: ""),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(r.cadence.label, modifier = Modifier.weight(1f))
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
