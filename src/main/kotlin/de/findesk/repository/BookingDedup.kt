package de.findesk.repository

import java.math.BigDecimal
import java.time.LocalDate

/**
 * Duplicate detection shared by FinTS sync and file import.
 *
 * Exact: a booking whose hash is already stored. Probable: the same booking from a different source
 * (FinTS vs CSV vs CAMT vs manual), whose partner/purpose text and therefore hash differ. For those, bookings
 * with the same account, booking date and amount are compared by count: if 2 such bookings exist and 3 arrive,
 * 1 is new. Genuine repeats (two coffees) are kept, and a different amount on the same day is always new.
 */
object BookingDedup {
    data class Candidate<T>(val item: T, val date: LocalDate, val amount: BigDecimal, val hash: String)

    data class Plan<T>(val toImport: List<Candidate<T>>, val exactDuplicates: Int, val probableDuplicates: Int)

    fun <T> plan(accountId: Int, candidates: List<Candidate<T>>, repository: TransactionRepository): Plan<T> {
        // Indexed so identical bookings (equal candidates) keep their own identity and the file order
        val (known, unknown) = candidates.withIndex().partition { repository.findByHash(it.value.hash) != null }
        val knownPerDay = known.groupingBy { it.value.date to it.value.amount.stripTrailingZeros() }.eachCount()
        val toImport = mutableListOf<IndexedValue<Candidate<T>>>()
        var probable = 0
        unknown.groupBy { it.value.date to it.value.amount.stripTrailingZeros() }.forEach { (key, group) ->
            // Existing bookings that this batch's exact matches don't already account for
            val existing = repository.countSameBooking(accountId, key.first, group.first().value.amount)
            val unmatchedExisting = (existing - (knownPerDay[key] ?: 0)).coerceAtLeast(0)
            val skip = minOf(unmatchedExisting, group.size)
            probable += skip
            toImport += group.drop(skip)
        }
        return Plan(toImport.sortedBy { it.index }.map { it.value }, known.size, probable)
    }
}
