package de.findesk.document

import de.findesk.model.TransactionItem
import de.findesk.ui.screens.recurringCost
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate

/**
 * The Reports fixes: the Tax summary's tax figures use the Tax bookings tab's definition, the recurring detector
 * marks transfers, drops zero groups and normalises names, and the recurring cost cards exclude transfers.
 * Built from [TransactionItem]s directly, no database needed.
 */
class ReportsFixesTest {
    private val service = ReportService()
    private val from = LocalDate.of(2026, 1, 1)
    private val to = LocalDate.of(2026, 12, 31)
    private val day = LocalDate.of(2026, 6, 1)
    private val today = LocalDate.of(2026, 10, 4)
    private var id = 0

    private fun item(
        amount: String,
        date: LocalDate = day,
        categoryId: Int? = null,
        categoryName: String? = null,
        taxRelevant: Boolean = false,
        isTransfer: Boolean = false,
        partner: String? = null,
    ): TransactionItem = TransactionItem(
        id = ++id,
        accountId = 1,
        accountIban = "DE89370400440532013000",
        date = date,
        amount = BigDecimal(amount),
        currency = "EUR",
        partnerName = partner,
        purpose = null,
        categoryId = categoryId,
        categoryName = categoryName,
        categoryColor = null,
        isTaxRelevant = taxRelevant,
        documentCount = 0,
        isTransfer = isTransfer,
    )

    @Test
    fun `a refund lowers the tax-relevant expenses to the tax bookings' deduction total`() {
        val txns = listOf(
            item("-100.00", categoryId = 1, categoryName = "Handwerk", taxRelevant = true),
            item("30.00", date = day.plusDays(1), categoryId = 1, categoryName = "Handwerk", taxRelevant = true),
        )

        val report = service.buildReport(from, to, txns)

        assertEquals(BigDecimal("70.00"), report.taxRelevantExpenses["Handwerk"], "the refund lowers its category")
        assertEquals(service.buildTaxBookings(from, to, txns).deductionTotal, report.taxRelevantTotal)
    }

    @Test
    fun `a tax-relevant income category is not among the tax-relevant expenses`() {
        val txns = listOf(
            item("500.00", categoryId = 2, categoryName = "Vermietung", taxRelevant = true),
            item("-50.00", date = day.plusDays(1), categoryId = 2, categoryName = "Vermietung", taxRelevant = true),
        )

        val report = service.buildReport(from, to, txns)

        assertTrue(report.taxRelevantExpenses.isEmpty(), "money out on a net-income category is no deduction")
        assertEquals(BigDecimal.ZERO, report.taxRelevantTotal)
    }

    @Test
    fun `a monthly transfer group is detected with isTransfer`() {
        val txns = (0 until 4).map { i -> item("-900.00", date = LocalDate.of(2026, 5, 20).plusMonths(i.toLong()), isTransfer = true, partner = "Vermieter") }

        val found = RecurringDetector.detect(txns, today)

        assertEquals(1, found.size)
        assertTrue(found.single().isTransfer, "the last booking's transfer flag is taken over")
        assertFalse(found.single().isIncome)
    }

    @Test
    fun `a zero-amount monthly group is not detected`() {
        val txns = (0 until 4).map { i -> item("0.00", date = LocalDate.of(2026, 5, 20).plusMonths(i.toLong()), partner = "Beispiel Inc.") }

        assertTrue(RecurringDetector.detect(txns, today).isEmpty(), "a zero median is a booking artifact, not a subscription")
    }

    @Test
    fun `partner names come out single-spaced and trimmed`() {
        val txns = (0 until 4).map { i -> item("-9.99", date = LocalDate.of(2026, 5, 20).plusMonths(i.toLong()), partner = "  Beispiel\tGmbH\n\n  Handwerk  ") }

        val found = RecurringDetector.detect(txns, today)

        assertEquals("Beispiel GmbH Handwerk", found.single().name)
    }

    @Test
    fun `the recurring cost excludes income and transfers`() {
        fun payment(amount: String, isIncome: Boolean = false, isTransfer: Boolean = false) = RecurringDetector.RecurringPayment(
            name = "Beispiel",
            cadence = RecurringDetector.Cadence.MONTHLY,
            typicalAmount = BigDecimal(amount),
            fixedAmount = true,
            lastDate = day,
            nextDate = day,
            occurrences = 4,
            categoryName = null,
            currency = "EUR",
            isTransfer = isTransfer,
        )

        val cost = recurringCost(
            listOf(
                payment("-100.00"),
                payment("-900.00", isTransfer = true),
                payment("2000.00", isIncome = true),
            ),
        )

        assertEquals(mapOf("EUR" to BigDecimal("1200.00")), cost, "only the -100 monthly payment counts, twelve times a year")
    }
}
