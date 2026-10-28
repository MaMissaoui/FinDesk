package de.findesk.repository

import de.findesk.data.entity.PendingBooking
import de.findesk.data.table.AccountsTable
import de.findesk.data.table.PendingBookingsTable
import de.findesk.model.PendingBookingItem
import de.findesk.model.toItem
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.LocalDateTime

/**
 * The bank's pending lines (vorgemerkte Umsätze): fetched apart from the real bookings, stored only here and
 * shown only greyed out above the booking list. Nothing of the booking pipeline reads them.
 */
interface PendingBookingRepository {
    /**
     * Replaces the account's stored pending rows with [items], stamped with [fetchedAt], in one transaction
     * (all or none). Called with an empty list when the bank reported none pending; a null pending snapshot
     * (statement failed) never calls this, so what's stored stays.
     */
    fun replaceForAccount(accountId: Int, items: List<PendingBookingItem>, fetchedAt: LocalDateTime)

    // The account's stored pending lines, newest first.
    fun findItems(accountId: Int): List<PendingBookingItem>
    fun deleteForAccount(accountId: Int)
}

class PendingBookingRepositoryImpl : PendingBookingRepository {
    override fun replaceForAccount(accountId: Int, items: List<PendingBookingItem>, fetchedAt: LocalDateTime): Unit = transaction {
        // The delete and the inserts share this transaction, so a failure either way keeps what was stored
        PendingBookingsTable.deleteWhere { PendingBookingsTable.accountId eq accountId }
        items.forEach { item ->
            PendingBookingsTable.insert {
                it[PendingBookingsTable.accountId] = EntityID(accountId, AccountsTable)
                it[PendingBookingsTable.date] = item.date
                it[PendingBookingsTable.valueDate] = item.valueDate
                it[PendingBookingsTable.amount] = item.amount
                it[PendingBookingsTable.currency] = item.currency
                it[PendingBookingsTable.partnerName] = item.partnerName
                it[PendingBookingsTable.purpose] = item.purpose
                it[PendingBookingsTable.fetchedAt] = fetchedAt
            }
        }
    }

    override fun findItems(accountId: Int): List<PendingBookingItem> = transaction {
        PendingBooking
            .find { PendingBookingsTable.accountId eq accountId }
            .orderBy(PendingBookingsTable.date to SortOrder.DESC, PendingBookingsTable.id to SortOrder.DESC)
            .map { it.toItem() }
    }

    override fun deleteForAccount(accountId: Int): Unit = transaction {
        PendingBookingsTable.deleteWhere { PendingBookingsTable.accountId eq accountId }
    }
}
