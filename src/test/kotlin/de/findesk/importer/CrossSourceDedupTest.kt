package de.findesk.importer

import de.findesk.TestData
import de.findesk.TestDatabase
import de.findesk.banking.BankingService
import de.findesk.banking.FinTSAccount
import de.findesk.banking.FinTSTransaction
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.math.BigDecimal
import java.time.LocalDate

/** The same booking arriving from different sources (CSV, FinTS, manual) must not be stored twice. */
class CrossSourceDedupTest {
    @TempDir
    lateinit var tmp: File

    private lateinit var data: TestData
    private val importer = StatementImportService()
    private val banking = BankingService()
    private var account = 0
    private val iban = "DE89370400440532013000"
    private val day = LocalDate.of(2026, 10, 3)

    @BeforeEach
    fun setup() {
        TestDatabase.init()
        data = TestData()
        account = data.account(iban = iban, balance = "0")
    }

    private fun csv(vararg rows: String) = File(tmp, "s${System.nanoTime()}.csv").apply {
        writeText("Buchungstag;Betrag;Beguenstigter/Zahlungspflichtiger;Verwendungszweck\n" + rows.joinToString("\n") + "\n")
    }

    // FinTS text differs from CSV text for the same booking, as it does in practice
    private fun fints(date: LocalDate, amount: String, partner: String, purpose: String, balanceAfter: String) = FinTSTransaction(
        account = FinTSAccount(iban, "", "", bankName = "B", blz = "1", accountType = "checking"),
        date = date, valueDate = date, amount = BigDecimal(amount), partnerName = partner, partnerIban = null, partnerBic = null,
        purpose = purpose, transactionCode = null, reference = null, balanceAfter = BigDecimal(balanceAfter),
    )

    @Test
    fun `csv back-fill then sync of the same bookings adds nothing`() = runBlocking {
        importer.import(account, importer.read(csv("03.10.2026;-45,67;REWE;Kartenzahlung", "03.10.2026;-4,50;Bäcker;Kaffee")))

        val result = banking.importTransactions(
            account,
            listOf(
                fints(day, "-45.67", "REWE Markt GmbH", "REWE SAGT DANKE 1234", "954.33"),
                fints(day, "-4.50", "BAECKEREI SCHMIDT", "KARTENZAHLUNG 03.10", "949.83"),
            ),
        )
        assertEquals(0, result.imported)
        assertEquals(2, data.transactionRepo.findItems().size)
    }

    @Test
    fun `genuine extra repeats and different amounts are still imported`() = runBlocking {
        importer.import(account, importer.read(csv("03.10.2026;-4,50;Bäcker;Kaffee", "03.10.2026;-4,50;Bäcker;Kaffee")))

        val result = banking.importTransactions(
            account,
            listOf(
                fints(day, "-4.50", "BAECKEREI", "Kaffee", "100.00"),
                fints(day, "-4.50", "BAECKEREI", "Kaffee", "95.50"),
                fints(day, "-4.50", "BAECKEREI", "Kaffee", "91.00"), // a third coffee the CSV didn't have
                fints(day, "-4.70", "BAECKEREI", "Kaffee gross", "86.30"),
            ),
        )
        assertEquals(2, result.imported, "one extra coffee + the different amount")
        assertEquals(4, data.transactionRepo.findItems().size)
    }

    @Test
    fun `sync first, then a csv of the same period adds nothing`() = runBlocking {
        banking.importTransactions(account, listOf(fints(day, "-45.67", "REWE Markt GmbH", "REWE SAGT DANKE", "954.33")))

        val report = importer.import(account, importer.read(csv("03.10.2026;-45,67;REWE;Kartenzahlung", "02.10.2026;-10,00;Kiosk;Zeitung")))

        assertEquals(1, report.imported, "only the Kiosk booking is new")
        assertEquals(1, report.probableDuplicates)
        assertTrue(report.summary().contains("1 probable duplicate"), report.summary())
    }

    @Test
    fun `manual entry followed by an import of the same booking`() = runBlocking {
        data.txn(account, day, "-89.99", partner = "Amazon", purpose = "Monitor (manual)")
        val report = importer.import(account, importer.read(csv("03.10.2026;-89,99;AMAZON EU;Bestellung 303")))
        assertEquals(0, report.imported)
        assertEquals(1, report.probableDuplicates)
    }

    @Test
    fun `an old statement doesn't reset a newer balance`() = runBlocking {
        data.txn(account, LocalDate.of(2026, 10, 4), "-1.00", partner = "Newest")
        data.accountRepo.updateBalance(account, BigDecimal("5000.00"))
        val old = File(tmp, "old.csv").apply {
            writeText(
                "IBAN;$iban\n\nBuchung;Valuta;Auftraggeber/Empfänger;Buchungstext;Verwendungszweck;Saldo;Währung;Betrag;Währung\n" +
                    "15.01.2025;15.01.2025;Old shop;Lastschrift;Old;1.234,00;EUR;-10,00;EUR\n",
            )
        }
        val statement = importer.read(old)
        assertFalse(importer.balanceUpdateAllowed(account, statement))

        val report = importer.import(account, statement, updateBalance = true)

        assertEquals(1, report.imported)
        assertNull(report.balanceUpdated)
        assertEquals(BigDecimal("5000.00"), data.accountRepo.findAllItems().single().balance)
    }
}
