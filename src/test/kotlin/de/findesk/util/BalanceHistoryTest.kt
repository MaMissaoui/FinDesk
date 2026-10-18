package de.findesk.util

import de.findesk.model.TransactionItem
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate

class BalanceHistoryTest {
    private var id = 0

    /** Newest-first list order is date DESC, id DESC; ids grow with insert order. */
    private fun txn(date: LocalDate, amount: String, balanceAfter: String? = null) = TransactionItem(
        id = ++id,
        accountId = 1,
        accountIban = "DE1",
        date = date,
        amount = BigDecimal(amount),
        balanceAfter = balanceAfter?.let(::BigDecimal),
        currency = "EUR",
        partnerName = null,
        purpose = null,
        categoryId = null,
        categoryName = null,
        categoryColor = null,
        isTaxRelevant = false,
        documentCount = 0,
    )

    private fun chart(newestFirst: List<TransactionItem>, currentBalance: String, from: LocalDate, to: LocalDate) = BalanceHistory.fromRunning(newestFirst, RunningBalance.compute(BigDecimal(currentBalance), newestFirst), from, to)

    @Test
    fun `days between bookings carry the previous value and several bookings on one day give the day's last balance`() {
        // Inserted oldest-id first, so the newest same-day booking (higher id) comes first in list order
        val sameDayOlder = txn(LocalDate.of(2026, 3, 3), "-20.00")
        val newest = txn(LocalDate.of(2026, 3, 3), "-10.00")
        val oldest = txn(LocalDate.of(2026, 3, 1), "50.00")
        // RunningBalance: newest 100, the same-day older one 110, the oldest 130 (current 100 is after every booking)
        val points = chart(listOf(newest, sameDayOlder, oldest), "100.00", LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 4))

        assertEquals(
            listOf(
                LocalDate.of(2026, 3, 1) to BigDecimal("130.00"),
                LocalDate.of(2026, 3, 2) to BigDecimal("130.00"),
                // The last booking of the day wins: 110 after the older one, 100 after the newer one
                LocalDate.of(2026, 3, 3) to BigDecimal("100.00"),
                LocalDate.of(2026, 3, 4) to BigDecimal("100.00"),
            ),
            points,
        )
    }

    @Test
    fun `a day before the oldest booking gives the balance before it`() {
        val newest = txn(LocalDate.of(2026, 3, 3), "-10.00")
        val oldest = txn(LocalDate.of(2026, 3, 1), "50.00")
        // Oldest entry is 110; the balance before that booking is 110 - 50 = 60
        val points = chart(listOf(newest, oldest), "100.00", LocalDate.of(2026, 2, 27), LocalDate.of(2026, 3, 2))

        assertEquals(
            listOf(
                LocalDate.of(2026, 2, 27) to BigDecimal("60.00"),
                LocalDate.of(2026, 2, 28) to BigDecimal("60.00"),
                LocalDate.of(2026, 3, 1) to BigDecimal("110.00"),
                LocalDate.of(2026, 3, 2) to BigDecimal("110.00"),
            ),
            points,
        )
    }

    @Test
    fun `a booking dated after to does not change the values up to to`() {
        val future = txn(LocalDate.of(2026, 4, 1), "-50.00")
        val today = txn(LocalDate.of(2026, 3, 1), "20.00")
        // current 200 includes the future booking; the 1 March entry is 200 - (-50) = 250
        val entries = RunningBalance.compute(BigDecimal("200.00"), listOf(future, today))
        val upTo = BalanceHistory.fromRunning(listOf(future, today), entries, LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 5))

        assertEquals(5, upTo.size)
        assertTrue(upTo.all { it.second.compareTo(BigDecimal("250.00")) == 0 }, "the future booking is ignored before its own date")

        // Once the period reaches it, the future booking counts from its own date on
        val past = BalanceHistory.fromRunning(listOf(future, today), entries, LocalDate.of(2026, 4, 1), LocalDate.of(2026, 4, 1))
        assertEquals(listOf(LocalDate.of(2026, 4, 1) to BigDecimal("200.00")), past)
    }

    @Test
    fun `a bank balance wins over the reconstructed chain, same as the column`() {
        // Computed would be 100 for the newest row, but the bank reported 70: the chart follows the bank
        val newest = txn(LocalDate.of(2026, 3, 3), "-30.00", balanceAfter = "70.00")
        val older = txn(LocalDate.of(2026, 3, 2), "50.00")
        val points = chart(listOf(newest, older), "100.00", LocalDate.of(2026, 3, 2), LocalDate.of(2026, 3, 3))

        assertEquals(
            listOf(
                LocalDate.of(2026, 3, 2) to BigDecimal("100.00"),
                LocalDate.of(2026, 3, 3) to BigDecimal("70.00"),
            ),
            points,
        )
    }

    @Test
    fun `no bookings and a from after to give an empty list`() {
        assertTrue(BalanceHistory.fromRunning(emptyList(), emptyMap(), LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 31)).isEmpty())
        val txn = txn(LocalDate.of(2026, 3, 1), "10.00")
        val entries = RunningBalance.compute(BigDecimal("10.00"), listOf(txn))
        assertTrue(BalanceHistory.fromRunning(listOf(txn), entries, LocalDate.of(2026, 3, 5), LocalDate.of(2026, 3, 1)).isEmpty())
    }
}
