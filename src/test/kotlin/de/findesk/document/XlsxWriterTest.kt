package de.findesk.document

import de.findesk.importer.XlsxReader
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.io.File
import java.math.BigDecimal
import java.time.LocalDate
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The hand-written XLSX writer: a valid package that Excel can open and [XlsxReader] can read back, with
 * inline strings, no formulas, escaped and sanitized bank text, frozen headers with filters and names that
 * are always valid. Everything goes to a temp directory only.
 */
class XlsxWriterTest {
    @TempDir
    lateinit var tmp: File

    @Test
    fun `two sheets round trip through the reader`() {
        val file = File(tmp, "round-trip.xlsx")
        XlsxWriter().apply {
            sheet("Data", listOf(20.0, 12.0, 12.0, 14.0)) {
                row(bold("Text"), bold("Count"), bold("Money"), bold("Date"))
                row(text("=1+1"), number(BigDecimal(42)), money(BigDecimal("-82.36")), date(LocalDate.of(2026, 8, 3)))
                row(text("A & B <tag> \"q\" 'a'"), number(BigDecimal("0.10")), money(BigDecimal("1234.50")), date(LocalDate.of(1999, 12, 31)))
                row(text("first"), empty(), empty(), text("sparse"))
            }
            sheet("Extra", listOf(10.0)) {
                row(text("second sheet"))
            }
        }.write(file)

        assertTrue(file.isFile, "the workbook is at the target")
        val rows = XlsxReader.firstSheetRows(file.readBytes())
        assertEquals(listOf("Text", "Count", "Money", "Date"), rows[0], "the bold header row")
        assertEquals("=1+1", rows[1][0], "a text value that looks like a formula stays text")
        assertEquals("42", rows[1][1])
        assertEquals("-82.36", rows[1][2])
        assertEquals("2026-08-03", rows[1][3], "a date cell reads back as an ISO date")
        assertEquals("A & B <tag> \"q\" 'a'", rows[2][0], "XML escapes round trip")
        assertEquals("0.1", rows[2][1], "the reader's number text drops trailing zeros")
        assertEquals("1234.5", rows[2][2])
        assertEquals("1999-12-31", rows[2][3])
        assertEquals("first", rows[3][0], "gaps become empty strings")
        assertEquals("", rows[3][1])
        assertEquals("sparse", rows[3][3])
    }

    @Test
    fun `every part is well formed xml with no formulas`() {
        val file = File(tmp, "parts.xlsx")
        XlsxWriter().apply {
            sheet(
                "Plan",
                listOf(20.0, 20.0),
                freezeHeader = true,
                autoFilter = true,
            ) {
                row(bold("Head & <More>"), bold("Second"))
                row(text("=1+1"), text("a${'\u0001'}b${'\u000B'}c"))
                row(text("lone${'\uD800'}surrogate"), text("quote \" and ' plus >"))
                row(text("row"))
            }
            sheet("Zweit", listOf(10.0)) {
                row(text("nur Text"))
            }
        }.write(file)

        ZipFile(file).use { zip ->
            val entryNames = zip.entries().toList().map { it.name }
            assertEquals(
                listOf("[Content_Types].xml", "_rels/.rels", "xl/workbook.xml", "xl/_rels/workbook.xml.rels", "xl/styles.xml", "xl/worksheets/sheet1.xml", "xl/worksheets/sheet2.xml"),
                entryNames,
            )
            val documents = entryNames.associateWith { name -> parse(zip.getInputStream(zip.getEntry(name)).readBytes()) }
            documents.values.forEach { doc -> assertTrue(elements(doc).none { it.localName == "f" }, "no formula element in any part") }

            // The content types part lists both sheets
            val contentTypes = documents.getValue("[Content_Types].xml")
            val partNames = elements(contentTypes).filter { it.localName == "Override" }.map { it.getAttribute("PartName") }
            assertTrue("/xl/worksheets/sheet1.xml" in partNames)
            assertTrue("/xl/worksheets/sheet2.xml" in partNames)

            // Text is inline strings, control characters are dropped, specials escaped
            val sheet1Xml = zip.getInputStream(zip.getEntry("xl/worksheets/sheet1.xml")).readBytes().decodeToString()
            assertTrue(sheet1Xml.contains("<t xml:space=\"preserve\">=1+1</t>"), "a formula-looking value is an inline string: $sheet1Xml")
            assertFalse(sheet1Xml.contains("'="), "no apostrophe prefix")
            assertFalse(sheet1Xml.toCharArray().any { it.code < 0x20 && it != '\t' && it != '\n' && it != '\r' }, "control characters are dropped: $sheet1Xml")
            assertTrue(sheet1Xml.contains("Head &amp; &lt;More&gt;"), "specials are escaped: $sheet1Xml")
            assertEquals("abc", cellText(documents.getValue("xl/worksheets/sheet1.xml"), "B2"), "control chars dropped")
            assertEquals("quote \" and ' plus >", cellText(documents.getValue("xl/worksheets/sheet1.xml"), "B3"))
            assertEquals("lonesurrogate", cellText(documents.getValue("xl/worksheets/sheet1.xml"), "A3"), "a lone surrogate is dropped")

            // The frozen pane and the auto filter over header and data
            val sheet1 = documents.getValue("xl/worksheets/sheet1.xml")
            val pane = elements(sheet1).first { it.localName == "pane" }
            assertEquals("1", pane.getAttribute("ySplit"))
            assertEquals("A2", pane.getAttribute("topLeftCell"))
            assertEquals("frozen", pane.getAttribute("state"))
            val filter = elements(sheet1).first { it.localName == "autoFilter" }
            assertEquals("A1:B4", filter.getAttribute("ref"), "header plus data range")
        }
    }

    @Test
    fun `sheet names are cleaned to valid unique names`() {
        val file = File(tmp, "names.xlsx")
        XlsxWriter().apply {
            sheet("Report: Q1/2026 [final] *draft?", listOf(10.0)) { row(text("a")) }
            sheet("Same Name", listOf(10.0)) { row(text("a")) }
            sheet("same name", listOf(10.0)) { row(text("a")) }
            sheet("This name is far too long for an Excel sheet name, it keeps going on and on", listOf(10.0)) { row(text("a")) }
            sheet("   ", listOf(10.0)) { row(text("a")) }
        }.write(file)

        ZipFile(file).use { zip ->
            val workbook = parse(zip.getInputStream(zip.getEntry("xl/workbook.xml")).readBytes())
            val names = elements(workbook).filter { it.localName == "sheet" }.map { it.getAttribute("name") }
            assertEquals(5, names.size)
            names.forEach { name ->
                assertTrue(name.length <= 31, "'$name' is at most 31 characters")
                assertTrue(name.isNotEmpty(), "'$name' is not empty")
                assertFalse(name.any { it in "[]:*?/\\" }, "'$name' has no forbidden character")
            }
            assertEquals(names.size, names.map { it.lowercase() }.toSet().size, "unique case-insensitively: $names")
            assertEquals("Sheet", names.last(), "a blank name gets the default")
        }
    }

    @Test
    fun `a failed write leaves no file at the target and no temp files behind`() {
        val writer = XlsxWriter()
        writer.sheet("Data", listOf(10.0)) { row(text("a")) }

        val missingDir = File(tmp, "missing-dir")
        assertThrows(Exception::class.java) { writer.write(File(missingDir, "out.xlsx")) }
        assertFalse(File(missingDir, "out.xlsx").exists())

        val locked = File(tmp, "locked").apply {
            mkdirs()
            setWritable(false)
        }
        try {
            assertThrows(Exception::class.java) { writer.write(File(locked, "out.xlsx")) }
            assertFalse(File(locked, "out.xlsx").exists(), "nothing at the target")
            assertTrue(locked.listFiles().isNullOrEmpty(), "no temp file is left behind")
        } finally {
            locked.setWritable(true)
        }
    }

    /** Namespace-aware, like [XlsxReader]: the parts use a default namespace, and localName is what we match. */
    private fun parse(bytes: ByteArray) = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder().parse(ByteArrayInputStream(bytes))

    private fun elements(node: org.w3c.dom.Node): List<Element> = buildList {
        val children = node.childNodes
        for (index in 0 until children.length) {
            val child = children.item(index)
            if (child is Element) {
                add(child)
                addAll(elements(child))
            }
        }
    }

    private fun cellText(sheet: org.w3c.dom.Document, ref: String): String {
        val cell = elements(sheet).first { it.localName == "c" && it.getAttribute("r") == ref }
        return elements(cell).first { it.localName == "t" }.textContent
    }

    @Test
    fun `a sheet whose rows are all empty still gets a valid range`() {
        val writer = XlsxWriter()
        writer.sheet("Empty", listOf(10.0), freezeHeader = true, autoFilter = true) { row(empty(), null) }
        val sheet = writer.parts().first { it.first == "xl/worksheets/sheet1.xml" }.second
        assertTrue(sheet.contains("<dimension ref=\"A1:A1\"/>"), sheet)
    }
}
