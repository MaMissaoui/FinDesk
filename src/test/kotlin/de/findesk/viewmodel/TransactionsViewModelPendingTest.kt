package de.findesk.viewmodel

import de.findesk.TestData
import de.findesk.TestDatabase
import de.findesk.model.PendingBookingItem
import de.findesk.repository.PendingBookingRepositoryImpl
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestInstance.Lifecycle.PER_CLASS
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime

/** The Transactions view model's pending (vorgemerkte) rows: loaded for one account, empty for any other. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TransactionsViewModelPendingTest {
    private lateinit var data: TestData
    private lateinit var pending: PendingBookingRepositoryImpl
    private val tank = "Beispiel Tank GmbH"

    @BeforeAll
    fun setup() {
        TestDatabase.init()
        data = TestData()
        pending = PendingBookingRepositoryImpl()
    }

    private fun item(accountId: Int) = PendingBookingItem(
        accountId = accountId,
        date = LocalDate.of(2026, 10, 3),
        valueDate = LocalDate.of(2026, 10, 2),
        amount = BigDecimal("-49.80"),
        currency = "EUR",
        partnerName = tank,
        purpose = "Kraftstoff",
        fetchedAt = LocalDateTime.of(2026, 10, 4, 9, 30),
    )

    @Test
    fun `pending rows are in the state for one account and absent for all accounts`() {
        val accountId = data.account(balance = "1000.00")
        data.txn(accountId, LocalDate.of(2026, 10, 3), "-120.00", partner = "Beispiel AG", purpose = "Gehalt")
        pending.replaceForAccount(accountId, listOf(item(accountId)), LocalDateTime.of(2026, 10, 4, 9, 30))

        val vm = TransactionsViewModel(pendingRepository = pending)
        runBlocking { vm.start(accountId).join() }
        assertEquals(listOf(item(accountId)), vm.state.value.pendingBookings, "one account shows its pending rows")

        runBlocking {
            vm.selectAccounts(AccountSelection.All).join()
        }
        assertTrue(vm.state.value.pendingBookings.isEmpty(), "all accounts shows none")
        vm.close()
    }

    @Test
    fun `pending rows stay out of the counted bookings`() {
        val accountId = data.account(balance = "1000.00")
        data.txn(accountId, LocalDate.of(2026, 10, 3), "-120.00", partner = "Beispiel AG", purpose = "Gehalt")
        pending.replaceForAccount(accountId, listOf(item(accountId)), LocalDateTime.of(2026, 10, 4, 9, 30))

        val vm = TransactionsViewModel(pendingRepository = pending)
        runBlocking { vm.start(accountId).join() }
        assertEquals(1, vm.state.value.transactions.size, "the list counts only the booking")
        assertEquals(1, vm.state.value.pendingBookings.size, "the pending block is listed separately")
        assertEquals(BigDecimal("120.00"), vm.state.value.totalExpenses.values.single(), "the totals don't count the pending line")
        vm.close()
    }
}
