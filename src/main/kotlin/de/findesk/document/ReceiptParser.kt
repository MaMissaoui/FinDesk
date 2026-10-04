package de.findesk.document

import java.math.BigDecimal
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/**
 * Heuristic extraction of the total amount and date from receipt/invoice text (German and English).
 */
object ReceiptParser {
    data class Fields(val total: BigDecimal?, val date: LocalDate?, val amounts: List<BigDecimal>)

    // 1.234,56 | 1234,56 | 1,234.56 | 1234.56  (exactly two decimals)
    private val amountRegex = Regex("""(?<![\d.,])(\d{1,3}(?:[.,\s]\d{3})+|\d+)([.,])(\d{2})(?!\d)(?![.,]\d)""")
    private val totalKeywords = Regex(
        """(?i)\b(gesamtbetrag|gesamtsumme|gesamt|summe|zu zahlen|endbetrag|rechnungsbetrag|total|amount due|betrag)\b""",
    )
    private val dateRegexes = listOf(
        Regex("""\b(\d{1,2})\.(\d{1,2})\.(\d{4})\b""") to "d.M.yyyy",
        Regex("""\b(\d{4})-(\d{2})-(\d{2})\b""") to "yyyy-MM-dd",
        Regex("""\b(\d{1,2})\.(\d{1,2})\.(\d{2})\b""") to "d.M.yy",
        Regex("""\b(\d{1,2})/(\d{1,2})/(\d{4})\b""") to "d/M/yyyy",
    )

    fun parse(text: String): Fields {
        val amounts = mutableListOf<BigDecimal>()
        var keywordTotal: BigDecimal? = null

        text.lineSequence().forEach { line ->
            val lineAmounts = amountRegex.findAll(line).mapNotNull { toAmount(it) }.toList()
            amounts += lineAmounts
            if (totalKeywords.containsMatchIn(line) && lineAmounts.isNotEmpty()) {
                // The last keyword line wins (totals tend to come after subtotals)
                keywordTotal = lineAmounts.last()
            }
        }

        return Fields(
            total = keywordTotal ?: amounts.maxOrNull(),
            date = findDate(text),
            amounts = amounts.distinct(),
        )
    }

    private fun toAmount(m: MatchResult): BigDecimal? {
        val (intPart, sep, decimals) = m.destructured
        // The grouping separator must differ from the decimal separator ("1.234,56" or "1,234.56")
        if (intPart.contains(sep)) return null
        val digits = intPart.replace(".", "").replace(",", "").replace(" ", "")
        return "$digits.$decimals".toBigDecimalOrNull()
    }

    private fun findDate(text: String): LocalDate? {
        for ((regex, pattern) in dateRegexes) {
            regex.findAll(text).forEach { m ->
                try {
                    val date = LocalDate.parse(m.value, DateTimeFormatter.ofPattern(pattern))
                    if (date.year in 1990..2100) return date
                } catch (_: DateTimeParseException) {
                    // try next
                }
            }
        }
        return null
    }
}
