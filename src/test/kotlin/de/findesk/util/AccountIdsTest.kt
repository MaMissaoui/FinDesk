package de.findesk.util

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The synthetic card identifier of offline banks' accounts, and how an IBAN stays one. */
class AccountIdsTest {
    private val iban = "DE89370400440532013000"

    @Test
    fun `a Norwegian IBAN is valid and normalizes unchanged`() {
        // Synthetic identifier from the ISO 3116 test set, no real account
        assertTrue(Iban.isValid("NO9386011117947"))
        assertEquals("NO9386011117947", Iban.normalize("NO93 8601 1117 947"))
    }

    @Test
    fun `a card identifier is not an IBAN`() {
        assertEquals("CARD4321", AccountIds.card("4321"))
        assertTrue(AccountIds.isCard("CARD4321"))
        assertFalse(Iban.isValid("CARD4321"), "a card id has no IBAN length")
        assertEquals("CARD4321", Iban.normalize("CARD4321"), "a card id is left unchanged")
        assertFalse(AccountIds.isCard(iban))
        assertFalse(AccountIds.isCard("CARD432"))
        assertFalse(AccountIds.isCard("CARD43219"))
        assertFalse(AccountIds.isCard("HEAD12"))
    }

    @Test
    fun `display shows a card's last 4 digits and formats an IBAN as before`() {
        assertEquals("•••• 4321", AccountIds.display("CARD4321"))
        assertEquals(Formats.iban(iban), AccountIds.display(iban))
    }
}
