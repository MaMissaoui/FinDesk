package de.findesk.viewmodel

import de.findesk.TestData
import de.findesk.TestDatabase
import de.findesk.i18n.AppLanguage
import de.findesk.i18n.StringKey
import de.findesk.i18n.Translations
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.YearMonth

/**
 * The dashboard's balance card follows the selected month: a past month shows what was carried over from the
 * previous one and the movements that income and expenses leave out, so all lines add up to the available balance.
 */
class DashboardCarryForwardTest {
    private val today = LocalDate.of(2026, 10, 20)

    private lateinit var data: TestData
    private var checking = 0
    private var loan = 0
    private var depot = 0
    private var transfers = 0

    @BeforeEach
    fun setup() {
        TestDatabase.init()
        data = TestData()
    }

    /**
     * Checking with today's balance of 1,000.00: October bookings sum to +200.00 and September's to -300.00.
     * A loan and a depot carry bookings from August, so every available account's history starts before September.
     */
    private fun fixtures() {
        checking = data.account(balance = "1000.00")
        loan = newAccount("-5000.00", "loan", "DE44500105175407324001")
        depot = newAccount("3000.00", "depot", "DE02120300000000202051")
        transfers = data.categoryRepo.create("Own transfers", "#4CAF50", isTransfer = true).id.value

        // Oldest bookings in August, before the first selected month
        data.txn(checking, LocalDate.of(2026, 8, 1), "-10.00", partner = "August")
        data.txn(loan, LocalDate.of(2026, 8, 2), "-100.00", partner = "Loan opening")
        data.txn(depot, LocalDate.of(2026, 8, 3), "100.00", partner = "Depot opening")

        // September: +2,000 income and -2,300 expense
        data.txn(checking, LocalDate.of(2026, 9, 4), "2000.00", partner = "Employer")
        data.txn(checking, LocalDate.of(2026, 9, 10), "-2300.00", partner = "Rent")
        // October: +250 income and -50 expense
        data.txn(checking, LocalDate.of(2026, 10, 3), "250.00", partner = "Refund")
        data.txn(checking, LocalDate.of(2026, 10, 5), "-50.00", partner = "Bakery")
    }

    private fun newAccount(balance: String, type: String, iban: String): Int {
        val bank = data.bankAccessRepo.create("Test Bank", "37040044", "COBADEFFXXX", "user1")
        return data.accountRepo.create(bank.id.value, iban, balance = BigDecimal(balance), accountType = type).id.value
    }

    private suspend fun load(month: YearMonth): DashboardState {
        val vm = DashboardViewModel(today = { today })
        vm.selectMonth(month).join()
        val state = vm.state.value
        vm.close()
        return state
    }

    @Test
    fun `a past month shows its own balance, carried over from the previous one`() = runBlocking {
        fixtures()

        val s = load(YearMonth.of(2026, 9))

        assertEquals(BigDecimal("800.00"), s.availableBalance, "1,000.00 today minus the October bookings (+200.00)")
        assertEquals(LocalDate.of(2026, 9, 30), s.balanceDate)
        assertEquals(BigDecimal("1100.00"), s.carryForward, "800.00 minus September's -300.00 movement")
        assertEquals(0, s.otherMovements!!.compareTo(BigDecimal.ZERO), "income and expenses explain all of September")
    }

    @Test
    fun `carry-forward, income, expenses and other movements add up exactly`() = runBlocking {
        fixtures()

        listOf(YearMonth.of(2026, 9), YearMonth.of(2026, 10)).forEach { month ->
            val s = load(month)
            val total = s.carryForward!! + s.monthlyIncome - s.monthlyExpenses + s.otherMovements!!
            assertEquals(0, total.compareTo(s.availableBalance), "the identity holds for $month")
        }
    }

    @Test
    fun `a loan repayment lowers the available balance but is not an expense`() = runBlocking {
        fixtures()
        data.txn(checking, LocalDate.of(2026, 9, 15), "-500.00", partner = "Loan repayment", categoryId = transfers)
        data.txn(loan, LocalDate.of(2026, 9, 15), "500.00", partner = "Loan repayment", categoryId = transfers)

        val s = load(YearMonth.of(2026, 9))

        assertEquals(BigDecimal("-500.00"), s.otherMovements, "the repayment is a transfer, not an expense")
        assertEquals(BigDecimal("2000.00"), s.monthlyIncome, "unchanged by the repayment")
        assertEquals(BigDecimal("2300.00"), s.monthlyExpenses, "unchanged by the repayment")
    }

    @Test
    fun `loan interest is an expense and comes back as an other movement`() = runBlocking {
        fixtures()
        data.txn(loan, LocalDate.of(2026, 9, 20), "-40.00", partner = "Loan interest")

        val s = load(YearMonth.of(2026, 9))

        assertEquals(BigDecimal("2340.00"), s.monthlyExpenses, "the interest counts as spending")
        assertEquals(BigDecimal("40.00"), s.otherMovements, "so the identity adds it back")
        val total = s.carryForward!! + s.monthlyIncome - s.monthlyExpenses + s.otherMovements!!
        assertEquals(0, total.compareTo(s.availableBalance))
    }

    @Test
    fun `a transfer between available accounts is no other movement`() = runBlocking {
        fixtures()
        val savings = newAccount("500.00", "checking", "DE44500105175407324931")
        data.txn(savings, LocalDate.of(2026, 8, 1), "1.00", partner = "August")
        data.txn(checking, LocalDate.of(2026, 9, 18), "-100.00", partner = "To savings", categoryId = transfers)
        data.txn(savings, LocalDate.of(2026, 9, 18), "100.00", partner = "From checking", categoryId = transfers)

        val s = load(YearMonth.of(2026, 9))

        assertEquals(0, s.otherMovements!!.compareTo(BigDecimal.ZERO), "money stayed within the available accounts")
        assertEquals(BigDecimal("2000.00"), s.monthlyIncome)
        assertEquals(BigDecimal("2300.00"), s.monthlyExpenses)
    }

    @Test
    fun `an incomplete history hides the carry-forward until the month is covered`() = runBlocking {
        fixtures()
        // Its first booking is in September, so FinDesk cannot know the balance it started September with
        val savings = newAccount("500.00", "checking", "DE44500105175407324931")
        data.txn(savings, LocalDate.of(2026, 9, 5), "100.00", partner = "First booking")

        val september = load(YearMonth.of(2026, 9))
        assertNull(september.carryForward)
        assertNull(september.otherMovements)

        val october = load(YearMonth.of(2026, 10))
        assertNotNull(october.carryForward, "September's booking is before October, so October is covered")
        assertNotNull(october.otherMovements)
    }

    @Test
    fun `the current month keeps today's balance even with a future-dated booking`() = runBlocking {
        fixtures()
        val vm = DashboardViewModel(today = { today })
        vm.loadDashboard().join()

        var s = vm.state.value
        assertNull(s.balanceDate)
        assertEquals(BigDecimal("1000.00"), s.availableBalance)

        // A future booking is already in the bank's balance, as the bank reports it
        data.txn(checking, LocalDate.of(2026, 11, 5), "-100.00", partner = "Future")
        vm.loadDashboard().join()
        s = vm.state.value

        val bankBalance = data.accountRepo.findAllItems().single { it.id == checking }.balance
        assertEquals(bankBalance, s.availableBalance)
        assertEquals(BigDecimal("1000.00"), s.availableBalance)
        val total = s.carryForward!! + s.monthlyIncome - s.monthlyExpenses + s.otherMovements!!
        assertEquals(0, total.compareTo(s.availableBalance))
        vm.close()
    }

    @Test
    fun `a depot keeps its current market balance for a past month`() = runBlocking {
        fixtures()
        data.txn(depot, LocalDate.of(2026, 9, 12), "700.00", partner = "Depot buy")

        val s = load(YearMonth.of(2026, 9))

        assertEquals(BigDecimal("800.00"), s.availableBalance)
        assertEquals(BigDecimal("-5000.00"), s.loanBalance)
        assertEquals(BigDecimal("-1200.00"), s.totalBalance, "the depot's 3,000.00 is its current balance, not reconstructed")
    }

    @Test
    fun `the new dashboard strings are translated`() {
        val en = Translations.of(AppLanguage.EN)
        assertEquals("Available on %s", en[StringKey.AvailableAsOf])
        assertEquals("Carried over from %1\$s: %2\$s", en[StringKey.CarryForwardFrom])
        assertEquals("Transfers and loans: %s", en[StringKey.OtherMovements])
    }
}
