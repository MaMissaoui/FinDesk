package de.findesk.document

import de.findesk.TestData
import de.findesk.TestDatabase
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
        assertTrue(text.contains("\"'=HYPERLINK(\"\"http://evil\"\")\""), text)
        assertTrue(text.contains("\"Monitor, 27\"\"\""), text)
        assertTrue(text.contains(",-250.00,EUR,"), "amounts keep their sign: $text")
        assertEquals(4, text.trim().lines().size, "header + 3 rows")
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
    fun `csv format escapes only when needed`() {
        assertEquals("plain", CsvFormat.escape("plain"))
        assertEquals("\"a,b\"", CsvFormat.escape("a,b"))
        assertEquals("'+49 123", CsvFormat.text("+49 123"))
        assertEquals("", CsvFormat.text(""))
    }
}
