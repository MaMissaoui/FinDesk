package de.findesk.data.entity

import de.findesk.data.table.PaymentOrdersTable
import org.jetbrains.exposed.dao.IntEntity
import org.jetbrains.exposed.dao.IntEntityClass
import org.jetbrains.exposed.dao.id.EntityID

class PaymentOrder(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<PaymentOrder>(PaymentOrdersTable)

    var createdAt by PaymentOrdersTable.createdAt
    var updatedAt by PaymentOrdersTable.updatedAt
    var action by PaymentOrdersTable.action
    var accountIban by PaymentOrdersTable.accountIban
    var recipientName by PaymentOrdersTable.recipientName
    var recipientIban by PaymentOrdersTable.recipientIban
    var amount by PaymentOrdersTable.amount
    var currency by PaymentOrdersTable.currency
    var purpose by PaymentOrdersTable.purpose
    var requestedDate by PaymentOrdersTable.requestedDate
    var schedule by PaymentOrdersTable.schedule
    var bankOrderId by PaymentOrdersTable.bankOrderId
    var status by PaymentOrdersTable.status
    var message by PaymentOrdersTable.message
}
