package de.findesk.importer

import de.findesk.TestData
import de.findesk.TestDatabase
import de.findesk.repository.AuditLogRepositoryImpl
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.File
import java.math.BigDecimal
import java.nio.charset.Charset
import java.time.LocalDate
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Card statement imports: HVB Mastercard CSV exports (UTF-16LE without BOM) and Bank Norwegian XLSX.
 * All fixtures are synthetic, built in the test code.
 */
class CardStatementImportTest {
    private val utf16le = Charset.forName("UTF-16LE")
    private val utf16be = Charset.forName("UTF-16BE")
    private val windows1252 = Charset.forName("windows-1252")

    /** HVB Mastercard layout. */
    private val hvbCsv = """
        Kartennummer;Zeitraum;Belegdatum;Eingangstag;Text/Verwendungszweck;Kurs;Betrag;Waehrung
        5412345678901234;01.05.2025 - 15.05.2025;03.05.2025;06.05.2025;REWE SAGT DANKE Ü;kurs;-58,94;EUR
        5412345678901234;01.05.2025 - 15.05.2025;04.05.2025;07.05.2025;Tankstelle am Ring;kurs;-12,50;EUR
        5412345678901234;01.05.2025 - 15.05.2025;05.05.2025;08.05.2025;Rückzahlung Versicherung;kurs;123,45;EUR
    """.trimIndent()

    @Test
    fun `hvb card csv in utf-16le without bom`() {
        val s = CsvStatementParser.parse(hvbCsv.toByteArray(utf16le))

        assertEquals("UTF-16LE", s.csv!!.charsetName)
        assertEquals(';', s.csv!!.delimiter)
        assertEquals(3, s.bookings.size)
        assertEquals(listOf(LocalDate.of(2025, 5, 6), LocalDate.of(2025, 5, 7), LocalDate.of(2025, 5, 8)), s.bookings.map { it.date }, "the posting date wins")
        assertEquals(
            listOf(LocalDate.of(2025, 5, 3), LocalDate.of(2025, 5, 4), LocalDate.of(2025, 5, 5)),
            s.bookings.map { it.valueDate },
            "the purchase date is the value date",
        )
        assertEquals(listOf(BigDecimal("-58.94"), BigDecimal("-12.50"), BigDecimal("123.45")), s.bookings.map { it.amount })
        assertEquals("REWE SAGT DANKE Ü", s.bookings[0].purpose, "UTF-16 survives the umlaut")
        assertEquals("EUR", s.bookings[0].currency)
        assertNull(s.bookings[0].partnerName, "no column becomes a partner")
    }

    @Test
    fun `hvb card csv in utf-16le with bom`() {
        val s = CsvStatementParser.parse(byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + hvbCsv.toByteArray(utf16le))

        assertEquals("UTF-16LE", s.csv!!.charsetName)
        assertEquals(3, s.bookings.size)
        assertEquals("REWE SAGT DANKE Ü", s.bookings[0].purpose)
    }

    @Test
    fun `hvb card csv in utf-16be without bom`() {
        val s = CsvStatementParser.parse(hvbCsv.toByteArray(utf16be))

        assertEquals("UTF-16BE", s.csv!!.charsetName)
        assertEquals(3, s.bookings.size)
        assertEquals("Rückzahlung Versicherung", s.bookings[2].purpose)
    }

    @Test
    fun `kartennummer maps to nothing`() {
        val headers = hvbCsv.lines().first().split(';')

        val mapping = CsvStatementParser.detectMapping(headers)

        assertEquals(3, mapping.date, "Eingangstag")
        assertEquals(2, mapping.valueDate, "Belegdatum")
        assertEquals(6, mapping.amount)
        assertEquals(4, mapping.purpose, "Text/Verwendungszweck")
        assertEquals(7, mapping.currency)
        assertNull(mapping.partner)
        assertNull(mapping.payer)
        assertNull(mapping.partnerIban)
        assertNull(mapping.balance)
    }

    @Test
    fun `existing german csv format still parses its grouped amounts`() {
        val utf8 = CsvStatementParser.parse("Buchungstag;Betrag;Verwendungszweck\n03.10.2026;1.234,56;Einkauf\n".toByteArray(Charsets.UTF_8))
        val cp1252 = CsvStatementParser.parse(
            "Buchungstag;Betrag;Zahlungsempfänger;Verwendungszweck\n03.10.2026;1.234,56;Räderwerk;Rad kaufen\n".toByteArray(windows1252),
        )

        assertEquals("UTF-8", utf8.csv!!.charsetName)
        assertEquals(BigDecimal("1234.56"), utf8.bookings.single().amount)
        assertEquals("windows-1252", cp1252.csv!!.charsetName)
        assertEquals(BigDecimal("1234.56"), cp1252.bookings.single().amount)
        assertEquals("Räderwerk", cp1252.bookings.single().partnerName, "umlauts survive windows-1252")
    }

    // -- Bank Norwegian XLSX -----------------------------------------------------------------------------------------

    private val workbookXml = """<?xml version="1.0" encoding="UTF-8"?>
<x:workbook xmlns:x="http://schemas.openxmlformats.org/spreadsheetml/2006/main"
    xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
  <x:workbookPr/>
  <x:sheets><x:sheet name="Transactions" sheetId="1" r:id="rId1"/></x:sheets>
</x:workbook>"""

    private val relsXml = """<?xml version="1.0" encoding="UTF-8"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>
</Relationships>"""

    /** numFmtId 14 re-declared as a custom code, like Bank Norwegian's styles.xml does. */
    private val stylesXml = """<?xml version="1.0" encoding="UTF-8"?>
<x:styleSheet xmlns:x="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
  <x:numFmts count="1"><x:numFmt numFmtId="14" formatCode="M/d/yyyy"/></x:numFmts>
  <x:cellXfs count="2"><x:xf numFmtId="0"/><x:xf numFmtId="14"/></x:cellXfs>
</x:styleSheet>"""

    private val sharedStringsXml = """<?xml version="1.0" encoding="UTF-8"?>
<x:sst xmlns:x="http://schemas.openxmlformats.org/spreadsheetml/2006/main" count="16">
  <x:si><x:t>TransactionDate</x:t></x:si>
  <x:si><x:t>Text</x:t></x:si>
  <x:si><x:t>Type</x:t></x:si>
  <x:si><x:t>Currency Amount</x:t></x:si>
  <x:si><x:t>Currency Rate</x:t></x:si>
  <x:si><x:t>Currency</x:t></x:si>
  <x:si><x:t>Amount</x:t></x:si>
  <x:si><x:t>Merchant Area</x:t></x:si>
  <x:si><x:t>Merchant Category</x:t></x:si>
  <x:si><x:t>BookDate</x:t></x:si>
  <x:si><x:t>ValueDate</x:t></x:si>
  <x:si><x:t>Simpl Jewellery</x:t></x:si>
  <x:si><x:t>Purchase</x:t></x:si>
  <x:si><x:t>Shopping</x:t></x:si>
  <x:si><x:t>Jewellery Specialist</x:t></x:si>
  <x:si><x:t>Currency</x:t></x:si>
</x:sst>"""

    /** Builds a synthetic XLSX package in memory. */
    private fun xlsx(
        sheetXml: String,
        workbook: String = workbookXml,
        sharedStrings: String? = sharedStringsXml,
        sheetName: String = "xl/worksheets/sheet1.xml",
    ): ByteArray = ByteArrayOutputStream().also { out ->
        ZipOutputStream(out).use { zip ->
            fun put(name: String, content: String) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            put("xl/workbook.xml", workbook)
            put("xl/_rels/workbook.xml.rels", relsXml)
            sharedStrings?.let { put("xl/sharedStrings.xml", it) }
            put("xl/styles.xml", stylesXml)
            put(sheetName, sheetXml)
        }
    }.toByteArray()

    /** 0..25 → 'A'..'Z'. */
    private fun col(i: Int): Char = 'A' + i

    /** Bank Norwegian header row: the 11 column names as shared strings 0–10. */
    private fun norwegianHeader() = (0 until 11).joinToString("") { """<x:c r="${col(it)}1" t="s"><x:v>$it</x:v></x:c>""" }

    /** A Norwegian data row about a jewellery purchase; [amount] is the billed EUR amount. */
    private fun norwegianRow(amount: String) = """<x:c r="A2" s="1"><x:v>46237</x:v></x:c>""" +
        """<x:c r="B2" t="s"><x:v>11</x:v></x:c><x:c r="C2" t="s"><x:v>12</x:v></x:c>""" +
        """<x:c r="D2"><x:v>-10.00</x:v></x:c><x:c r="E2"><x:v>1.08</x:v></x:c><x:c r="F2" t="s"><x:v>5</x:v></x:c>""" +
        """<x:c r="G2"><x:v>$amount</x:v></x:c><x:c r="H2" t="s"><x:v>13</x:v></x:c><x:c r="I2" t="s"><x:v>14</x:v></x:c>""" +
        """<x:c r="J2" s="1"><x:v>46238</x:v></x:c><x:c r="K2" s="1"><x:v>46239</x:v></x:c>"""

    private fun norwegianSheet(row: String) = """<?xml version="1.0"?>
<x:worksheet xmlns:x="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
  <x:sheetData>
    <x:row r="1">${norwegianHeader()}</x:row>
    <x:row r="2">$row</x:row>
  </x:sheetData>
</x:worksheet>"""

    @Test
    fun `bank norwegian xlsx with prefixed namespace, shared strings and a re-declared date style`() {
        val s = CsvStatementParser.parseRows(
            XlsxReader.firstSheetRows(xlsx(norwegianSheet(norwegianRow("-82.36")))),
            null,
            StatementFormat.XLSX,
            "XLSX",
            ';',
        )

        assertEquals(StatementFormat.XLSX, s.format)
        assertEquals("XLSX", s.csv!!.charsetName)
        assertEquals(1, s.bookings.size)
        val booking = s.bookings.single()
        assertEquals(LocalDate.of(2026, 8, 4), booking.date, "BookDate serial 46238")
        assertEquals(LocalDate.of(2026, 8, 5), booking.valueDate, "ValueDate serial 46239")
        assertEquals(BigDecimal("-82.36"), booking.amount, "the billed EUR amount, not the USD amount")
        assertEquals("Simpl Jewellery", booking.purpose)
        assertNull(booking.currency)
    }

    @Test
    fun `bank norwegian xlsx with default namespace and inline strings`() {
        val ns = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
        val names = listOf("TransactionDate", "Text", "Type", "Currency Amount", "Currency Rate", "Currency", "Amount", "Merchant Area", "Merchant Category", "BookDate", "ValueDate")
        val header = names.mapIndexed { i, text -> """<c r="${col(i)}1" t="inlineStr"><is><t>$text</t></is></c>""" }.joinToString("")
        val sheet = """<?xml version="1.0"?>
<worksheet xmlns="$ns">
  <sheetData>
    <row r="1">$header</row>
    <row r="2"><c r="A2" s="1"><v>46237</v></c><c r="B2" t="inlineStr"><is><t>Simpl Jewellery</t></is></c><c r="C2" t="inlineStr"><is><t>Purchase</t></is></c>""" +
            """<c r="D2"><v>-10.00</v></c><c r="E2"><v>1.08</v></c><c r="F2" t="inlineStr"><is><t>EUR</t></is></c><c r="G2"><v>-82.36</v></c>""" +
            """<c r="H2" t="inlineStr"><is><t>Shopping</t></is></c><c r="I2" t="inlineStr"><is><t>Jewellery Specialist</t></is></c>""" +
            """<c r="J2" s="1"><v>46238</v></c><c r="K2" s="1"><v>46239</v></c></row>
  </sheetData>
</worksheet>"""
        val s = CsvStatementParser.parseRows(
            XlsxReader.firstSheetRows(xlsx(sheet, sharedStrings = null)),
            null,
            StatementFormat.XLSX,
            "XLSX",
            ';',
        )

        assertEquals(1, s.bookings.size)
        val booking = s.bookings.single()
        assertEquals(LocalDate.of(2026, 8, 4), booking.date)
        assertEquals(LocalDate.of(2026, 8, 5), booking.valueDate)
        assertEquals(BigDecimal("-82.36"), booking.amount)
        assertEquals("Simpl Jewellery", booking.purpose)
    }

    @Test
    fun `a usd purchase with its own currency amount imports the billed eur amount`() {
        val s = CsvStatementParser.parseRows(
            XlsxReader.firstSheetRows(xlsx(norwegianSheet(norwegianRow("-9.12")))),
            null,
            StatementFormat.XLSX,
            "XLSX",
            ';',
        )

        assertEquals(1, s.bookings.size, "not skipped as another currency")
        assertEquals(BigDecimal("-9.12"), s.bookings.single().amount)
        assertNull(s.bookings.single().currency)
    }

    @Test
    fun `numeric amounts from xlsx cells`() {
        val numbers = listOf("1.5", "0.1", "1234.5", "-82.36", "1000")
        val rows = numbers.mapIndexed { i, number ->
            """<x:row r="${i + 2}"><x:c r="A${i + 2}" t="inlineStr"><x:is><x:t>2026-08-04</x:t></x:is></x:c>""" +
                """<x:c r="B${i + 2}"><x:v>$number</x:v></x:c>""" +
                """<x:c r="C${i + 2}" t="inlineStr"><x:is><x:t>Sinus Power</x:t></x:is></x:c></x:row>"""
        }.joinToString("\n    ")
        val sheet = """<?xml version="1.0"?>
<x:worksheet xmlns:x="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
  <x:sheetData>
    <x:row r="1"><x:c r="A1" t="inlineStr"><x:is><x:t>Date</x:t></x:is></x:c><x:c r="B1" t="inlineStr"><x:is><x:t>Amount</x:t></x:is></x:c><x:c r="C1" t="inlineStr"><x:is><x:t>Purpose</x:t></x:is></x:c></x:row>
    $rows
  </x:sheetData>
</x:worksheet>"""
        val s = CsvStatementParser.parseRows(
            XlsxReader.firstSheetRows(xlsx(sheet)),
            null,
            StatementFormat.XLSX,
            "XLSX",
            ';',
        )

        assertEquals(listOf("1.50", "0.10", "1234.50", "-82.36", "1000.00"), s.bookings.map { it.amount.toString() })
    }

    // -- end-to-end ---------------------------------------------------------------------------------------------------

    @TempDir
    lateinit var tmp: File

    private lateinit var data: TestData
    private val service = StatementImportService()
    private var account = 0

    @BeforeEach
    fun setup() {
        TestDatabase.init()
        data = TestData()
        account = data.account(iban = "DE89370400440532013000", balance = "0")
    }

    @Test
    fun `reading and importing a card xlsx file`() = runBlocking {
        val file = File(tmp, "card.xlsx").apply { writeBytes(xlsx(norwegianSheet(norwegianRow("-82.36")))) }

        val statement = service.read(file)
        assertEquals(StatementFormat.XLSX, statement.format)
        assertEquals(1, statement.bookings.size)
        assertEquals(LocalDate.of(2026, 8, 4), statement.bookings.single().date)

        val report = service.import(account, statement)
        assertEquals(1, report.imported)
        assertEquals(LocalDate.of(2026, 8, 4), data.transactionRepo.findItems().single().date)
        assertTrue(
            AuditLogRepositoryImpl().findByAction("IMPORT_STATEMENT").first().details!!.startsWith("XLSX into"),
            "the audit entry names the format",
        )
    }
}
