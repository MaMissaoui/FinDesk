package de.findesk.util

import de.findesk.model.TransactionItem
import java.math.BigDecimal

/**
 * The account balance after each booking, reconstructed backwards from the account's current balance.
 *
 * Where a statement reported the bank's running balance, that value is preferred and the chain continues from it,
 * so a missing booking marks one row instead of every older row.
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
     */
    fun compute(currentBalance: BigDecimal, newestFirst: List<TransactionItem>): Map<Int, Entry> {
        val result = LinkedHashMap<Int, Entry>()
        // Balance after the booking being placed; starts as the balance after all bookings
        var balance = currentBalance
        newestFirst.forEach { txn ->
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
        return result
    }
}
