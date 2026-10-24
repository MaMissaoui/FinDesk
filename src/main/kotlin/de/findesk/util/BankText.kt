package de.findesk.util

import de.findesk.model.TransactionItem

/**
 * Display-only cleanup of raw bank text for lists.
 *
 * The stored `purpose` and `partnerName` never change: rules, search and duplicate detection are written
 * against the raw text. These functions only render the readable part of it in rows and lists.
 */
object BankText {
    /**
     * SEPA structured-field tags: an upper-case code of 4 letters (`BIC` is 3) directly followed by `+`.
     * Banks write them in upper case, but they are matched case-insensitively to be robust.
     */
    private val TAG = Regex("(?i)(EREF|KREF|MREF|CRED|DEBT|SVWZ|ABWA|ABWE|IBAN|BIC|PURP|COAM|OAMT|SQTP|BREF|RREF)\\+")

    private const val PLACEHOLDER = "—"

    /**
     * The readable partner: splits on the bank's runs of 3 or more padding characters and keeps the first
     * non-blank part; any remaining whitespace run collapses to one space. Blank resolves to null.
     */
    fun displayPartner(raw: String?): String? {
        if (raw == null) return null
        val cleaned = raw
            .split(Regex("\\s{3,}"))
            .firstOrNull { it.isNotBlank() }
            ?.replace(Regex("\\s+"), " ")
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
        return result?.replace(Regex("\\s+"), " ")?.trim()?.takeIf { it.isNotBlank() }
    }

    /** The readable row title: the cleaned partner, else the cleaned purpose, else a placeholder. */
    fun displayDescription(t: TransactionItem): String = displayPartner(t.partnerName) ?: displayPurpose(t.purpose) ?: PLACEHOLDER

    /** Each tagged field as `(tag, text up to the next tag or the end)`; text before the first tag is dropped. */
    private fun substrings(text: String, matches: List<MatchResult>): List<Pair<String, String>> = matches
        .mapIndexed { index, match ->
            val end = matches.getOrNull(index + 1)?.range?.first ?: text.length
            Pair(text.substring(match.range.first, match.range.first + match.value.length), text.substring(match.range.last + 1, end))
        }
}
