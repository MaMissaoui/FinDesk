package de.findesk.document

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.math.BigDecimal
import java.math.RoundingMode
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Writes [writeAtomically]-style files through a temporary file next to the target and moves the finished
 * file into place, so a failed export never leaves half a file at the target.
 */
internal fun writeAtomically(target: File, write: (File) -> Unit) {
    val directory = target.absoluteFile.parentFile
    requireNotNull(directory) { "The export target needs a parent directory: $target" }
    var temp: File? = null
    try {
        temp = File.createTempFile("findesk-export", ".tmp", directory)
        write(temp)
        Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
    } catch (e: Exception) {
        temp?.delete()
        throw e
    }
}

/**
 * A minimal SpreadsheetML (.xlsx) writer on `java.util.zip` and hand-written XML — no third-party library.
 * Cell kinds: text and bold text as inline strings, plain numbers, money with `#,##0.00` and dates as Excel
 * serial numbers with `dd.mm.yyyy`. There are deliberately no formulas (`<f>`): totals are computed in Kotlin.
 * A text value starting with `=`, `+`, `-` or `@` stays a plain inline string (no apostrophe prefix — Excel
 * would show it literally); the XML is escaped and stripped of characters invalid in XML 1.0, since bank text
 * can contain them.
 */
class XlsxWriter {
    private val sheets = mutableListOf<Sheet>()

    /**
     * Opens a sheet and fills it from [content]. [columns] holds the columns' display widths; [freezeHeader]
     * freezes row 1; [autoFilter] puts a filter over the header and data range.
     */
    fun sheet(
        name: String,
        columns: List<Double>,
        freezeHeader: Boolean = false,
        autoFilter: Boolean = false,
        content: Sheet.() -> Unit,
    ) {
        val sheet = Sheet(name, columns, freezeHeader, autoFilter)
        sheet.content()
        sheets += sheet
    }

    /** Writes the workbook to [target] through a temp file next to it, then moves it into place. */
    fun write(target: File) {
        val parts = parts()
        writeAtomically(target) { temp ->
            ZipOutputStream(BufferedOutputStream(FileOutputStream(temp))).use { zip ->
                parts.forEach { (name, xml) ->
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(xml.toByteArray(StandardCharsets.UTF_8))
                    zip.closeEntry()
                }
            }
        }
    }

    /** One worksheet: its name as given (cleaned when written), its columns' widths and its rows. */
    class Sheet internal constructor(
        internal val rawName: String,
        val columns: List<Double>,
        val freezeHeader: Boolean,
        val autoFilter: Boolean,
    ) {
        internal val rows = mutableListOf<List<Cell?>>()

        /** Appends one row; [cells] are placed by their position, `empty()`/null cells leave a gap. */
        fun row(vararg cells: Cell?) = row(cells.toList())

        fun row(cells: List<Cell?>) {
            rows += cells
        }

        fun text(value: String): Cell = Cell(Kind.TEXT, value)

        fun bold(value: String): Cell = Cell(Kind.BOLD, value)

        fun number(value: BigDecimal): Cell = Cell(Kind.NUMBER, value.toPlainString())

        fun money(value: BigDecimal): Cell = Cell(Kind.MONEY, value.setScale(2, RoundingMode.HALF_UP).toPlainString())

        /** Dates are written as Excel serial numbers (days since 1899-12-30). */
        fun date(value: LocalDate): Cell = Cell(Kind.DATE, ChronoUnit.DAYS.between(BASE_DATE, value).toString())

        fun empty(): Cell = Cell(Kind.EMPTY, "")

        internal enum class Kind { EMPTY, TEXT, BOLD, NUMBER, MONEY, DATE }
    }

    /** One cell of a [Sheet]; created only through the sheet's factory functions. */
    class Cell internal constructor(internal val kind: Sheet.Kind, internal val value: String)

    /**
     * Every package part in write order: the content types, the two relationship parts, the workbook, the
     * styles and one worksheet per sheet. Package-private for tests.
     */
    internal fun parts(): List<Pair<String, String>> {
        val names = cleanedSheetNames()
        return buildList {
            add("[Content_Types].xml" to contentTypesXml(names))
            add("_rels/.rels" to ROOT_RELS)
            add("xl/workbook.xml" to workbookXml(names))
            add("xl/_rels/workbook.xml.rels" to workbookRelsXml(names.size))
            add("xl/styles.xml" to STYLES)
            names.forEachIndexed { index, _ -> add("xl/worksheets/sheet${index + 1}.xml" to sheetXml(sheets[index])) }
        }
    }

    /** The sheets' names cleaned to valid, unique (case-insensitive) Excel sheet names, in order. */
    private fun cleanedSheetNames(): List<String> {
        val taken = mutableSetOf<String>()
        return sheets.map { cleanSheetName(it.rawName, taken) }
    }

    private fun contentTypesXml(sheetNames: List<String>): String = buildString {
        append(XML_DECLARATION)
        append("<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">")
        append("<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>")
        append("<Default Extension=\"xml\" ContentType=\"application/xml\"/>")
        append("<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>")
        sheetNames.indices.forEach { index ->
            append("<Override PartName=\"/xl/worksheets/sheet${index + 1}.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>")
        }
        append("<Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/>")
        append("</Types>")
    }

    private fun workbookXml(sheetNames: List<String>): String = buildString {
        append(XML_DECLARATION)
        append("<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"")
        append(" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">")
        append("<sheets>")
        sheetNames.forEachIndexed { index, name ->
            append("<sheet name=\"${escapeXml(name)}\" sheetId=\"${index + 1}\" r:id=\"rId${index + 1}\"/>")
        }
        append("</sheets></workbook>")
    }

    private fun workbookRelsXml(sheetCount: Int): String = buildString {
        append(XML_DECLARATION)
        append("<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">")
        repeat(sheetCount) { index ->
            append("<Relationship Id=\"rId${index + 1}\"")
            append(" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\"")
            append(" Target=\"worksheets/sheet${index + 1}.xml\"/>")
        }
        append("<Relationship Id=\"rId${sheetCount + 1}\"")
        append(" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\"")
        append(" Target=\"styles.xml\"/>")
        append("</Relationships>")
    }

    private fun sheetXml(sheet: Sheet): String {
        // At least column A, so a sheet whose rows are all empty still gets a valid range
        val lastColumn = (sheet.rows.maxOfOrNull { row -> row.indexOfLast { it != null && it.kind != Sheet.Kind.EMPTY } + 1 } ?: 0).coerceAtLeast(1)
        val lastRow = sheet.rows.size
        val range = if (sheet.rows.isEmpty()) {
            "A1"
        } else {
            "A1:${columnLetter(lastColumn - 1)}$lastRow"
        }
        return buildString {
            append(XML_DECLARATION)
            append("<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"")
            append(" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">")
            append("<dimension ref=\"$range\"/>")
            append("<sheetViews><sheetView workbookViewId=\"0\">")
            if (sheet.freezeHeader && sheet.rows.isNotEmpty()) {
                append("<pane ySplit=\"1\" topLeftCell=\"A2\" activePane=\"bottomLeft\" state=\"frozen\"/>")
            }
            append("</sheetView></sheetViews>")
            if (sheet.columns.isNotEmpty()) {
                append("<cols>")
                sheet.columns.forEachIndexed { index, width ->
                    append("<col min=\"${index + 1}\" max=\"${index + 1}\" width=\"$width\" customWidth=\"1\"/>")
                }
                append("</cols>")
            }
            append("<sheetData>")
            sheet.rows.forEachIndexed { rowIndex, cells ->
                append("<row r=\"${rowIndex + 1}\">")
                cells.forEachIndexed { columnIndex, cell -> append(cellXml(cell, rowIndex + 1, columnIndex)) }
                append("</row>")
            }
            append("</sheetData>")
            if (sheet.autoFilter && sheet.rows.isNotEmpty()) append("<autoFilter ref=\"$range\"/>")
            append("</worksheet>")
        }
    }

    private fun cellXml(cell: Cell?, row: Int, column: Int): String {
        if (cell == null || cell.kind == Sheet.Kind.EMPTY) return ""
        val ref = "${columnLetter(column)}$row"
        return when (cell.kind) {
            Sheet.Kind.TEXT -> inlineStr(ref, style = 0, value = cell.value)
            Sheet.Kind.BOLD -> inlineStr(ref, style = 1, value = cell.value)
            Sheet.Kind.NUMBER -> "<c r=\"$ref\"><v>${cell.value}</v></c>"
            Sheet.Kind.MONEY -> "<c r=\"$ref\" s=\"2\"><v>${cell.value}</v></c>"
            Sheet.Kind.DATE -> "<c r=\"$ref\" s=\"3\"><v>${cell.value}</v></c>"
            Sheet.Kind.EMPTY -> ""
        }
    }

    private fun inlineStr(ref: String, style: Int, value: String): String {
        val styleAttribute = if (style == 0) "" else " s=\"$style\""
        return "<c r=\"$ref\"$styleAttribute t=\"inlineStr\"><is><t xml:space=\"preserve\">${escapeXml(value)}</t></is></c>"
    }

    companion object {
        private val XML_DECLARATION = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
        private val BASE_DATE = LocalDate.of(1899, 12, 30)

        /** Characters Excel forbids in sheet names. */
        private val FORBIDDEN_IN_SHEET_NAME = Regex("[\\[\\]:*?/\\\\]")
        private const val MAX_SHEET_NAME_LENGTH = 31
        private const val DEFAULT_SHEET_NAME = "Sheet"
        private const val UNIQUE_SUFFIX_ROOM = 5

        private val ROOT_RELS = XML_DECLARATION +
            "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
            "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/>" +
            "</Relationships>"

        /**
         * Style 0 = text, 1 = bold text, 2 = money (`#,##0.00`), 3 = date (`dd.mm.yyyy`). The two number
         * formats are declared as custom formats (ids 164+), so every reader sees what each cell means. Excel
         * reserves the first two fills (none, gray125), so both are declared even though no cell uses them.
         */
        private val STYLES: String = XML_DECLARATION +
            """<styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
 <numFmts count="2"><numFmt numFmtId="164" formatCode="#,##0.00"/><numFmt numFmtId="165" formatCode="dd.mm.yyyy"/></numFmts>
 <fonts count="2"><font><sz val="11"/><name val="Calibri"/></font><font><b/><sz val="11"/><name val="Calibri"/></font></fonts>
 <fills count="2"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill></fills>
 <borders count="1"><border/></borders>
 <cellStyleXfs count="1"><xf numFmtId="0" fontId="0"/></cellStyleXfs>
 <cellXfs count="4">
  <xf numFmtId="0" fontId="0" xfId="0"/>
  <xf numFmtId="0" fontId="1" applyFont="1" xfId="0"/>
  <xf numFmtId="164" fontId="0" applyNumberFormat="1" xfId="0"/>
  <xf numFmtId="165" fontId="0" applyNumberFormat="1" xfId="0"/>
 </cellXfs>
</styleSheet>"""

        /** Cleans [raw] into a valid sheet name and, taken one, a unique (case-insensitive) variant. */
        internal fun cleanSheetName(raw: String, taken: MutableSet<String>): String {
            val base = raw.replace(FORBIDDEN_IN_SHEET_NAME, "_").trim()
                .take(MAX_SHEET_NAME_LENGTH)
                .ifEmpty { DEFAULT_SHEET_NAME }
            if (taken.none { it.equals(base, ignoreCase = true) }) {
                taken += base
                return base
            }
            val prefix = base.take(MAX_SHEET_NAME_LENGTH - UNIQUE_SUFFIX_ROOM)
            var number = 2
            while (true) {
                val candidate = "$prefix-$number"
                if (taken.none { it.equals(candidate, ignoreCase = true) }) {
                    taken += candidate
                    return candidate
                }
                number++
            }
        }

        /**
         * Escapes the five XML special characters and drops what XML 1.0 cannot carry: control characters
         * other than tab, LF and CR, lone surrogates and U+FFFE/U+FFFF.
         */
        internal fun escapeXml(value: String): String = buildString {
            var index = 0
            while (index < value.length) {
                val c = value[index]
                when {
                    c == '&' -> append("&amp;")

                    c == '<' -> append("&lt;")

                    c == '>' -> append("&gt;")

                    c == '"' -> append("&quot;")

                    c == '\'' -> append("&apos;")

                    c == '\t' || c == '\n' || c == '\r' -> append(c)

                    c.code < 0x20 || c.code == 0xFFFE || c.code == 0xFFFF -> {}

                    c.isHighSurrogate() && index + 1 < value.length && value[index + 1].isLowSurrogate() -> {
                        append(c)
                        append(value[index + 1])
                        index++
                    }

                    c.isSurrogate() -> {}

                    else -> append(c)
                }
                index++
            }
        }

        /** "A", …, "Z", "AA", … for a 0-based column index. */
        internal fun columnLetter(index: Int): String {
            check(index >= 0) { "Negative column index: $index" }
            var remaining = index
            val letters = StringBuilder()
            while (true) {
                letters.insert(0, ('A' + remaining % 26))
                if (remaining < 26) break
                remaining = remaining / 26 - 1
            }
            return letters.toString()
        }
    }
}
