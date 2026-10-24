package de.findesk.importer

import de.findesk.util.Iban
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import java.io.File
import java.math.BigDecimal
import java.time.LocalDate
import java.util.zip.ZipInputStream
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Reads ISO 20022 CAMT.053 (and CAMT.052) account statements, as XML or a ZIP of XML files.
 * Namespace versions (camt.053.001.02 … .08) are handled by matching local element names.
 */
object CamtParser {
    fun looksLikeCamt(bytes: ByteArray): Boolean {
        if (isZip(bytes)) return true
        val head = String(bytes, 0, minOf(bytes.size, 2048), Charsets.UTF_8)
        return head.contains("<Document") && (head.contains("camt.053") || head.contains("camt.052") || head.contains("BkToCstmrStmt"))
    }

    fun parse(file: File): ParsedStatement = parse(file.readBytes())

    fun parse(bytes: ByteArray): ParsedStatement {
        val documents = if (isZip(bytes)) unzipXml(bytes) else listOf(bytes)
        val bookings = mutableListOf<ImportedBooking>()
        val skipped = mutableListOf<String>()
        val accounts = mutableListOf<String>()
        val messageTypes = mutableListOf<String>()
        var iban: String? = null
        var entryNo = 0
        documents.forEach { xml ->
            val root = parseXml(xml).documentElement
            messageType(root)?.let { if (!messageTypes.contains(it)) messageTypes += it }
            root.descendants("Stmt").ifEmpty { root.descendants("Rpt") }.forEach { stmt ->
                val acctId = stmt.child("Acct")?.child("Id")
                val acctIban = acctId?.child("IBAN")?.textContent?.let(Iban::normalize)
                val acctOther = acctId?.child("Othr")?.child("Id")?.textContent?.trim()
                val account = acctIban ?: acctOther?.takeIf { it.isNotEmpty() }
                if (account != null && !accounts.contains(account)) accounts += account
                if (iban == null) iban = acctIban
                stmt.children("Ntry").forEach { entry ->
                    entryNo++
                    try {
                        bookings += entry.toBookings()
                    } catch (e: IllegalArgumentException) {
                        skipped += "Entry $entryNo: ${e.message}"
                    }
                }
            }
        }
        return ParsedStatement(
            StatementFormat.CAMT053,
            bookings,
            accountIban = iban,
            accounts = accounts,
            skipped = skipped,
            formatDetail = messageTypes.takeIf { it.isNotEmpty() }?.joinToString("/"),
        )
    }

    /** The document's message type, e.g. "CAMT.052", from the namespace, or the root's first child element. */
    private fun messageType(root: Element): String? = Regex("camt\\.(\\d{3})\\.").find(root.namespaceURI ?: "")
        ?.let { "CAMT.${it.groupValues[1]}" }
        ?: root.firstChildElement()?.let { first ->
            when (first.localName ?: first.nodeName) {
                "BkToCstmrAcctRpt" -> "CAMT.052"
                "BkToCstmrStmt" -> "CAMT.053"
                "BkToCstmrDbtCdtNtfctn" -> "CAMT.054"
                else -> null
            }
        }

    private fun Element.firstChildElement(): Element? = (0 until childNodes.length).map { childNodes.item(it) }.filterIsInstance<Element>().firstOrNull()

    private fun Element.toBookings(): List<ImportedBooking> {
        // Only booked entries; pending ones (PDNG) would be imported twice once they book
        val status = child("Sts")?.let { it.child("Cd")?.textContent ?: it.textContent }?.trim()
        if (status != null && status != "BOOK") return emptyList()

        val date = date(child("BookgDt")) ?: date(child("ValDt")) ?: throw IllegalArgumentException("no booking date")
        val valueDate = date(child("ValDt"))
        val amtEl = child("Amt") ?: throw IllegalArgumentException("no amount")
        val currency = amtEl.getAttribute("Ccy").ifEmpty { null }
        val debit = child("CdtDbtInd")?.textContent?.trim() == "DBIT"
        val entryRef = child("AcctSvcrRef")?.textContent?.trim()

        val details = descendants("TxDtls")
        // Batch bookings carry several TxDtls with their own amounts; otherwise use the entry
        if (details.size > 1 && details.all { it.child("Amt") != null || it.child("AmtDtls") != null }) {
            return details.mapIndexed { i, tx ->
                val txAmount = amount(tx.child("Amt") ?: tx.child("AmtDtls")?.descendants("Amt")?.firstOrNull()) ?: throw IllegalArgumentException("batch item without amount")
                booking(date, valueDate, if (debit) txAmount.negate() else txAmount, currency, tx, debit, entryRef?.let { "$it/$i" })
            }
        }
        val value = amount(amtEl) ?: throw IllegalArgumentException("unreadable amount")
        return listOf(booking(date, valueDate, if (debit) value.negate() else value, currency, details.firstOrNull(), debit, entryRef, fallbackText = child("AddtlNtryInf")?.textContent))
    }

    private fun booking(
        date: LocalDate,
        valueDate: LocalDate?,
        amount: BigDecimal,
        currency: String?,
        tx: Element?,
        debit: Boolean,
        reference: String?,
        fallbackText: String? = null,
    ): ImportedBooking {
        // For a debit the partner is the creditor, for a credit the debtor
        val parties = tx?.child("RltdPties")
        val party = if (debit) parties?.child("Cdtr") else parties?.child("Dbtr")
        val partyName = (party?.child("Pty") ?: party)?.child("Nm")?.textContent?.trim()
        val partyIban = (if (debit) parties?.child("CdtrAcct") else parties?.child("DbtrAcct"))?.child("Id")?.child("IBAN")?.textContent?.let(Iban::normalize)
        val purpose = tx?.child("RmtInf")?.children("Ustrd")?.joinToString(" ") { it.textContent.trim() }?.ifEmpty { null }
            ?: tx?.child("AddtlTxInf")?.textContent?.trim()
            ?: fallbackText?.trim()
        val ref = tx?.child("Refs")?.child("AcctSvcrRef")?.textContent?.trim() ?: reference
        return ImportedBooking(
            date = date,
            valueDate = valueDate,
            amount = amount,
            currency = currency,
            partnerName = partyName?.ifEmpty { null },
            partnerIban = partyIban?.takeIf { Iban.isValid(it) },
            purpose = purpose?.replace(Regex("\\s+"), " ")?.ifEmpty { null },
            bankReference = ref?.takeIf { it.isNotEmpty() && it != "NOTPROVIDED" },
        )
    }

    private fun date(el: Element?): LocalDate? {
        val text = (el?.child("Dt") ?: el?.child("DtTm"))?.textContent?.trim() ?: return null
        return runCatching { LocalDate.parse(text.take(10)) }.getOrNull()
    }

    private fun amount(el: Element?): BigDecimal? = el?.textContent?.trim()?.toBigDecimalOrNull()?.abs()

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

    private fun isZip(bytes: ByteArray) = bytes.size > 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()

    private fun unzipXml(bytes: ByteArray): List<ByteArray> {
        val result = mutableListOf<ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var total = 0L
            generateSequence { zip.nextEntry }.forEach { entry ->
                if (!entry.isDirectory && entry.name.endsWith(".xml", ignoreCase = true)) {
                    val data = zip.readNBytes(MAX_ENTRY_BYTES + 1)
                    require(data.size <= MAX_ENTRY_BYTES) { "ZIP entry ${entry.name} is too large" }
                    total += data.size
                    require(total <= MAX_ZIP_BYTES) { "ZIP content is too large" }
                    result += data
                }
            }
        }
        require(result.isNotEmpty()) { "The ZIP file contains no XML statements" }
        return result
    }

    // Guards against zip bombs; real statements are far smaller
    private const val MAX_ENTRY_BYTES = 50 * 1024 * 1024
    private const val MAX_ZIP_BYTES = 200L * 1024 * 1024

    private fun Element.children(name: String): List<Element> = (0 until childNodes.length).map { childNodes.item(it) }.filterIsInstance<Element>().filter { (it.localName ?: it.nodeName) == name }

    private fun Element.child(name: String): Element? = children(name).firstOrNull()

    private fun Element.descendants(name: String): List<Element> {
        val list = getElementsByTagNameNS("*", name)
        return (0 until list.length).map { list.item(it) }.filter { it.nodeType == Node.ELEMENT_NODE }.map { it as Element }
    }
}
