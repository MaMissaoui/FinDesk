package de.findesk.document

import de.findesk.document.RecurringDetector.Cadence
import de.findesk.model.TransactionItem
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate

class RecurringDetectorTest {
    private val today = LocalDate.of(2026, 10, 4)
    private var id = 0

    private fun t(date: LocalDate, amount: String, partner: String, category: String? = null) = TransactionItem(
        id = ++id, accountId = 1, accountIban = "DE1", date = date, amount = BigDecimal(amount), currency = "EUR",
        partnerName = partner, purpose = null, categoryId = null, categoryName = category, categoryColor = null,
        isTaxRelevant = false, documentCount = 0,
    )

    private fun monthly(start: LocalDate, count: Int, amount: (Int) -> String, partner: (Int) -> String, dayJitter: (Int) -> Long = { 0 }) = (0 until count).map { i -> t(start.plusMonths(i.toLong()).plusDays(dayJitter(i)), amount(i), partner(i)) }

    @Test
    fun `detects subscriptions, salary and varying bills`() {
        val txns = monthly(LocalDate.of(2026, 4, 15), 6, { "-12.99" }, { "NETFLIX.COM ${1000 + it}" }) +
            monthly(LocalDate.of(2026, 4, 30), 6, { "3200.00" }, { "Employer GmbH" }, { if (it % 2 == 0) 0 else -2 }) +
            monthly(LocalDate.of(2026, 4, 5), 6, { listOf("-61.20", "-58.90", "-64.00", "-60.10", "-59.50", "-62.30")[it] }, { "Stadtwerke Köln" }) +
            listOf(t(LocalDate.of(2025, 11, 1), "-240.00", "HUK Coburg"), t(LocalDate.of(2024, 11, 2), "-240.00", "HUK Coburg"))

        val found = RecurringDetector.detect(txns, today).associateBy { it.name.substringBefore(' ') }

        val netflix = found.getValue("NETFLIX.COM")
        assertEquals(Cadence.MONTHLY, netflix.cadence)
        assertTrue(netflix.fixedAmount)
        assertEquals(LocalDate.of(2026, 10, 15), netflix.nextDate)
        assertEquals(BigDecimal("-155.88"), netflix.yearlyAmount)

        assertTrue(found.getValue("Employer").isIncome)

        val power = found.getValue("Stadtwerke")
        assertFalse(power.fixedAmount)
        assertEquals(Cadence.MONTHLY, power.cadence)

        val insurance = found.getValue("HUK")
        assertEquals(Cadence.YEARLY, insurance.cadence)
        assertEquals(LocalDate.of(2026, 11, 1), insurance.nextDate)
    }

    @Test
    fun `irregular, too rare and cancelled bookings are not recurring`() {
        val groceries = listOf(1, 3, 9, 10, 22, 40, 41, 70).map { t(today.minusDays(it.toLong()), "-23.00", "REWE") }
        val twice = monthly(LocalDate.of(2026, 8, 1), 2, { "-9.99" }, { "Spotify" })
        val cancelled = monthly(LocalDate.of(2026, 1, 10), 4, { "-7.99" }, { "Disney Plus" }) // last in April

        val found = RecurringDetector.detect(groceries + twice + cancelled, today).map { it.name }

        assertFalse("REWE" in found)
        assertFalse("Spotify" in found, "two monthly bookings aren't enough")
        assertFalse("Disney Plus" in found, "stopped months ago")
    }

    @Test
    fun `a late booking stays due and upcoming lists the next 30 days`() {
        // Salary normally on the 1st; October's hasn't arrived by the 4th
        val salary = monthly(LocalDate.of(2026, 5, 1), 5, { "3000.00" }, { "Employer" })
        val rent = monthly(LocalDate.of(2026, 5, 20), 5, { "-900.00" }, { "Vermieter" })
        val yearly = listOf(t(LocalDate.of(2025, 3, 1), "-100.00", "ADAC"), t(LocalDate.of(2024, 3, 1), "-100.00", "ADAC"))

        val recurring = RecurringDetector.detect(salary + rent + yearly, today)
        assertEquals(LocalDate.of(2026, 10, 1), recurring.first { it.name == "Employer" }.nextDate)

        val upcoming = RecurringDetector.upcoming(recurring, today).map { it.name }
        assertEquals(listOf("Employer", "Vermieter"), upcoming)
    }
}
