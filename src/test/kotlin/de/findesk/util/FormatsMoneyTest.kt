package de.findesk.util

import de.findesk.i18n.AppLanguage
import de.findesk.i18n.AppStrings
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/** Money in the UI language: German puts the symbol after the number, separated by a no-break space. */
class FormatsMoneyTest {
    private val nbsp = 0xA0.toChar().toString()
    private val narrowNbsp = 0x202F.toChar().toString()

    @Test
    fun `German money puts the symbol after the number`() {
        try {
            AppStrings.set(AppLanguage.DE)
            assertEquals("2.450,37$nbsp€", Formats.money(BigDecimal("2450.37")))
            assertEquals("-5.000,00$nbsp€", Formats.money(BigDecimal("-5000.00")))
            assertEquals("+431,00$nbsp€", Formats.money(BigDecimal("431.00"), signed = true))
            assertEquals("4.000$nbsp€", Formats.wholeMoney(BigDecimal("4000")))
            assertEquals("500,00$nbsp$", Formats.money(BigDecimal("500.00"), "USD"))
            assertEquals("12,00${nbsp}CHF", Formats.money(BigDecimal("12.00"), "CHF"))
        } finally {
            AppStrings.set(AppLanguage.EN)
        }
    }

    @Test
    fun `English money is unchanged`() {
        try {
            AppStrings.set(AppLanguage.EN)
            assertEquals("€2,450.37", Formats.money(BigDecimal("2450.37")))
            assertEquals("-€5,000.00", Formats.money(BigDecimal("-5000.00")))
            assertEquals("+€431.00", Formats.money(BigDecimal("431.00"), signed = true))
            assertEquals("€4,000", Formats.wholeMoney(BigDecimal("4000")))
            assertEquals("$500.00", Formats.money(BigDecimal("500.00"), "USD"))
            assertEquals("CHF12.00", Formats.money(BigDecimal("12.00"), "CHF"))
        } finally {
            AppStrings.set(AppLanguage.EN)
        }
    }

    @Test
    fun `French money is unchanged`() {
        try {
            AppStrings.set(AppLanguage.FR)
            assertEquals("€2${narrowNbsp}450,37", Formats.money(BigDecimal("2450.37")))
            assertEquals("-€5${narrowNbsp}000,00", Formats.money(BigDecimal("-5000.00")))
            assertEquals("+€431,00", Formats.money(BigDecimal("431.00"), signed = true))
            assertEquals("€4${narrowNbsp}000", Formats.wholeMoney(BigDecimal("4000")))
            assertEquals("$500,00", Formats.money(BigDecimal("500.00"), "USD"))
            assertEquals("CHF12,00", Formats.money(BigDecimal("12.00"), "CHF"))
        } finally {
            AppStrings.set(AppLanguage.EN)
        }
    }

    @Test
    fun `German formatted money parses back`() {
        try {
            AppStrings.set(AppLanguage.DE)
            assertEquals(BigDecimal("2450.37"), Formats.parseAmount(Formats.money(BigDecimal("2450.37"))))
            assertEquals(BigDecimal("-5000.00"), Formats.parseAmount(Formats.money(BigDecimal("-5000.00"))))
            assertEquals(BigDecimal("431.00"), Formats.parseAmount(Formats.money(BigDecimal("431.00"), signed = true)))
            assertEquals(null, Formats.parseAmount("12abc"))
        } finally {
            AppStrings.set(AppLanguage.EN)
        }
    }
}
