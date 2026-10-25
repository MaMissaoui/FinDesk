package de.findesk.data.table

import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.sql.javatime.datetime

/**
 * Bookings the user deleted, remembered so a later statement import or bank sync cannot bring them back:
 * when the import's dedup hash no longer has a stored row, it still matches a remembered deletion and is skipped.
 * One row per deleted booking, not per deletion action. The account's rows go away when the account goes.
 */
object DeletedBookingsTable : IntIdTable("deleted_bookings") {
    val accountId = reference("account_id", AccountsTable).index()

    /** The booking's dedup hash at deletion time; 64 chars like [TransactionsTable.hash]. */
    val hash = varchar("hash", 64).index()
    val deletedAt = datetime("deleted_at")
}
