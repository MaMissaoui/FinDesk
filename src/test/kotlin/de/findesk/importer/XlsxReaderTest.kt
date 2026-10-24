package de.findesk.importer

import de.findesk.i18n.AppStrings
import de.findesk.i18n.StringKey
import de.findesk.util.Formats
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Reads (synthetic, in-memory) XLSX packages directly, including hardening against oversized or damaged files.
 */
class XlsxReaderTest {
    private val workbookXml = """<?xml version="1.0" encoding="UTF-8"?>
<x:workbook xmlns:x="http://schemas.openxmlformats.org/spreadsheetml/2006/main"
    xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
  <x:workbookPr/>
  <x:sheets><x:sheet name="Sheet1" sheetId="1" r:id="rId1"/></x:sheets>
</x:workbook>"""

    private val workbook1904 = workbookXml.replace("<x:workbookPr/>", """<x:workbookPr date1904="true"/>""")

    private val relsXml = """<?xml version="1.0" encoding="UTF-8"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>
</Relationships>"""

    private val dateStylesXml = """<?xml version="1.0" encoding="UTF-8"?>
<x:styleSheet xmlns:x="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
  <x:numFmts count="1"><x:numFmt numFmtId="14" formatCode="M/d/yyyy"/></x:numFmts>
  <x:cellXfs count="3">
    <x:xf numFmtId="0"/>
    <x:xf numFmtId="14"/>
    <x:xf numFmtId="2" applyNumberFormat="1"/>
  </x:cellXfs>
</x:styleSheet>"""

    /** Builds a small XLSX package in memory from the parts it should contain. */
    private fun xlsx(
        sheetXml: String,
        workbook: String = workbookXml,
        rels: String = relsXml,
        sharedStrings: String? = null,
        styles: String? = null,
    ): ByteArray = ByteArrayOutputStream().also { out ->
        ZipOutputStream(out).use { zip ->
            fun put(name: String, content: String) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            put("xl/workbook.xml", workbook)
            put("xl/_rels/workbook.xml.rels", rels)
            sharedStrings?.let { put("xl/sharedStrings.xml", it) }
            styles?.let { put("xl/styles.xml", it) }
            put("xl/worksheets/sheet1.xml", sheetXml)
        }
    }.toByteArray()

    @Test
    fun `sparse cells at A and AB and a formula cell with cached value`() {
        val sheet = """<?xml version="1.0"?>
<x:worksheet xmlns:x="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
  <x:sheetData>
    <x:row r="1">
      <x:c r="A1" t="inlineStr"><x:is><x:t>head</x:t></x:is></x:c>
      <x:c r="AB1" t="inlineStr"><x:is><x:t>wide</x:t></x:is></x:c>
    </x:row>
    <x:row r="2"><x:c r="A2"><x:f>1+1</x:f><x:v>2</x:v></x:c></x:row>
  </x:sheetData>
</x:worksheet>"""
        val rows = XlsxReader.firstSheetRows(xlsx(sheet))

        assertEquals(2, rows.size)
        assertEquals("head", rows[0][0])
        assertEquals("wide", rows[0][27], "cells are placed by their column letter")
        assertEquals(28, rows[0].size, "the gap between A and AB is empty")
        assertEquals(listOf("2"), rows[1], "a formula cell uses its cached v")
    }

    @Test
    fun `numbers come out as plain dot decimals and parse amounts reads them`() {
        val sheet = """<?xml version="1.0"?>
<x:worksheet xmlns:x="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
  <x:sheetData>
    <x:row r="1">
      <x:c r="A1"><x:v>1.5</x:v></x:c>
      <x:c r="B1"><x:v>0.1</x:v></x:c>
      <x:c r="C1"><x:v>1234.5</x:v></x:c>
      <x:c r="D1"><x:v>-82.36</x:v></x:c>
      <x:c r="E1"><x:v>1000</x:v></x:c>
    </x:row>
  </x:sheetData>
</x:worksheet>"""
        val rows = XlsxReader.firstSheetRows(xlsx(sheet))

        assertEquals(listOf("1.5", "0.1", "1234.5", "-82.36", "1000"), rows[0])
        assertEquals(listOf("1.50", "0.10", "1234.50", "-82.36", "1000.00"), rows[0].map { Formats.parseAmount(it)!!.toString() })
    }

    @Test
    fun `date styles render ISO dates and the 1904 epoch shifts the base`() {
        val sheet = """<?xml version="1.0"?>
<x:worksheet xmlns:x="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
  <x:sheetData>
    <x:row r="1">
      <x:c r="A1" s="1"><x:v>46237</x:v></x:c>
      <x:c r="B1" s="1"><x:v>46239.75</x:v></x:c>
      <x:c r="C1" s="2"><x:v>45.67</x:v></x:c>
    </x:row>
  </x:sheetData>
</x:worksheet>"""
        val rows = XlsxReader.firstSheetRows(xlsx(sheet, styles = dateStylesXml))
        assertEquals("2026-08-03", rows[0][0])
        assertEquals("2026-08-05", rows[0][1], "the time fraction is dropped")
        assertEquals("45.67", rows[0][2], "a money style is not a date")

        val rows1904 = XlsxReader.firstSheetRows(
            xlsx(
                """<?xml version="1.0"?>
<x:worksheet xmlns:x="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
  <x:sheetData><x:row r="1"><x:c r="A1" s="1"><x:v>0</x:v></x:c></x:row></x:sheetData>
</x:worksheet>""",
                workbook = workbook1904,
                styles = dateStylesXml,
            ),
        )
        assertEquals("1904-01-01", rows1904[0][0])
    }

    @Test
    fun `an out-of-range shared string index only empties that cell`() {
        val sheet = """<?xml version="1.0"?>
<x:worksheet xmlns:x="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
  <x:sheetData>
    <x:row r="1">
      <x:c r="A1" t="s"><x:v>0</x:v></x:c>
      <x:c r="B1" t="s"><x:v>99</x:v></x:c>
    </x:row>
  </x:sheetData>
</x:worksheet>"""
        val strings = """<?xml version="1.0" encoding="UTF-8"?>
<x:sst xmlns:x="http://schemas.openxmlformats.org/spreadsheetml/2006/main" count="1">
  <x:si><x:t>ok</x:t></x:si>
</x:sst>"""
        val rows = XlsxReader.firstSheetRows(xlsx(sheet, sharedStrings = strings))
        assertEquals(listOf("ok", ""), rows[0])
    }

    private fun assertTooLarge(block: () -> Unit) {
        val e = assertThrows(IllegalArgumentException::class.java, block)
        assertEquals(AppStrings[StringKey.ErrorXlsxTooLarge], e.message)
    }

    private fun assertUnreadable(block: () -> Unit) {
        val e = assertThrows(IllegalArgumentException::class.java, block)
        assertEquals(AppStrings[StringKey.ErrorXlsxUnreadable], e.message)
    }

    @Test
    fun `more than 2000 entries is refused`() {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            repeat(2001) { i ->
                zip.putNextEntry(ZipEntry("part/$i.xml"))
                zip.write("x".toByteArray())
                zip.closeEntry()
            }
        }
        assertTooLarge { XlsxReader.firstSheetRows(out.toByteArray()) }
    }

    @Test
    fun `a 61 MB entry that is not needed is refused`() {
        val sheet = """<?xml version="1.0"?>
<x:worksheet xmlns:x="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
  <x:sheetData><x:row r="1"><x:c r="A1"><x:v>1</x:v></x:c></x:row></x:sheetData>
</x:worksheet>"""
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("xl/padding.bin"))
            val chunk = ByteArray(1024 * 1024) // zeros are highly compressible
            repeat(61) { zip.write(chunk) }
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("xl/workbook.xml"))
            zip.write(workbookXml.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("xl/_rels/workbook.xml.rels"))
            zip.write(relsXml.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("xl/worksheets/sheet1.xml"))
            zip.write(sheet.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
        assertTooLarge { XlsxReader.firstSheetRows(out.toByteArray()) }
    }

    @Test
    fun `a missing workbook is unreadable`() {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("xl/worksheets/sheet1.xml"))
            zip.write("<worksheet/>".toByteArray())
            zip.closeEntry()
        }
        val e = assertThrows(IllegalArgumentException::class.java) { XlsxReader.firstSheetRows(out.toByteArray()) }
        assertEquals(AppStrings[StringKey.ErrorXlsxUnreadable], e.message)
    }

    @Test
    fun `a doctype in the sheet is unreadable`() {
        val sheet = """<?xml version="1.0"?>
<!DOCTYPE worksheet>
<x:worksheet xmlns:x="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
  <x:sheetData><x:row r="1"><x:c r="A1"><x:v>1</x:v></x:c></x:row></x:sheetData>
</x:worksheet>"""
        val e = assertThrows(IllegalArgumentException::class.java) { XlsxReader.firstSheetRows(xlsx(sheet)) }
        assertEquals(AppStrings[StringKey.ErrorXlsxUnreadable], e.message)
    }

    @Test
    fun `malformed sheet xml is unreadable`() {
        val sheet = """<?xml version="1.0"?>
<x:worksheet xmlns:x="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
  <x:sheetData><x:row r="1"><x:c r="A1"<x:v>1</x:v></x:c></x:row></x:sheetData>
</x:worksheet>"""
        val e = assertThrows(IllegalArgumentException::class.java) { XlsxReader.firstSheetRows(xlsx(sheet)) }
        assertEquals(AppStrings[StringKey.ErrorXlsxUnreadable], e.message)
    }

    private val oneCellSheet = """<?xml version="1.0"?>
<x:worksheet xmlns:x="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
  <x:sheetData><x:row r="1"><x:c r="A1"><x:v>1</x:v></x:c></x:row></x:sheetData>
</x:worksheet>"""

    @Test
    fun `sparse cells with real cell references land in their own columns`() {
        val sheet = """<?xml version="1.0"?>
<x:worksheet xmlns:x="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
  <x:sheetData>
    <x:row r="3">
      <x:c r="A3" t="inlineStr"><x:is><x:t>x</x:t></x:is></x:c>
      <x:c r="C3" t="inlineStr"><x:is><x:t>z</x:t></x:is></x:c>
    </x:row>
    <x:row r="5"><x:c r="AB5"><x:v>7</x:v></x:c></x:row>
  </x:sheetData>
</x:worksheet>"""
        val rows = XlsxReader.firstSheetRows(xlsx(sheet))

        assertEquals(listOf("x", "", "z"), rows[2], "the C cell skips the empty B column")
        assertEquals(28, rows[4].size, "the gap up to AB is empty")
        assertEquals("7", rows[4][27], "AB lands at index 27")
    }

    @Test
    fun `an omitted middle cell keeps later values in their columns`() {
        val sheet = """<?xml version="1.0"?>
<x:worksheet xmlns:x="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
  <x:sheetData>
    <x:row r="1">
      <x:c r="A1" t="inlineStr"><x:is><x:t>Date</x:t></x:is></x:c>
      <x:c r="B1" t="inlineStr"><x:is><x:t>Payee</x:t></x:is></x:c>
      <x:c r="C1" t="inlineStr"><x:is><x:t>Amount</x:t></x:is></x:c>
    </x:row>
    <x:row r="2">
      <x:c r="A2" t="inlineStr"><x:is><x:t>2026-08-03</x:t></x:is></x:c>
      <x:c r="C2"><x:v>-12.5</x:v></x:c>
    </x:row>
  </x:sheetData>
</x:worksheet>"""
        val rows = XlsxReader.firstSheetRows(xlsx(sheet))

        assertEquals(listOf("Date", "Payee", "Amount"), rows[0])
        assertEquals(listOf("2026-08-03", "", "-12.5"), rows[1], "the amount stays in the Amount column")
    }

    @Test
    fun `two entries with the same wanted sheet name are unreadable`() {
        // ZipOutputStream refuses duplicate names itself, so the second sheet entry is built by hand
        // (a STORED local-file header plus raw bytes) and spliced in before the central directory;
        // ZipInputStream reads strictly sequentially and stops at the central directory.
        fun storedEntry(name: String, content: String): ByteArray {
            val nameBytes = name.toByteArray(Charsets.UTF_8)
            val contentBytes = content.toByteArray(Charsets.UTF_8)
            val crc = CRC32().apply { update(contentBytes) }.value
            return ByteBuffer.allocate(30 + nameBytes.size).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(0x04034b50) // local file header signature
                .putShort(20.toShort()) // version needed to extract
                .putShort(0) // flags
                .putShort(0) // method STORED
                .putShort(0) // modification time
                .putShort(0) // modification date
                .putInt(crc.toInt())
                .putInt(contentBytes.size)
                .putInt(contentBytes.size)
                .putShort(nameBytes.size.toShort())
                .putShort(0) // extra field length
                .put(nameBytes)
                .array()
        }
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            fun put(name: String, content: String) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            put("xl/workbook.xml", workbookXml)
            put("xl/_rels/workbook.xml.rels", relsXml)
            put("xl/worksheets/sheet1.xml", oneCellSheet)
        }
        val base = out.toByteArray()
        val centralDir = byteArrayOf(0x50, 0x4B, 0x01, 0x02)
        val insertAt = (0..base.size - centralDir.size).first { at ->
            centralDir.indices.all { base[at + it] == centralDir[it] }
        }
        val bytes = base.copyOfRange(0, insertAt) +
            storedEntry("xl/worksheets/sheet1.xml", oneCellSheet) +
            base.copyOfRange(insertAt, base.size)
        assertUnreadable { XlsxReader.firstSheetRows(bytes) }
    }

    @Test
    fun `random bytes are unreadable`() {
        val bytes = ByteArray(512) { (it * 31 + 7).toByte() }
        assertUnreadable { XlsxReader.firstSheetRows(bytes) }
    }

    @Test
    fun `a truncated zip is unreadable`() {
        val bytes = xlsx(oneCellSheet)
        val truncated = bytes.copyOfRange(0, bytes.size / 2)
        assertUnreadable { XlsxReader.firstSheetRows(truncated) }
    }

    @Test
    fun `a sheet nested 100 levels deep is unreadable`() {
        val sheet = buildString {
            append("""<?xml version="1.0"?>""")
            append("""<x:worksheet xmlns:x="http://schemas.openxmlformats.org/spreadsheetml/2006/main">""")
            repeat(100) { append("<x:n>") }
            append("<x:sheetData><x:row r=\"1\"><x:c r=\"A1\"><x:v>1</x:v></x:c></x:row></x:sheetData>")
            repeat(100) { append("</x:n>") }
            append("</x:worksheet>")
        }
        assertUnreadable { XlsxReader.firstSheetRows(xlsx(sheet)) }
    }
}
