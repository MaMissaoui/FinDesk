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
