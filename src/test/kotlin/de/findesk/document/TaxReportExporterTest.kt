package de.findesk.document

import de.findesk.i18n.AppLanguage
import de.findesk.i18n.AppStrings
import de.findesk.importer.XlsxReader
import de.findesk.model.TaxSource
import de.findesk.model.TransactionItem
import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import java.io.File
import java.math.BigDecimal
import java.time.LocalDate
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The Tax bookings exports, built from synthetic [ReportService.TaxBookings]: the Excel workbook's two
 * sheets (localized) and the paginated PDF (text, footers, column fitting, WinAnsi-safe text). Pure data,
 * no database — the exporter only reads the report.
 */
class TaxReportExporterTest {
    @TempDir
    lateinit var tmp: File

    private val exporter = TaxReportExporter()
    private val day = LocalDate.of(2026, 6, 1)

    private fun item(
        id: Int,
        amount: String,
        date: LocalDate = day,
        categoryId: Int? = 20,
        categoryName: String? = "Spenden",
        taxSource: TaxSource = TaxSource.CATEGORY,
        partner: String? = null,
        purpose: String? = null,
        documentCount: Int = 0,
    ): TransactionItem = TransactionItem(
        id = id,
        accountId = 1,
        accountIban = "DE89370400440532013000",
        date = date,
        amount = BigDecimal(amount),
        currency = "EUR",
        partnerName = partner,
        purpose = purpose,
        categoryId = categoryId,
        categoryName = categoryName,
        categoryColor = "#9C27B0",
        isTaxRelevant = true,
        documentCount = documentCount,
        taxSource = taxSource,
    )

    /** Two income and two deduction groups over [incomeCount] + [deductionCount] synthetic bookings. */
    private fun bookings(
        incomeCount: Int,
        deductionCount: Int,
        longDescription: Boolean = false,
        unencodablePartner: Boolean = false,
        otherCurrency: Int = 0,
    ): ReportService.TaxBookings {
        val salary = (1..incomeCount).map { id ->
            item(id, "1200.00", date = day.minusDays((incomeCount - id).toLong()), categoryId = 10, categoryName = "Gehalt", purpose = "Gehalt $id")
        }
        val royalties = (1..incomeCount).map { id ->
            item(100 + id, "45.50", date = day.minusDays(id.toLong()), categoryId = 11, categoryName = "Tantiemen", purpose = "Auszahlung $id")
        }
        val donations = (1..deductionCount).map { id ->
            item(
                200 + id,
                "-30.00",
                date = day.minusDays(id.toLong()),
                categoryId = 20,
                categoryName = "Spenden",
                partner = if (unencodablePartner && id == 1) "Beispielhilfe ✓ e. V." else "Beispielhilfe e. V.",
                purpose = "Spende $id",
                documentCount = if (id == 1) 1 else 0,
            )
        }
        val craft = (1..deductionCount).map { id ->
            item(
                300 + id,
                "-85.20",
                date = day.minusDays((deductionCount - id).toLong()),
                categoryId = 21,
                categoryName = "Handwerk",
                partner = if (longDescription) {
                    "Beispielhaft lang benannte Handwerksfirma mit sehr ausführlichem Namen und Ort"
                } else {
                    "Beispiel Werk GmbH"
                },
                purpose = "Renovierung $id",
            )
        }
        fun group(categoryId: Int, name: String, bookings: List<TransactionItem>, income: Boolean): ReportService.TaxGroup = ReportService.TaxGroup(
            categoryId = categoryId,
            name = name,
            color = "#9C27B0",
            bookings = bookings,
            income = if (income) bookings.sumOf { it.amount } else BigDecimal.ZERO,
            expenses = if (income) BigDecimal.ZERO else bookings.sumOf { it.amount.abs() },
        )
        return ReportService.TaxBookings(
            from = LocalDate.of(2026, 1, 1),
            to = LocalDate.of(2026, 12, 31),
            currency = "EUR",
            incomeGroups = listOf(group(10, "Gehalt", salary, income = true), group(11, "Tantiemen", royalties, income = true)),
            deductionGroups = listOf(group(20, "Spenden", donations, income = false), group(21, "Handwerk", craft, income = false)),
            bookingCount = salary.size + royalties.size + donations.size + craft.size,
            otherCurrencyBookings = otherCurrency,
        )
    }

    @Test
    fun `excel has one overview row per group plus totals and one bookings row per booking`() {
        val info = bookings(incomeCount = 2, deductionCount = 3, otherCurrency = 2)
        val file = File(tmp, "tax.xlsx")
        exporter.exportXlsx(info, file)

        // The overview sheet: title, currency, header, one row per group, totals, then the hint
        val rows = XlsxReader.firstSheetRows(file.readBytes())
        assertEquals("Tax-relevant bookings 2026", rows[0][0])
        assertEquals("Currency: EUR", rows[1][0])
        assertEquals(listOf("Section", "Category", "Bookings", "Income", "Expenses", "Net", "With receipt"), rows[2])
        // Money numbers read back without trailing zeros (the reader normalizes), signed where negative
        assertEquals(listOf("Income", "Gehalt", "2", "2400", "0", "2400", "0"), rows[3])
        assertEquals(listOf("Income", "Tantiemen", "2", "91", "0", "91", "0"), rows[4])
        assertEquals(listOf("Deductions", "Spenden", "3", "0", "90", "-90", "1"), rows[5])
        assertEquals(listOf("Deductions", "Handwerk", "3", "0", "255.6", "-255.6", "0"), rows[6])
        assertEquals("Tax-relevant income", rows[7][1])
        assertEquals("2491", rows[7][3])
        assertEquals("Deductions", rows[8][1])
        assertEquals("345.6", rows[8][4])
        assertEquals("Net income", rows[9][1])
        assertEquals("2145.4", rows[9][5])
        assertEquals("Receipts", rows[10][1])
        assertEquals("1 of 10", rows[10][2])
        assertTrue(rows[11][0].startsWith("All amounts in EUR"), "the other-currency hint: ${rows[11][0]}")

        // The bookings sheet, read from its XML part: header plus one row per booking
        ZipFile(file).use { zip ->
            val sheet = parse(zip.getInputStream(zip.getEntry("xl/worksheets/sheet2.xml")).readBytes())
            val sheetRows = elements(sheet).filter { it.localName == "row" }
            assertEquals(1 + info.bookingCount, sheetRows.size)
            assertEquals(
                listOf("Section", "Category", "Date", "Partner", "Shop", "Purpose", "Account", "Amount", "Currency", "Receipt", "Source"),
                ('A'..'K').map { cellText(sheet, "$it" + "1") },
                "the Shop column sits directly after the Partner",
            )
            val firstDonation = 2 + 4 // after the header and the four income rows
            assertEquals("Deductions", cellText(sheet, "A$firstDonation"))
            assertEquals("Spenden", cellText(sheet, "B$firstDonation"))
            assertEquals("2026-05-31", dateCell(sheet, "C$firstDonation"), "the date is a real date cell")
            assertEquals("3", cellStyle(sheet, "C$firstDonation"), "the date format")
            assertEquals("Beispielhilfe e. V.", cellText(sheet, "D$firstDonation"))
            assertEquals("Spende 1", cellText(sheet, "F$firstDonation"), "the raw purpose keeps its column")
            assertEquals("-30.00", cellValue(sheet, "H$firstDonation"), "signed amount as a number")
            assertEquals("2", cellStyle(sheet, "H$firstDonation"), "the money format")
            assertEquals("EUR", cellText(sheet, "I$firstDonation"))
            assertEquals("Yes", cellText(sheet, "J$firstDonation"), "the first donation carries a receipt")
            assertEquals("DE89370400440532013000", cellText(sheet, "G$firstDonation"), "the full IBAN as text")
            assertTrue(cellText(sheet, "K$firstDonation").contains("from category"), "the source names the deciding layer")
            val last = sheetRows.size
            assertEquals("Deductions", cellText(sheet, "A$last"), "the deductions come after the income")
            val filter = elements(sheet).first { it.localName == "autoFilter" }
            assertEquals("A1:K$last", filter.getAttribute("ref"), "the filter spans header and data")
            val pane = elements(sheet).first { it.localName == "pane" }
            assertEquals("frozen", pane.getAttribute("state"))
        }
    }

    /** One deduction group with a PayPal booking and a plain one; the PayPal partner stays raw. */
    private fun paypalBookings(): ReportService.TaxBookings {
        val paypal = item(
            id = 1,
            amount = "-19.99",
            partner = "PayPal Europe S.a.r.l. et Cie S.C.A",
            purpose = "PP.1234.PP . Beispiel Shop GmbH, Ihr Einkauf bei Beispiel Shop GmbH",
        )
        val plain = item(id = 2, amount = "-4.50", partner = "Beispiel Bäckerei", purpose = "Brötchen")
        val group = ReportService.TaxGroup(
            categoryId = 20,
            name = "Spenden",
            color = "#9C27B0",
            bookings = listOf(paypal, plain),
            income = BigDecimal.ZERO,
            expenses = BigDecimal("24.49"),
        )
        return ReportService.TaxBookings(
            from = LocalDate.of(2026, 1, 1),
            to = LocalDate.of(2026, 12, 31),
            currency = "EUR",
            deductionGroups = listOf(group),
            bookingCount = 2,
        )
    }

    @Test
    fun `excel shows the shop after the partner and keeps the raw partner`() {
        val info = paypalBookings()
        val file = File(tmp, "tax-shop.xlsx")
        exporter.exportXlsx(info, file)

        ZipFile(file).use { zip ->
            val sheet = parse(zip.getInputStream(zip.getEntry("xl/worksheets/sheet2.xml")).readBytes())
            assertEquals("PayPal Europe S.a.r.l. et Cie S.C.A", cellText(sheet, "D2"), "the raw partner")
            assertEquals("Beispiel Shop GmbH", cellText(sheet, "E2"), "the shop follows the partner")
            assertEquals("", cellText(sheet, "E3"), "a non-PayPal booking has an empty shop")
            val filter = elements(sheet).first { it.localName == "autoFilter" }
            assertEquals("A1:K3", filter.getAttribute("ref"))
        }
    }

    @Test
    fun `pdf titles a PayPal booking with the provider and the shop`() {
        exporter.buildPdf(paypalBookings()).use { doc ->
            val text = PDFTextStripper().getText(doc)
            assertTrue(text.contains("PayPal · Beispiel Shop GmbH"), "the WinAnsi periodcentered survives: $text")
        }
    }

    @Test
    fun `excel sheet names are localized in German`() {
        val file = File(tmp, "tax-de.xlsx")
        try {
            AppStrings.set(AppLanguage.DE)
            exporter.exportXlsx(bookings(incomeCount = 1, deductionCount = 1), file)
            ZipFile(file).use { zip ->
                val workbook = parse(zip.getInputStream(zip.getEntry("xl/workbook.xml")).readBytes())
                val names = elements(workbook).filter { it.localName == "sheet" }.map { it.getAttribute("name") }
                assertEquals(listOf("Übersicht", "Buchungen"), names)
            }
            assertEquals("Steuerrelevante Buchungen 2026", XlsxReader.firstSheetRows(file.readBytes())[0][0])
        } finally {
            AppStrings.set(AppLanguage.EN)
        }
    }

    @Test
    fun `pdf paginates with footers sections and fitted descriptions`() {
        val info = bookings(incomeCount = 40, deductionCount = 55, longDescription = true, unencodablePartner = true)
        exporter.buildPdf(info).use { doc ->
            val pages = doc.numberOfPages
            assertTrue(pages >= 3, "150 bookings fill several pages, was $pages")

            val text = PDFTextStripper().getText(doc)
            assertTrue(text.contains("Tax-relevant bookings 2026"), "the title")
            assertTrue(text.contains("Income"))
            assertTrue(text.contains("Deductions"))
            info.incomeGroups.plus(info.deductionGroups).forEach { group ->
                assertTrue(text.contains(group.name), "every group header: ${group.name}")
            }
            (1..pages).forEach { page -> assertTrue(text.contains("Page $page of $pages"), "the footer of page $page") }
            assertTrue(text.contains("…"), "the long description is cut with an ellipsis")
            assertTrue(text.contains("Beispielhilfe ? e. V."), "characters outside WinAnsi become ? without throwing")
        }
    }

    @Test
    fun `exportPdf writes a loadable file with the same page count`() {
        val info = bookings(incomeCount = 30, deductionCount = 30)
        val file = File(tmp, "tax.pdf")
        val pages: Int
        exporter.buildPdf(info).use { doc ->
            pages = doc.numberOfPages
            exporter.exportPdf(info, file)
        }
        assertTrue(file.isFile)
        Loader.loadPDF(file).use { loaded -> assertEquals(pages, loaded.numberOfPages) }
    }

    /** Namespace-aware, like [XlsxReader]: the parts use a default namespace, and localName is what we match. */
    private fun parse(bytes: ByteArray): Document = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder().parse(ByteArrayInputStream(bytes))

    private fun elements(node: Node): List<Element> = buildList {
        val children = node.childNodes
        for (index in 0 until children.length) {
            val child = children.item(index)
            if (child is Element) {
                add(child)
                addAll(elements(child))
            }
        }
    }

    private fun cell(sheet: Document, ref: String): Element = elements(sheet).first { it.localName == "c" && it.getAttribute("r") == ref }

    private fun cellText(sheet: Document, ref: String): String = elements(cell(sheet, ref)).first { it.localName == "t" }.textContent

    private fun cellValue(sheet: Document, ref: String): String = elements(cell(sheet, ref)).first { it.localName == "v" }.textContent

    private fun cellStyle(sheet: Document, ref: String): String = cell(sheet, ref).getAttribute("s")

    /** A date cell's Excel serial number, converted back to the day it means (base 1899-12-30). */
    private fun dateCell(sheet: Document, ref: String): String {
        val serial = elements(cell(sheet, ref)).first { it.localName == "v" }.textContent.toLong()
        return LocalDate.of(1899, 12, 30).plusDays(serial).toString()
    }
}
