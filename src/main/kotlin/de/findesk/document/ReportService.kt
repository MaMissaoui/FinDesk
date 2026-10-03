package de.findesk.document

import de.findesk.data.entity.Category
import de.findesk.data.entity.Transaction
import de.findesk.repository.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.YearMonth

/**
 * Service for generating tax reports and financial analytics
 */
class ReportService(
    private val transactionRepository: TransactionRepository = TransactionRepositoryImpl(),
    private val categoryRepository: CategoryRepository = CategoryRepositoryImpl(),
    private val accountRepository: AccountRepository = AccountRepositoryImpl()
) {
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    /**
     * Generate annual tax report
     */
    suspend fun generateTaxReport(year: Int): TaxReport = withContext(Dispatchers.IO) {
        _isLoading.value = true

        try {
            val startDate = LocalDate.of(year, 1, 1)
            val endDate = LocalDate.of(year, 12, 31)
            val transactions = transactionRepository.findByDateRange(startDate, endDate)

            // Separate income and expenses
            val income = transactions.filter { it.amount > BigDecimal.ZERO }
            val expenses = transactions.filter { it.amount < BigDecimal.ZERO }

            // Calculate totals
            val totalIncome = income.sumOf { it.amount }
            val totalExpenses = expenses.sumOf { it.amount.abs() }

            // Group expenses by category
            val expensesByCategory = expenses
                .groupBy { it.category?.name ?: "Uncategorized" }
                .mapValues { (_, txns) -> txns.sumOf { it.amount.abs() } }

            // Get tax-relevant transactions
            val taxRelevantCategories = categoryRepository.findTaxRelevant().map { it.name }
            val taxRelevantExpenses = expenses
                .filter { it.category?.name in taxRelevantCategories }
                .groupBy { it.category?.name ?: "Uncategorized" }
                .mapValues { (_, txns) -> txns.sumOf { it.amount.abs() } }

            // Monthly breakdown
            val monthlyBreakdown = (1..12).map { month ->
                val monthTxns = transactions.filter {
                    it.date.monthValue == month && it.date.year == year
                }
                MonthlyData(
                    month = month,
                    income = monthTxns.filter { it.amount > BigDecimal.ZERO }.sumOf { it.amount },
                    expenses = monthTxns.filter { it.amount < BigDecimal.ZERO }.sumOf { it.amount.abs() }
                )
            }

            _isLoading.value = false

            TaxReport(
                year = year,
                totalIncome = totalIncome,
                totalExpenses = totalExpenses,
                netIncome = totalIncome - totalExpenses,
                expensesByCategory = expensesByCategory,
                taxRelevantExpenses = taxRelevantExpenses,
                monthlyBreakdown = monthlyBreakdown,
                transactionCount = transactions.size
            )
        } catch (e: Exception) {
            _isLoading.value = false
            TaxReport.empty(year)
        }
    }

    /**
     * Generate monthly spending report
     */
    suspend fun generateMonthlyReport(year: Int, month: Int): MonthlyReport = withContext(Dispatchers.IO) {
        val startDate = LocalDate.of(year, month, 1)
        val endDate = startDate.plusMonths(1).minusDays(1)
        val transactions = transactionRepository.findByDateRange(startDate, endDate)

        val income = transactions.filter { it.amount > BigDecimal.ZERO }.sumOf { it.amount }
        val expenses = transactions.filter { it.amount < BigDecimal.ZERO }.sumOf { it.amount.abs() }

        val byCategory = transactions
            .filter { it.amount < BigDecimal.ZERO }
            .groupBy { it.category?.name ?: "Uncategorized" }
            .mapValues { (_, txns) -> txns.sumOf { it.amount.abs() } }
            .toSortedMap()

        val topExpenses = transactions
            .filter { it.amount < BigDecimal.ZERO }
            .sortedBy { it.amount }
            .take(10)
            .map { ExpenseItem(
                description = it.partnerName ?: it.purpose ?: "Unknown",
                amount = it.amount.abs(),
                category = it.category?.name ?: "Uncategorized",
                date = it.date
            ) }

        MonthlyReport(
            year = year,
            month = month,
            totalIncome = income,
            totalExpenses = expenses,
            netAmount = income - expenses,
            byCategory = byCategory,
            topExpenses = topExpenses,
            transactionCount = transactions.size
        )
    }

    /**
     * Generate category spending trends
     */
    suspend fun getCategoryTrends(
        categoryId: Int,
        months: Int = 12
    ): CategoryTrend = withContext(Dispatchers.IO) {
        val category = categoryRepository.findById(categoryId)
        val now = LocalDate.now()
        val monthlyData = mutableListOf<MonthlyAmount>()

        for (i in months - 1 downTo 0) {
            val month = now.minusMonths(i.toLong())
            val startDate = month.withDayOfMonth(1)
            val endDate = startDate.plusMonths(1).minusDays(1)

            val transactions = transactionRepository.findByDateRange(startDate, endDate)
                .filter { it.category?.id?.value == categoryId }

            val total = transactions.sumOf { it.amount.abs() }

            monthlyData.add(MonthlyAmount(
                year = month.year,
                month = month.monthValue,
                amount = total
            ))
        }

        // Calculate trend
        val amounts = monthlyData.map { it.amount }
        val average = if (amounts.isNotEmpty()) {
            amounts.reduce { acc, amount -> acc + amount } / BigDecimal(amounts.size)
        } else BigDecimal.ZERO

        val latest = amounts.lastOrNull() ?: BigDecimal.ZERO
        val trend = if (average > BigDecimal.ZERO) {
            ((latest - average) / average * BigDecimal(100)).setScale(1, RoundingMode.HALF_UP)
        } else BigDecimal.ZERO

        CategoryTrend(
            categoryId = categoryId,
            categoryName = category?.name ?: "Unknown",
            monthlyData = monthlyData,
            averageMonthly = average,
            trend = trend
        )
    }

    /**
     * Export report to PDF
     */
    suspend fun exportToPdf(report: TaxReport, outputFile: java.io.File): Boolean = withContext(Dispatchers.IO) {
        // TODO: Implement PDF export using Apache PDFBox or iText
        // 1. Create PDF document
        // 2. Add header with FinDesk branding
        // 3. Add summary tables
        // 4. Add charts
        // 5. Save to file

        try {
            // Placeholder
            outputFile.writeText("Tax Report ${report.year}\n\n")
            outputFile.appendText("Total Income: €${report.totalIncome}\n")
            outputFile.appendText("Total Expenses: €${report.totalExpenses}\n")
            outputFile.appendText("Net Income: €${report.netIncome}\n")
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Export report to CSV
     */
    suspend fun exportToCsv(transactions: List<Transaction>, outputFile: java.io.File): Boolean = withContext(Dispatchers.IO) {
        try {
            outputFile.bufferedWriter().use { writer ->
                writer.write("Date,Description,Category,Amount,Currency")
                writer.newLine()
                transactions.forEach { txn ->
                    writer.write("${txn.date},\"${txn.partnerName ?: txn.purpose ?: ""}\",\"${txn.category?.name ?: ""}\",${txn.amount},${txn.currency}")
                    writer.newLine()
                }
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    // Data classes for reports
    data class TaxReport(
        val year: Int,
        val totalIncome: BigDecimal,
        val totalExpenses: BigDecimal,
        val netIncome: BigDecimal,
        val expensesByCategory: Map<String, BigDecimal>,
        val taxRelevantExpenses: Map<String, BigDecimal>,
        val monthlyBreakdown: List<MonthlyData>,
        val transactionCount: Int
    ) {
        companion object {
            fun empty(year: Int) = TaxReport(
                year = year,
                totalIncome = BigDecimal.ZERO,
                totalExpenses = BigDecimal.ZERO,
                netIncome = BigDecimal.ZERO,
                expensesByCategory = emptyMap(),
                taxRelevantExpenses = emptyMap(),
                monthlyBreakdown = emptyList(),
                transactionCount = 0
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
        val transactionCount: Int
    )

    data class MonthlyData(
        val month: Int,
        val income: BigDecimal,
        val expenses: BigDecimal
    )

    data class ExpenseItem(
        val description: String,
        val amount: BigDecimal,
        val category: String,
        val date: LocalDate
    )

    data class CategoryTrend(
        val categoryId: Int,
        val categoryName: String,
        val monthlyData: List<MonthlyAmount>,
        val averageMonthly: BigDecimal,
        val trend: BigDecimal // Percentage change
    )

    data class MonthlyAmount(
        val year: Int,
        val month: Int,
        val amount: BigDecimal
    )
}