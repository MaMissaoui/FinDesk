package de.findesk.document

import de.findesk.TestData
import de.findesk.TestDatabase
import kotlinx.coroutines.runBlocking
import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.LocalDate

/** The tax report PDF titles a tax-relevant PayPal booking with the provider and the shop. */
class PdfReportExporterTitleTest {
    private lateinit var data: TestData
    private val service = ReportService()

    @TempDir
    lateinit var tmp: File

    @BeforeEach
    fun setup() {
        TestDatabase.init()
        data = TestData()
    }

    @Test
    fun `pdf titles a tax relevant PayPal booking with the shop`() = runBlocking {
        val account = data.account()
        val online = data.category("Online shopping", taxRelevant = true)
        val today = LocalDate.now()
        data.txn(
            account,
            today,
            "-250.00",
            partner = "PayPal Europe S.a.r.l. et Cie S.C.A",
            purpose = "PP.1234.PP . Beispiel Shop GmbH, Ihr Einkauf bei Beispiel Shop GmbH",
            categoryId = online,
        )

        val report = service.generateTaxReport(today.year)
        val file = File(tmp, "tax-title.pdf")
        assertTrue(service.exportToPdf(report, file))

        val text = Loader.loadPDF(file).use { PDFTextStripper().getText(it) }
        assertTrue(text.contains("PayPal · Beispiel Shop GmbH"), "the tax booking row uses the display title: $text")
    }
}
