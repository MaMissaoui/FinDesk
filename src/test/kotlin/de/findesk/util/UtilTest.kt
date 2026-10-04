package de.findesk.util

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class UtilTest {
    @Test
    fun `iban checksum`() {
        assertTrue(Iban.isValid("DE89 3704 0044 0532 0130 00"))
        assertTrue(Iban.isValid("GB82WEST12345698765432"))
        assertFalse(Iban.isValid("DE89 3704 0044 0532 0130 01"))
        assertFalse(Iban.isValid("DE8937040044053201300"), "German IBANs have 22 characters")
        assertFalse(Iban.isValid("not an iban"))
    }

    @Test
    fun `money formatting`() {
        assertEquals("€1,234.50", Formats.money(BigDecimal("1234.5")))
        assertEquals("-€45.67", Formats.money(BigDecimal("-45.67")))
        assertEquals("+€3,200.00", Formats.money(BigDecimal("3200"), signed = true))
        assertEquals("€0.00", Formats.money(BigDecimal.ZERO, signed = true))
    }

    @Test
    fun `amount parsing accepts german and english input`() {
        assertEquals(BigDecimal("1234.56"), Formats.parseAmount("1.234,56"))
        assertEquals(BigDecimal("1234.56"), Formats.parseAmount("1,234.56"))
        assertEquals(BigDecimal("-12.50"), Formats.parseAmount("-12,5"))
        assertEquals(BigDecimal("12.00"), Formats.parseAmount("12 €"))
        assertNull(Formats.parseAmount("abc"))
        assertNull(Formats.parseAmount(""))
    }

    @Test
    fun `iban grouping`() {
        assertEquals("DE89 3704 0044 0532 0130 00", Formats.iban("DE89370400440532013000"))
    }
}

class ShortcutTest {
    @org.junit.jupiter.api.Test
    fun `ctrl or cmd with digits navigates in sidebar order`() {
        val k = androidx.compose.ui.input.key.Key
        org.junit.jupiter.api.Assertions.assertEquals(de.findesk.ui.navigation.Screen.DASHBOARD, de.findesk.ui.navigation.shortcutScreen(k.One, true))
        org.junit.jupiter.api.Assertions.assertEquals(de.findesk.ui.navigation.Screen.SETTINGS, de.findesk.ui.navigation.shortcutScreen(k.Seven, true))
        org.junit.jupiter.api.Assertions.assertEquals(de.findesk.ui.navigation.Screen.SETTINGS, de.findesk.ui.navigation.shortcutScreen(k.Comma, true))
        org.junit.jupiter.api.Assertions.assertNull(de.findesk.ui.navigation.shortcutScreen(k.One, false), "plain digits are typing")
        org.junit.jupiter.api.Assertions.assertNull(de.findesk.ui.navigation.shortcutScreen(k.Eight, true))
    }
}
