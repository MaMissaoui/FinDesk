package de.findesk.util

import de.findesk.repository.SettingsRepository
import java.math.BigDecimal

/**
 * Amounts in different currencies are never added up or converted (there are no exchange rates offline).
 * Overviews that combine accounts (dashboard, reports, budgets) use the primary currency; other currencies
 * are shown next to it as separate totals.
 */
object Currencies {
    const val KEY_PRIMARY = "money.primary_currency"
    const val DEFAULT = "EUR"

    /** Offered when adding an account or choosing the primary currency. */
    val COMMON = listOf("EUR", "USD", "GBP", "CHF", "PLN", "CZK", "SEK", "DKK", "NOK", "HUF", "JPY")

    fun primary(settings: SettingsRepository): String = settings.getValue(KEY_PRIMARY)?.takeIf { it.length == 3 } ?: DEFAULT

    /** Sums [amount] per currency, primary currency first. */
    fun <T> totals(items: List<T>, primary: String, currency: (T) -> String, amount: (T) -> BigDecimal): Map<String, BigDecimal> = items
        .groupBy(currency)
        .mapValues { (_, group) -> group.fold(BigDecimal.ZERO) { acc, item -> acc + amount(item) } }
        .toList()
        .sortedWith(compareBy({ it.first != primary }, { it.first }))
        .toMap()

    /** "€1,234.00 · $50.00"; zero when there is nothing. */
    fun format(totals: Map<String, BigDecimal>, primary: String): String = if (totals.isEmpty()) Formats.money(BigDecimal.ZERO, primary) else totals.entries.joinToString(" · ") { (c, v) -> Formats.money(v, c) }
}
