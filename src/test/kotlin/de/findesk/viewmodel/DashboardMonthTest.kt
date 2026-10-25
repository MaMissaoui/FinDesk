package de.findesk.viewmodel

import de.findesk.TestData
import de.findesk.TestDatabase
import de.findesk.i18n.AppLanguage
import de.findesk.i18n.Translations
import de.findesk.repository.TransactionRepositoryImpl
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.YearMonth

/**
 * Month selection on the dashboard (default: the current month, never a future one): which figures follow the
 * selected month and which stay "now", the clamping against the oldest booking, and the exact-month period in
 * Transactions the dashboard's click-through uses. Plus [TransactionRepositoryImpl.oldestDate].
 */
class DashboardMonthTest {
    private val today = LocalDate.of(2026, 10, 15)

    private lateinit var data: TestData
    private var account = 0
    private var food = 0
    private var tax = 0
    private var transfers = 0

    @BeforeEach
    fun setup() {
        TestDatabase.init()
        data = TestData()
    }

    /** Account, categories with a Food budget and the multi-month bookings: March (oldest), August, September, October. */
    private fun fixtures() {
        account = data.account(balance = "1000.00")
        food = data.category("Food")
        tax = data.category("Donations", taxRelevant = true)
        transfers = data.categoryRepo.create("Own transfers", "#4CAF50", isTransfer = true).id.value
        data.categoryRepo.setBudget(food, BigDecimal("100.00"))
        data.txn(account, LocalDate.of(2026, 3, 5), "-100.00", partner = "MaerzJetzt", categoryId = tax)
        data.txn(account, LocalDate.of(2026, 8, 2), "-120.00", partner = "August Nazar", categoryId = food)
        data.txn(account, LocalDate.of(2026, 8, 3), "2000.00", partner = "Employer")
        data.txn(account, LocalDate.of(2026, 8, 4), "-200.00", partner = "Erika Musterfrau", categoryId = transfers)
        data.txn(account, LocalDate.of(2026, 8, 5), "-15.00") // uncategorized in August
        data.txn(account, LocalDate.of(2026, 9, 4), "-60.00", partner = "September Nazar", categoryId = food)
        data.txn(account, LocalDate.of(2026, 9, 6), "-50.00", partner = "September Spenden", categoryId = tax)
        data.txn(account, LocalDate.of(2026, 10, 2), "-40.00", partner = "Oktober Nazar", categoryId = food)
        data.txn(account, LocalDate.of(2026, 10, 3), "1500.00", partner = "Employer")
    }

    @Test
    fun `the dashboard opens on the current month`() = runBlocking {
        fixtures()
        val vm = DashboardViewModel(today = { today })
        vm.loadDashboard().join()

        val s = vm.state.value
        assertEquals(YearMonth.of(2026, 10), s.selectedMonth)
        assertEquals(YearMonth.of(2026, 3), s.earliestMonth, "the month of the oldest booking")
        assertTrue(s.isCurrentMonth)
        assertNull(s.error)
        vm.close()

        // And the month label renders in the UI languages (standalone month name, capitalised in FR too)
        assertEquals("September 2026", Translations.of(AppLanguage.EN).month(YearMonth.of(2026, 9)))
        assertEquals("September 2026", Translations.of(AppLanguage.DE).month(YearMonth.of(2026, 9)))
        assertEquals("Septembre 2026", Translations.of(AppLanguage.FR).month(YearMonth.of(2026, 9)))
    }

    @Test
    fun `selecting a past month moves every month figure and leaves the 'now' ones`() = runBlocking {
        fixtures()
        val vm = DashboardViewModel(today = { today })
        vm.loadDashboard().join()
        val before = vm.state.value

        vm.selectMonth(YearMonth.of(2026, 8)).join()
        val s = vm.state.value

        assertEquals(YearMonth.of(2026, 8), s.selectedMonth)
        assertFalse(s.isCurrentMonth)
        assertEquals(BigDecimal("2000.00"), s.monthlyIncome, "August only")
        assertEquals(BigDecimal("135.00"), s.monthlyExpenses, "August only: the food and the uncategorized booking")
        assertEquals(listOf("Food", "Uncategorized"), s.expensesByCategory.map { it.label }, "the August split")
        assertEquals(BigDecimal("200.00"), s.transfersThisMonth, "the August transfer")
        assertEquals(2, s.uncategorizedCount, "the uncategorized expense and salary in August")
        val foodBudget = s.budgets.single { it.categoryId == food }
        assertEquals(BigDecimal("120.00"), foodBudget.spent, "budget spending is judged for August")
        assertEquals(1f, s.monthProgress, "a past month is over, so the pace warning cannot fire")
        assertEquals(listOf(3, 4, 5, 6, 7, 8), s.lastMonths.map { it.month }, "the six months ending with August")
        assertEquals(1, s.lastMonths.single { it.month == 8 }.income.signum(), "the August bar shows August")
        assertEquals(listOf("Donations"), s.taxDeductionsByCategory.map { it.label }, "January 1 to August 31")
        assertEquals(BigDecimal("100.00"), s.taxDeductionsByCategory.single().amount, "the September donation stays out")
        // Unchanged, always "now"
        assertEquals(before.totalBalance, s.totalBalance)
        assertEquals(before.availableBalance, s.availableBalance)
        assertEquals(before.loanBalance, s.loanBalance)
        assertEquals(before.recentTransactions.map { it.id }, s.recentTransactions.map { it.id })
        assertEquals(before.unlinkedDocumentCount, s.unlinkedDocumentCount)
        vm.close()
    }

    @Test
    fun `the selection clamps to the loaded range on every action`() = runBlocking {
        fixtures()
        val vm = DashboardViewModel(today = { today })
        vm.loadDashboard().join()
        val earliest = YearMonth.of(2026, 3)

        assertEquals(earliest, vm.state.value.earliestMonth)
        vm.nextMonth().join()
        assertEquals(YearMonth.of(2026, 10), vm.state.value.selectedMonth, "a next month at the current month stays there")
        vm.selectMonth(YearMonth.of(2026, 12)).join()
        assertEquals(YearMonth.of(2026, 10), vm.state.value.selectedMonth, "a future month clamps to the current month")
        vm.selectMonth(YearMonth.of(2026, 1)).join()
        assertEquals(earliest, vm.state.value.selectedMonth, "before the oldest booking clamps to earliestMonth")
        vm.previousMonth().join()
        assertEquals(earliest, vm.state.value.selectedMonth, "a previous month at the earliest month stays there")
        vm.currentMonth().join()
        assertTrue(vm.state.value.isCurrentMonth)
        vm.close()
    }

    @Test
    fun `a reload keeps the selection while a new view model starts at the current month`() = runBlocking {
        fixtures()
        val vm = DashboardViewModel(today = { today })
        vm.loadDashboard().join()
        vm.selectMonth(YearMonth.of(2026, 8)).join()

        vm.loadDashboard().join() // the error banner's retry
        assertEquals(YearMonth.of(2026, 8), vm.state.value.selectedMonth)
        assertEquals(BigDecimal("135.00"), vm.state.value.monthlyExpenses, "August again")
        vm.close()

        val fresh = DashboardViewModel(today = { today })
        fresh.loadDashboard().join()
        assertEquals(YearMonth.of(2026, 10), fresh.state.value.selectedMonth, "the selection is not persisted")
        assertEquals(YearMonth.of(2026, 3), fresh.state.value.earliestMonth)
        fresh.close()
    }

    @Test
    fun `without bookings every month is the current month`() = runBlocking {
        data.account()
        val vm = DashboardViewModel(today = { today })
        vm.loadDashboard().join()
        assertEquals(YearMonth.of(2026, 10), vm.state.value.earliestMonth)
        assertEquals(YearMonth.of(2026, 10), vm.state.value.selectedMonth)
        vm.close()
    }

    @Test
    fun `oldestDate returns the oldest booking per currency or null`() {
        val repo = TransactionRepositoryImpl()
        assertNull(repo.oldestDate(), "an empty table")
        assertNull(repo.oldestDate("EUR"), "an empty table, per currency")

        val euro = data.account()
        val dollar = data.account(iban = "DE44500105175407324931", balance = "0", currency = "USD")
        data.transactionRepo.create(euro, LocalDate.of(2026, 8, 2), BigDecimal("-5.00"), currency = "EUR", hash = "eur-1")
        data.transactionRepo.create(dollar, LocalDate.of(2026, 1, 10), BigDecimal("-5.00"), currency = "USD", hash = "usd-1")

        assertEquals(LocalDate.of(2026, 1, 10), repo.oldestDate(), "the oldest booking of any currency")
        assertEquals(LocalDate.of(2026, 8, 2), repo.oldestDate("EUR"), "the oldest booking in that currency")
        assertNull(repo.oldestDate("GBP"), "nothing in that currency")
    }

    @Test
    fun `a month navigation lists exactly that month's bookings and a picked period ends it`() = runBlocking {
        fixtures()
        val vm = TransactionsViewModel(today = { today })
        vm.start(categoryId = food, month = YearMonth.of(2026, 8)).join()

        assertEquals(YearMonth.of(2026, 8), vm.state.value.month)
        assertEquals(listOf("August Nazar"), vm.state.value.transactions.map { it.partnerName })
        assertNull(vm.state.value.error)

        vm.updatePeriod(Period.THIS_MONTH).join()
        assertNull(vm.state.value.month, "picking a period clears the month")
        assertEquals(listOf("Oktober Nazar"), vm.state.value.transactions.map { it.partnerName }, "October again")
        vm.close()
    }

    @Test
    fun `with a month set and one account the balance chart covers only that month`() = runBlocking {
        fixtures()
        val vm = TransactionsViewModel(today = { today })
        vm.start(accountId = account, month = YearMonth.of(2026, 8)).join()

        val chart = vm.state.value.balanceChart
        assertEquals(LocalDate.of(2026, 8, 1), chart!!.first().first, "the chart starts with the month")
        assertEquals(LocalDate.of(2026, 8, 31), chart.last().first, "and ends with the month, not today")
        assertEquals(31, chart.size)
        vm.close()
    }
}
