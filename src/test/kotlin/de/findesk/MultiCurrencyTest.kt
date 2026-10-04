package de.findesk

import de.findesk.document.RecurringDetector
import de.findesk.document.ReportService
import de.findesk.importer.ImportedBooking
import de.findesk.importer.ParsedStatement
import de.findesk.importer.StatementFormat
import de.findesk.importer.StatementImportService
import de.findesk.model.TransactionFilter
import de.findesk.repository.SettingsRepositoryImpl
import de.findesk.util.Currencies
import de.findesk.viewmodel.DashboardViewModel
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate

/** Amounts in different currencies must never be added up. */
class MultiCurrencyTest {
    private lateinit var data: TestData
    private var eur = 0
    private var usd = 0
    private val today = LocalDate.of(2026, 10, 15)

    @BeforeEach
    fun setup() {
        TestDatabase.init()
        data = TestData()
        eur = data.account("DE89370400440532013000", "1000.00")
        usd = data.account("DE44500105175407324931", "500.00", currency = "USD")
    }

    private fun statement(vararg bookings: ImportedBooking) = ParsedStatement(StatementFormat.OFX, bookings.toList())

    @Test
    fun `import skips bookings in another currency and adopts the account currency when the file has none`() = runBlocking {
        val day = LocalDate.of(2026, 10, 3)
        val report = StatementImportService().import(
            eur,
            statement(
                ImportedBooking(day, amount = BigDecimal("-10.00"), currency = "EUR", partnerName = "Bakery"),
                ImportedBooking(day, amount = BigDecimal("-99.00"), currency = "USD", partnerName = "US shop"),
                ImportedBooking(day, amount = BigDecimal("-5.00"), currency = null, partnerName = "Kiosk"),
            ),
        )

        assertEquals(2, report.imported)
        assertEquals(1, report.wrongCurrency)
        val stored = data.transactionRepo.findItems(TransactionFilter(accountId = eur))
        assertEquals(setOf("Bakery", "Kiosk"), stored.map { it.partnerName }.toSet())
        assertTrue(stored.all { it.currency == "EUR" })

        StatementImportService().import(usd, statement(ImportedBooking(day, amount = BigDecimal("-7.00"), partnerName = "Diner")))
        assertEquals("USD", data.transactionRepo.findItems(TransactionFilter(accountId = usd)).single().currency, "QIF-style booking without currency")
    }

    @Test
    fun `dashboard totals stay in the primary currency`() = runBlocking {
        data.txn(eur, today.withDayOfMonth(2), "2000.00", partner = "Employer")
        data.txn(usd, today.withDayOfMonth(3), "800.00", partner = "US client", currency = "USD")
        data.txn(usd, today.withDayOfMonth(4), "-40.00", partner = "US shop", currency = "USD")

        val vm = DashboardViewModel(today = { today })
        vm.loadDashboard().join()
        val s = vm.state.value

        assertNull(s.error)
        assertEquals("EUR", s.currency)
        assertEquals(BigDecimal("1000.00"), s.totalBalance, "not 1500: USD isn't added to EUR")
        assertEquals(mapOf("USD" to BigDecimal("500.00")), s.otherBalances)
        assertEquals(BigDecimal("2000.00"), s.monthlyIncome)
        assertEquals(0, s.monthlyExpenses.signum())
        assertTrue(s.expensesByCategory.isEmpty())
        assertEquals(3, s.uncategorizedCount, "the pending-tasks link opens all uncategorized bookings, any currency")
    }

    @Test
    fun `primary currency setting switches what the dashboard and reports total`() = runBlocking {
        data.txn(eur, today.withDayOfMonth(2), "2000.00", partner = "Employer")
        data.txn(usd, today.withDayOfMonth(3), "800.00", partner = "US client", currency = "USD")
        SettingsRepositoryImpl().setValue(Currencies.KEY_PRIMARY, "USD")

        val vm = DashboardViewModel(today = { today })
        vm.loadDashboard().join()
        assertEquals("USD", vm.state.value.currency)
        assertEquals(BigDecimal("500.00"), vm.state.value.totalBalance)
        assertEquals(BigDecimal("800.00"), vm.state.value.monthlyIncome)

        val report = ReportService().generateTaxReport(2026)
        assertEquals("USD", report.currency)
        assertEquals(BigDecimal("800.00"), report.totalIncome)
        assertEquals(1, report.otherCurrencyBookings)
    }

    @Test
    fun `reports count bookings in other currencies instead of adding them`() {
        data.txn(eur, LocalDate.of(2026, 3, 1), "-30.00", partner = "REWE")
        data.txn(usd, LocalDate.of(2026, 3, 2), "-70.00", partner = "Walmart", currency = "USD")

        val report = ReportService().buildTaxReport(2026, data.transactionRepo.findItems())

        assertEquals("EUR", report.currency)
        assertEquals(BigDecimal("30.00"), report.totalExpenses)
        assertEquals(1, report.otherCurrencyBookings)
        assertEquals(1, report.transactionCount)
    }

    @Test
    fun `filters, balances and recurring payments are per currency`() {
        assertEquals(BigDecimal("1000.00"), data.accountRepo.getTotalBalance("EUR"))
        assertEquals(BigDecimal("500.00"), data.accountRepo.getTotalBalance("USD"))

        // Same subscription billed monthly in two currencies: two recurring payments, not one with a mixed median
        (0..3).forEach { m ->
            val day = LocalDate.of(2026, 6 + m, 5)
            data.txn(eur, day, "-9.99", partner = "Spotify")
            data.txn(usd, day, "-11.99", partner = "Spotify", currency = "USD")
        }
        assertEquals(4, data.transactionRepo.findItems(TransactionFilter(currency = "USD")).size)

        val recurring = RecurringDetector.detect(data.transactionRepo.findItems(), LocalDate.of(2026, 9, 20))
        assertEquals(
            setOf("EUR" to BigDecimal("-9.99"), "USD" to BigDecimal("-11.99")),
            recurring.map { it.currency to it.typicalAmount }.toSet(),
        )
    }

    @Test
    fun `totals are formatted side by side, primary first`() {
        val totals = Currencies.totals(
            listOf("USD" to "5.00", "EUR" to "10.00", "USD" to "2.50"),
            "EUR",
            { it.first },
            { BigDecimal(it.second) },
        )
        assertEquals(listOf("EUR", "USD"), totals.keys.toList())
        assertEquals(BigDecimal("7.50"), totals["USD"])
    }
}
