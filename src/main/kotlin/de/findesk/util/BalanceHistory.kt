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
}
