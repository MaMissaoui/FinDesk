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

    /** The fixed today the view models run on: the fixtures' stamps (2026-10-04) are recent on it. */
    private val today: LocalDate = LocalDate.of(2026, 10, 4)

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

        val vm = TransactionsViewModel(pendingRepository = pending, today = { today })
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

        val vm = TransactionsViewModel(pendingRepository = pending, today = { today })
        runBlocking { vm.start(accountId).join() }
        assertEquals(1, vm.state.value.transactions.size, "the list counts only the booking")
        assertEquals(1, vm.state.value.pendingBookings.size, "the pending block is listed separately")
        assertEquals(BigDecimal("120.00"), vm.state.value.totalExpenses.values.single(), "the totals don't count the pending line")
        vm.close()
    }

    /**
     * Pending rows preview one account's own unfiltered, current list: they show for all time and for this
     * month, and disappear under a category filter, the uncategorized link, a past month or a search.
     */
    @Test
    fun `pending rows show only unfiltered for a period that includes today`() {
        val accountId = data.account(balance = "1000.00")
        val category = data.category("Beispiel Haushalt")
        data.txn(accountId, LocalDate.of(2026, 10, 3), "-120.00", partner = "Beispiel AG", purpose = "Gehalt")
        pending.replaceForAccount(accountId, listOf(item(accountId)), LocalDateTime.of(2026, 10, 4, 9, 30))

        val vm = TransactionsViewModel(pendingRepository = pending, today = { today })
        runBlocking {
            // A deep link into the account is all time: shown
            vm.start(accountId).join()
            assertEquals(listOf(item(accountId)), vm.state.value.pendingBookings, "all time shows the pending rows")

            // This month includes today: shown
            vm.updatePeriod(Period.THIS_MONTH).join()
            assertEquals(listOf(item(accountId)), vm.state.value.pendingBookings, "this month shows the pending rows")

            // A category drill-down: hidden, still one account selected
            vm.updateCategoryFilter(CategoryFilter.Only(category)).join()
            assertTrue(vm.state.value.pendingBookings.isEmpty(), "a category filter hides the pending rows")
            vm.updateCategoryFilter(CategoryFilter.Any).join()
            assertEquals(listOf(item(accountId)), vm.state.value.pendingBookings, "clearing the filter shows them again")

            // The dashboard's uncategorized link: still one account, but the filter hides them
            vm.start(accountId, uncategorizedOnly = true).join()
            assertTrue(vm.state.value.pendingBookings.isEmpty(), "uncategorized-only hides the pending rows")

            // A past month excludes today: hidden
            vm.start(accountId, period = Period.LAST_MONTH).join()
            assertTrue(vm.state.value.pendingBookings.isEmpty(), "a past month hides the pending rows")

            // A search: hidden
            vm.start(accountId).join()
            vm.updateSearchQuery("Gehalt").join()
            assertTrue(vm.state.value.pendingBookings.isEmpty(), "a search hides the pending rows")
        }
        vm.close()
    }

    /**
     * The pending block shows only while the account's fetch stamp is recent: 6 days and exactly 7 days before
     * today ([TransactionsViewModel.PENDING_MAX_AGE_DAYS]) still show, 8 days don't. The cutoff is on the fetch
     * stamp, never on a line's own date, so a month-old card hold with a stamp from today still shows.
     */
    @Test
    fun `pending rows show only while their fetch stamp is within the max age`() {
        val accountId = data.account(balance = "1000.00")
        data.txn(accountId, LocalDate.of(2026, 10, 3), "-120.00", partner = "Beispiel AG", purpose = "Gehalt")
        // A card hold whose own date is a month old, but whose stamp can be from the fixed today
        val hold = item(accountId).copy(date = LocalDate.of(2026, 9, 4))

        val vm = TransactionsViewModel(pendingRepository = pending, today = { today })
        runBlocking {
            // Stamp from the fixed today: the month-old hold still shows
            val todayStamp = LocalDateTime.of(2026, 10, 4, 12, 0)
            pending.replaceForAccount(accountId, listOf(hold), todayStamp)
            vm.start(accountId).join()
            assertEquals(listOf(hold.copy(fetchedAt = todayStamp)), vm.state.value.pendingBookings, "the cutoff is on the fetch stamp, not the line's date")

            // Stamp 6 days before today: shown
            val sixDays = LocalDateTime.of(2026, 9, 28, 12, 0)
            pending.replaceForAccount(accountId, listOf(hold), sixDays)
            vm.start(accountId).join()
            assertEquals(listOf(hold.copy(fetchedAt = sixDays)), vm.state.value.pendingBookings, "6 days old shows the rows")

            // Stamp exactly 7 days before today: shown
            val sevenDays = LocalDateTime.of(2026, 9, 27, 12, 0)
            pending.replaceForAccount(accountId, listOf(hold), sevenDays)
            vm.start(accountId).join()
            assertEquals(listOf(hold.copy(fetchedAt = sevenDays)), vm.state.value.pendingBookings, "exactly 7 days old still shows the rows")

            // Stamp 8 days before today: hidden
            val eightDays = LocalDateTime.of(2026, 9, 26, 12, 0)
            pending.replaceForAccount(accountId, listOf(hold), eightDays)
            vm.start(accountId).join()
            assertTrue(vm.state.value.pendingBookings.isEmpty(), "8 days old hides the rows")
        }
        vm.close()
    }
}
