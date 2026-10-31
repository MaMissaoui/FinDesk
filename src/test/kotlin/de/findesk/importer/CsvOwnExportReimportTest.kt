package de.findesk.importer

import de.findesk.document.ReportService
import de.findesk.i18n.AppLanguage
import de.findesk.i18n.AppStrings
import de.findesk.model.TransactionItem
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.math.BigDecimal
import java.time.LocalDate

class CsvOwnExportReimportTest {
    @TempDir
    lateinit var tmp: File

    private val service = ReportService()

    private val bookings = listOf(
        txn(1, "2026-10-01", "2600.00", "Beispiel AG", "Gehalt Oktober"),
        txn(2, "2026-10-02", "-42.50", "Beispiel Markt", "Einkauf 4711"),
        txn(3, "2026-10-03", "-7.25", "Beispiel Laden", "Einkauf \"Spezial\", Teil 2"),
    )

    private fun txn(id: Int, date: String, amount: String, partner: String, purpose: String) = TransactionItem(
        id = id,
        accountId = 1,
        accountIban = "DE89370400440532013000",
        date = LocalDate.parse(date),
        amount = BigDecimal(amount),
        currency = "EUR",
        partnerName = partner,
        purpose = purpose,
        categoryId = null,
        categoryName = null,
        categoryColor = null,
        isTaxRelevant = false,
        documentCount = 0,
    )

    @Test
    fun `own export round trips in every language`() = runBlocking {
        for (language in listOf(AppLanguage.EN, AppLanguage.DE, AppLanguage.FR)) {
            try {
                AppStrings.set(language)
                val file = File(tmp, "own-${language.name.lowercase()}.csv")
                assertTrue(service.exportToCsv(bookings, file))

                val parsed = CsvStatementParser.parse(file)

                assertEquals(3, parsed.bookings.size, "$language: ${parsed.skipped}")
                assertTrue(parsed.skipped.isEmpty(), "$language: ${parsed.skipped}")
                bookings.zip(parsed.bookings).forEach { (exported, reimported) ->
                    assertEquals(exported.date, reimported.date, "$language date")
                    assertEquals(0, exported.amount.compareTo(reimported.amount), "$language amount")
                    assertEquals(exported.currency, reimported.currency, "$language currency")
                    assertEquals(exported.partnerName, reimported.partnerName, "$language partner")
                    assertEquals(exported.purpose, reimported.purpose, "$language purpose")
                }
            } finally {
                AppStrings.set(AppLanguage.EN)
            }
        }
    }

    @Test
    fun `own export round trips the balance column in every language`() = runBlocking {
        val balances = mapOf(
            1 to BigDecimal("2600.00"),
            2 to BigDecimal("2557.50"),
            3 to BigDecimal("2550.25"),
        )
        for (language in listOf(AppLanguage.EN, AppLanguage.DE, AppLanguage.FR)) {
            try {
                AppStrings.set(language)
                val file = File(tmp, "own-balances-${language.name.lowercase()}.csv")
                assertTrue(service.exportToCsv(bookings, file, balances))

                val parsed = CsvStatementParser.parse(file)

                assertEquals(3, parsed.bookings.size, "$language: ${parsed.skipped}")
                bookings.zip(parsed.bookings).forEach { (exported, reimported) ->
                    val parsedBalance = reimported.balanceAfter
                    assertNotNull(parsedBalance, "$language balance")
                    assertEquals(0, balances.getValue(exported.id).compareTo(parsedBalance!!), "$language balance")
                }
            } finally {
                AppStrings.set(AppLanguage.EN)
            }
        }
    }

    @Test
    fun `bank headers keep their mapping`() {
        assertEquals(
            CsvMapping(date = 0, valueDate = 1, amount = 8, partner = 4, payer = 3, partnerIban = 7, purpose = 5),
            CsvStatementParser.detectMapping(
                listOf(
                    "Buchungsdatum", "Wertstellung", "Status", "Zahlungspflichtige*r", "Zahlungsempfänger*in",
                    "Verwendungszweck", "Umsatztyp", "IBAN", "Betrag (€)", "Gläubiger-ID", "Mandatsreferenz", "Kundenreferenz",
                ),
            ),
        )
        assertEquals(
            CsvMapping(date = 0, amount = 4, partner = 1, purpose = 3),
            CsvStatementParser.detectMapping(listOf("Datum", "Name", "Partner", "Verwendungszweck", "Betrag")),
        )
        assertEquals(
            CsvMapping(date = 0, amount = 3, partner = 1, purpose = 2, currency = 4),
            CsvStatementParser.detectMapping(listOf("Date", "Payee", "Purpose", "Amount", "Currency", "Partner")),
        )
    }

    @Test
    fun `fallbacks are exact match only`() {
        assertNull(CsvStatementParser.detectMapping(listOf("Datum", "Partnerbank", "Betrag")).partner)
        assertEquals(3, CsvStatementParser.detectMapping(listOf("Date", "Montants", "Libellé", "Amount")).amount)
    }

    @Test
    fun `bank norwegian rule still clears the currency`() {
        val mapping = CsvStatementParser.detectMapping(listOf("BookDate", "Amount", "Currency Amount", "Currency", "Devise"))
        assertNull(mapping.currency)
    }
}
