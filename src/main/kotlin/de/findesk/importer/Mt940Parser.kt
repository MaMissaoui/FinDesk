package de.findesk.importer

import de.findesk.util.Iban
import java.math.BigDecimal
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.time.LocalDate

/**
 * Reads SWIFT MT940 account statement files (.sta, .mt940, .txt).
 *
 * Handles the subset produced by German retail banks: tags :20: :25: :28C: :60F:/:60M:
 * :61: :86: :62F:/:62M:, statement blocks separated by "-" lines, structured :86:
 * fields (German "Verwendungszweck"), and Windows-1252 / UTF-8 encoding.
 */
object Mt940Parser {
    fun looksLike(bytes: ByteArray): Boolean {
        val head = decodeHead(bytes)
        return head.contains(":20:") && head.contains(":25:") && (head.contains(":61:") || head.contains(":60F:") || head.contains(":62F:"))
    }

    fun parse(bytes: ByteArray): ParsedStatement {
        if (!looksLike(bytes)) throw IllegalArgumentException("Not an MT940 file")
        val text = decode(bytes)
        val statements = splitStatements(text)
        val bookings = mutableListOf<ImportedBooking>()
        val skipped = mutableListOf<String>()
        var accountIban: String? = null
        var recordNo = 0

        statements.forEach { stmt ->
            accountIban = accountIban ?: extractIban(stmt)
            val currency = extractCurrency(stmt) ?: "EUR"
            val tags = parseTags(stmt)

            var i = 0
            while (i < tags.size) {
                if (tags[i].name != "61") {
                    i++
                    continue
                }
                val entryText = tags[i].content
                val infoText = if (i + 1 < tags.size && tags[i + 1].name == "86") {
                    i += 2
                    tags[i - 1].content
                } else {
                    i++
                    null
                }
                recordNo++
                try {
                    bookings += parseRecord(entryText, infoText, currency)
                } catch (e: IllegalArgumentException) {
                    skipped += "Record $recordNo: ${e.message}"
                }
            }
        }

        return ParsedStatement(
            format = StatementFormat.MT940,
            bookings = bookings,
            accountIban = accountIban,
            skipped = skipped,
        )
    }

    private fun decodeHead(bytes: ByteArray): String = String(bytes, 0, minOf(bytes.size, 4096), Charsets.ISO_8859_1)

    private fun decode(bytes: ByteArray): String = TextDecoding.decode(bytes)

    /**
     * Splits a file into statement blocks. A line that consists only of '-' (possibly with
     * whitespace and trailing tags) separates statements; some files also start with :20:.
     */
    private fun splitStatements(text: String): List<String> {
        val blocks = mutableListOf<String>()
        val current = StringBuilder()
        val dashLine = Regex("^\\s*-+\\s*$")
        text.lines().forEach { line ->
            if (dashLine.matches(line)) {
                if (current.isNotBlank()) {
                    blocks += current.toString()
                    current.clear()
                }
            } else {
                current.appendLine(line)
            }
        }
        if (current.isNotBlank()) blocks += current.toString()
        return blocks.ifEmpty { listOf(text) }
    }

    /**
     * Parses tags only when they appear at the start of a line. This prevents a colon
     * inside field content (e.g. a time "14:30:15" or structured purpose "?30:")
     * from being misread as a tag.
     */
    private fun parseTags(text: String): List<Tag> {
        val tags = mutableListOf<Tag>()
        var current: Tag? = null
        val tagStart = Regex("^:(\\d{2}[a-zA-Z]?):(.*)$")

        fun flush() {
            current?.let { tags += it }
            current = null
        }

        text.lines().forEach { line ->
            val match = tagStart.matchEntire(line)
            if (match != null) {
                flush()
                current = Tag(match.groupValues[1], match.groupValues[2])
            } else if (current != null) {
                current = current!!.copy(content = current!!.content + "\n" + line)
            }
        }
        flush()
        return tags
    }

    private data class Tag(val name: String, val content: String)

    private fun extractIban(text: String): String? {
        val raw = parseTags(text).firstOrNull { it.name == "25" }?.content?.trim() ?: return null
        // :25: may be "BLZ/Konto" or just an IBAN; some banks append the currency to the IBAN.
        val candidate = raw.substringAfterLast("/").trim()
        val normalized = Iban.normalize(candidate).takeIf { Iban.isValid(it) }
        if (normalized != null) return normalized
        if (candidate.length > 3 && candidate.takeLast(3).all { it.isLetter() }) {
            return Iban.normalize(candidate.dropLast(3)).takeIf { Iban.isValid(it) }
        }
        return null
    }

    private fun extractCurrency(text: String): String? = extractBalanceTag(text, "60F")?.currency
        ?: extractBalanceTag(text, "60M")?.currency
        ?: extractBalanceTag(text, "62F")?.currency

    private data class Balance(val amount: BigDecimal, val currency: String)

    private fun extractBalanceTag(text: String, tag: String): Balance? {
        val content = parseTags(text).firstOrNull { it.name == tag }?.content?.trim() ?: return null
        // :60F: or :62F: layout: C or D + YYMMDD + currency + amount
        if (content.length < 10) return null
        val sign = if (content[0] == 'D') BigDecimal.ONE.negate() else BigDecimal.ONE
        val currency = content.substring(7, 10)
        val amountText = content.substring(10).trim()
        val amount = parseAmount(amountText) ?: return null
        return Balance(amount.multiply(sign), currency)
    }

    private fun parseRecord(text: String, infoText: String?, currency: String): ImportedBooking {
        // :61: may continue onto a second line with supplementary details; only the first line matters.
        val line = text.substringBefore('\n').trim()
        // :61: YYMMDD[MMDD]CD|D|RC|RD[funds] amount Ncode+reference
        if (line.length < 8) throw IllegalArgumentException("too short")
        val valueDate = parseMtDate(line.substring(0, 6))
            ?: throw IllegalArgumentException("invalid value date '${line.substring(0, 6)}'")

        var pos = 6
        var entryDate: LocalDate? = null
        if (pos + 4 <= line.length && line.substring(pos, pos + 4).all { it.isDigit() }) {
            entryDate = inferBookingDate(valueDate, line.substring(pos, pos + 4))
            pos += 4
        }
        val bookingDate = entryDate ?: valueDate

        val indicator = when {
            line.startsWith("RC", pos) -> {
                pos += 2
                "RC"
            }

            line.startsWith("RD", pos) -> {
                pos += 2
                "RD"
            }

            line.startsWith("C", pos) -> {
                pos += 1
                "C"
            }

            line.startsWith("D", pos) -> {
                pos += 1
                "D"
            }

            else -> throw IllegalArgumentException("missing debit/credit indicator")
        }

        // Optional single-letter funds code (e.g. R for EUR) follows the debit/credit mark.
        if (pos < line.length && line[pos].isLetter()) pos++

        // Amount runs until the transaction-details marker (N/S/F) or end of amount-like text.
        val amountStart = pos
        while (pos < line.length && (line[pos].isDigit() || line[pos] == ',' || line[pos] == '.')) pos++
        if (pos == amountStart) throw IllegalArgumentException("missing amount")
        val rawAmount = parseAmount(line.substring(amountStart, pos))
            ?: throw IllegalArgumentException("invalid amount '${line.substring(amountStart, pos)}'")

        val amount = when (indicator) {
            "C", "RC" -> rawAmount
            "D", "RD" -> rawAmount.negate()
            else -> rawAmount
        }

        // Customer reference after N/S/F + 3-character transaction type code.
        val detailStart = if (pos < line.length && line[pos] in "NSF") pos + 4 else pos
        val detailTail = line.substring(detailStart).trim()
        val bankRef = detailTail.substringAfter("//", "").trim().takeIf { it.isNotEmpty() }
        val customerRef = detailTail.substringBefore("//", detailTail).trim()
            .takeIf { it.isNotEmpty() && it != "NONREF" }
        val reference = bankRef ?: customerRef

        val info = infoText?.let { parse86(it) }
        val purpose = info?.purpose ?: infoText?.trim()?.takeIf { it.isNotEmpty() }
        return ImportedBooking(
            date = bookingDate,
            valueDate = valueDate,
            amount = amount,
            currency = currency,
            partnerName = info?.partnerName,
            partnerIban = info?.partnerIban?.let(Iban::normalize)?.takeIf { Iban.isValid(it) },
            purpose = purpose,
            bankReference = reference,
        )
    }

    private fun parseMtDate(text: String): LocalDate? {
        if (text.length != 6 || !text.all { it.isDigit() }) return null
        val year = 2000 + text.substring(0, 2).toInt()
        val month = text.substring(2, 4).toInt()
        val day = text.substring(4, 6).toInt()
        return runCatching { LocalDate.of(year, month, day) }.getOrNull()
    }

    private fun inferBookingDate(valueDate: LocalDate, entryDateMmdd: String): LocalDate? {
        val month = entryDateMmdd.substring(0, 2).toInt()
        val day = entryDateMmdd.substring(2, 4).toInt()
        var year = valueDate.year
        // Entry date (booking date) can roll over year boundaries around New Year.
        if (valueDate.monthValue == 12 && month == 1) year++
        if (valueDate.monthValue == 1 && month == 12) year--
        return runCatching { LocalDate.of(year, month, day) }.getOrNull()
    }

    private data class StructuredInfo(
        val partnerName: String? = null,
        val partnerIban: String? = null,
        val purpose: String? = null,
    )

    private fun parse86(text: String): StructuredInfo {
        val trimmed = text.trim()
        val isStructured = trimmed.startsWith("?") || Regex("^\\d{3}\\?").containsMatchIn(trimmed.replace("\n", ""))
        if (!isStructured) {
            return StructuredInfo(purpose = trimmed.replace(Regex("\\s+"), " ").ifEmpty { null })
        }

        // For structured text, line breaks are fixed-width wrapping and must disappear entirely
        // (they can fall inside a subfield marker like "?2\n1").
        val compact = trimmed.replace("\n", "")
        // Drop the optional 3-digit Geschäftsvorfallcode at the front.
        val body = compact.replaceFirst(Regex("^\\d{3}"), "")

        val fields = Regex("\\?(\\d{2})")
            .findAll(body)
            .map { it.groupValues[1] to it.range }
            .toList()
        val values = mutableListOf<Pair<String, String>>()
        for (i in fields.indices) {
            val (key, range) = fields[i]
            val end = if (i + 1 < fields.size) fields[i + 1].second.first else body.length
            values += key to body.substring(range.last + 1, end)
        }

        val purpose = values
            .filter { it.first.toIntOrNull() in 20..29 || it.first.toIntOrNull() in 60..63 }
            .joinToString("") { it.second.trim() }
            .ifEmpty { null }
            ?: values.firstOrNull { it.first == "00" }?.second?.trim()
            ?: values.firstOrNull { it.first == "10" }?.second?.trim()

        val partnerName = values
            .filter { it.first == "32" || it.first == "33" }
            .joinToString("") { it.second.trim() }
            .ifEmpty { null }
        val partnerIban = values.firstOrNull { it.first == "31" }?.second?.trim()
        return StructuredInfo(
            partnerName = partnerName,
            partnerIban = partnerIban?.ifEmpty { null },
            purpose = purpose?.ifEmpty { null },
        )
    }

    private fun parseAmount(text: String): BigDecimal? {
        val normalized = text.replace(".", "").replace(',', '.')
        return normalized.toBigDecimalOrNull()
    }
}

/**
 * Shared bank-text decoding: UTF-8 if the bytes are valid UTF-8, otherwise
 * Windows-1252 (the encoding most German bank exports use).
 */
internal object TextDecoding {
    private val WINDOWS_1252: Charset = Charset.forName("windows-1252")

    fun decode(bytes: ByteArray): String = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (e: CharacterCodingException) {
        String(bytes, WINDOWS_1252)
    }
}
