package de.findesk.util

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

    fun date(date: LocalDate): String = date.format(dateFormatter)

    /** Parses what users type in date fields: "05.11.2026" or "5.11.2026". */
    fun parseDate(input: String): LocalDate? = runCatching { LocalDate.parse(input.trim(), java.time.format.DateTimeFormatter.ofPattern("d.M.yyyy")) }.getOrNull()

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
