package de.findesk.data.table

import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.sql.javatime.date
import org.jetbrains.exposed.sql.javatime.datetime

/**
 * The bank's pending lines (vorgemerkte Umsätze) as the last sync fetched them. Not bookings: the bank may
 * still change or drop them, so nothing of the booking pipeline reads this table (see BankingService).
 */
object PendingBookingsTable : IntIdTable("pending_bookings") {
    val accountId = reference("account_id", AccountsTable).index()
    val date = date("date")
    val valueDate = date("value_date").nullable()
    val amount = decimal("amount", 15, 2)
    val currency = varchar("currency", 3).default("EUR")
    val partnerName = varchar("partner_name", 255).nullable()
    val purpose = text("purpose").nullable()

    /** When the sync fetched the lines, shown in the pending block's header. */
    val fetchedAt = datetime("fetched_at")
}
