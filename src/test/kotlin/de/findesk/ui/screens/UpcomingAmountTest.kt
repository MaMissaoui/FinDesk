package de.findesk.ui.screens

import de.findesk.document.RecurringDetector
import de.findesk.util.Formats
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate

/**
 * Varying upcoming amounts carry no "~" prefix: their meta line already says "amount varies", and the amount is
 * the signed money format as everywhere else.
 */
class UpcomingAmountTest {
    private fun payment(fixedAmount: Boolean, amount: String = "-9.99") = RecurringDetector.RecurringPayment(
        name = "Netflix",
        cadence = RecurringDetector.Cadence.MONTHLY,
        typicalAmount = BigDecimal(amount),
        fixedAmount = fixedAmount,
        lastDate = LocalDate.of(2026, 9, 20),
        nextDate = LocalDate.of(2026, 10, 20),
        occurrences = 5,
        categoryName = null,
    )

    @Test
    fun `varying and fixed amounts both render as signed money without a tilde`() {
        val varying = upcomingAmount(payment(fixedAmount = false))
        val fixed = upcomingAmount(payment(fixedAmount = true))

        assertEquals(Formats.money(BigDecimal("-9.99"), signed = true), varying, "-€9.99, not ~-€9.99")
        assertEquals(Formats.money(BigDecimal("-9.99"), signed = true), fixed)
        assertFalse("~" in varying)
        assertFalse("~" in fixed)
    }
}
