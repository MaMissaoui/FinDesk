package de.findesk.util

import de.findesk.i18n.AppLanguage
import de.findesk.i18n.AppStrings
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Currency
import java.util.Locale
import kotlin.math.roundToInt

object Formats {
    private val dateFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy")
    private val dateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")

    /** Uses the app language's decimal/grouping separators ("1.234,56" in German, "1,234.56" in English). */
    private fun decimalFormat() = DecimalFormat("#,##0.00", DecimalFormatSymbols(AppStrings.current.language.locale))

    /** "€1,234.56" / "-€1,234.56"; with [signed], positive amounts get a leading "+". */
    fun money(amount: BigDecimal, currency: String = "EUR", signed: Boolean = false): String {
        val symbol = symbolFor(currency)
        val abs = decimalFormat().format(amount.abs().setScale(2, RoundingMode.HALF_UP))
        val sign = when {
            amount.signum() < 0 -> "-"
            signed && amount.signum() > 0 -> "+"
            else -> ""
        }
        return "$sign$symbol$abs"
    }

    /** Whole currency units for chart axes: "€4,000" / "€4.000". */
    fun wholeMoney(amount: BigDecimal, currency: String = "EUR"): String {
        val abs = DecimalFormat("#,##0", DecimalFormatSymbols(AppStrings.current.language.locale)).format(amount.abs().setScale(0, RoundingMode.HALF_UP))
        return (if (amount.signum() < 0) "-" else "") + symbolFor(currency) + abs
    }

    /**
     * Whole percentages of one share: "49%" in English, "49 %" with a no-break space in German and French.
     * Tiny shares between 0 and 0.5 % read "<1%" / "<1 %"; a zero share shows "0".
     */
    fun percent(share: Double): String {
        val separator = if (AppStrings.current.language == AppLanguage.EN) "" else NO_BREAK_SPACE
        val text = when {
            share <= 0.0 -> "0"
            share < 0.005 -> "<1"
            else -> ((share * 100).roundToInt().coerceIn(0, 100)).toString()
        }
        return text + separator + "%"
    }

    /**
     * Whole percentages of [amounts] against their sum, rounded by the largest remainder, so the list always sums
     * to exactly 100 when it shows every share ([1, 1, 1] → 34, 33, 33). Empty or all-zero lists give all zeros.
     */
    fun shares(amounts: List<BigDecimal>): List<Int> {
        if (amounts.isEmpty()) return emptyList()
        val total = amounts.fold(BigDecimal.ZERO, BigDecimal::add)
        if (total.signum() == 0) return List(amounts.size) { 0 }
        // Fractions of 100 with enough decimals that the remainders compare meaningfully
        val raw = amounts.map { it.multiply(BigDecimal(100)).divide(total, 10, RoundingMode.DOWN) }
        val floors = raw.map { it.setScale(0, RoundingMode.FLOOR).toInt() }
        val missing = 100 - floors.sum()
        val remainders = raw.withIndex().sortedWith(
            compareByDescending<IndexedValue<BigDecimal>> { it.value - it.value.setScale(0, RoundingMode.FLOOR) }.thenBy { it.index },
        ).map { it.index }
        val result = floors.toMutableList()
        remainders.take(missing).forEach { result[it] += 1 }
        return result
    }

    // The separator before "%" in German and French: a plain no-break space, not a literal in the source
    private val NO_BREAK_SPACE: String = 0xA0.toChar().toString()

    fun date(date: LocalDate): String = date.format(dateFormatter)

    /**
     * Parses what users type in date fields: "05.11.2026" or "5.11.2026". Strict, so an impossible day such as
     * 31.02. is rejected instead of silently becoming 28.02. (these dates schedule real transfers).
     */
    fun parseDate(input: String): LocalDate? = runCatching { LocalDate.parse(input.trim(), strictDateInput) }.getOrNull()

    private val strictDateInput = java.time.format.DateTimeFormatter.ofPattern("d.M.uuuu").withResolverStyle(java.time.format.ResolverStyle.STRICT)

    fun dateTime(dateTime: LocalDateTime): String = dateTime.format(dateTimeFormatter)

    /** Groups an IBAN in blocks of four: "DE89 3704 0044 ...". */
    fun iban(iban: String): String = iban.replace(" ", "").chunked(4).joinToString(" ")

    /** Parses user input like "1.234,56", "1,234.56", "-12.5" or "12". Returns null if it isn't a number. */
    fun parseAmount(input: String): BigDecimal? {
        var s = input.trim().replace("€", "").replace(" ", "")
        if (s.isEmpty()) return null
        val lastComma = s.lastIndexOf(',')
        val lastDot = s.lastIndexOf('.')
        s = when {
            lastComma > lastDot -> s.replace(".", "").replace(',', '.')
            else -> s.replace(",", "")
        }
        return s.toBigDecimalOrNull()?.setScale(2, RoundingMode.HALF_UP)
    }

    private fun symbolFor(currency: String): String = when (currency) {
        "EUR" -> "€"
        else -> runCatching { Currency.getInstance(currency).getSymbol(Locale.US) }.getOrDefault("$currency ")
    }
}
