package de.findesk.document

import de.findesk.TestData
import de.findesk.TestDatabase
import de.findesk.importer.CsvStatementParser
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.LocalDate

/** PayPal shops in reports and in the localized CSV headers the importer sees again. */
class ShopNamesTest {
    private lateinit var data: TestData
    private val service = ReportService()

    @BeforeEach
    fun setup() {
        TestDatabase.init()
        data = TestData()
    }

    @Test
    fun `monthly report titles a PayPal top expense with the shop`() = runBlocking {
        val account = data.account()
        val online = data.category("Online shopping")
        val today = LocalDate.now()
        data.txn(
            account,
            today,
            "-250.00",
            partner = "PayPal Europe S.a.r.l. et Cie S.C.A",
            purpose = "PP.1234.PP . Beispiel Shop GmbH, Ihr Einkauf bei Beispiel Shop GmbH",
            categoryId = online,
        )
        data.txn(account, today, "-10.00", partner = "Beispiel Bäckerei", purpose = "Brötchen")

        val report = service.generateMonthlyReport(today.year, today.monthValue)

        assertEquals("PayPal · Beispiel Shop GmbH", report.topExpenses.first().description)
    }

    @Test
    fun `localized shop headers are not picked up as import text columns`() {
        val shop = mapOf("Shop" to 8, "Händler" to 8, "Marchand" to 8)
        shop.forEach { (header, index) ->
            val headers = listOf("Date", "Partner", "Purpose", "Category", "Amount", "Currency", "Account", "Balance", header)
            val mapping = CsvStatementParser.detectMapping(headers)

            assertNotEquals(index, mapping.partner, "$header is not the partner")
            assertNotEquals(index, mapping.purpose, "$header is not the purpose")
            assertNotEquals(index, mapping.payer, "$header is not the payer")
        }
    }
}
