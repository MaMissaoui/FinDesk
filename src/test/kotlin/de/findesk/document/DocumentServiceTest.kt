package de.findesk.document

import de.findesk.TestData
import de.findesk.TestDatabase
import kotlinx.coroutines.runBlocking
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.math.BigDecimal
import java.time.LocalDate

class DocumentServiceTest {
    @TempDir
    lateinit var tmp: File

    private lateinit var data: TestData
    private lateinit var service: DocumentService
    private lateinit var docsDir: File

    @BeforeEach
    fun setup() {
        TestDatabase.init()
        data = TestData()
        docsDir = File(tmp, "documents")
        // No OCR engine: PDF text layers still work via PDFBox
        service = DocumentService(ocrService = OcrService(engine = null), documentsDir = docsDir)
    }

    private fun textPdf(name: String, vararg lines: String): File {
        val file = File(tmp, name)
        PDDocument().use { doc ->
            val page = PDPage()
            doc.addPage(page)
            PDPageContentStream(doc, page).use { cs ->
                cs.beginText()
                cs.setFont(PDType1Font(Standard14Fonts.FontName.HELVETICA), 12f)
                cs.setLeading(16f)
                cs.newLineAtOffset(50f, 700f)
                lines.forEach {
                    cs.showText(it)
                    cs.newLine()
                }
                cs.endText()
            }
            doc.save(file)
        }
        return file
    }

    @Test
    fun `upload copies file, extracts pdf text and makes it searchable`() = runBlocking {
        val pdf = textPdf("invoice.pdf", "Rechnung Nr. 4711", "Datum: 12.03.2026", "Gesamtbetrag 89,99 EUR")

        val result = service.uploadDocument(pdf)

        assertEquals(OcrService.Source.TEXT_LAYER, result.ocr?.source)
        assertTrue(result.document.hasOcr)
        assertTrue(File(result.document.filePath).startsWith(docsDir))
        assertEquals(listOf(result.document.id), service.searchDocuments("4711").map { it.id })
        assertEquals(listOf(result.document.id), service.searchDocuments("invoice").map { it.id })
        assertTrue(service.searchDocuments("nothing-like-this").isEmpty())
    }

    @Test
    fun `suggests the transaction with the receipt total near the receipt date`() = runBlocking {
        val acc = data.account()
        val match = data.txn(acc, LocalDate.of(2026, 3, 13), "-89.99", partner = "Amazon")
        data.txn(acc, LocalDate.of(2026, 3, 13), "-12.00", partner = "Other")
        data.txn(acc, LocalDate.of(2025, 3, 13), "-89.99", partner = "Year earlier")
        val doc = service.uploadDocument(textPdf("r.pdf", "Datum: 12.03.2026", "Zwischensumme 75,62", "Gesamtbetrag 89,99 EUR")).document

        val suggestions = service.suggestTransactions(doc.id)

        assertEquals(match, suggestions.first().transaction.id)
        assertTrue(suggestions.none { it.transaction.partnerName == "Year earlier" })
        assertTrue(suggestions.none { it.transaction.partnerName == "Other" })
    }

    @Test
    fun `link, unlink and delete`() = runBlocking {
        val acc = data.account()
        val txn = data.txn(acc, LocalDate.of(2026, 3, 13), "-10.00", partner = "Shop")
        val doc = service.uploadDocument(textPdf("r.pdf", "Some receipt text here that is long enough")).document

        assertTrue(service.linkToTransaction(doc.id, txn))
        assertEquals(1, data.transactionRepo.findItems().first().documentCount)
        assertTrue(service.getUnlinkedDocuments().isEmpty())

        assertTrue(service.unlinkFromTransaction(doc.id))
        assertEquals(1, service.getUnlinkedDocuments().size)

        assertTrue(service.deleteDocument(doc.id))
        assertFalse(File(doc.filePath).exists())
        assertTrue(service.listDocuments().isEmpty())
        assertNull(service.getOcrText(doc.id))
    }

    @Test
    fun `rejects unsupported and missing files`() {
        val txt = File(tmp, "notes.txt").apply { writeText("hi") }
        assertThrows<IllegalArgumentException> { runBlocking { service.uploadDocument(txt) } }
        assertThrows<IllegalArgumentException> { runBlocking { service.uploadDocument(File(tmp, "missing.pdf")) } }
    }

    @Test
    fun `images without an OCR engine are stored without text`() = runBlocking {
        val png = File(tmp, "scan.png")
        javax.imageio.ImageIO.write(java.awt.image.BufferedImage(10, 10, java.awt.image.BufferedImage.TYPE_INT_RGB), "png", png)
        val result = service.uploadDocument(png)
        assertNull(result.ocr)
        assertFalse(result.document.hasOcr)
    }

    @Test
    fun `receipt parser handles german and english formats`() {
        val de = ReceiptParser.parse("EDEKA\n03.10.2026 14:22\nArtikel 1.234,50\nSUMME EUR 1.299,95")
        assertEquals(BigDecimal("1299.95"), de.total)
        assertEquals(LocalDate.of(2026, 10, 3), de.date)
        assertFalse(de.amounts.contains(BigDecimal("3.10")), "date fragments are not amounts")

        val en = ParserCase("Invoice 2026-01-05\nSubtotal 1,000.00\nTotal 1,190.00").fields
        assertEquals(BigDecimal("1190.00"), en.total)
        assertEquals(LocalDate.of(2026, 1, 5), en.date)

        assertNull(ReceiptParser.parse("no numbers here").total)
    }

    private class ParserCase(text: String) {
        val fields = ReceiptParser.parse(text)
    }
}
