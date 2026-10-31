package de.findesk.util

/**
 * Extracts the actual shop from a payment-provider booking, currently for PayPal. The provider is booked as the
 * partner (e.g. "PayPal Europe S.a.r.l. et Cie S.C.A") and the real shop appears in the purpose in one of three
 * known formats: the `Ihr Einkauf bei <Shop>` marker (usually inside SEPA tags), a pre-comma name without that
 * marker, or a card descriptor such as `PAYPAL *<SHOP>`.
 *
 * The result is the input for rules, not just display: the rule matcher (`RuleMatcher.select` in
 * `de.findesk.repository` and the tax lookup beside it) tests the shop as the partner of its own view, and
 * [BankText.displayDescription] shows it as the row title. A booking that names no shop stays null, so it is
 * categorized and displayed exactly as before.
 *
 * Extraction works on the raw purpose, so rules do not depend on [BankText.displayPurpose]; the partner is only
 * inspected for the provider. Pure text work, no I/O; the regexes are compiled once.
 */
object PaymentProviders {
    /**
     * A shop extracted from a booking plus the provider view of that booking: the booking's text with the shop
     * removed. [RuleMatcher][de.findesk.repository.RuleMatcher] uses the view to recognise a provider rule.
     */
    data class ShopMatch(val shop: String, val providerPurpose: String?, val providerPartner: String?)

    /** The marker, compiled once, matched ignoring case and tolerating one space between any two letters. */
    private val MARKER = Regex("(?i)${flexible("Ihr")}\\s+${flexible("Einkauf")}\\s+${flexible("bei")}")

    /** `Ihr` at the start of the words after a pre-comma name, with the same letter spacing tolerance. */
    private val IHR = flexible("Ihr") + "\\b"

    /** An id (`PP.<n>.PP`, optionally with the usual one-letter suffix) before a comma and `Ihr`. */
    private val PRE_COMMA = Regex("(?i)PP\\.\\d+\\.PP[A-Z]?\\s*\\.?\\s*(.+?)\\s*,\\s*$IHR")

    /** The same id when looking for the pre-comma name before the marker. */
    private val PP_ID_IN_PURPOSE = Regex("(?i)PP\\.\\d+\\.PP[A-Z]?")

    /** A card descriptor: `PAYPAL` then optional spaces then `*`. */
    private val CARD_START = Regex("(?i)^PAYPAL\\s*\\*")

    /** The end of a card descriptor: an all-digit token or a comma followed by digits. */
    private val CARD_STOP = Regex("(?i)(?:\\s+|^)\\d+(?=\\s|$)|,\\s*\\d")

    /** One trailing code token, such as `, AB12CD34`, that follows a shop in the marker format. */
    private val CODE_TAIL = Regex(",\\s*\\S*\\d\\S*$")

    /** A `PP.<n>.PP` id that must never become a shop. */
    private val PP_ID = Regex("(?i)PP\\.\\d+\\.PP")

    private val WHITESPACE = Regex("\\s+")

    private const val MAX_SHOP_LENGTH = 70

    private const val PROVIDER = "PayPal"

    private val PROVIDER_PURPOSE = "PAYPAL"

    private val NON_SHOPS = setOf("ihr", "einkauf", "bei", "abbuchung", "vom", "transfer")

    /** The row title for a [shop] found by [merchantOf]: "PayPal · <shop>", or null without a shop. */
    fun titleOf(purpose: String?, partnerName: String?): String? = merchantOf(purpose, partnerName)?.let { "$PROVIDER · $it" }

    /** The shop the raw [purpose] buys from, or null when the booking names no shop (class doc). */
    fun merchantOf(purpose: String?, partnerName: String?): String? = match(purpose, partnerName)?.shop

    /**
     * The shop named by [purpose] for the PayPal [partnerName], with the provider view that
     * [de.findesk.repository.RuleMatcher] uses to recognise a provider rule. Null when no format yields a shop
     * that passes the quality gate.
     *
     * Formats are tried in this order: the `Ihr Einkauf bei` marker, a pre-comma name without that marker, and a
     * card descriptor (`PAYPAL *<SHOP>`). The marker format prefers the pre-comma name when it is the fuller one.
     */
    fun match(purpose: String?, partnerName: String?): ShopMatch? {
        val partnerHasPaypal = partnerName?.contains("paypal", ignoreCase = true) == true
        val text = purpose?.replace(WHITESPACE, " ")?.trim().orEmpty()

        // 1. Marker format, e.g. "PP.0471.PPX . <Shop>, Ihr Einkauf bei <Shop>"
        val marker = if (partnerHasPaypal) MARKER.find(text) else null
        if (marker != null) {
            val tail = text.substring(marker.range.last + 1)
            val nextTag = BankText.TAG.find(tail)
            val postRaw = if (nextTag == null) tail else tail.substring(0, nextTag.range.first)
            val preRaw = preCommaName(text, marker.range.first)
            val post = sanitize(postRaw)
            val pre = sanitize(preRaw)
            val shop = when {
                post == null -> pre
                pre == null -> post
                else -> preferred(pre, post)
            }
            if (shop != null) return ShopMatch(shop, null, partnerName)
        }

        // 2. A pre-comma name without the marker, e.g. "PP.1234.PP . <Shop>, Ihr Abo"
        if (partnerHasPaypal && marker == null) {
            PRE_COMMA.find(text)?.let { found ->
                sanitize(found.groupValues[1])?.let { return ShopMatch(it, null, partnerName) }
            }
        }

        // 3. Card format, e.g. "PAYPAL *<SHOP>" with a blank or PayPal partner
        val cardStart = CARD_START.find(text)
        if (cardStart != null && (partnerName.isNullOrBlank() || partnerHasPaypal)) {
            val afterStar = text.substring(cardStart.range.last + 1)
            val stop = CARD_STOP.find(afterStar)
            val shopRaw = if (stop == null) afterStar else afterStar.substring(0, stop.range.first)
            sanitize(shopRaw)?.let {
                return ShopMatch(it, PROVIDER_PURPOSE, if (partnerHasPaypal) PROVIDER_PURPOSE else null)
            }
        }

        return null
    }

    /** One word with an optional space between any two letters, so bank line breaks do not hide the marker. */
    private fun flexible(word: String): String = word.map { Regex.escape(it.toString()) }.joinToString("\\s?")

    /** The `<name>` between a `PP.<n>.PP` id and the comma immediately before the marker, or null. */
    private fun preCommaName(text: String, markerStart: Int): String? {
        val prefix = text.substring(0, markerStart)
        val id = PP_ID_IN_PURPOSE.findAll(prefix).lastOrNull() ?: return null
        val rest = prefix.substring(id.range.last + 1)
            .trim()
            .removePrefix(".")
            .trim()
        if (!rest.endsWith(",")) return null
        return rest.dropLast(1).trim().takeIf { it.isNotEmpty() }
    }

    /** The fuller of the pre-comma name and the post-marker shop when both pass the gate. */
    private fun preferred(pre: String, post: String): String {
        val normalizedPre = normalize(pre)
        val normalizedPost = normalize(post)
        return when {
            normalizedPre == normalizedPost ->
                if (pre.count { it.isWhitespace() } < post.count { it.isWhitespace() }) pre else post

            normalizedPre.startsWith(normalizedPost) -> if (normalizedPre.length > normalizedPost.length) pre else post

            normalizedPost.startsWith(normalizedPre) -> if (normalizedPost.length > normalizedPre.length) post else pre

            else -> post
        }
    }

    private fun normalize(shop: String): String = shop.filterNot { it.isWhitespace() }.lowercase()

    /**
     * Cleans a candidate shop and rejects text that only looks like one: ids, codes, provider names and the
     * marker words themselves. The code tail is stripped before leading punctuation, so `, AB12CD34` leaves
     * nothing rather than a bare code.
     */
    private fun sanitize(raw: String?): String? {
        if (raw == null) return null
        var candidate = raw.replace(WHITESPACE, " ").trim()
        candidate = candidate.replace(CODE_TAIL, "")
        candidate = candidate.trimStart { !it.isLetterOrDigit() }
        candidate = candidate.trimEnd { it == ',' || it == ';' || it == ':' || it.isWhitespace() }
        candidate = candidate.take(MAX_SHOP_LENGTH).trimEnd()
        if (candidate.lastOrNull()?.isHighSurrogate() == true) candidate = candidate.dropLast(1)

        if (candidate.length < 2) return null
        if (candidate.none(Char::isLetter)) return null
        if (candidate.all(Char::isDigit)) return null
        if (candidate.contains("paypal", ignoreCase = true)) return null
        if (PP_ID.containsMatchIn(candidate)) return null
        if (candidate.lowercase() in NON_SHOPS) return null
        return candidate
    }
}
