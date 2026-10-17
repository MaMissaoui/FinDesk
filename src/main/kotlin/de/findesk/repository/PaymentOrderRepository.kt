package de.findesk.repository

import de.findesk.data.entity.PaymentOrder
import de.findesk.data.table.PaymentOrdersTable
import de.findesk.model.PaymentOrderItem
import de.findesk.model.PaymentOrderStatus
import de.findesk.model.toItem
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.transactions.transaction
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime

interface PaymentOrderRepository {
    /** Records a requested order before it is sent; returns its id. */
    fun create(
        action: String,
        accountIban: String?,
        recipientName: String?,
        recipientIban: String?,
        amount: BigDecimal?,
        currency: String,
        purpose: String?,
        requestedDate: LocalDate?,
        schedule: String?,
    ): Int

    /** Stores the bank's answer for a previously recorded order. */
    fun complete(id: Int, status: String, bankOrderId: String? = null, message: String? = null)

    fun findRecentItems(limit: Int = 20): List<PaymentOrderItem>

    /**
     * Marks orders still pending from before [startedBefore] (an earlier run of the app that stopped mid-send) as
     * [PaymentOrderStatus.UNKNOWN]: nothing will ever complete them, and the bank may have executed them.
     * Returns how many were marked.
     */
    fun markInterrupted(startedBefore: LocalDateTime): Int
}

class PaymentOrderRepositoryImpl : PaymentOrderRepository {
    override fun create(
        action: String,
        accountIban: String?,
        recipientName: String?,
        recipientIban: String?,
        amount: BigDecimal?,
        currency: String,
        purpose: String?,
        requestedDate: LocalDate?,
        schedule: String?,
    ): Int = transaction {
        val now = LocalDateTime.now()
        PaymentOrder.new {
            this.createdAt = now
            this.updatedAt = now
            this.action = action
            this.accountIban = accountIban
            this.recipientName = recipientName
            this.recipientIban = recipientIban
            this.amount = amount
            this.currency = currency
            this.purpose = purpose
            this.requestedDate = requestedDate
            this.schedule = schedule
            this.status = PaymentOrderStatus.PENDING
        }.id.value
    }

    override fun complete(id: Int, status: String, bankOrderId: String?, message: String?): Unit = transaction {
        PaymentOrder[id].apply {
            this.status = status
            bankOrderId?.takeIf { it.isNotBlank() }?.let { this.bankOrderId = it }
            this.message = message
            this.updatedAt = LocalDateTime.now()
        }
    }

    override fun markInterrupted(startedBefore: LocalDateTime): Int = transaction {
        val now = LocalDateTime.now()
        PaymentOrder.find { (PaymentOrdersTable.status eq PaymentOrderStatus.PENDING) and (PaymentOrdersTable.createdAt less startedBefore) }
            .toList()
            .onEach {
                it.status = PaymentOrderStatus.UNKNOWN
                it.updatedAt = now
            }.size
    }

    override fun findRecentItems(limit: Int): List<PaymentOrderItem> = transaction {
        PaymentOrder.all()
            .orderBy(PaymentOrdersTable.createdAt to SortOrder.DESC)
            .limit(limit)
            .map { it.toItem() }
    }
}
