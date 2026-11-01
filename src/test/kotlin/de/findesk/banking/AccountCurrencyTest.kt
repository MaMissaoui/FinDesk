package de.findesk.banking

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AccountCurrencyTest {
    @Test
    fun `prefers the bank's account currency`() {
        assertEquals("CHF", Hbci4JavaGateway.accountCurrency("CHF", "EUR"))
    }

    @Test
    fun `falls back to the balance currency when the account has none`() {
        assertEquals("CHF", Hbci4JavaGateway.accountCurrency(null, "CHF"))
    }

    @Test
    fun `treats a blank account currency as missing and upper-cases the balance currency`() {
        assertEquals("CHF", Hbci4JavaGateway.accountCurrency("", "chf"))
    }

    @Test
    fun `defaults to EUR when neither currency is known`() {
        assertEquals("EUR", Hbci4JavaGateway.accountCurrency(null, null))
    }

    @Test
    fun `defaults to EUR when both currencies are blank`() {
        assertEquals("EUR", Hbci4JavaGateway.accountCurrency(" ", " "))
    }

    @Test
    fun `only a currency the bank states counts as stated`() {
        assertTrue(Hbci4JavaGateway.accountCurrencyStated("CHF"))
        assertFalse(Hbci4JavaGateway.accountCurrencyStated(null))
        assertFalse(Hbci4JavaGateway.accountCurrencyStated(""))
        assertFalse(Hbci4JavaGateway.accountCurrencyStated("  "))
    }
}
