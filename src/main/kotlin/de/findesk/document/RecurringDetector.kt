package de.findesk.document

import de.findesk.model.TransactionItem
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.abs

/** Finds subscriptions, rent, salary and other bookings that repeat on a regular schedule. */
object RecurringDetector {
    enum class Cadence(val label: String, val days: Int, val toleranceDays: Int, val perYear: Int) {
        WEEKLY("Weekly", 7, 2, 52),
        MONTHLY("Monthly", 30, 4, 12),
        QUARTERLY("Quarterly", 91, 10, 4),
        YEARLY("Yearly", 365, 15, 1),
        ;

        fun next(from: LocalDate): LocalDate = when (this) {
            WEEKLY -> from.plusWeeks(1)
            MONTHLY -> from.plusMonths(1)
            QUARTERLY -> from.plusMonths(3)
            YEARLY -> from.plusYears(1)
        }
    }

    data class RecurringPayment(
        val name: String,
        val cadence: Cadence,
        /** Median amount (signed: negative for payments). */
        val typicalAmount: BigDecimal,
        /** True if every occurrence had (almost) the same amount. */
        val fixedAmount: Boolean,
        val lastDate: LocalDate,
        val nextDate: LocalDate,
        val occurrences: Int,
        val categoryName: String?,
    ) {
        val isIncome: Boolean get() = typicalAmount.signum() > 0
        val yearlyAmount: BigDecimal get() = typicalAmount.multiply(BigDecimal(cadence.perYear))
    }

    /** At least this many bookings (two for yearly, which rarely has more history). */
    private const val MIN_OCCURRENCES = 3

    /** Share of intervals that must match the cadence. */
    private const val MIN_REGULARITY = 0.75

    fun detect(transactions: List<TransactionItem>, today: LocalDate = LocalDate.now()): List<RecurringPayment> = transactions
        .groupBy { key(it) }
        .filterKeys { it.isNotEmpty() }
        .values
        .mapNotNull { group -> analyse(group, today) }
        .sortedWith(compareBy({ it.nextDate }, { it.name }))

    /** Bookings in the next [days] days, earliest first. */
    fun upcoming(recurring: List<RecurringPayment>, today: LocalDate = LocalDate.now(), days: Long = 30): List<RecurringPayment> = recurring.filter { !it.nextDate.isAfter(today.plusDays(days)) }

    /** Same counterparty and direction. Partner names are normalised ("NETFLIX.COM 1234" ~ "Netflix.com"). */
    private fun key(t: TransactionItem): String {
        val name = (t.partnerName ?: t.purpose ?: "").lowercase()
            .replace(Regex("\\d+"), " ") // reference numbers, dates
            .replace(Regex("[^\\p{L} ]"), " ")
            .split(' ').filter { it.length > 1 }.take(3).joinToString(" ")
        return if (name.isBlank()) "" else "$name|${t.amount.signum()}"
    }

    private fun analyse(group: List<TransactionItem>, today: LocalDate): RecurringPayment? {
        // One booking per day at most (split payments on the same day count once)
        val byDate = group.sortedBy { it.date }.distinctBy { it.date }
        if (byDate.size < 2) return null
        val intervals = byDate.zipWithNext { a, b -> ChronoUnit.DAYS.between(a.date, b.date).toInt() }
        val median = intervals.sorted()[intervals.size / 2]
        val cadence = Cadence.entries.firstOrNull { abs(median - it.days) <= it.toleranceDays } ?: return null
        val minCount = if (cadence == Cadence.YEARLY) 2 else MIN_OCCURRENCES
        if (byDate.size < minCount) return null
        val regular = intervals.count { abs(it - cadence.days) <= cadence.toleranceDays }
        if (regular.toDouble() / intervals.size < MIN_REGULARITY) return null

        val last = byDate.last()
        var next = cadence.next(last.date)
        // Stopped subscriptions: more than one full cycle overdue
        if (next.plusDays(cadence.days.toLong()).isBefore(today)) return null
        // A booking a few days late is still "due", not skipped to the next cycle
        while (next.isBefore(today.minusDays(cadence.toleranceDays.toLong()))) next = cadence.next(next)

        val amounts = group.map { it.amount }.sorted()
        val medianAmount = amounts[amounts.size / 2]
        val fixed = amounts.all { (it - medianAmount).abs() <= medianAmount.abs().multiply(BigDecimal("0.01")).max(BigDecimal("0.01")) }
        return RecurringPayment(
            name = last.partnerName ?: last.description,
            cadence = cadence,
            typicalAmount = medianAmount.setScale(2, RoundingMode.HALF_UP),
            fixedAmount = fixed,
            lastDate = last.date,
            nextDate = next,
            occurrences = byDate.size,
            categoryName = last.categoryName,
        )
    }
}
