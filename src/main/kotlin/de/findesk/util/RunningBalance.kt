package de.findesk.util

import de.findesk.model.TransactionItem
import java.math.BigDecimal

/**
 * The account balance after each booking, reconstructed backwards from the account's current balance.
 *
 * Days are reconciled newest first: rows of a day that checks out against the bank are placed arithmetically in list
 * order, so a day whose list order differs from the bank's booking order cannot mark wrong rows or shift neighbours.
 * A day checks out when it carries no bank balance, or when a bank balance equals the day's end or its start balance;
 * when EVERY booking of the day carries a bank balance, both ends of the bank's chain are known and BOTH must match.
 */
object RunningBalance {
    data class Entry(
        val balance: BigDecimal,
        /** bank balance minus computed balance, when they differ */
        val bankDifference: BigDecimal? = null,
    )

    /**
     * Balance after each booking of ONE account, by transaction id. [newestFirst] is every booking of the account in
     * the list order (date DESC, id DESC); [currentBalance] is `accounts.balance`, which (like BalanceHistory) is the
     * balance after ALL bookings, future-dated ones included.
     *
     * Each day (newest first, the newest day ending at [currentBalance], every older day ending at the previous day's
     * start balance) is checked against the bank and reconciles when it has no bank balance, or one equal to that day's
     * end or start balance; when EVERY booking of the day has a bank balance, it reconciles only if some booking's
     * balance equals the day's end AND some booking's bank value minus its amount equals the day's start (both ends of
     * the bank's chain are known there, so accepting just one end could hide a missing booking). A reconciled day gets
     * arithmetic balances in list order and no [Entry.bankDifference].
     * A day that does not reconcile keeps the per-row behaviour: each bank value marks its own row and the chain
     * continues from it, so a missing booking appears as a bank difference on the day before the gap.
     */
    fun compute(currentBalance: BigDecimal, newestFirst: List<TransactionItem>): Map<Int, Entry> {
        val result = LinkedHashMap<Int, Entry>()
        // End-of-day balance E; the newest day ends at the current balance, an older day at the previous day's start
        var endOfDay = currentBalance
        // Encounter order of the date-DESC list: days newest first, each day's rows in list order
        newestFirst.groupBy { it.date }.forEach { (_, dayRows) ->
            val startOfDay = dayRows.fold(endOfDay) { acc, txn -> acc - txn.amount }
            val reconciles = when {
                dayRows.all { it.balanceAfter == null } -> true

                // Every booking carries a bank balance: both ends of the bank's chain are known, so both must match
                dayRows.all { it.balanceAfter != null } ->
                    dayRows.any { it.balanceAfter?.compareTo(endOfDay) == 0 } &&
                        dayRows.any { it.balanceAfter?.minus(it.amount)?.compareTo(startOfDay) == 0 }

                else -> dayRows.any { it.balanceAfter?.compareTo(endOfDay) == 0 } ||
                    dayRows.any { it.balanceAfter?.minus(it.amount)?.compareTo(startOfDay) == 0 }
            }
            var balance = endOfDay
            if (reconciles) {
                // Arithmetic placement from E in list order; bank values were only used for the check
                dayRows.forEach { txn ->
                    result[txn.id] = Entry(balance)
                    balance -= txn.amount
                }
            } else {
                dayRows.forEach { txn ->
                    val computed = balance
                    val bank = txn.balanceAfter
                    if (bank == null) {
                        result[txn.id] = Entry(computed)
                    } else {
                        val difference = if (bank.compareTo(computed) != 0) bank - computed else null
                        result[txn.id] = Entry(bank, difference)
                        // Continue from the bank's value, so one gap marks one row, not all older rows
                        balance = bank
                    }
                    balance -= txn.amount
                }
            }
            // The next older day ends where this day's walk left off (its start balance S on a reconciled day)
            endOfDay = balance
        }
        return result
    }
}
