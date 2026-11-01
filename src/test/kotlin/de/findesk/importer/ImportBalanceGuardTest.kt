package de.findesk.importer

import de.findesk.TestData
import de.findesk.TestDatabase
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate

class ImportBalanceGuardTest {
    private lateinit var data: TestData
    private val service = StatementImportService(today = { LocalDate.of(2026, 5, 20) })

    @BeforeEach
    fun setup() {
        TestDatabase.init()
        data = TestData()
    }

    private fun statement(date: LocalDate, balance: String = "100.00") = ParsedStatement(
        format = StatementFormat.CSV,
        bookings = listOf(
            ImportedBooking(
                date = date,
                amount = BigDecimal("10.00"),
                currency = "EUR",
                balanceAfter = BigDecimal(balance),
            ),
        ),
    )

    private fun balance(account: Int): BigDecimal = data.accountRepo.findAllItems().single { it.id == account }.balance

    @Test
    fun `an old file never replaces a balance typed on an account without bookings`() = runBlocking {
        val account = data.account(iban = "DE89370400440532013000", balance = "500.00")
        val old = statement(LocalDate.of(2019, 12, 31))

        assertFalse(service.balanceUpdateAllowed(account, old))

        val report = service.import(account, old, updateBalance = true)
        assertEquals(1, report.imported)
        assertNull(report.balanceUpdated)
        assertEquals(BigDecimal("500.00"), balance(account))
    }

    @Test
    fun `a zero balance on an account without bookings may still be set`() {
        val account = data.account(iban = "DE89370400440532013000", balance = "0")

        assertTrue(service.balanceUpdateAllowed(account, statement(LocalDate.of(2019, 12, 31))))
    }

    @Test
    fun `a zero balance set today is not empty and rejects an old file`() = runBlocking {
        val account = data.account(iban = "DE89370400440532013000", balance = "0")
        data.accountRepo.updateBalance(account, BigDecimal.ZERO)
        val old = statement(LocalDate.of(2019, 12, 31))

        assertFalse(service.balanceUpdateAllowed(account, old))
        assertTrue(service.balanceUpdateAllowed(account, statement(LocalDate.now())))

        val report = service.import(account, old, updateBalance = true)
        assertNull(report.balanceUpdated)
        assertEquals(0, balance(account).compareTo(BigDecimal.ZERO))
    }

    @Test
    fun `a balance set by updateBalance only accepts a file at least as recent as the update`() {
        val account = data.account(iban = "DE89370400440532013000", balance = "500.00")
        data.accountRepo.updateBalance(account, BigDecimal("500.00"))
        val today = LocalDate.now()

        assertTrue(service.balanceUpdateAllowed(account, statement(today)))
        assertFalse(service.balanceUpdateAllowed(account, statement(today.minusDays(1))))
    }

    @Test
    fun `a typed balance accepts a file 45 days old`() {
        val account = data.account(iban = "DE89370400440532013000", balance = "500.00")

        assertTrue(service.balanceUpdateAllowed(account, statement(LocalDate.of(2026, 4, 5))))
    }

    @Test
    fun `a typed balance rejects a file 46 days old`() {
        val account = data.account(iban = "DE89370400440532013000", balance = "500.00")

        assertFalse(service.balanceUpdateAllowed(account, statement(LocalDate.of(2026, 4, 4))))
    }

    @Test
    fun `a card account with a typed balance rejects an old file`() {
        val offline = data.bankAccessRepo.createOffline("Cards Bank").id.value
        val card = data.accountRepo.create(offline, "CARD1234", balance = BigDecimal("100.00"), accountType = "credit_card").id.value

        assertFalse(service.balanceUpdateAllowed(card, statement(LocalDate.of(2019, 12, 31))))
    }

    @Test
    fun `an account with bookings still compares the file with its newest booking`() {
        val account = data.account(iban = "DE89370400440532013000", balance = "1000.00")
        data.txn(account, LocalDate.of(2026, 10, 1), "-10.00")

        assertTrue(service.balanceUpdateAllowed(account, statement(LocalDate.of(2026, 10, 1))))
        assertFalse(service.balanceUpdateAllowed(account, statement(LocalDate.of(2026, 9, 30))))
    }
}
