package de.findesk.viewmodel

import de.findesk.TestData
import de.findesk.TestDatabase
import de.findesk.document.ReportService
import de.findesk.model.Direction
import de.findesk.model.TransactionFilter
import de.findesk.util.Currencies
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.math.BigDecimal
import java.time.LocalDate

/**
 * Transfers between the user's own accounts leave income, spending and the category split everywhere the
 * dashboard and the reports count, while taxes, budgets, balances and the shown lists stay untouched.
 */
class TransferExclusionTest {
    @TempDir
    lateinit var tmp: File

    private lateinit var data: TestData
    private val today = LocalDate.of(2026, 6, 15)
    private var account = 0
    private var second = 0
    private var transfers = 0
    private var groceries = 0

    @BeforeEach
    fun setup() {
        TestDatabase.init()
        data = TestData()
        account = data.account(balance = "1000.00")
        second = data.account(iban = "DE12500105170137075030", balance = "500.00")
        transfers = data.categoryRepo.create("Own transfers", "#4CAF50", isTransfer = true).id.value
        groceries = data.category("Groceries")
    }

    @Test
    fun `dashboard and report agree and leave the transfer out of the totals`() = runBlocking {
        data.txn(account, today, "-500.00", partner = "Erika Musterfrau", categoryId = transfers)
        data.txn(second, today, "500.00", partner = "Erika Musterfrau", categoryId = transfers)
        data.txn(account, today.plusDays(1), "-40.00", partner = "BEISPIEL MARKT", categoryId = groceries)
        data.txn(account, today.plusDays(2), "1000.00", partner = "Erika Mustermann")

        val vm = DashboardViewModel(today = { today })
        vm.loadDashboard().join()
        val state = vm.state.value
        assertEquals(BigDecimal("1000.00"), state.monthlyIncome)
        assertEquals(BigDecimal("40.00"), state.monthlyExpenses)
        assertEquals(listOf("Groceries"), state.expensesByCategory.map { it.label })
        assertEquals(BigDecimal("500.00"), state.transfersThisMonth)
        // The six-month bars exclude the transfer the same way
        val juneBar = state.lastMonths.single { it.month == 6 }
        assertEquals(BigDecimal("1000.00"), juneBar.income)
        assertEquals(BigDecimal("40.00"), juneBar.expenses)
        vm.close()

        val service = ReportService()
        val report = service.buildReport(
            today.withDayOfMonth(1),
            today.withDayOfMonth(1).plusMonths(1).minusDays(1),
            data.transactionRepo.findItems(TransactionFilter(startDate = today.withDayOfMonth(1), endDate = today.plusDays(30))),
            Currencies.DEFAULT,
        )
        assertEquals(BigDecimal("1000.00"), report.totalIncome)
        assertEquals(BigDecimal("40.00"), report.totalExpenses)
        assertEquals(BigDecimal("960.00"), report.netIncome)
        assertEquals(BigDecimal("500.00"), report.transferTotal)
        assertEquals(listOf("BEISPIEL MARKT"), report.topVendors.map { it.first }, "the transfer partner is not a vendor")
        assertEquals(listOf("Groceries"), report.expensesByCategory.keys.toList())
        val month = report.monthlyBreakdown.single { it.month == 6 && it.year == 2026 }
        assertEquals(BigDecimal("1000.00"), month.income)
        assertEquals(BigDecimal("40.00"), month.expenses)
        assertEquals(listOf("Groceries"), report.categoryShares.map { it.name })
        assertEquals(listOf(groceries), report.categoryShares.map { it.categoryId })
        assertEquals(BigDecimal("40.00"), report.categoryShares.single().amount)
        assertEquals(groceriesColor(), report.categoryShares.single().color)
    }

    @Test
    fun `tax-relevant totals and budgets stay as they were`() = runBlocking {
        val transport = data.category("Transport", taxRelevant = true)
        data.categoryRepo.setBudget(transport, BigDecimal("100.00"))
        data.txn(account, today, "-500.00", categoryId = transfers)
        data.txn(second, today, "500.00", categoryId = transfers)
        data.txn(account, today, "-30.00", categoryId = transport)

        val vm = DashboardViewModel(today = { today })
        vm.loadDashboard().join()
        val budgets = vm.state.value.budgets
        assertEquals(1, budgets.size)
        assertEquals(BigDecimal("30.00"), budgets.single().spent)
        vm.close()

        val report = ReportService().buildReport(
            today.withDayOfMonth(1),
            today.plusDays(30),
            data.transactionRepo.findItems(TransactionFilter(startDate = today.withDayOfMonth(1), endDate = today.plusDays(30))),
            Currencies.DEFAULT,
        )
        assertEquals(BigDecimal("30.00"), report.taxRelevantTotal)
        assertEquals(mapOf("Transport" to BigDecimal("30.00")), report.taxRelevantExpenses)
    }

    @Test
    fun `a budget on a transfer category stays at 0 spent`() = runBlocking {
        data.categoryRepo.setBudget(transfers, BigDecimal("500.00"))
        data.txn(account, today, "-500.00", categoryId = transfers)

        val vm = DashboardViewModel(today = { today })
        vm.loadDashboard().join()
        val budget = vm.state.value.budgets.single { it.categoryId == transfers }
        assertEquals(BigDecimal.ZERO, budget.spent)
        assertEquals(BigDecimal("500.00"), budget.remaining)
        vm.close()
    }

    private fun groceriesColor(): String = data.categoryRepo.findAllItems().single { it.id == groceries }.color
}
