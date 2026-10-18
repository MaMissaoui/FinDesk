package de.findesk.util

import de.findesk.model.TransactionItem
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate

class RunningBalanceTest {
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

    @Test
    fun `newest row equals the current balance and every row chains to the older one`() {
        val newest = txn(LocalDate.of(2026, 3, 3), "-30.00")
        val middle = txn(LocalDate.of(2026, 3, 2), "100.00")
        val oldest = txn(LocalDate.of(2026, 3, 1), "-50.00")

        val entries = RunningBalance.compute(BigDecimal("120.00"), listOf(newest, middle, oldest))

        assertEquals(BigDecimal("120.00"), entries[newest.id]!!.balance)
        assertEquals(BigDecimal("150.00"), entries[middle.id]!!.balance)
        assertEquals(BigDecimal("50.00"), entries[oldest.id]!!.balance)
        // Row balance minus its amount is the next older row's balance
        assertEquals(entries[middle.id]!!.balance, entries[newest.id]!!.balance - newest.amount)
        assertEquals(entries[oldest.id]!!.balance, entries[middle.id]!!.balance - middle.amount)
        assertTrue(entries.values.all { it.bankDifference == null })
    }

    @Test
    fun `same-day bookings chain by id order`() {
        val oldest = txn(LocalDate.of(2026, 3, 1), "-10.00")
        val middle = txn(LocalDate.of(2026, 3, 1), "-20.00")
        val newest = txn(LocalDate.of(2026, 3, 1), "-30.00")

        // date DESC, id DESC: the newest insert (highest id) comes first
        val entries = RunningBalance.compute(BigDecimal("40.00"), listOf(newest, middle, oldest))

        assertEquals(BigDecimal("40.00"), entries[newest.id]!!.balance)
        assertEquals(BigDecimal("70.00"), entries[middle.id]!!.balance)
        assertEquals(BigDecimal("90.00"), entries[oldest.id]!!.balance)
    }

    @Test
    fun `a future-dated booking is the newest row and still starts at the current balance`() {
        val future = txn(LocalDate.of(2026, 12, 31), "-50.00")
        val today = txn(LocalDate.of(2026, 3, 1), "20.00")

        val entries = RunningBalance.compute(BigDecimal("200.00"), listOf(future, today))

        assertEquals(BigDecimal("200.00"), entries[future.id]!!.balance, "the account balance includes future bookings")
        assertEquals(BigDecimal("250.00"), entries[today.id]!!.balance, "current balance minus the future booking")
    }

    @Test
    fun `a bank balance is preferred and only its own row is marked`() {
        // Computed would be 150.00, but the bank reports 100.00: one gap, not every older row
        val newest = txn(LocalDate.of(2026, 3, 3), "-30.00", balanceAfter = "100.00")
        val older = txn(LocalDate.of(2026, 3, 2), "50.00")

        val entries = RunningBalance.compute(BigDecimal("150.00"), listOf(newest, older))

        assertEquals(BigDecimal("100.00"), entries[newest.id]!!.balance)
        assertEquals(BigDecimal("-50.00"), entries[newest.id]!!.bankDifference)
        // The chain continues from the bank value: 100.00 - (-30.00)
        assertEquals(BigDecimal("130.00"), entries[older.id]!!.balance)
        assertNull(entries[older.id]!!.bankDifference)
    }

    @Test
    fun `an equal bank balance with a different scale is not a mismatch`() {
        val newest = txn(LocalDate.of(2026, 3, 3), "-30.00", balanceAfter = "150.5")

        val entries = RunningBalance.compute(BigDecimal("150.50"), listOf(newest))

        assertEquals(0, entries[newest.id]!!.balance.compareTo(BigDecimal("150.5")))
        assertNull(entries[newest.id]!!.bankDifference)
    }

    @Test
    fun `without bank values the per-day balances match BalanceHistory`() {
        val d1 = LocalDate.of(2026, 3, 1)
        val d2 = LocalDate.of(2026, 3, 2)
        val d3 = LocalDate.of(2026, 3, 3)
        val newestFirst = listOf(
            txn(d3, "-30.00"),
            txn(d3, "-20.00"),
            txn(d2, "100.00"),
            txn(d1, "-50.00"),
        )
        val current = BigDecimal("120.00")
        val entries = RunningBalance.compute(current, newestFirst)

        listOf(d1, d2, d3).forEach { day ->
            val newestThatDay = newestFirst.first { it.date == day }
            val endOfDay = BalanceHistory.compute(current, newestFirst, day, day).single().second
            assertEquals(endOfDay, entries[newestThatDay.id]!!.balance, "end of $day")
        }
    }
}
