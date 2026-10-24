package de.findesk.importer

import de.findesk.i18n.AppStrings
import de.findesk.i18n.StringKey
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.util.zip.ZipInputStream
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Reads the first worksheet of an untrusted XLSX file as rows of cell strings, in memory and without any
 * third-party library: the package (a ZIP of XML parts) is read with `java.util.zip` and the JDK XML parser.
 * Cells are placed by their column letter (`A`, …, `Z`, `AA`, …) and rows by their `r` attribute; gaps
 * become empty strings.
 */
internal object XlsxReader {
    private const val MAX_ENTRIES = 2000
    private const val MAX_TOTAL_BYTES = 60L * 1024 * 1024
    private const val MAX_PART_BYTES = 20L * 1024 * 1024
    private const val MAX_ROWS = 100_000
    private const val MAX_COLUMNS = 200
    private const val WORKBOOK = "xl/workbook.xml"
    private const val RELS = "xl/_rels/workbook.xml.rels"
    private const val SHARED_STRINGS = "xl/sharedStrings.xml"
    private const val STYLES = "xl/styles.xml"

    /** Built-in number format ids that display a date. */
    private val BUILT_IN_DATE_FMT_IDS = (14..22).toSet() + (45..47).toSet()

    private fun tooLarge() = IllegalArgumentException(AppStrings[StringKey.ErrorXlsxTooLarge])

    private fun unreadable() = IllegalArgumentException(AppStrings[StringKey.ErrorXlsxUnreadable])

    /** The first worksheet, row by row. */
    fun firstSheetRows(bytes: ByteArray): List<List<String>> {
        // Streaming pass over the whole package: bounds the entry count and the total decompressed size and
        // collects the parts that name the first sheet. A second pass collects the parts they point to.
        var parts = scan(bytes, setOf(WORKBOOK, RELS))
        val workbookXml = parts[WORKBOOK] ?: throw unreadable()
        val relsXml = parts[RELS] ?: throw unreadable()

        val workbook = parseXml(workbookXml).documentElement
        val sheetRef = workbook.descendants("sheet").firstOrNull()?.relationshipId() ?: throw unreadable()
        val sheetName = parseXml(relsXml).documentElement
            .children("Relationship")
            .filter { it.getAttribute("Id") == sheetRef }
            .mapNotNull { it.getAttribute("Target") }
            .firstOrNull { target -> !target.startsWith("/") || target.removePrefix("/").isNotEmpty() }
            ?.let(::resolveTargetPath)
            ?: throw unreadable()
        val date1904 = workbook.children("workbookPr").any { it.getAttribute("date1904").equals("true", ignoreCase = true) }

        val wanted = setOf(sheetName, SHARED_STRINGS, STYLES)
        parts = scan(bytes, wanted, known = parts)
        val sheetXml = parts[sheetName] ?: throw unreadable()

        val sharedStrings = parts[SHARED_STRINGS]?.let(::sharedStrings)
        val dateStyles = parts[STYLES]?.let(::dateStyles)
        return rows(parseXml(sheetXml).documentElement, sharedStrings, dateStyles, date1904)
    }

    /**
     * One streaming pass over the ZIP: counts every entry and every decompressed byte against the limits
     * (any entry counts towards the total, not only the ones wanted) and keeps only [wanted] parts.
     */
    private fun scan(bytes: ByteArray, wanted: Set<String>, known: Map<String, ByteArray> = emptyMap()): MutableMap<String, ByteArray> {
        val parts = mutableMapOf<String, ByteArray>()
        try {
            ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                var total = 0L
                var entries = 0
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val entry = zip.nextEntry ?: break
                    entries++
                    if (entries > MAX_ENTRIES) throw tooLarge()
                    val keep = entry.name in wanted
                    // A duplicate wanted entry (cross-scan or within this scan) hides which copy is real
                    if (keep && (entry.name in known || entry.name in parts)) throw unreadable()
                    var count = 0L
                    val kept = if (keep) ByteArrayOutputStream() else null
                    while (true) {
                        val read = zip.read(buffer)
                        if (read < 0) break
                        total += read
                        count += read
                        if (kept != null) {
                            kept.write(buffer, 0, read)
                            if (count > MAX_PART_BYTES) throw tooLarge()
                        }
                        if (total > MAX_TOTAL_BYTES) throw tooLarge()
                    }
                    if (kept != null) parts[entry.name] = kept.toByteArray()
                }
            }
        } catch (e: IOException) {
            // A malformed or truncated ZIP (ZipException is an IOException) is not a binary we can read at all
            throw unreadable()
        }
        return parts
    }

    /** Well-formed XML with hardened parser settings; anything else (DTD, malformed, …) is "unreadable". */
    private fun parseXml(bytes: ByteArray) = try {
        DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            // Untrusted input: no DTDs, no external entities
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
            isXIncludeAware = false
            isExpandEntityReferences = false
            // Untrusted input: bound the element nesting so a pathological sheet becomes "unreadable"
            setAttribute("http://www.oracle.com/xml/jaxp/properties/maxElementDepth", "64")
        }.newDocumentBuilder().parse(ByteArrayInputStream(bytes))
    } catch (e: IllegalArgumentException) {
        throw e
    } catch (e: Exception) {
        throw unreadable()
    }

    /** The relationship id (`r:id`) of a workbook sheet. */
    private fun Element.relationshipId(): String? {
        val attributes = attributes
        return (0 until attributes.length).map { attributes.item(it) }.firstOrNull { it.localName == "id" }?.nodeValue
    }

    /** Target of an internal relationship, resolved relative to `xl/`; a leading `/` is absolute from the package root. */
    private fun resolveTargetPath(target: String): String {
        val segments = (if (target.startsWith("/")) target.removePrefix("/") else "xl/$target").split('/')
        val parts = mutableListOf<String>()
        segments.forEach { segment ->
            when (segment) {
                "", "." -> {}
                ".." -> if (parts.isNotEmpty() && parts.last() != "..") parts.removeAt(parts.lastIndex) else parts += ".."
                else -> parts += segment
            }
        }
        return parts.joinToString("/")
    }

    /** `xl/sharedStrings.xml`: the text of each `<si>`, either a plain `<t>` or concatenated rich-text runs. */
    private fun sharedStrings(xml: ByteArray): List<String> = parseXml(xml)
        .documentElement
        .children("si")
        .map { si ->
            val plain = si.children("t")
            val runs = si.children("r").flatMap { it.children("t") }
            (plain + runs).joinToString("") { it.textContent ?: "" }
        }

    /** `xl/styles.xml`: index within `cellXfs` → whether that cell format displays a date. */
    private fun dateStyles(xml: ByteArray): Map<Int, Boolean> {
        val root = parseXml(xml).documentElement
        val customDates = root
            .descendants("numFmt")
            .mapNotNull { fmt ->
                fmt.getAttribute("numFmtId").toIntOrNull()?.let { id -> id to isDateFormatCode(fmt.getAttribute("formatCode")) }
            }
            .toMap()
        val cellXfs = root.children("cellXfs").firstOrNull() ?: return emptyMap()
        return cellXfs.children("xf")
            .mapIndexedNotNull { index, xf ->
                xf.getAttribute("numFmtId").toIntOrNull()?.let { id ->
                    index to (id in BUILT_IN_DATE_FMT_IDS || customDates[id] == true)
                }
            }
            .toMap()
    }

    /** A custom date format mentions `d` or `y` outside quoted text and sections. */
    private fun isDateFormatCode(code: String): Boolean = code
        .replace(Regex("\"[^\"]*\""), "")
        .replace(Regex("\\[[^\\]]*\\]"), "")
        .lowercase()
        .let { 'd' in it || 'y' in it }

    /** Sheet XML → rows: cells placed by column letter, rows by their `r` attribute. */
    private fun rows(sheet: Element, sharedStrings: List<String>?, dateStyles: Map<Int, Boolean>?, date1904: Boolean): List<List<String>> {
        val result = mutableListOf<List<String>>()
        sheet.descendants("row").forEach { row ->
            val index = row.getAttribute("r").toIntOrNull()?.let { it - 1 } ?: result.size
            if (index < 0 || index >= MAX_ROWS) throw tooLarge()
            val cells = mutableListOf<String>()
            var width = 0
            row.children("c").forEach { cell ->
                val attr = cell.getAttribute("r")
                val explicit = attr.columnIndex()
                val column = if (explicit >= 0) explicit else width
                if (column >= MAX_COLUMNS) throw tooLarge()
                while (cells.size <= column) cells += ""
                cells[column] = cellValue(cell, sharedStrings, dateStyles, date1904)
                width = column + 1
            }
            while (result.size < index) result.add(emptyList())
            if (result.size == index) result.add(cells) else result[index] = cells
        }
        return result
    }

    /** The text of one `<c>` cell, by its `t` attribute. */
    private fun cellValue(cell: Element, sharedStrings: List<String>?, dateStyles: Map<Int, Boolean>?, date1904: Boolean): String {
        val value = cell.children("v").firstOrNull()?.textContent ?: ""
        return when (cell.getAttribute("t")) {
            "s" -> value.trim().toIntOrNull()?.let { sharedStrings?.getOrNull(it) }.orEmpty()

            "inlineStr" -> cell.children("is").firstOrNull()?.let { si ->
                si.children("t") + si.children("r").flatMap { it.children("t") }
            }?.joinToString("") { it.textContent ?: "" }.orEmpty()

            "str" -> value

            "b" -> if (value.trim() == "1" || value.trim().equals("true", ignoreCase = true)) "TRUE" else "FALSE"

            "e" -> ""

            else -> {
                // A formula cell (<f>) uses its cached <v> here too
                val number = value.trim().toBigDecimalOrNull() ?: return ""
                if (dateStyles?.get(cell.getAttribute("s").toIntOrNull() ?: 0) == true) {
                    dateOf(number, date1904).orEmpty()
                } else {
                    number.stripTrailingZeros().toPlainString()
                }
            }
        }
    }

    /** Excel serial number → ISO date (base 1899-12-30, or 1904-01-01 for a 1904 workbook); the fraction is dropped. */
    private fun dateOf(serial: BigDecimal, date1904: Boolean): String? {
        val days = runCatching { serial.setScale(0, RoundingMode.FLOOR).toBigInteger().longValueExact() }.getOrNull() ?: return null
        val base = if (date1904) LocalDate.of(1904, 1, 1) else LocalDate.of(1899, 12, 30)
        return runCatching { base.plusDays(days).toString() }.getOrNull()
    }

    /** "AB" → 27, "B2" → 1; the letters decide and the row digits are ignored. Only gaps wider than the sheet are -1. */
    private fun String.columnIndex(): Int {
        var index = 0
        for (ch in this) {
            val up = ch.uppercaseChar()
            if (up !in 'A'..'Z') break
            index = index * 26 + (up - 'A' + 1)
        }
        return index - 1
    }

    /** Direct child elements with this local name (prefix and default namespace both match). */
    private fun Element.children(name: String): List<Element> = childNodes()
        .filterIsInstance<Element>()
        .filter { it.localName == name }

    /** All descendant elements with this local name, in document order; iterative, so deep XML cannot overflow the stack. */
    private fun Element.descendants(name: String): List<Element> {
        val found = mutableListOf<Element>()
        val stack = ArrayDeque<Element>()
        for (child in childNodes().filterIsInstance<Element>().reversed()) stack.addLast(child)
        while (stack.isNotEmpty()) {
            val element = stack.removeLast()
            if (element.localName == name) {
                found += element
            } else {
                for (child in element.childNodes().filterIsInstance<Element>().reversed()) stack.addLast(child)
            }
        }
        return found
    }

    private fun Element.childNodes(): List<Node> = (0 until childNodes.length).map { childNodes.item(it) }
}
