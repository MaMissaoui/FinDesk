package de.findesk.util

import de.findesk.model.TransactionItem
import java.math.BigDecimal
import java.time.LocalDate

object BalanceHistory {
    /**
     * End-of-day balances from [from] to [to], reconstructed backwards from [currentBalance] (the balance
     * after every booking in [transactions]). Bookings dated after [to] are undone first.
     */
    fun compute(currentBalance: BigDecimal, transactions: List<TransactionItem>, from: LocalDate, to: LocalDate): List<Pair<LocalDate, BigDecimal>> {
        if (from.isAfter(to)) return emptyList()
        val byDay = transactions.groupBy { it.date }.mapValues { (_, t) -> t.fold(BigDecimal.ZERO) { a, x -> a + x.amount } }
        var balance = currentBalance - byDay.filterKeys { it.isAfter(to) }.values.fold(BigDecimal.ZERO, BigDecimal::add)
        val points = ArrayList<Pair<LocalDate, BigDecimal>>()
        var day = to
        while (!day.isBefore(from)) {
            points += day to balance
            balance -= byDay[day] ?: BigDecimal.ZERO
            day = day.minusDays(1)
        }
        return points.asReversed()
    }

    /**
     * One balance point per day from [from] to [to], oldest first, read from the running balances of the column
     * (so chart and column never disagree). [newestFirst] is every booking of ONE account in list order
     * (date DESC, id DESC); [running] is [RunningBalance.compute] for that same list.
     *
     * A day's value is the running balance after the newest booking dated on or before that day, so days between
     * bookings carry the previous value and several bookings on one day give that day's last balance. Days before
     * the account's oldest booking show the balance before it, and bookings dated after [to] never affect the days
     * up to [to]. No bookings at all, or [from] after [to], gives an empty list.
     */
    fun fromRunning(
        newestFirst: List<TransactionItem>,
        running: Map<Int, RunningBalance.Entry>,
        from: LocalDate,
        to: LocalDate,
    ): List<Pair<LocalDate, BigDecimal>> {
        if (from.isAfter(to) || newestFirst.isEmpty()) return emptyList()
        // Ascending by date then id: the last booking reached on a day is the newest dated on or before it
        val ascending = newestFirst.asReversed()
        val oldest = ascending.first()
        // Before the oldest booking the balance is that booking's chain value minus its own amount
        var current = running[oldest.id]?.balance?.minus(oldest.amount)
        val points = ArrayList<Pair<LocalDate, BigDecimal>>()
        var index = 0
        var day = from
        while (!day.isAfter(to)) {
            while (index < ascending.size && !ascending[index].date.isAfter(day)) {
                running[ascending[index].id]?.let { current = it.balance }
                index++
            }
            current?.let { points += day to it }
            day = day.plusDays(1)
        }
        return points
    }
}
