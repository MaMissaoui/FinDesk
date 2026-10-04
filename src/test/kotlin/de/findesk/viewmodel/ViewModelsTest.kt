package de.findesk.viewmodel

import de.findesk.TestData
import de.findesk.TestDatabase
import de.findesk.model.Direction
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.math.BigDecimal
import java.time.LocalDate

class ViewModelsTest {
    private lateinit var data: TestData
    private val today = LocalDate.of(2026, 3, 20)
    private var account = 0
    private var food = 0

    @TempDir
    lateinit var tmp: File

    @BeforeEach
    fun setup() {
        TestDatabase.init()
        data = TestData()
        account = data.account(balance = "1500.00")
        food = data.category("Food", color = "#4CAF50")
        val tax = data.category("Spenden", taxRelevant = true)
        data.txn(account, LocalDate.of(2026, 3, 2), "-30.00", partner = "REWE", categoryId = food)
        data.txn(account, LocalDate.of(2026, 3, 5), "-20.00", partner = "Kiosk")
        data.txn(account, LocalDate.of(2026, 3, 1), "2500.00", partner = "Employer")
        data.txn(account, LocalDate.of(2026, 1, 15), "-100.00", partner = "UNICEF", categoryId = tax)
    }

    @Test
    fun `dashboard separates income from expenses (regression)`() = runBlocking {
        val vm = DashboardViewModel(today = { today })
        vm.loadDashboard().join()
        val s = vm.state.value

        assertNull(s.error)
        assertEquals(BigDecimal("1500.00"), s.totalBalance)
        assertEquals(BigDecimal("2500.00"), s.monthlyIncome)
        assertEquals(BigDecimal("50.00"), s.monthlyExpenses)
        assertEquals(2, s.uncategorizedCount, "Kiosk and the uncategorized salary")
        assertEquals(listOf("Food", "Uncategorized"), s.expensesByCategory.map { it.label })
        assertEquals("#4CAF50", s.expensesByCategory.first().color)
        assertEquals(listOf("Spenden"), s.taxDeductionsByCategory.map { it.label })
        assertEquals(6, s.lastMonths.size)
        assertEquals(BigDecimal("100.00"), s.lastMonths.first { it.month == 1 }.expenses)
        vm.close()
    }

    @Test
    fun `transactions filters apply and category filter is honoured`() = runBlocking {
        val vm = TransactionsViewModel(today = { today })
        vm.loadTransactions().join()
        assertEquals(3, vm.state.value.transactions.size, "this month")

        vm.updateDirection(Direction.EXPENSES).join()
        assertEquals(2, vm.state.value.transactions.size)

        vm.updateCategoryFilter(CategoryFilter.Only(food)).join()
        assertEquals(listOf("REWE"), vm.state.value.transactions.map { it.partnerName })

        vm.updateCategoryFilter(CategoryFilter.None).join()
        assertEquals(listOf("Kiosk"), vm.state.value.transactions.map { it.partnerName })

        vm.updateCategoryFilter(CategoryFilter.Any).join()
        vm.updatePeriod(Period.THIS_YEAR).join()
        vm.updateSearchQuery("unic").join()
        assertEquals(listOf("UNICEF"), vm.state.value.transactions.map { it.partnerName })
        vm.close()
    }

    @Test
    fun `manual transaction gets auto-categorized and duplicates are rejected`() = runBlocking {
        data.ruleRepo.create("edeka", food)
        val vm = TransactionsViewModel(today = { today })
        vm.addManualTransaction(account, today, BigDecimal("-12.34"), "EDEKA Center", null, null).join()
        val added = vm.state.value.transactions.first { it.partnerName == "EDEKA Center" }
        assertEquals("Food", added.categoryName)

        vm.addManualTransaction(account, today, BigDecimal("-12.34"), "EDEKA Center", null, null).join()
        assertEquals("An identical transaction already exists", vm.state.value.error)
        vm.close()
    }

    @Test
    fun `statement import previews, picks the account from the file and imports`() = runBlocking {
        val other = data.account(iban = "DE44500105175407324931", balance = "0")
        val csv = File(tmp, "ing.csv").apply {
            writeText(
                "IBAN;DE44 5001 0517 5407 3249 31\n\nBuchung;Valuta;Auftraggeber/Empfänger;Buchungstext;Verwendungszweck;Saldo;Währung;Betrag;Währung\n" +
                    "02.10.2025;02.10.2025;Stadtwerke;Lastschrift;Strom;80,00;EUR;-20,00;EUR\n",
            )
        }
        val vm = TransactionsViewModel(today = { today })
        vm.loadTransactions().join()
        vm.openImport(csv).join()

        val preview = vm.state.value.importPreview!!
        assertEquals(other, preview.accountId, "matched by the IBAN in the file")
        assertEquals(1, preview.statement.bookings.size)
        assertEquals(0, data.transactionRepo.findItems().count { it.partnerName == "Stadtwerke" }, "nothing stored before confirming")

        vm.confirmImport(updateBalance = true).join()
        assertNull(vm.state.value.importPreview)
        assertEquals(Period.ALL, vm.state.value.period, "imported history is visible even if older than this month")
        assertEquals(listOf("Stadtwerke"), vm.state.value.transactions.map { it.partnerName })
        assertEquals(BigDecimal("80.00"), data.accountRepo.findAllItems().first { it.id == other }.balance)
        vm.close()
    }

    @Test
    fun `auto categorize applies rules to uncategorized transactions`() = runBlocking {
        data.ruleRepo.create("kiosk", food)
        val vm = TransactionsViewModel(today = { today })
        vm.autoCategorizeTransactions().join()
        assertEquals("Categorized 1 of 2 uncategorized transactions", vm.state.value.message)
        vm.close()
    }

    @Test
    fun `accounts validate iban and cascade deletes`() = runBlocking {
        val vm = AccountsViewModel()
        vm.loadAccounts().join()
        val bankId = vm.state.value.bankAccesses.first().id

        vm.addAccount(bankId, "DE00 1234 5678 9012 3456 78", "checking", BigDecimal.ZERO).join()
        assertEquals("IBAN is not valid (checksum failed)", vm.state.value.error)

        vm.addAccount(bankId, "de44 5001 0517 5407 3249 31", "savings", BigDecimal("10")).join()
        assertEquals(2, vm.state.value.accounts.size)
        assertEquals("DE44500105175407324931", vm.state.value.accounts.last().iban)

        vm.deleteBankAccess(bankId).join()
        assertTrue(vm.state.value.accounts.isEmpty())
        assertTrue(data.transactionRepo.findItems().isEmpty())
        vm.close()
    }

    @Test
    fun `reports view model loads and exports`() = runBlocking {
        val vm = ReportsViewModel()
        vm.loadTaxReport(2026).join()
        assertEquals(BigDecimal("2500.00"), vm.state.value.taxReport?.totalIncome)

        val pdf = File(tmp, "r.pdf")
        vm.exportToPdf(pdf).join()
        assertTrue(pdf.length() > 0)
        assertEquals("Saved r.pdf", vm.state.value.message)
        vm.close()
    }

    @Test
    fun `settings validates rules and deleting a category unassigns transactions`() = runBlocking {
        val vm = SettingsViewModel(dataDirectory = tmp.absolutePath)
        vm.load().join()
        assertNotNull(vm.validateRule("(unclosed"))
        assertNull(vm.validateRule("rewe|edeka"))
        assertEquals("Category already exists", vm.validateCategory("food", "#000000"))

        vm.setDarkTheme(true).join()
        assertTrue(SettingsViewModel(dataDirectory = tmp.absolutePath).also { it.load().join() }.state.value.darkTheme)

        vm.deleteCategory(food).join()
        assertNull(data.transactionRepo.findItems().first { it.partnerName == "REWE" }.categoryId)
        vm.close()
    }
}
