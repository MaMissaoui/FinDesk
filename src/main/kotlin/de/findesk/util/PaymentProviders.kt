package de.findesk.util

/**
 * Extracts the actual shop from a payment-provider booking, currently for PayPal: the provider is booked as the
 * partner (e.g. "PayPal Europe S.a.r.l. et Cie S.C.A") and the real shop only appears in the purpose, as
 * `"<id> PP.<n>.PP . <Shop>, Ihr Einkauf bei <Shop>"`, usually inside SEPA tags (`EREF+… SVWZ+…`).
 *
 * The result is the input for rules, not just display: the rule matcher (`RuleMatcher.select` in
 * `de.findesk.repository` and the tax lookup beside it) tests the shop as the partner of its own view, and
 * [BankText.displayDescription] shows it as the row title. A purpose without the `Ihr Einkauf bei` marker simply
 * gives null, so such a booking is categorized and displayed exactly as before.
 *
 * Extraction works on the raw purpose, so rules do not depend on [BankText.displayPurpose]; only the partner is
 * cleaned first. Pure text work, no I/O; the regexes are compiled once.
 */
object PaymentProviders {
    /** The text that introduces the shop inside a PayPal purpose; the only supported marker. */
    private const val SHOP_MARKER = "Ihr Einkauf bei "

    /** The marker, compiled once, matched ignoring case. */
    private val MARKER = Regex("(?i)" + Regex.escape(SHOP_MARKER))
    private val WHITESPACE = Regex("\\s+")

    private const val MAX_SHOP_LENGTH = 70

    private const val PROVIDER = "PayPal"

    /** The row title for a [shop] found by [merchantOf]: "PayPal · <shop>", or null without a shop. */
    fun titleOf(purpose: String?, partnerName: String?): String? = merchantOf(purpose, partnerName)?.let { "$PROVIDER · $it" }

    /** The shop the raw [purpose] buys from, when the cleaned [partnerName] is PayPal; null otherwise (class doc). */
    fun merchantOf(purpose: String?, partnerName: String?): String? {
        // Cheap guard before any regex work: a PayPal partner is required in the raw text
        if (partnerName == null || !partnerName.contains("paypal", ignoreCase = true)) return null
        val partner = BankText.displayPartner(partnerName) ?: return null
        if (!partner.contains("paypal", ignoreCase = true)) return null
        val raw = purpose ?: return null

        val text = raw.replace(WHITESPACE, " ").trim()
        val marker = MARKER.find(text) ?: return null
        val tail = text.substring(marker.range.last + 1)
        val nextTag = BankText.TAG.find(tail)
        val shop = (if (nextTag == null) tail else tail.substring(0, nextTag.range.first))
            .trim()
            .trimEnd { it == ',' || it == ';' || it == ':' || it.isWhitespace() }
            .take(MAX_SHOP_LENGTH)
            .trimEnd()
            .let { capped -> if (capped.lastOrNull()?.isHighSurrogate() == true) capped.dropLast(1) else capped }
        return shop.takeIf { candidate -> candidate.isNotBlank() && candidate.any(Char::isLetterOrDigit) }
    }
}
