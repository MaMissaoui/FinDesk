package de.findesk.util

import de.findesk.model.TransactionItem

/**
 * Display-only cleanup of raw bank text for lists.
 *
 * The stored `purpose` and `partnerName` never change: rules, search and duplicate detection work on the raw
 * text, and [PaymentProviders] extracts a PayPal shop from the raw purpose. These functions only render the
 * readable part of it in rows and lists — the row title also goes through [PaymentProviders], so a PayPal
 * booking is titled with the provider and the shop its purpose names instead of the partner alone.
 */
object BankText {
    /**
     * SEPA structured-field tags: an upper-case code of 4 letters (`BIC` is 3) directly followed by `+`.
     * Banks write them in upper case, but they are matched case-insensitively to be robust.
     */
    internal val TAG = Regex("(?i)(EREF|KREF|MREF|CRED|DEBT|SVWZ|ABWA|ABWE|IBAN|BIC|PURP|COAM|OAMT|SQTP|BREF|RREF)\\+")

    /** Runs of 3 or more whitespace characters are the bank's padding. */
    private val PADDING = Regex("\\s{3,}")

    /** Any whitespace run collapses to one space. */
    private val WHITESPACE = Regex("\\s+")

    private const val PLACEHOLDER = "—"

    /**
     * The readable partner: splits on the bank's runs of 3 or more padding characters and keeps the first
     * non-blank part; any remaining whitespace run collapses to one space. Blank resolves to null.
     */
    fun displayPartner(raw: String?): String? {
        if (raw == null) return null
        val cleaned = raw
            .split(PADDING)
            .firstOrNull { it.isNotBlank() }
            ?.replace(WHITESPACE, " ")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
        return cleaned
    }

    /**
     * The readable purpose: if an `SVWZ+` field exists, its text up to the next tag; otherwise the text
     * outside all tagged fields (each tagged field runs from its tag to the next tag). A text with no tag
     * at all is returned with whitespace collapsed and no other change. Blank resolves to null.
     */
    fun displayPurpose(raw: String?): String? {
        if (raw == null) return null
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        val matches = TAG.findAll(trimmed).toList()
        val result = when {
            matches.isEmpty() -> trimmed

            // SVWZ holds the actual description; everything after it up to the next tag belongs to it
            matches.any { it.value.startsWith("SVWZ", ignoreCase = true) } ->
                substrings(trimmed, matches).firstOrNull { (tag, _) -> tag.startsWith("SVWZ", ignoreCase = true) }?.second

            else ->
                // Only the text before the first tag is outside the tagged fields
                trimmed.substring(0, matches.first().range.first)
        }
        return result?.replace(WHITESPACE, " ")?.trim()?.takeIf { it.isNotBlank() }
    }

    /**
     * The readable title on every screen: for a PayPal payment "PayPal · <shop>" ([PaymentProviders.titleOf]),
     * else the cleaned partner, else the cleaned purpose, else a placeholder. Used by lists, the details dialog
     * title, confirmations, tax bookings, the document picker, the account panel and the report PDFs. "PayPal" is
     * a brand name, the same in every language. Search, stored text and the raw Partner/Purpose of exports and of
     * the details rows are untouched; exports add a separate Shop column.
     */
    fun displayDescription(t: TransactionItem): String = PaymentProviders.titleOf(t.purpose, t.partnerName)
        ?: displayPartner(t.partnerName)
        ?: displayPurpose(t.purpose)
        ?: PLACEHOLDER

    /**
     * The row's second line: null when [PaymentProviders.merchantOf] names a shop (the title already names the
     * provider and the shop, and the full purpose stays in the details dialog), else the cleaned purpose when the
     * booking has a partner, exactly as before.
     */
    fun secondaryLine(t: TransactionItem): String? = if (PaymentProviders.merchantOf(t.purpose, t.partnerName) != null) {
        null
    } else {
        displayPurpose(t.purpose).takeIf { t.partnerName != null }
    }

    /** Each tagged field as `(tag, text up to the next tag or the end)`; text before the first tag is dropped. */
    private fun substrings(text: String, matches: List<MatchResult>): List<Pair<String, String>> = matches
        .mapIndexed { index, match ->
            val end = matches.getOrNull(index + 1)?.range?.first ?: text.length
            Pair(text.substring(match.range.first, match.range.first + match.value.length), text.substring(match.range.last + 1, end))
        }
}
