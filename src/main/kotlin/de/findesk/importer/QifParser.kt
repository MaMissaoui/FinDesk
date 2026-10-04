package de.findesk.importer

import de.findesk.util.Formats
import java.math.BigDecimal
import java.time.LocalDate

/**
 * Reads Quicken Interchange Format (.qif) files with !Type:Bank, !Type:Cash or
 * !Type:CCard records. Supports US (MM/DD/YY) and European (DD/MM/YY) slash
 * dates, dotted dates, apostrophe year separators and comma thousands
 * separators in amounts.
 */
object QifParser {
    private val BOM = 0xFEFF.toChar().toString()
    private val BANK_TYPES = setOf("bank", "cash", "ccard", "oth a", "oth l")
    private val SKIP_TYPES = setOf("cat", "class", "memorized", "invst")

    fun looksLike(bytes: ByteArray): Boolean {
        val head = TextDecoding.decode(bytes).take(1024).removePrefix(BOM)
        val normalised = head.lowercase()
        return normalised.contains("!type:") && (
            normalised.contains("!type:bank") ||
                normalised.contains("!type:cash") ||
                normalised.contains("!type:ccard") ||
                normalised.contains("!type:oth a") ||
                normalised.contains("!type:oth l")
            )
    }

    fun parse(bytes: ByteArray): ParsedStatement {
        if (!looksLike(bytes)) throw IllegalArgumentException("Not a QIF file")
        val text = TextDecoding.decode(bytes).removePrefix(BOM)
        val lines = text.lines()
        val slashOrder = detectSlashOrder(lines)

        val bookings = mutableListOf<ImportedBooking>()
        val skipped = mutableListOf<String>()
        val current = mutableMapOf<Char, String>()
        var activeType: String? = null
        var recordNo = 0

        fun flush() {
            if (current.isEmpty()) return
            recordNo++
            if (activeType in BANK_TYPES) {
                try {
                    bookings += toBooking(current, slashOrder)
                } catch (e: IllegalArgumentException) {
                    skipped += "Record $recordNo: ${e.message}"
                }
            }
            current.clear()
        }

        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.startsWith("!Type:", ignoreCase = true)) {
                flush()
                activeType = trimmed.drop(6).trim().lowercase()
                continue
            }
            if (trimmed.startsWith("!Account", ignoreCase = true) ||
                trimmed.startsWith("!Option:", ignoreCase = true) ||
                trimmed.startsWith("!", ignoreCase = true)
            ) {
                // Header or unsupported type block; flush and ignore until a recognised type starts.
                flush()
                activeType = null
                continue
            }
            if (activeType in SKIP_TYPES) continue
            if (activeType == null) continue
            if (trimmed == "^") {
                flush()
                continue
            }
            if (trimmed.isEmpty()) continue
            val code = trimmed.firstOrNull() ?: continue
            val value = trimmed.drop(1).trim()
            current[code] = (current[code]?.plus("\n") ?: "") + value
        }
        flush()

        return ParsedStatement(StatementFormat.QIF, bookings, skipped = skipped)
    }

    private fun toBooking(fields: Map<Char, String>, slashOrder: SlashOrder): ImportedBooking {
        val dateText = fields['D'] ?: throw IllegalArgumentException("missing date")
        val date = parseQifDate(dateText, slashOrder) ?: throw IllegalArgumentException("invalid date '$dateText'")
        val amountText = fields['T'] ?: fields['U'] ?: throw IllegalArgumentException("missing amount")
        val amount = Formats.parseAmount(amountText) ?: throw IllegalArgumentException("invalid amount '$amountText'")
        return ImportedBooking(
            date = date,
            valueDate = null,
            amount = amount,
            currency = "EUR",
            partnerName = fields['P']?.trim()?.ifEmpty { null },
            purpose = fields['M']?.trim()?.replace(Regex("\\s+"), " ")?.ifEmpty { null },
            bankReference = null,
        )
    }

    private enum class SlashOrder { US, DAY_MONTH, UNKNOWN }

    private fun detectSlashOrder(lines: List<String>): SlashOrder {
        for (line in lines) {
            val text = line.trimStart()
            if (!text.startsWith("D", ignoreCase = true)) continue
            val value = text.drop(1).trim().replace(" ", "")
            if (!value.contains('/') && !value.contains('\'')) continue
            val sepIdx = value.indexOfFirst { it == '/' || it == '\'' }
            if (sepIdx <= 0) continue
            val first = value.substring(0, sepIdx).toIntOrNull() ?: continue
            if (first > 12) return SlashOrder.DAY_MONTH
        }
        return SlashOrder.UNKNOWN
    }

    private fun parseQifDate(text: String, slashOrder: SlashOrder): LocalDate? {
        val t = text.replace(" ", "")
        if (t.contains('.')) {
            val parts = t.split('.')
            if (parts.size != 3) return null
            val day = parts[0].toIntOrNull() ?: return null
            val month = parts[1].toIntOrNull() ?: return null
            val year = parseYear(parts[2]) ?: return null
            return runCatching { LocalDate.of(year, month, day) }.getOrNull()
        }
        val normalised = t.replace('\'', '/').replace('-', '/')
        if (!normalised.contains('/')) return null
        val parts = normalised.split('/')
        if (parts.size != 3) return null
        val first = parts[0].toIntOrNull() ?: return null
        val second = parts[1].toIntOrNull() ?: return null
        val year = parseYear(parts[2]) ?: return null
        val (month, day) = if (slashOrder == SlashOrder.DAY_MONTH) {
            second to first
        } else {
            first to second
        }
        return runCatching { LocalDate.of(year, month, day) }.getOrNull()
    }

    private fun parseYear(text: String): Int? {
        val digits = text.filter { it.isDigit() }
        return when (digits.length) {
            2 -> 2000 + digits.toInt()
            4 -> digits.toInt()
            else -> null
        }
    }
}
