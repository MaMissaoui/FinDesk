package de.findesk.document

import de.findesk.model.Direction
import de.findesk.model.TransactionFilter
import de.findesk.model.TransactionItem
import de.findesk.repository.*
import de.findesk.util.Currencies
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
        require(!from.isAfter(to)) { "Start date is after end date" }
        _isLoading.value = true
        try {
            buildReport(from, to, transactionRepository.findItems(TransactionFilter(startDate = from, endDate = to)), Currencies.primary(settingsRepository))
        } finally {
            _isLoading.value = false
        }
    }

    internal fun buildTaxReport(year: Int, transactions: List<TransactionItem>, currency: String = Currencies.DEFAULT): TaxReport = buildReport(LocalDate.of(year, 1, 1), LocalDate.of(year, 12, 31), transactions, currency)

    /** Totals cover bookings in [currency] only; the others are counted so the report can say they're missing. */
    internal fun buildReport(from: LocalDate, to: LocalDate, all: List<TransactionItem>, currency: String = Currencies.DEFAULT): TaxReport {
        val (transactions, otherCurrency) = all.partition { it.currency == currency }
        val income = transactions.filter { it.amount.signum() > 0 }
        val expenses = transactions.filter { it.isExpense }

        val totalIncome = income.sumOf { it.amount }
        val totalExpenses = expenses.sumOf { it.amount.abs() }

        val taxRelevant = expenses.filter { it.isTaxRelevant }

        val months = generateSequence(java.time.YearMonth.from(from)) { it.plusMonths(1) }.takeWhile { !it.isAfter(java.time.YearMonth.from(to)) }.toList()
        val monthlyBreakdown = months.map { ym ->
            val monthTxns = transactions.filter { it.date.monthValue == ym.monthValue && it.date.year == ym.year }
            MonthlyData(
                year = ym.year,
                month = ym.monthValue,
                income = monthTxns.filter { it.amount.signum() > 0 }.sumOf { it.amount },
                expenses = monthTxns.filter { it.isExpense }.sumOf { it.amount.abs() },
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
            taxRelevantExpenses = sumByCategory(taxRelevant),
            taxRelevantTransactions = taxRelevant,
            monthlyBreakdown = monthlyBreakdown,
            topVendors = topVendors(expenses),
            transactionCount = transactions.size,
            categoriesUsed = transactions.mapNotNull { it.categoryId }.distinct().size,
            currency = currency,
            otherCurrencyBookings = otherCurrency.size,
        )
    }

    /**
     * Generate monthly spending report
     */
    suspend fun generateMonthlyReport(year: Int, month: Int): MonthlyReport = withContext(Dispatchers.IO) {
        val startDate = LocalDate.of(year, month, 1)
        val endDate = startDate.plusMonths(1).minusDays(1)
        val transactions = transactionRepository.findItems(TransactionFilter(startDate = startDate, endDate = endDate, currency = Currencies.primary(settingsRepository)))

        val income = transactions.filter { it.amount.signum() > 0 }.sumOf { it.amount }
        val expenseTxns = transactions.filter { it.isExpense }
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
            categoryName = category?.name ?: "Unknown",
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
     * Export transactions to CSV (RFC 4180 quoting, UTF-8 with BOM so Excel detects the encoding)
     */
    suspend fun exportToCsv(transactions: List<TransactionItem>, outputFile: File): Boolean = withContext(Dispatchers.IO) {
        try {
            outputFile.bufferedWriter(Charsets.UTF_8).use { writer ->
                writer.write(UTF8_BOM)
                writer.write(CsvFormat.row("Date", "Partner", "Purpose", "Category", "Amount", "Currency", "Account"))
                transactions.forEach { txn ->
                    writer.write(
                        CsvFormat.row(
                            txn.date.toString(),
                            CsvFormat.text(txn.partnerName.orEmpty()),
                            CsvFormat.text(txn.purpose.orEmpty()),
                            CsvFormat.text(txn.categoryName.orEmpty()),
                            txn.amount.toPlainString(),
                            txn.currency,
                            txn.accountIban,
                        ),
                    )
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
        .groupBy { it.description }
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
