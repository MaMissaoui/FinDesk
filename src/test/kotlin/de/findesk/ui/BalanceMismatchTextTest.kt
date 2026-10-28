package de.findesk.ui

import de.findesk.i18n.AppLanguage
import de.findesk.i18n.AppStrings
import de.findesk.i18n.Translations
import de.findesk.ui.screens.balanceMismatchText
import de.findesk.util.Formats
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/** The balance-mismatch warning names the bank's balance and the missing amount with its sign. */
class BalanceMismatchTextTest {
    // Synthetic numbers; the signs are asserted against Formats.money itself, not hard-coded characters
    private val en = Translations.of(AppLanguage.EN)
    private val de = Translations.of(AppLanguage.DE)

    @Test
    fun `the warning names the bank's balance and the signed missing amount`() {
        AppStrings.set(AppLanguage.EN)
        val text = balanceMismatchText(en, BigDecimal("2500.00"), BigDecimal("120.40"), "EUR")
        assertTrue(text.contains(Formats.money(BigDecimal("2500.00"), "EUR")), text)
        assertTrue(text.contains(Formats.money(BigDecimal("-120.40"), "EUR", signed = true)), text)
    }

    @Test
    fun `a bank below the computed balance gives a positive missing amount`() {
        AppStrings.set(AppLanguage.EN)
        val text = balanceMismatchText(en, BigDecimal("100.00"), BigDecimal("-175.00"), "EUR")
        assertTrue(text.contains(Formats.money(BigDecimal("100.00"), "EUR")), text)
        assertTrue(text.contains(Formats.money(BigDecimal("175.00"), "EUR", signed = true)), text)
    }

    @Test
    fun `german renders with the german text`() {
        AppStrings.set(AppLanguage.DE)
        try {
            val text = balanceMismatchText(de, BigDecimal("2500.00"), BigDecimal("120.40"), "EUR")
            assertEquals(
                "Die Bank meldet nach dieser Buchung ${Formats.money(BigDecimal("2500.00"), "EUR")}. " +
                    "Danach fehlen Buchungen über ${Formats.money(BigDecimal("-120.40"), "EUR", signed = true)}, " +
                    "z. B. solche, die noch nicht im Kontoauszug der Bank stehen.",
                text,
            )
        } finally {
            // English again, so later tests see the default language
            AppStrings.set(AppLanguage.EN)
        }
    }
}
