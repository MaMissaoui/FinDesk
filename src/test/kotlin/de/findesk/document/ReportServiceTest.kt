package de.findesk.document

import de.findesk.data.database.DatabaseFactory
import de.findesk.repository.*
import org.jetbrains.exposed.sql.Database
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import java.math.BigDecimal
import java.time.LocalDate

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ReportServiceTest {
    private lateinit var service: ReportService
    private lateinit var transactionRepo: TransactionRepository
    private lateinit var accountRepo: AccountRepository
    private lateinit var bankAccessRepo: BankAccessRepository
    private lateinit var categoryRepo: CategoryRepository

    @BeforeAll
    fun setup() {
        Database.connect("jdbc:sqlite::memory:", driver = "org.sqlite.JDBC")
        DatabaseFactory.init()
        service = ReportService()
        transactionRepo = TransactionRepositoryImpl()
        accountRepo = AccountRepositoryImpl()
        bankAccessRepo = BankAccessRepositoryImpl()
        categoryRepo = CategoryRepositoryImpl()

        // Create test data
        val bankAccess = bankAccessRepo.create("Test Bank", "12345678", "TESTDEFF", "testuser")
        val account = accountRepo.create(bankAccess.id.value, "DE89370400440532013000")
        val groceries = categoryRepo.create("Groceries", "#FF5722")
        val transport = categoryRepo.create("Transport", "#2196F3", isTaxRelevant = true)

        // Create test transactions
        val now = LocalDate.now()
        transactionRepo.create(account.id.value, now, BigDecimal("-50.00"), categoryId = groceries.id.value, hash = "hash1")
        transactionRepo.create(account.id.value, now, BigDecimal("-30.00"), categoryId = transport.id.value, hash = "hash2")
        transactionRepo.create(account.id.value, now, BigDecimal("3200.00"), hash = "hash3")
    }

    @Test
    fun `test tax report data class`() {
        val report = ReportService.TaxReport(
            year = 2026,
            totalIncome = BigDecimal("38400.00"),
            totalExpenses = BigDecimal("25800.00"),
            netIncome = BigDecimal("12600.00"),
            expensesByCategory = mapOf("Groceries" to BigDecimal("6240.00")),
            taxRelevantExpenses = mapOf("Transport" to BigDecimal("3600.00")),
            monthlyBreakdown = emptyList(),
            transactionCount = 100
        )

        assertEquals(2026, report.year)
        assertEquals(BigDecimal("38400.00"), report.totalIncome)
        assertEquals(BigDecimal("12600.00"), report.netIncome)
    }

    @Test
    fun `test tax report empty factory`() {
        val report = ReportService.TaxReport.empty(2025)

        assertEquals(2025, report.year)
        assertEquals(BigDecimal.ZERO, report.totalIncome)
        assertEquals(BigDecimal.ZERO, report.totalExpenses)
        assertEquals(0, report.transactionCount)
    }

    @Test
    fun `test monthly report data class`() {
        val report = ReportService.MonthlyReport(
            year = 2026,
            month = 10,
            totalIncome = BigDecimal("3200.00"),
            totalExpenses = BigDecimal("2100.00"),
            netAmount = BigDecimal("1100.00"),
            byCategory = mapOf("Groceries" to BigDecimal("500.00")),
            topExpenses = emptyList(),
            transactionCount = 50
        )

        assertEquals(2026, report.year)
        assertEquals(10, report.month)
    }

    @Test
    fun `test category trend data class`() {
        val trend = ReportService.CategoryTrend(
            categoryId = 1,
            categoryName = "Groceries",
            monthlyData = listOf(
                ReportService.MonthlyAmount(2026, 1, BigDecimal("500.00")),
                ReportService.MonthlyAmount(2026, 2, BigDecimal("550.00"))
            ),
            averageMonthly = BigDecimal("525.00"),
            trend = BigDecimal("4.8")
        )

        assertEquals("Groceries", trend.categoryName)
        assertEquals(2, trend.monthlyData.size)
    }
}