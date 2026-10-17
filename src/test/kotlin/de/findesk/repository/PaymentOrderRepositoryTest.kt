package de.findesk.repository

import de.findesk.TestDatabase
import de.findesk.model.PaymentAction
import de.findesk.model.PaymentOrderStatus
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import java.math.BigDecimal
import java.time.LocalDate

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PaymentOrderRepositoryTest {
    private lateinit var repository: PaymentOrderRepository

    @BeforeAll
    fun setup() {
        TestDatabase.init()
        repository = PaymentOrderRepositoryImpl()
    }

    @Test
    fun `orders left pending by an earlier run are marked outcome unknown`() {
        TestDatabase.init() // counts below must not see the other tests' orders
        fun order() = repository.create(PaymentAction.TRANSFER, "DE02120300000000202051", "Max", "DE02120300000000202052", BigDecimal("5.00"), "EUR", null, null, null)
        val stale = order()
        val done = order().also { repository.complete(it, PaymentOrderStatus.ACCEPTED) }
        val cutoff = java.time.LocalDateTime.now().plusSeconds(1)

        assertEquals(1, repository.markInterrupted(cutoff))

        val items = repository.findRecentItems(50)
        assertEquals(PaymentOrderStatus.UNKNOWN, items.first { it.id == stale }.status)
        assertEquals(PaymentOrderStatus.ACCEPTED, items.first { it.id == done }.status)
        assertEquals(0, repository.markInterrupted(cutoff))
        // An order still in flight in this run is newer than the start and stays pending
        val inFlight = order()
        assertEquals(0, repository.markInterrupted(java.time.LocalDateTime.now().minusMinutes(1)))
        assertEquals(PaymentOrderStatus.PENDING, repository.findRecentItems(50).first { it.id == inFlight }.status)
    }

    @Test
    fun `new order starts pending and gets the bank's answer`() {
        val id = repository.create(
            action = PaymentAction.TRANSFER,
            accountIban = "DE02120300000000202051",
            recipientName = "Vermieter GmbH",
            recipientIban = "DE02120300000000202052",
            amount = BigDecimal("850.00"),
            currency = "EUR",
            purpose = "Miete",
            requestedDate = null,
            schedule = null,
        )

        var order = repository.findRecentItems(50).first { it.id == id }
        assertEquals(PaymentOrderStatus.PENDING, order.status)
        assertNull(order.bankOrderId)

        repository.complete(id, PaymentOrderStatus.ACCEPTED, bankOrderId = "REF-123", message = null)
        order = repository.findRecentItems(50).first { it.id == id }
        assertEquals(PaymentOrderStatus.ACCEPTED, order.status)
        assertEquals("REF-123", order.bankOrderId)
    }

    @Test
    fun `recent orders are newest first and respect the limit`() {
        for (i in 1..5) {
            repository.create(
                action = PaymentAction.SCHEDULED_TRANSFER,
                accountIban = "DE02120300000000202051",
                recipientName = "Empfänger $i",
                recipientIban = "DE02120300000000202052",
                amount = BigDecimal("$i.00"),
                currency = "EUR",
                purpose = null,
                requestedDate = LocalDate.of(2026, 1, i),
                schedule = null,
            )
        }
        val recent = repository.findRecentItems(3)
        assertEquals(3, recent.size)
        assertTrue(recent.zipWithNext().all { (a, b) -> !a.createdAt.isBefore(b.createdAt) })
    }
}
