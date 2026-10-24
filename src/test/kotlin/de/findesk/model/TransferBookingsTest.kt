package de.findesk.model

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate

/** The transfer flag excludes own-account transfers from income and spending counts, nothing else. */
class TransferBookingsTest {
    private fun txn(amount: String, isTransfer: Boolean = false) = TransactionItem(
        id = 0,
        accountId = 1,
        accountIban = "",
        date = LocalDate.of(2026, 6, 15),
        amount = BigDecimal(amount),
        currency = "EUR",
        partnerName = null,
        purpose = null,
        categoryId = null,
        categoryName = null,
        categoryColor = null,
        isTaxRelevant = false,
        documentCount = 0,
        isTransfer = isTransfer,
    )

    @Test
    fun `a transfer booking counts neither as income nor as spending`() {
        val out = txn("-500.00", isTransfer = true)
        assertFalse(out.countsAsIncome)
        assertFalse(out.countsAsSpending)
        val inbound = txn("500.00", isTransfer = true)
        assertFalse(inbound.countsAsIncome)
        assertFalse(inbound.countsAsSpending)
    }

    @Test
    fun `normal bookings count exactly as before`() {
        assertTrue(txn("1000.00").countsAsIncome)
        assertFalse(txn("1000.00").countsAsSpending)
        assertTrue(txn("-40.00").countsAsSpending)
        assertFalse(txn("-40.00").countsAsIncome)
    }

    @Test
    fun `uncategorized bookings keep counting and default to no transfer`() {
        val booking = txn("-40.00")
        assertFalse(booking.isTransfer)
        assertTrue(booking.countsAsSpending)
    }

    @Test
    fun `isExpense and the amounts' signs stay untouched`() {
        assertTrue(txn("-40.00").isExpense)
        assertFalse(txn("40.00").isExpense)
    }
}
