package de.findesk.util

/**
 * Extracts the actual shop from a payment-provider booking, currently for PayPal: the provider is booked as the
 * partner (e.g. "PayPal Europe S.a.r.l. et Cie S.C.A") and the real shop only appears in the purpose, as
 * `"<id> PP.<n>.PP . <Shop>, Ihr Einkauf bei <Shop>"`, usually inside SEPA tags (`EREF+… SVWZ+…`).
 *
 * The result is the input for rules, not just display: the rule matcher (see `RuleMatcher.views` in
 * `de.findesk.repository` and the tax lookup beside it) tests the shop as the partner of its own view before the
 * original text, and [BankText.displayDescription] shows it as the row title. A purpose without the
 * `Ihr Einkauf bei` marker simply gives null, so such a booking is categorized and displayed exactly as before.
 *
 * Pure text work, no I/O; the regexes are compiled once.
 */
object PaymentProviders {
    /** The text that introduces the shop inside a PayPal purpose; the only supported marker. */
    private const val SHOP_MARKER = "Ihr Einkauf bei "

    /** The marker, compiled once, matched ignoring case. */
    private val MARKER = Regex("(?i)" + Regex.escape(SHOP_MARKER))
    private val WHITESPACE = Regex("\\s+")

    private const val MAX_SHOP_LENGTH = 70

    /** The shop the [purpose] buys from, when the cleaned [partnerName] is PayPal; null otherwise (class doc). */
    fun merchantOf(purpose: String?, partnerName: String?): String? {
        val partner = BankText.displayPartner(partnerName) ?: return null
        if (!partner.contains("paypal", ignoreCase = true)) return null
        val text = BankText.displayPurpose(purpose) ?: return null
        val marker = MARKER.find(text) ?: return null
        val shop = text
            .substring(marker.range.last + 1)
            .replace(WHITESPACE, " ")
            .trim()
            .trimEnd('.', ';', ':', ',')
            .take(MAX_SHOP_LENGTH)
            .trimEnd()
        return shop.takeIf { it.isNotBlank() }
    }
}
