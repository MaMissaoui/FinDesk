package de.findesk.util

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.math.BigDecimal

/**
 * The cap on numbers parsed from untrusted file text: a huge exponent parses cheaply but hangs or exhausts
 * memory once it is scaled or printed, so `parseDecimal` rejects such values without expanding them.
 */
class NumberCapTest {
    private val digits65 = "12345678901234567890123456789012345678901234567890123456789012345" // 65 digits

    @Test
    @Timeout(5)
    fun `parseDecimal rejects numbers that would expand into billions of digits`() {
        assertNull(Formats.parseDecimal("1E999999999"))
        assertNull(Formats.parseDecimal("-1E999999999"))
        assertNull(Formats.parseDecimal("1E-999999999"))
    }

    @Test
    @Timeout(5)
    fun `parseDecimal rejects values out of the decimal(15, 2) range`() {
        assertNull(Formats.parseDecimal("1E15"), "16 integer digits")
        assertNull(Formats.parseDecimal("1000000000000000"), "16 integer digits")
        assertEquals(BigDecimal("99999999999999.99"), Formats.parseDecimal("99999999999999.99"), "15 integer digits still fits")
    }

    @Test
    @Timeout(5)
    fun `parseDecimal rejects over-long input and more than 10 decimals`() {
        assertNull(Formats.parseDecimal(digits65))
        assertNull(Formats.parseDecimal("0.12345678901"), "11 decimal places")
    }

    @Test
    @Timeout(5)
    fun `parseAmount caps the same values and keeps its separator handling`() {
        assertNull(Formats.parseAmount("1E999999999"))
        assertNull(Formats.parseAmount("-1E999999999"))
        assertNull(Formats.parseAmount("1E-999999999"))
        assertNull(Formats.parseAmount("1E15"))
        assertNull(Formats.parseAmount(digits65))
        assertNull(Formats.parseAmount("0.12345678901"))
        assertNull(Formats.parseAmount("1000000000000000"))
        assertEquals(BigDecimal("99999999999999.99"), Formats.parseAmount("99999999999999.99"))

        assertEquals(BigDecimal("1234.56"), Formats.parseAmount("1.234,56"), "German format")
        assertEquals(BigDecimal("1234.56"), Formats.parseAmount("1,234.56"), "English format")
        assertEquals(BigDecimal("-12.30"), Formats.parseAmount("-12,30"), "German format")
    }
}
