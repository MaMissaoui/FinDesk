package de.findesk.data.entity

import de.findesk.data.table.PendingBookingsTable
import org.jetbrains.exposed.dao.IntEntity
import org.jetbrains.exposed.dao.IntEntityClass
import org.jetbrains.exposed.dao.id.EntityID
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime

class PendingBooking(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<PendingBooking>(PendingBookingsTable)

    var account by Account referencedOn PendingBookingsTable.accountId
    var date by PendingBookingsTable.date
    var valueDate by PendingBookingsTable.valueDate
    var amount by PendingBookingsTable.amount
    var currency by PendingBookingsTable.currency
    var partnerName by PendingBookingsTable.partnerName
    var purpose by PendingBookingsTable.purpose
    var fetchedAt by PendingBookingsTable.fetchedAt
}
