package de.findesk.repository

import de.findesk.TestData
import de.findesk.TestDatabase
import de.findesk.model.PendingBookingItem
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestInstance.Lifecycle.PER_CLASS
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime

/** The pending bookings table: replace, order, delete, and the cascades with the account and bank access. */
@TestInstance(PER_CLASS)
class PendingBookingRepositoryTest {
    private lateinit var data: TestData
    private lateinit var pendingRepo: PendingBookingRepositoryImpl
    private var bankAccessId = 0
    private val fetchedAt = LocalDateTime.of(2026, 10, 4, 9, 30)

    @BeforeAll
    fun setup() {
        TestDatabase.init()
        data = TestData()
        bankAccessId = data.bankAccessRepo.create("Beispiel Bank", "37050198", "COLSDE33XXX", "demo-user").id.value
        pendingRepo = PendingBookingRepositoryImpl()
    }

    private fun item(accountId: Int, amount: String, partner: String?) = PendingBookingItem(
        accountId = accountId,
        date = LocalDate.of(2026, 10, 3),
        valueDate = LocalDate.of(2026, 10, 2),
        amount = BigDecimal(amount),
        currency = "EUR",
        partnerName = partner,
        purpose = "Musterzahlung",
    )

    @Test
    fun `test replaceForAccount deletes and inserts in one go and stamps the fetch time`() {
        val accountId = data.account(balance = "500.00")
        pendingRepo.replaceForAccount(accountId, listOf(item(accountId, "-10.00", "Beispiel GmbH")), fetchedAt)

        val first = pendingRepo.findItems(accountId).single()
        assertEquals("-10.00", first.amount.toPlainString())
        assertEquals("Musterzahlung", first.purpose)
        assertEquals(LocalDate.of(2026, 10, 2), first.valueDate)
        assertEquals(fetchedAt, first.fetchedAt, "replaced rows carry the fetch stamp")

        // A second replace deletes the old row instead of adding a second one
        pendingRepo.replaceForAccount(accountId, listOf(item(accountId, "-20.00", "Andere GmbH"), item(accountId, "-30.00", null)), fetchedAt.plusMinutes(5))
        val second = pendingRepo.findItems(accountId)
        assertEquals(2, second.size)
        assertTrue(second.none { it.partnerName == "Beispiel GmbH" })
        assertEquals(fetchedAt.plusMinutes(5), second.first().fetchedAt)
    }

    @Test
    fun `test findItems lists the newest first`() {
        val accountId = data.account(balance = "500.00")
        pendingRepo.replaceForAccount(
            accountId,
            listOf(item(accountId, "-10.00", "Alt GmbH").copy(date = LocalDate.of(2026, 9, 1)), item(accountId, "-20.00", "Neu GmbH")),
            fetchedAt,
        )
        assertEquals(listOf("Neu GmbH", "Alt GmbH"), pendingRepo.findItems(accountId).map { it.partnerName })
    }

    @Test
    fun `test deleteForAccount and the account cascade remove the rows`() {
        val accountId = data.account(balance = "500.00")
        pendingRepo.replaceForAccount(accountId, listOf(item(accountId, "-10.00", "Beispiel GmbH")), fetchedAt)
        assertTrue(pendingRepo.findItems(accountId).isNotEmpty())

        pendingRepo.deleteForAccount(accountId)
        assertTrue(pendingRepo.findItems(accountId).isEmpty(), "deleteForAccount clears them")

        // An account delete cascades: pending rows are gone with the account
        pendingRepo.replaceForAccount(accountId, listOf(item(accountId, "-10.00", "Beispiel GmbH")), fetchedAt)
        transaction { deleteAccountCascade(accountId) }
        assertTrue(pendingRepo.findItems(accountId).isEmpty(), "the account cascade removes the pending rows")
    }

    @Test
    fun `test the bank access cascade removes its accounts' pending rows`() {
        val accountId = data.account(balance = "500.00")
        val bankOfAccount = data.accountRepo.findAllItems().first { it.id == accountId }.bankAccessId
        pendingRepo.replaceForAccount(accountId, listOf(item(accountId, "-10.00", "Beispiel GmbH")), fetchedAt)

        assertTrue(data.bankAccessRepo.deleteWithAccounts(bankOfAccount))
        assertTrue(pendingRepo.findItems(accountId).isEmpty(), "the bank access cascade removes the pending rows too")
    }
}
