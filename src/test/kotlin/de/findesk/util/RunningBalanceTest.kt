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

    // Day 2026-10-01 rows in list order -20.00, -700.00, -500.00 (NOT the bank's booking order), created so that
    // ids sort into that list order (the row that comes last in the list is created first).
    private val oct1 = LocalDate.of(2026, 10, 1)
    private val sep30 = LocalDate.of(2026, 9, 30)

    /** The day-reconcile base example: rows and the expected list given to compute. */
    private fun baseExample(): Pair<List<TransactionItem>, Map<TransactionItem, String>> {
        val minus1000 = txn(sep30, "-1000.00", balanceAfter = "3000.00")
        val minus500 = txn(oct1, "-500.00", balanceAfter = "2480.00")
        val minus700 = txn(oct1, "-700.00", balanceAfter = "1780.00")
        val minus20 = txn(oct1, "-20.00", balanceAfter = "2980.00")
        return listOf(minus20, minus700, minus500, minus1000) to mapOf(
            minus20 to "-20.00",
            minus500 to "-500.00",
            minus700 to "-700.00",
            minus1000 to "-1000.00",
        )
    }

    @Test
    fun `a day whose list order differs from the bank's booking order is placed arithmetically`() {
        val (newestFirst, rows) = baseExample()
        val minus20 = rows.keys.first { it.amount == BigDecimal("-20.00") }
        val minus700 = rows.keys.first { it.amount == BigDecimal("-700.00") }
        val minus500 = rows.keys.first { it.amount == BigDecimal("-500.00") }
        val minus1000 = rows.keys.first { it.amount == BigDecimal("-1000.00") }

        val entries = RunningBalance.compute(BigDecimal("1780.00"), newestFirst)

        assertTrue(entries.values.all { it.bankDifference == null })
        assertEquals(BigDecimal("1780.00"), entries[minus20.id]!!.balance)
        assertEquals(BigDecimal("1800.00"), entries[minus700.id]!!.balance)
        assertEquals(BigDecimal("2500.00"), entries[minus500.id]!!.balance)
        assertEquals(BigDecimal("3000.00"), entries[minus1000.id]!!.balance)
        val history = BalanceHistory.fromRunning(newestFirst, entries, sep30, oct1)
        assertEquals(
            listOf(sep30 to BigDecimal("3000.00"), oct1 to BigDecimal("1780.00")),
            history,
        )
    }

    @Test
    fun `a day reconciles from its start balance even when its end balance matches no bank value`() {
        val minus1000 = txn(sep30, "-1000.00", balanceAfter = "3000.00")
        val minus500 = txn(oct1, "-500.00", balanceAfter = "2480.00")
        val minus700 = txn(oct1, "-700.00", balanceAfter = "1780.00")
        val minus20 = txn(oct1, "-20.00", balanceAfter = "2980.00")
        val plus100 = txn(oct1, "100.00")

        val entries = RunningBalance.compute(BigDecimal("1880.00"), listOf(plus100, minus20, minus700, minus500, minus1000))

        assertTrue(entries.values.all { it.bankDifference == null })
        assertEquals(BigDecimal("1880.00"), entries[plus100.id]!!.balance)
        assertEquals(BigDecimal("1780.00"), entries[minus20.id]!!.balance)
        assertEquals(BigDecimal("1800.00"), entries[minus700.id]!!.balance)
        assertEquals(BigDecimal("2500.00"), entries[minus500.id]!!.balance)
        assertEquals(BigDecimal("3000.00"), entries[minus1000.id]!!.balance)
    }

    @Test
    fun `a day reconciles when a bank value equals its end balance`() {
        val minus1000 = txn(sep30, "-1000.00", balanceAfter = "3000.00")
        val plus100 = txn(oct1, "100.00")
        val minus500 = txn(oct1, "-500.00", balanceAfter = "2580.00")
        val minus700 = txn(oct1, "-700.00", balanceAfter = "1880.00")
        val minus20 = txn(oct1, "-20.00", balanceAfter = "3080.00")

        val entries = RunningBalance.compute(BigDecimal("1880.00"), listOf(minus20, minus700, minus500, plus100, minus1000))

        assertTrue(entries.values.all { it.bankDifference == null })
        assertEquals(BigDecimal("3000.00"), entries[minus1000.id]!!.balance)
    }

    @Test
    fun `a fully bank-covered day with a real gap no longer reconciles from one matching end`() {
        // Base example with the -500.00 booking removed. With both ends of the 10-01 bank chain known, only the END
        // matches (1780.00), but S = 2500.00 matches neither 2980.00 + 20.00 nor 1780.00 + 700.00: the day does not
        // reconcile and the missing booking is flagged by bank differences instead.
        val minus1000 = txn(sep30, "-1000.00", balanceAfter = "3000.00")
        val minus700 = txn(oct1, "-700.00", balanceAfter = "1780.00")
        val minus20 = txn(oct1, "-20.00", balanceAfter = "2980.00")

        val entries = RunningBalance.compute(BigDecimal("1780.00"), listOf(minus20, minus700, minus1000))

        assertTrue(
            listOf(minus20, minus700).any { entries[it.id]!!.bankDifference != null },
            "at least one 10-01 row is flagged with a bank difference",
        )
        val sum = entries.values.fold(BigDecimal.ZERO) { acc, entry -> acc + (entry.bankDifference ?: BigDecimal.ZERO) }
        assertEquals(BigDecimal("500.00"), sum)
    }

    @Test
    fun `a fully bank-covered day where only the end matches keeps a bank difference`() {
        // Every booking has a bank balance and both ends must match: E = 100.00 matches, but S = 130.00 matches
        // neither 150.00 + 20.00 nor 100.00 + 10.00, so the day does not reconcile.
        val minus20 = txn(LocalDate.of(2026, 10, 1), "-20.00", balanceAfter = "150.00")
        val minus10 = txn(LocalDate.of(2026, 10, 1), "-10.00", balanceAfter = "100.00")

        val entries = RunningBalance.compute(BigDecimal("100.00"), listOf(minus10, minus20))

        assertTrue(entries.values.any { it.bankDifference != null })
    }
}
