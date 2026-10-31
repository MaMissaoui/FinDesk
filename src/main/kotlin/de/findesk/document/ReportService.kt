package de.findesk.document

import de.findesk.model.CategoryShare
import de.findesk.model.Direction
import de.findesk.model.TransactionFilter
import de.findesk.model.TransactionItem
import de.findesk.repository.*
import de.findesk.util.Currencies
import de.findesk.util.PaymentProviders
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate

/**
 * Service for generating tax reports and financial analytics
 */
class ReportService(
    private val transactionRepository: TransactionRepository = TransactionRepositoryImpl(),
    private val categoryRepository: CategoryRepository = CategoryRepositoryImpl(),
    private val pdfExporter: PdfReportExporter = PdfReportExporter(),
    private val settingsRepository: SettingsRepository = SettingsRepositoryImpl(),
) {
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    companion object {
        const val UNCATEGORIZED = "Uncategorized"
        private const val UTF8_BOM = 0xFEFF
    }

    /**
     * Generate annual tax report
     */
    suspend fun generateTaxReport(year: Int): TaxReport = withContext(Dispatchers.IO) {
        _isLoading.value = true
        try {
            val transactions = transactionRepository.findItems(
                TransactionFilter(startDate = LocalDate.of(year, 1, 1), endDate = LocalDate.of(year, 12, 31)),
            )
            buildTaxReport(year, transactions, Currencies.primary(settingsRepository))
        } finally {
            _isLoading.value = false
        }
    }

    /** Same report for any inclusive date range (months may span years). */
    suspend fun generateRangeReport(from: LocalDate, to: LocalDate): TaxReport = withContext(Dispatchers.IO) {
        require(!from.isAfter(to)) { de.findesk.i18n.AppStrings[de.findesk.i18n.StringKey.InvalidDateRange] }
        _isLoading.value = true
        try {
            buildReport(from, to, transactionRepository.findItems(TransactionFilter(startDate = from, endDate = to)), Currencies.primary(settingsRepository))
        } finally {
            _isLoading.value = false
        }
    }

    internal fun buildTaxReport(year: Int, transactions: List<TransactionItem>, currency: String = Currencies.DEFAULT): TaxReport = buildReport(LocalDate.of(year, 1, 1), LocalDate.of(year, 12, 31), transactions, currency)

    /** The Tax bookings view over [from]..[to], with every amount in the primary currency. */
    suspend fun generateTaxBookings(from: LocalDate, to: LocalDate): TaxBookings = withContext(Dispatchers.IO) {
        require(!from.isAfter(to)) { de.findesk.i18n.AppStrings[de.findesk.i18n.StringKey.InvalidDateRange] }
        _isLoading.value = true
        try {
            buildTaxBookings(
                from,
                to,
                transactionRepository.findItems(TransactionFilter(startDate = from, endDate = to)),
                Currencies.primary(settingsRepository),
            )
        } finally {
            _isLoading.value = false
        }
    }

    /** Totals cover bookings in [currency] only; the others are counted so the report can say they're missing. */
    internal fun buildReport(from: LocalDate, to: LocalDate, all: List<TransactionItem>, currency: String = Currencies.DEFAULT): TaxReport {
        val (transactions, otherCurrency) = all.partition { it.currency == currency }
        val income = transactions.filter { it.countsAsIncome }
        val expenses = transactions.filter { it.countsAsSpending }

        val totalIncome = income.sumOf { it.amount }
        val totalExpenses = expenses.sumOf { it.amount.abs() }

        val taxRelevant = transactions.filter { it.isExpense && it.isTaxRelevant }

        // One tax total: the summary's figures use the Tax bookings tab's definition — each deduction category at
        // its absolute net, so a refund lowers its category and money out on a net-income category is no deduction
        val taxDeductions = buildTaxBookings(from, to, all, currency).deductionGroups.associate { it.name to it.net.abs() }

        val months = generateSequence(java.time.YearMonth.from(from)) { it.plusMonths(1) }.takeWhile { !it.isAfter(java.time.YearMonth.from(to)) }.toList()
        val monthlyBreakdown = months.map { ym ->
            val monthTxns = transactions.filter { it.date.monthValue == ym.monthValue && it.date.year == ym.year }
            MonthlyData(
                year = ym.year,
                month = ym.monthValue,
                income = monthTxns.filter { it.countsAsIncome }.sumOf { it.amount },
                expenses = monthTxns.filter { it.countsAsSpending }.sumOf { it.amount.abs() },
            )
        }

        return TaxReport(
            year = from.year,
            from = from,
            to = to,
            totalIncome = totalIncome,
            totalExpenses = totalExpenses,
            netIncome = totalIncome - totalExpenses,
            expensesByCategory = sumByCategory(expenses),
            taxRelevantExpenses = taxDeductions,
            taxRelevantTransactions = taxRelevant,
            monthlyBreakdown = monthlyBreakdown,
            topVendors = topVendors(expenses),
            transactionCount = transactions.size,
            categoriesUsed = transactions.mapNotNull { it.categoryId }.distinct().size,
            currency = currency,
            otherCurrencyBookings = otherCurrency.size,
            transferTotal = transactions
                .filter { it.isTransfer && it.amount.signum() < 0 }
                .sumOf { it.amount.abs() },
            categoryShares = categoryShares(expenses),
        )
    }

    /**
     * The pure Tax bookings view: effectively tax-relevant bookings of [currency], grouped by category. A group's
     * net decides its section (net ≥ 0 income, net < 0 deduction — a refund in a deduction category lowers that
     * category instead of showing up as income); within each section groups sort by absolute net, then name.
     */
    internal fun buildTaxBookings(from: LocalDate, to: LocalDate, all: List<TransactionItem>, currency: String = Currencies.DEFAULT): TaxBookings {
        val relevant = all.filter { it.isTaxRelevant }
        val (inCurrency, otherCurrency) = relevant.partition { it.currency == currency }
        val groups = inCurrency
            .groupBy { it.categoryId }
            .map { (categoryId, txns) ->
                TaxGroup(
                    categoryId = categoryId,
                    name = txns.first().categoryName ?: de.findesk.i18n.AppStrings[de.findesk.i18n.StringKey.Uncategorized],
                    color = txns.first().categoryColor ?: "",
                    bookings = txns.sortedWith(compareBy({ it.date }, { it.id })),
                    income = txns.filter { it.amount.signum() > 0 }.sumOf { it.amount },
                    expenses = txns.filter { it.amount.signum() < 0 }.sumOf { it.amount.abs() },
                )
            }
        return TaxBookings(
            from = from,
            to = to,
            currency = currency,
            incomeGroups = groups.filter { it.net.signum() >= 0 }.sortedWith(compareByDescending<TaxGroup> { it.net.abs() }.thenBy { it.name }),
            deductionGroups = groups.filter { it.net.signum() < 0 }.sortedWith(compareByDescending<TaxGroup> { it.net.abs() }.thenBy { it.name }),
            bookingCount = inCurrency.size,
            otherCurrencyBookings = otherCurrency.size,
        )
    }

    /** One share per category with the money it accounts for, largest first; null category id = uncategorized. */
    private fun categoryShares(spending: List<TransactionItem>): List<CategoryShare> = spending
        .groupBy { it.categoryId }
        .map { (categoryId, txns) ->
            CategoryShare(
                categoryId = categoryId,
                name = txns.first().categoryName ?: UNCATEGORIZED,
                amount = txns.sumOf { it.amount.abs() },
                color = txns.first().categoryColor ?: "",
            )
        }
        .sortedByDescending { it.amount }

    /**
     * Generate monthly spending report
     */
    suspend fun generateMonthlyReport(year: Int, month: Int): MonthlyReport = withContext(Dispatchers.IO) {
        val startDate = LocalDate.of(year, month, 1)
        val endDate = startDate.plusMonths(1).minusDays(1)
        val transactions = transactionRepository.findItems(TransactionFilter(startDate = startDate, endDate = endDate, currency = Currencies.primary(settingsRepository)))

        val income = transactions.filter { it.countsAsIncome }.sumOf { it.amount }
        val expenseTxns = transactions.filter { it.countsAsSpending }
        val expenses = expenseTxns.sumOf { it.amount.abs() }

        val topExpenses = expenseTxns
            .sortedBy { it.amount }
            .take(10)
            .map {
                ExpenseItem(
                    description = it.description,
                    amount = it.amount.abs(),
                    category = it.categoryName ?: UNCATEGORIZED,
                    date = it.date,
                )
            }

        MonthlyReport(
            year = year,
            month = month,
            totalIncome = income,
            totalExpenses = expenses,
            netAmount = income - expenses,
            byCategory = sumByCategory(expenseTxns).toSortedMap(),
            topExpenses = topExpenses,
            transactionCount = transactions.size,
        )
    }

    /**
     * Generate category spending trends over the last [months] months (including the current one)
     */
    suspend fun getCategoryTrends(
        categoryId: Int,
        months: Int = 12,
        today: LocalDate = LocalDate.now(),
    ): CategoryTrend = withContext(Dispatchers.IO) {
        val category = categoryRepository.findAllItems().firstOrNull { it.id == categoryId }
        val firstMonth = today.withDayOfMonth(1).minusMonths((months - 1).toLong())
        val transactions = transactionRepository.findItems(
            TransactionFilter(startDate = firstMonth, endDate = today, categoryId = categoryId, currency = Currencies.primary(settingsRepository)),
        )

        val monthlyData = (0 until months).map { i ->
            val month = firstMonth.plusMonths(i.toLong())
            MonthlyAmount(
                year = month.year,
                month = month.monthValue,
                amount = transactions
                    .filter { it.date.year == month.year && it.date.monthValue == month.monthValue }
                    .sumOf { it.amount.abs() },
            )
        }

        val amounts = monthlyData.map { it.amount }
        val average = if (amounts.isNotEmpty()) {
            amounts.fold(BigDecimal.ZERO, BigDecimal::add).divide(BigDecimal(amounts.size), 2, RoundingMode.HALF_UP)
        } else {
            BigDecimal.ZERO
        }

        val latest = amounts.lastOrNull() ?: BigDecimal.ZERO
        val trend = if (average.signum() > 0) {
            (latest - average).multiply(BigDecimal(100)).divide(average, 1, RoundingMode.HALF_UP)
        } else {
            BigDecimal.ZERO
        }

        CategoryTrend(
            categoryId = categoryId,
            categoryName = category?.name ?: de.findesk.i18n.AppStrings[de.findesk.i18n.StringKey.ReportUnknownCategory],
            monthlyData = monthlyData,
            averageMonthly = average,
            trend = trend,
        )
    }

    /**
     * Export the tax report to a PDF file
     */
    suspend fun exportToPdf(report: TaxReport, outputFile: File): Boolean = withContext(Dispatchers.IO) {
        try {
            pdfExporter.export(report, outputFile)
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Export transactions to CSV (RFC 4180 quoting, UTF-8 with BOM so Excel detects the encoding). [balances] adds a
     * trailing Balance column when the transactions list shows a running balance; the Reports export leaves it out.
     */
    suspend fun exportToCsv(transactions: List<TransactionItem>, outputFile: File, balances: Map<Int, BigDecimal>? = null): Boolean = withContext(Dispatchers.IO) {
        try {
            outputFile.bufferedWriter(Charsets.UTF_8).use { writer ->
                writer.write(UTF8_BOM)
                val header = listOf(
                    de.findesk.i18n.AppStrings[de.findesk.i18n.StringKey.ColDate],
                    de.findesk.i18n.AppStrings[de.findesk.i18n.StringKey.ColPartner],
                    de.findesk.i18n.AppStrings[de.findesk.i18n.StringKey.ColPurpose],
                    de.findesk.i18n.AppStrings[de.findesk.i18n.StringKey.ColCategory],
                    de.findesk.i18n.AppStrings[de.findesk.i18n.StringKey.ColAmount],
                    de.findesk.i18n.AppStrings[de.findesk.i18n.StringKey.ColCurrency],
                    de.findesk.i18n.AppStrings[de.findesk.i18n.StringKey.ColAccount],
                ) + if (balances != null) listOf(de.findesk.i18n.AppStrings[de.findesk.i18n.StringKey.ColBalance]) else emptyList()
                writer.write(CsvFormat.row(*header.toTypedArray()))
                transactions.forEach { txn ->
                    val fields = listOf(
                        txn.date.toString(),
                        CsvFormat.text(txn.partnerName.orEmpty()),
                        CsvFormat.text(txn.purpose.orEmpty()),
                        CsvFormat.text(txn.categoryName.orEmpty()),
                        txn.amount.toPlainString(),
                        CsvFormat.text(txn.currency),
                        CsvFormat.text(txn.accountIban.orEmpty()),
                    ) + if (balances != null) listOf(balances[txn.id]?.toPlainString().orEmpty()) else emptyList()
                    writer.write(CsvFormat.row(*fields.toTypedArray()))
                }
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    /** Convenience for exporting a whole year from the reports screen. */
    suspend fun exportYearToCsv(year: Int, outputFile: File): Boolean = exportRangeToCsv(LocalDate.of(year, 1, 1), LocalDate.of(year, 12, 31), outputFile)

    suspend fun exportRangeToCsv(from: LocalDate, to: LocalDate, outputFile: File): Boolean {
        val transactions = withContext(Dispatchers.IO) {
            transactionRepository.findItems(TransactionFilter(startDate = from, endDate = to, direction = Direction.ALL))
        }
        return exportToCsv(transactions, outputFile)
    }

    private fun sumByCategory(transactions: List<TransactionItem>): Map<String, BigDecimal> = transactions
        .groupBy { it.categoryName ?: UNCATEGORIZED }
        .mapValues { (_, txns) -> txns.sumOf { it.amount.abs() } }
        .entries
        .sortedByDescending { it.value }
        .associate { it.key to it.value }

    private fun topVendors(expenses: List<TransactionItem>, limit: Int = 5): List<Pair<String, BigDecimal>> = expenses
        .groupBy {
            // A PayPal purchase is titled like its row: "PayPal · <shop>" so the purchases don't collapse into
            // one PayPal entry; everything else keeps its description
            PaymentProviders.titleOf(it.purpose, it.partnerName) ?: it.description
        }
        .map { (name, txns) -> name to txns.sumOf { it.amount.abs() } }
        .sortedByDescending { it.second }
        .take(limit)

    // Data classes for reports
    data class TaxReport(
        val year: Int,
        val from: LocalDate = LocalDate.of(year, 1, 1),
        val to: LocalDate = LocalDate.of(year, 12, 31),
        val totalIncome: BigDecimal,
        val totalExpenses: BigDecimal,
        val netIncome: BigDecimal,
        val expensesByCategory: Map<String, BigDecimal>,

        /** The Tax bookings tab's deduction groups: each category name at its absolute net, refunds included. */
        val taxRelevantExpenses: Map<String, BigDecimal>,
        val taxRelevantTransactions: List<TransactionItem> = emptyList(),
        val monthlyBreakdown: List<MonthlyData>,
        val topVendors: List<Pair<String, BigDecimal>> = emptyList(),
        val transactionCount: Int,
        val categoriesUsed: Int = 0,
        /** Currency of every amount in the report. */
        val currency: String = Currencies.DEFAULT,
        /** Bookings in the period that are in another currency and therefore not included. */
        val otherCurrencyBookings: Int = 0,
        /** Money moved between the user's own accounts in the period: excluded from every total above. */
        val transferTotal: BigDecimal = BigDecimal.ZERO,
        /** The spending split behind [expensesByCategory], per category with its colour (uncategorized = null id). */
        val categoryShares: List<CategoryShare> = emptyList(),
    ) {
        val taxRelevantTotal: BigDecimal get() = taxRelevantExpenses.values.fold(BigDecimal.ZERO, BigDecimal::add)

        /** True for a full calendar year. */
        val isCalendarYear: Boolean get() = from == LocalDate.of(year, 1, 1) && to == LocalDate.of(year, 12, 31)

        val title: String get() = if (isCalendarYear) "$year" else "${de.findesk.util.Formats.date(from)} – ${de.findesk.util.Formats.date(to)}"

        companion object {
            fun empty(year: Int) = TaxReport(
                year = year,
                totalIncome = BigDecimal.ZERO,
                totalExpenses = BigDecimal.ZERO,
                netIncome = BigDecimal.ZERO,
                expensesByCategory = emptyMap(),
                taxRelevantExpenses = emptyMap(),
                monthlyBreakdown = emptyList(),
                transactionCount = 0,
            )
        }
    }

    data class MonthlyReport(
        val year: Int,
        val month: Int,
        val totalIncome: BigDecimal,
        val totalExpenses: BigDecimal,
        val netAmount: BigDecimal,
        val byCategory: Map<String, BigDecimal>,
        val topExpenses: List<ExpenseItem>,
        val transactionCount: Int,
    )

    data class MonthlyData(
        val month: Int,
        val income: BigDecimal,
        val expenses: BigDecimal,
        val year: Int = 0,
    ) {
        val net: BigDecimal get() = income - expenses
    }

    data class ExpenseItem(
        val description: String,
        val amount: BigDecimal,
        val category: String,
        val date: LocalDate,
    )

    data class CategoryTrend(
        val categoryId: Int,
        val categoryName: String,
        val monthlyData: List<MonthlyAmount>,
        val averageMonthly: BigDecimal,
        val trend: BigDecimal, // Percentage change
    )

    data class MonthlyAmount(
        val year: Int,
        val month: Int,
        val amount: BigDecimal,
    )

    /** One category's tax bookings of a period, split into money in ([income]) and money out ([expenses]). */
    data class TaxGroup(
        /** The category's id, or null for the uncategorized group. */
        val categoryId: Int?,
        val name: String,
        val color: String,
        /** The group's bookings, date ascending, then id. */
        val bookings: List<TransactionItem>,
        val income: BigDecimal,
        val expenses: BigDecimal,
    ) {
        val net: BigDecimal get() = income - expenses

        /** How many of the group's bookings carry at least one linked receipt. */
        val withReceipt: Int get() = bookings.count { it.documentCount > 0 }
    }

    /**
     * The Tax bookings view over a period: groups split by net into income and deductions, with the totals of the
     * whole period. Bookings in another currency are counted ([otherCurrencyBookings]), never summed.
     */
    data class TaxBookings(
        val from: LocalDate,
        val to: LocalDate,
        val currency: String,
        val incomeGroups: List<TaxGroup> = emptyList(),
        val deductionGroups: List<TaxGroup> = emptyList(),
        val bookingCount: Int,
        /** Tax-relevant bookings of the period in another currency; not included in any total. */
        val otherCurrencyBookings: Int = 0,
    ) {
        /** The sum of the income groups' nets. */
        val incomeTotal: BigDecimal get() = incomeGroups.fold(BigDecimal.ZERO) { acc, group -> acc.add(group.net) }

        /** The sum of the deduction groups' absolute nets. */
        val deductionTotal: BigDecimal get() = deductionGroups.fold(BigDecimal.ZERO) { acc, group -> acc.add(group.net.abs()) }

        /** Bookings with a receipt, across both sections. */
        val withReceipt: Int get() = (incomeGroups + deductionGroups).sumOf { it.withReceipt }

        val netTotal: BigDecimal get() = incomeTotal.subtract(deductionTotal)

        /** "2026" for a full calendar year, otherwise the range like [TaxReport.title]. */
        val title: String
            get() = if (from == LocalDate.of(from.year, 1, 1) && to == LocalDate.of(from.year, 12, 31)) {
                from.year.toString()
            } else {
                "${de.findesk.util.Formats.date(from)} – ${de.findesk.util.Formats.date(to)}"
            }
    }
}

internal object CsvFormat {
    /** Neutralises spreadsheet formula injection (=, +, -, @) in free-text fields from bank data. */
    fun text(field: String): String = if (field.isNotEmpty() && field[0] in "=+-@\t\r") "'$field" else field

    fun row(vararg fields: String): String = fields.joinToString(",") { escape(it) } + "\r\n"

    fun escape(field: String): String = if (field.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
        "\"" + field.replace("\"", "\"\"") + "\""
    } else {
        field
    }
}
