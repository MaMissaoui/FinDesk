package de.findesk.importer

import de.findesk.util.Iban
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import java.math.BigDecimal
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Reads OFX bank statement files, both OFX 1.x SGML (no closing leaf tags) and
 * OFX 2.x XML. Rejects XML files that contain a DOCTYPE / external entity to
 * avoid XXE attacks.
 */
object OfxParser {
    fun looksLike(bytes: ByteArray): Boolean {
        val head = decodeHead(bytes)
        return (head.contains("OFXHEADER:") && head.contains("<OFX>")) ||
            (head.contains("<OFX>") && head.contains("<STMTTRN>"))
    }

    fun parse(bytes: ByteArray): ParsedStatement {
        if (!looksLike(bytes)) throw IllegalArgumentException("Not an OFX file")
        val text = TextDecoding.decode(bytes)
        val isXml = text.trimStart().startsWith("<?xml") || text.contains("</")
        return if (isXml && !isSgml(text)) parseXmlOfx(bytes) else parseSgmlOfx(text)
    }

    /** OFX 1.x SGML: leaf elements have no closing tag, so we extract values by hand. */
    private fun parseSgmlOfx(text: String): ParsedStatement {
        val ofxStart = text.indexOf("<OFX>")
        require(ofxStart >= 0) { "OFX root not found" }
        val body = text.substring(ofxStart)
        val accountIban = extractSgmlAccountId(body)
        val accounts = parseSgmlStatementAccounts(body)
        val currency = extractSgmlCurrency(body)
        val transactions = extractSgmlTransactions(body)
        val bookings = mutableListOf<ImportedBooking>()
        val skipped = mutableListOf<String>()

        transactions.forEachIndexed { i, block ->
            try {
                bookings += parseSgmlTransaction(block, currency)
            } catch (e: IllegalArgumentException) {
                skipped += "Record ${i + 1}: ${e.message}"
            }
        }

        return ParsedStatement(StatementFormat.OFX, bookings, accountIban = accountIban, accounts = accounts, skipped = skipped)
    }

    private fun extractSgmlAccountId(text: String): String? {
        val accountTags = listOf("BANKACCTFROM", "CCACCTFROM", "INVACCTFROM")
        for (tag in accountTags) {
            val block = extractSgmlBlock(text, tag) ?: continue
            val id = extractSgmlLeaf(block, "ACCTID") ?: continue
            val normalized = Iban.normalize(id).takeIf { Iban.isValid(it) }
            if (normalized != null) return normalized
        }
        return null
    }

    private fun extractSgmlCurrency(text: String): String? = extractSgmlLeaf(text, "CURDEF")?.trim()?.uppercase()?.takeIf { it.length == 3 }

    private fun extractSgmlTransactions(text: String): List<String> {
        val result = mutableListOf<String>()
        var start = text.indexOf("<STMTTRN>")
        while (start >= 0) {
            val end = text.indexOf("</STMTTRN>", start)
            if (end < 0) break
            result += text.substring(start, end + "</STMTTRN>".length)
            start = text.indexOf("<STMTTRN>", end)
        }
        return result
    }

    private fun parseSgmlTransaction(block: String, currency: String?): ImportedBooking {
        val amountText = extractSgmlLeaf(block, "TRNAMT") ?: throw IllegalArgumentException("missing amount")
        val amount = parseOfxAmount(amountText) ?: throw IllegalArgumentException("invalid amount '$amountText'")
        val dateText = extractSgmlLeaf(block, "DTPOSTED") ?: throw IllegalArgumentException("missing date")
        val date = parseOfxDate(dateText) ?: throw IllegalArgumentException("invalid date '$dateText'")
        val name = decodeXmlEntities(
            extractSgmlLeaf(block, "NAME")
                ?: extractSgmlLeaf(block, "PAYEE")?.let { extractSgmlLeaf(it, "NAME") },
        )
        val memo = decodeXmlEntities(extractSgmlLeaf(block, "MEMO"))
        val fitid = extractSgmlLeaf(block, "FITID")
        return ImportedBooking(
            date = date,
            valueDate = null,
            amount = amount,
            currency = currency,
            partnerName = name?.trim()?.ifEmpty { null },
            purpose = memo?.trim()?.ifEmpty { null },
            bankReference = fitid?.trim()?.ifEmpty { null },
        )
    }

    private fun extractSgmlBlock(text: String, tag: String): String? {
        val start = text.indexOf("<$tag>")
        if (start < 0) return null
        val end = text.indexOf("</$tag>", start)
        return if (end < 0) null else text.substring(start, end + "</$tag>".length)
    }

    private fun extractSgmlLeaf(text: String, tag: String): String? {
        val start = text.indexOf("<$tag>")
        if (start < 0) return null
        val valueStart = start + tag.length + 2
        val end = text.indexOf("<", valueStart)
        return if (end < 0) text.substring(valueStart) else text.substring(valueStart, end)
    }

    /** OFX 2.x XML: parse with a secure XML parser. */
    private fun parseXmlOfx(bytes: ByteArray): ParsedStatement {
        val doc = parseXml(bytes)
        val root = doc.documentElement
        val accountIban = findAccountId(root)
        val accounts = parseXmlStatementAccounts(root)
        val currency = findCurrency(root)
        val txElements = root.descendants("STMTTRN")
        val bookings = mutableListOf<ImportedBooking>()
        val skipped = mutableListOf<String>()

        txElements.forEachIndexed { i, el ->
            try {
                bookings += parseXmlTransaction(el, currency)
            } catch (e: IllegalArgumentException) {
                skipped += "Record ${i + 1}: ${e.message}"
            }
        }

        return ParsedStatement(StatementFormat.OFX, bookings, accountIban = accountIban, accounts = accounts, skipped = skipped)
    }

    private fun parseXmlTransaction(el: Element, currency: String?): ImportedBooking {
        val amountText = el.child("TRNAMT")?.textContent?.trim()
            ?: throw IllegalArgumentException("missing amount")
        val amount = parseOfxAmount(amountText) ?: throw IllegalArgumentException("invalid amount '$amountText'")
        val dateText = el.child("DTPOSTED")?.textContent?.trim()
            ?: throw IllegalArgumentException("missing date")
        val date = parseOfxDate(dateText) ?: throw IllegalArgumentException("invalid date '$dateText'")
        val name = el.child("NAME")?.textContent?.trim()
            ?: el.child("PAYEE")?.child("NAME")?.textContent?.trim()
        val memo = el.child("MEMO")?.textContent?.trim()
        val fitid = el.child("FITID")?.textContent?.trim()
        return ImportedBooking(
            date = date,
            valueDate = null,
            amount = amount,
            currency = currency,
            partnerName = name?.ifEmpty { null },
            purpose = memo?.ifEmpty { null },
            bankReference = fitid?.ifEmpty { null },
        )
    }

    private fun findAccountId(root: Element): String? {
        val tags = listOf("BANKACCTFROM", "CCACCTFROM", "INVACCTFROM")
        for (tag in tags) {
            val id = root.descendants(tag).firstOrNull()?.child("ACCTID")?.textContent?.trim() ?: continue
            val normalized = Iban.normalize(id).takeIf { Iban.isValid(it) }
            if (normalized != null) return normalized
        }
        return null
    }

    private fun findCurrency(root: Element): String? = root.child("CURDEF")?.textContent?.trim()?.uppercase()?.takeIf { it.length == 3 }
        ?: root.descendants("CURDEF").firstOrNull()?.textContent?.trim()?.uppercase()?.takeIf { it.length == 3 }

    private fun parseSgmlStatementAccounts(text: String): List<String> {
        val result = mutableListOf<String>()
        val tags = listOf("STMTRS", "CCSTMTRS")
        for (tag in tags) {
            var start = text.indexOf("<$tag>")
            while (start >= 0) {
                val end = text.indexOf("</$tag>", start)
                if (end < 0) break
                val block = text.substring(start, end + "</$tag>".length)
                // A block without an account id adds nothing, but the blocks after it still count
                extractSgmlLeaf(block, "ACCTID")?.trim()?.takeIf { it.isNotEmpty() }?.let { id ->
                    val normalized = Iban.normalize(id).takeIf { Iban.isValid(it) } ?: id
                    if (!result.contains(normalized)) result += normalized
                }
                start = text.indexOf("<$tag>", end)
            }
        }
        return result
    }

    private fun parseXmlStatementAccounts(root: Element): List<String> {
        val result = mutableListOf<String>()
        val tags = listOf("STMTRS", "CCSTMTRS")
        val accountTags = listOf("BANKACCTFROM", "CCACCTFROM", "INVACCTFROM")
        for (tag in tags) {
            root.descendants(tag).forEach { block ->
                val id = accountTags.firstNotNullOfOrNull { block.child(it)?.child("ACCTID")?.textContent?.trim() } ?: return@forEach
                val normalized = Iban.normalize(id).takeIf { Iban.isValid(it) } ?: id
                if (!result.contains(normalized)) result += normalized
            }
        }
        return result
    }

    private fun isSgml(text: String): Boolean {
        // OFX 1.x uses a header block and leaf elements without closing tags.
        return text.contains("OFXHEADER:") || !text.contains("</STMTTRN>")
    }

    private fun parseOfxDate(text: String): LocalDate? {
        val digits = text.filter { it.isDigit() }
        if (digits.length < 8) return null
        return runCatching { LocalDate.parse(digits.take(8), DateTimeFormatter.BASIC_ISO_DATE) }.getOrNull()
    }

    private fun parseOfxAmount(text: String): BigDecimal? {
        val t = text.trim()
        // European exports may use a comma decimal ("-45,67"); if there is no dot, treat comma as decimal.
        val normalized = if (!t.contains('.') && t.contains(',')) {
            t.replace(',', '.')
        } else {
            t.replace(",", "")
        }
        return normalized.toBigDecimalOrNull()
    }

    private fun decodeXmlEntities(text: String?): String? {
        if (text == null) return null
        // &amp; last, so "&amp;lt;" stays the literal text "&lt;"
        return text
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&amp;", "&")
    }

    private fun decodeHead(bytes: ByteArray): String = String(bytes, 0, minOf(bytes.size, 4096), Charsets.ISO_8859_1)

    private fun parseXml(bytes: ByteArray) = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
        // Untrusted input: no DTDs, no external entities
        setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        setFeature("http://xml.org/sax/features/external-general-entities", false)
        setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
        isXIncludeAware = false
        isExpandEntityReferences = false
    }.newDocumentBuilder().parse(ByteArrayInputStream(bytes))

    private fun Element.children(name: String): List<Element> = (0 until childNodes.length).map { childNodes.item(it) }.filterIsInstance<Element>().filter { (it.localName ?: it.nodeName) == name }

    private fun Element.child(name: String): Element? = children(name).firstOrNull()

    private fun Element.descendants(name: String): List<Element> {
        val list = getElementsByTagNameNS("*", name)
        return (0 until list.length).map { list.item(it) }.filter { it.nodeType == Node.ELEMENT_NODE }.map { it as Element }
    }
}
