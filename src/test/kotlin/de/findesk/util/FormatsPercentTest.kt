package de.findesk.util

import de.findesk.i18n.AppLanguage
import de.findesk.i18n.AppStrings
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/** Percentages in the UI language, and share lists that always sum to exactly 100. */
class FormatsPercentTest {
    private val nbsp = 0xA0.toChar().toString()

    @Test
    fun `percent is whole and language-specific`() {
        AppStrings.set(AppLanguage.EN)
        assertEquals("49%", Formats.percent(0.49))
        assertEquals("100%", Formats.percent(1.0))
        assertEquals("0%", Formats.percent(0.0))
        assertEquals("<1%", Formats.percent(0.004))
        assertEquals("<1%", Formats.percent(0.0004))

        AppStrings.set(AppLanguage.DE)
        assertEquals("49$nbsp%", Formats.percent(0.49))
        assertEquals("0$nbsp%", Formats.percent(0.0))
        assertEquals("<1$nbsp%", Formats.percent(0.0001))
        assertEquals("12$nbsp%", Formats.percent(0.1234))

        AppStrings.set(AppLanguage.FR)
        assertEquals("3$nbsp%", Formats.percent(0.03))
        // English again, so later tests see the default language
        AppStrings.set(AppLanguage.EN)
    }

    @Test
    fun `shares round by largest remainder and sum to exactly 100`() {
        assertEquals(listOf(34, 33, 33), Formats.shares(listOf(BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE)))
        assertEquals(listOf(30, 70), Formats.shares(listOf(BigDecimal("30.00"), BigDecimal("70.00"))))
        val shares = Formats.shares((1..10).map { BigDecimal(it) })
        assertEquals(100, shares.sum())
        // Sorted ten ways, as the split bar receives them: 10 first
        assertEquals(listOf(18, 16, 15, 13, 11, 9, 7, 5, 4, 2), Formats.shares((1..10).reversed().map { BigDecimal(it) }))
    }

    @Test
    fun `shares handle empty and all-zero lists`() {
        assertTrue(Formats.shares(emptyList()).isEmpty())
        assertEquals(listOf(0, 0, 0), Formats.shares(listOf(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO)))
    }
}
