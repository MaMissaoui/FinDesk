package de.findesk.document

import de.findesk.model.TaxSource
import de.findesk.model.TransactionItem
import de.findesk.repository.SettingsRepository

/** One suggestion: a booking that a keyword hints is tax relevant, with the keyword that matched. */
data class TaxSuggestion(val booking: TransactionItem, val keyword: String)

/**
 * Suggests tax relevance from keywords in the booking's partner name or purpose. The keywords are generic
 * everyday terms in German, English and French; no brand or personal names, so the list is safe to ship.
 */
object TaxSuggestions {
    /** Settings key: the user's own keywords, one per line; absent (or empty) falls back to [DEFAULT_KEYWORDS]. */
    const val KEY_KEYWORDS = "tax.suggestionKeywords"

    val DEFAULT_KEYWORDS: List<String> = listOf(
        "Spende",
        "Handwerker",
        "Schornsteinfeger",
        "Steuerberater",
        "Lohnsteuerhilfe",
        "Kita",
        "Kindergarten",
        "Tagesmutter",
        "Schulgeld",
        "Krankenkasse",
        "Zahnarzt",
        "Apotheke",
        "Fortbildung",
        "Fachliteratur",
        "Gewerkschaft",
        "Kirchensteuer",
        "Riester",
        "Haftpflicht",
        "donation",
        "charity",
        "tax advisor",
        "tuition",
        "assurance",
    )

    /** The keywords to suggest with: the setting's lines, or the built-in defaults when there is no setting. */
    fun keywords(settings: SettingsRepository): List<String> {
        val stored = settings.getValue(KEY_KEYWORDS) ?: return DEFAULT_KEYWORDS
        val lines = stored.lines().map { it.trim() }.filter { it.isNotEmpty() }
        return lines.ifEmpty { DEFAULT_KEYWORDS }
    }

    /**
     * Every booking of [bookings] that is not effectively tax relevant, was not decided manually (a booking
     * the user decided is never suggested again), does not sit on a transfer category, and matches a keyword
     * case-insensitively in partner name or purpose. A keyword matches only at the start of a word ("Spende" finds
     * "Spenden" and "Kita" finds "Kita-Beitrag", but "Kita" never finds "Akita"), so short keywords don't fire
     * inside unrelated words. The first matching keyword (list order) wins; keywords are trimmed, blanks and ones
     * shorter than 3 characters ignored. Newest first.
     */
    fun find(bookings: List<TransactionItem>, keywords: List<String>): List<TaxSuggestion> {
        val prepared = keywords.map { it.trim() }.filter { it.length >= 3 }.map { it to wordStart(it) }
        if (prepared.isEmpty()) return emptyList()
        return bookings
            .mapNotNull { booking -> match(prepared, booking)?.let { TaxSuggestion(booking, it) } }
            .sortedByDescending { it.booking.date }
    }

    /** Matches [keyword] literally where no letter or digit precedes it, ignoring case. */
    private fun wordStart(keyword: String): Regex = Regex("(?<![\\p{L}\\p{N}])" + Regex.escape(keyword), RegexOption.IGNORE_CASE)

    private fun match(keywords: List<Pair<String, Regex>>, booking: TransactionItem): String? {
        if (booking.isTaxRelevant) return null
        if (booking.taxSource == TaxSource.MANUAL) return null
        if (booking.isTransfer) return null
        return keywords.firstOrNull { (_, regex) ->
            booking.partnerName?.let(regex::containsMatchIn) == true || booking.purpose?.let(regex::containsMatchIn) == true
        }?.first
    }
}
