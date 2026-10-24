package de.findesk.util

/**
 * Identifiers stored in `accounts.iban`. An offline bank's account may name a card instead of an IBAN:
 * the synthetic identifier "CARD" plus exactly the card's last 4 digits (e.g. CARD4321). A full card
 * number is never stored, so the identifier carries nothing sensitive.
 */
object AccountIds {
    /** Prefix of the synthetic identifier of a card account. */
    const val CARD_PREFIX = "CARD"

    /** Synthetic account identifier of a card account: the prefix plus the 4 given digits. */
    fun card(last4: String): String = CARD_PREFIX + last4.trim()

    /** Whether [id] names a card account ("CARD" + 4 digits) instead of an IBAN. */
    fun isCard(id: String): Boolean = id.length == CARD_PREFIX.length + 4 && id.startsWith(CARD_PREFIX) && id.drop(CARD_PREFIX.length).all { it.isDigit() }

    /** Display text of an account identifier: a card's last 4 digits, otherwise the formatted IBAN. */
    fun display(id: String): String = if (isCard(id)) "•••• ${id.takeLast(4)}" else Formats.iban(id)
}
