package de.findesk.importer

import java.time.LocalDate

/**
 * One rule for two-digit years in every import format (CSV, MT940, QIF).
 *
 * Bank files never date bookings more than a year ahead, so a later 20yy is a 19yy.
 */
internal object TwoDigitYear {
    /**
     * Resolves a two-digit year [yy] (0..99) to a full year: 2000 + [yy], unless that is later than
     * [today]'s year + 1, in which case it is 1900 + [yy]. With today in 2026, 28..99 become 1928..1999
     * and 00..27 become 2000..2027.
     */
    fun resolve(yy: Int, today: LocalDate = LocalDate.now()): Int {
        require(yy in 0..99) { "two-digit year out of range: $yy" }
        val year = 2000 + yy
        return if (year > today.year + 1) 1900 + yy else year
    }
}
