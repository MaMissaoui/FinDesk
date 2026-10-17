package de.findesk.data.table

import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.sql.javatime.date
import org.jetbrains.exposed.sql.javatime.datetime

/**
 * One row per order sent to a bank (transfer, scheduled transfer, standing order), kept as a local record of what
 * was requested and what the bank answered. There is no foreign key, so the record survives deleting the account.
 */
object PaymentOrdersTable : IntIdTable("payment_orders") {
    val createdAt = datetime("created_at")
    val updatedAt = datetime("updated_at")
    val action = varchar("action", 40).index()
    val accountIban = varchar("account_iban", 34).nullable()
    val recipientName = varchar("recipient_name", 255).nullable()
    val recipientIban = varchar("recipient_iban", 34).nullable()
    val amount = decimal("amount", 15, 2).nullable()
    val currency = varchar("currency", 3).default("EUR")
    val purpose = text("purpose").nullable()
    val requestedDate = date("requested_date").nullable()
    val schedule = varchar("schedule", 255).nullable()
    val bankOrderId = varchar("bank_order_id", 100).nullable()
    val status = varchar("status", 20).index()
    val message = text("message").nullable()
}
