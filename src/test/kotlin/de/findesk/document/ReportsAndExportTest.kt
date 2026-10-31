package de.findesk.document

import de.findesk.TestData
import de.findesk.TestDatabase
import de.findesk.i18n.AppLanguage
import de.findesk.i18n.AppStrings
import de.findesk.importer.CsvStatementParser
import de.findesk.model.TransactionItem
import kotlinx.coroutines.runBlocking
import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.math.BigDecimal
import java.time.LocalDate

class ReportsAndExportTest {
    private lateinit var data: TestData
    private val service = ReportService()

    @TempDir
    lateinit var tmp: File

    @BeforeEach
    fun setup() {
        TestDatabase.init()
        data = TestData()
        val acc = data.account()
        val food = data.category("Food")
        val office = data.category("Arbeitsmittel", taxRelevant = true)
        data.txn(acc, LocalDate.of(2026, 1, 10), "-40.00", partner = "REWE", categoryId = food)
        data.txn(acc, LocalDate.of(2026, 2, 10), "-250.00", partner = "=HYPERLINK(\"http://evil\")", purpose = "Monitor, 27\"", categoryId = office)
        data.txn(acc, LocalDate.of(2026, 2, 28), "3000.00", partner = "Employer GmbH")
        data.txn(acc, LocalDate.of(2025, 12, 31), "-1.00", partner = "Last year")
    }

    @Test
    fun `tax report includes categorized transactions (regression)`() = runBlocking {
        val report = service.generateTaxReport(2026)

        assertEquals(3, report.transactionCount)
        assertEquals(BigDecimal("3000.00"), report.totalIncome)
        assertEquals(BigDecimal("290.00"), report.totalExpenses)
        assertEquals(BigDecimal("2710.00"), report.netIncome)
        assertEquals(mapOf("Arbeitsmittel" to BigDecimal("250.00")), report.taxRelevantExpenses)
        assertEquals(BigDecimal("250.00"), report.taxRelevantTotal)
        assertEquals(BigDecimal("250.00"), report.monthlyBreakdown[1].expenses)
        assertEquals(BigDecimal("3000.00"), report.monthlyBreakdown[1].income)
        assertEquals(2, report.categoriesUsed)
    }

    @Test
    fun `category trend averages over the requested months`() = runBlocking {
        val foodId = data.categoryRepo.findAllItems().first { it.name == "Food" }.id
        val trend = service.getCategoryTrends(foodId, months = 3, today = LocalDate.of(2026, 3, 15))
        assertEquals(listOf(1, 2, 3), trend.monthlyData.map { it.month })
        assertEquals(BigDecimal("40.00"), trend.monthlyData[0].amount)
        assertEquals(BigDecimal("13.33"), trend.averageMonthly)
    }

    @Test
    fun `csv export quotes fields and neutralises formulas`() = runBlocking {
        val file = File(tmp, "out.csv")
        assertTrue(service.exportYearToCsv(2026, file))

        val text = file.readText(Charsets.UTF_8)
        assertEquals(0xFEFF, text[0].code, "UTF-8 BOM")
        assertTrue(text.substring(1).startsWith("Date,Partner,Purpose"))
        assertTrue(text.substring(1).lineSequence().first().endsWith(",Shop"), "the Shop column is last: $text")
        assertTrue(text.contains("\"'=HYPERLINK(\"\"http://evil\"\")\""), text)
        assertTrue(text.contains("\"Monitor, 27\"\"\""), text)
        assertTrue(text.contains(",-250.00,EUR,"), "amounts keep their sign: $text")
        assertEquals(4, text.trim().lines().size, "header + 3 rows")
    }

    @Test
    fun `csv export neutralises currency and account iban formulas`() = runBlocking {
        val file = File(tmp, "out.csv")
        val txn = TransactionItem(
            id = 1,
            accountId = 1,
            accountIban = "@TACKER-123",
            date = LocalDate.of(2026, 3, 10),
            amount = BigDecimal("-10.00"),
            currency = "=1+1",
            partnerName = null,
            purpose = null,
            categoryId = null,
            categoryName = null,
            categoryColor = null,
            isTaxRelevant = false,
            documentCount = 0,
        )
        assertTrue(service.exportToCsv(listOf(txn), file))

        val text = file.readText(Charsets.UTF_8)
        assertTrue(text.contains(",'=1+1,"), "currency formula is neutralised: $text")
        assertTrue(text.contains(",'@TACKER-123"), "account IBAN formula is neutralised: $text")
    }

    @Test
    fun `pdf export writes a readable report`() = runBlocking {
        val file = File(tmp, "report.pdf")
        val report = service.generateTaxReport(2026)
        assertTrue(service.exportToPdf(report, file))

        val text = Loader.loadPDF(file).use { PDFTextStripper().getText(it) }
        assertTrue(text.contains("FinDesk Tax Report 2026"))
        assertTrue(text.contains("Arbeitsmittel"))
        assertTrue(text.contains("€3,000.00"))
    }

    @Test
    fun `csv export adds the shop last and keeps the raw partner`() = runBlocking {
        val paypal = paypalBooking(id = 1)
        val plain = plainBooking(id = 2)
        val file = File(tmp, "shop.csv")
        assertTrue(service.exportToCsv(listOf(paypal, plain), file))

        val records = CsvStatementParser.splitRecords(file.readText(Charsets.UTF_8).removePrefix("\uFEFF"), ',')
        assertEquals("Date,Partner,Purpose,Category,Amount,Currency,Account,Shop", records[0].joinToString(","))
        assertEquals("PayPal Europe S.a.r.l. et Cie S.C.A", records[1][1], "the raw partner stays in column 2")
        assertEquals("Beispiel Shop GmbH", records[1].last(), "the shop is the last field")
        assertEquals("", records[2].last(), "a non-PayPal booking has no shop")
    }

    @Test
    fun `csv export puts the shop after the balance`() = runBlocking {
        val paypal = paypalBooking(id = 1)
        val plain = plainBooking(id = 2)
        val file = File(tmp, "shop-balances.csv")
        assertTrue(service.exportToCsv(listOf(paypal, plain), file, balances = mapOf(paypal.id to BigDecimal("100.00"), plain.id to BigDecimal("90.00"))))

        val records = CsvStatementParser.splitRecords(file.readText(Charsets.UTF_8).removePrefix("\uFEFF"), ',')
        assertEquals("Date,Partner,Purpose,Category,Amount,Currency,Account,Balance,Shop", records[0].joinToString(","))
        assertEquals("100.00", records[1][records[1].size - 2], "the balance stays before the shop")
        assertEquals("Beispiel Shop GmbH", records[1].last())
        assertEquals("90.00", records[2][records[2].size - 2])
        assertEquals("", records[2].last())
    }

    @Test
    fun `localized shop headers never map to the partner or purpose column`() = runBlocking {
        val paypal = paypalBooking(id = 1)
        try {
            AppLanguage.values().forEach { language ->
                AppStrings.set(language)
                val file = File(tmp, "shop-${language.name}.csv")
                assertTrue(service.exportToCsv(listOf(paypal), file))
                val header = CsvStatementParser.splitRecords(file.readText(Charsets.UTF_8).removePrefix("\uFEFF"), ',').first()
                val mapping = CsvStatementParser.detectMapping(header)

                assertEquals(AppStrings[de.findesk.i18n.StringKey.ColShop], header.last(), "${language.name}: the Shop header")
                // Appending the Shop column changes nothing the importer detects in the export's header
                assertEquals(CsvStatementParser.detectMapping(header.dropLast(1)), mapping, "${language.name}: mapping without vs. with Shop")
                assertEquals(0, mapping.date, "${language.name}: date")
                assertNotEquals(header.lastIndex, mapping.partner, "${language.name}: the Shop column is not the partner")
                assertNotEquals(header.lastIndex, mapping.purpose, "${language.name}: the Shop column is not the purpose")
                assertNotEquals(header.lastIndex, mapping.payer, "${language.name}: the Shop column is not the payer")
            }
        } finally {
            AppStrings.set(AppLanguage.EN)
        }
    }

    @Test
    fun `the shop header leaves recognized import columns where they were`() {
        val english = CsvStatementParser.detectMapping(listOf("Date", "Payee", "Purpose", "Amount", "Shop"))
        assertEquals(1, english.partner, "EN partner stays on its column")
        assertEquals(2, english.purpose, "EN purpose stays on its column")
        assertNotEquals(4, english.partner)
        assertNotEquals(4, english.purpose)

        val german = CsvStatementParser.detectMapping(listOf("Buchungstag", "Zahlungsempfänger", "Verwendungszweck", "Betrag", "Händler"))
        assertEquals(1, german.partner, "DE partner stays on its column")
        assertEquals(2, german.purpose, "DE purpose stays on its column")
        assertNotEquals(4, german.partner)
        assertNotEquals(4, german.purpose)
    }

    private fun paypalBooking(id: Int) = TransactionItem(
        id = id,
        accountId = 1,
        accountIban = "DE89370400440532013000",
        date = LocalDate.of(2026, 3, 10),
        amount = BigDecimal("-19.99"),
        currency = "EUR",
        partnerName = "PayPal Europe S.a.r.l. et Cie S.C.A",
        purpose = "PP.1234.PP . Beispiel Shop GmbH, Ihr Einkauf bei Beispiel Shop GmbH",
        categoryId = null,
        categoryName = null,
        categoryColor = null,
        isTaxRelevant = false,
        documentCount = 0,
    )

    private fun plainBooking(id: Int) = TransactionItem(
        id = id,
        accountId = 1,
        accountIban = "DE89370400440532013000",
        date = LocalDate.of(2026, 3, 11),
        amount = BigDecimal("-4.50"),
        currency = "EUR",
        partnerName = "Beispiel Bäckerei",
        purpose = "Brötchen",
        categoryId = null,
        categoryName = null,
        categoryColor = null,
        isTaxRelevant = false,
        documentCount = 0,
    )

    @Test
    fun `csv format escapes only when needed`() {
        assertEquals("plain", CsvFormat.escape("plain"))
        assertEquals("\"a,b\"", CsvFormat.escape("a,b"))
        assertEquals("'+49 123", CsvFormat.text("+49 123"))
        assertEquals("", CsvFormat.text(""))
    }
}
