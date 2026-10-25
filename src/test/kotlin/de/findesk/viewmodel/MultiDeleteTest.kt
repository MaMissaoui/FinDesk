package de.findesk.viewmodel

import de.findesk.TestData
import de.findesk.TestDatabase
import de.findesk.i18n.AppStrings
import de.findesk.i18n.StringKey
import de.findesk.model.Direction
import de.findesk.repository.AuditLogRepositoryImpl
import de.findesk.repository.TransactionRepositoryImpl
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.math.BigDecimal
import java.time.LocalDate
import java.time.YearMonth

/** Multi-selection on Transactions: toggle, range, select-all-shown, filter changes and the batch delete. */
class MultiDeleteTest {
    @TempDir
    lateinit var tmp: File

    private val today = LocalDate.of(2026, 6, 15)
    private lateinit var data: TestData
    private val audit = AuditLogRepositoryImpl()

    @BeforeEach
    fun setup() {
        TestDatabase.init()
        data = TestData()
    }

    private fun vm() = TransactionsViewModel(today = { today })

    /** Loads with all time, so the booking list shows every booking of the test. */
    private suspend fun shown(vm: TransactionsViewModel) {
        vm.start(period = Period.ALL).join()
    }

    @Test
    fun `toggleSelection twice removes again and sets the anchor`() = runBlocking {
        val account = data.account()
        val one = data.txn(account, today.minusDays(2), "-10.00", partner = "One")
        val vm = vm()
        shown(vm)

        vm.toggleSelection(one)
        assertEquals(setOf(one), vm.state.value.selectedIds)
        assertEquals(one, vm.state.value.selectionAnchorId, "the toggled row is the anchor")

        vm.toggleSelection(one)
        assertTrue(vm.state.value.selectedIds.isEmpty(), "the second toggle removes the booking again")
        assertEquals(one, vm.state.value.selectionAnchorId, "the anchor keeps the last toggled row")
        vm.close()
    }

    @Test
    fun `selectRange selects the shown rows between anchor and target, in either direction`() = runBlocking {
        val account = data.account()
        val one = data.txn(account, today.minusDays(4), "-1.00", partner = "One")
        val two = data.txn(account, today.minusDays(3), "-2.00", partner = "Two")
        val three = data.txn(account, today.minusDays(2), "-3.00", partner = "Three")
        val four = data.txn(account, today.minusDays(1), "-4.00", partner = "Four")
        val vm = vm()
        shown(vm)
        assertEquals(listOf(four, three, two, one), vm.state.value.transactions.map { it.id }, "the list is newest first")

        // Down the list, from the anchor to the later row
        vm.toggleSelection(two)
        vm.selectRange(four)
        assertEquals(setOf(two, three, four), vm.state.value.selectedIds)
        assertEquals(four, vm.state.value.selectionAnchorId)
        vm.close()

        // Up the list: the range runs from the target row back to the anchor
        val other = vm()
        shown(other)
        other.toggleSelection(three)
        other.selectRange(one)
        assertEquals(setOf(one, two, three), other.state.value.selectedIds, "the range is inclusive on both ends")
        other.close()
    }

    @Test
    fun `selectRange without an anchor behaves like a toggle`() = runBlocking {
        val account = data.account()
        val one = data.txn(account, today.minusDays(1), "-1.00", partner = "One")
        val vm = vm()
        shown(vm)
        vm.selectAllShown()
        assertNull(vm.state.value.selectionAnchorId, "a selection without a range click has no anchor")

        // Without an anchor the range click toggles: an already selected row is removed again
        vm.selectRange(one)
        assertTrue(vm.state.value.selectedIds.isEmpty())
        assertEquals(one, vm.state.value.selectionAnchorId, "the range click becomes the anchor")
        vm.close()
    }

    @Test
    fun `selectAllShown selects only what the current filter shows`() = runBlocking {
        val groceries = data.category("Groceries")
        val loans = data.category("Loans")
        val account = data.account()
        val inCategory = data.txn(account, today.minusDays(1), "-10.00", partner = "InCategory", categoryId = groceries)
        val otherCategory = data.txn(account, today.minusDays(2), "-20.00", partner = "OtherCategory", categoryId = loans)
        val vm = vm()
        shown(vm)
        vm.updateCategoryFilter(CategoryFilter.Only(groceries)).join()
        assertEquals(listOf(inCategory), vm.state.value.transactions.map { it.id })

        vm.selectAllShown()

        assertEquals(setOf(inCategory), vm.state.value.selectedIds)
        assertFalse(otherCategory in vm.state.value.selectedIds, "a booking the filter hides stays unselected")
        vm.close()
    }

    @Test
    fun `every filter change clears the selection`() = runBlocking {
        val account = data.account()
        val account2 = data.account(iban = "DE44500105175407324931")
        data.txn(account, today.minusDays(1), "-1.00", partner = "One")
        data.txn(account2, today.minusDays(1), "-2.00", partner = "Two")
        val vm = vm()
        shown(vm)
        vm.selectAllShown()
        assertTrue(vm.state.value.selectedIds.isNotEmpty())

        vm.updatePeriod(Period.LAST_MONTH).join()
        assertTrue(vm.state.value.selectedIds.isEmpty(), "a period change clears the selection")

        vm.selectAllShown()
        vm.updateSearchQuery("One").join()
        assertTrue(vm.state.value.selectedIds.isEmpty(), "a search change clears the selection")

        vm.selectAllShown()
        vm.updateDirection(Direction.EXPENSES).join()
        assertTrue(vm.state.value.selectedIds.isEmpty(), "a direction change clears the selection")

        vm.selectAllShown()
        vm.updateAmountRange(BigDecimal("5.00"), null).join()
        assertTrue(vm.state.value.selectedIds.isEmpty(), "an amount-bound change clears the selection")

        vm.selectAllShown()
        vm.updateCategoryFilter(CategoryFilter.None).join()
        assertTrue(vm.state.value.selectedIds.isEmpty(), "an uncategorized-only change clears the selection")

        vm.selectAllShown()
        vm.selectAccounts(AccountSelection.Account(account)).join()
        assertTrue(vm.state.value.selectedIds.isEmpty(), "an account change clears the selection")

        vm.selectAllShown()
        vm.start(month = YearMonth.from(today)).join()
        assertTrue(vm.state.value.selectedIds.isEmpty(), "the single-month navigation clears the selection")
        vm.close()
    }

    @Test
    fun `deleteSelected deletes exactly the confirmed bookings with one count-only audit entry`() = runBlocking {
        val account = data.account()
        val a = data.txn(account, today.minusDays(3), "-10.00", partner = "Secret Partner", purpose = "Secret purpose")
        val b = data.txn(account, today.minusDays(2), "-20.00", partner = "Other Partner")
        val c = data.txn(account, today.minusDays(1), "30.00", partner = "Third")
        val vm = vm()
        shown(vm)
        vm.toggleSelection(a)
        vm.toggleSelection(b)

        vm.deleteSelected(setOf(a, b)).join()

        val rows = TransactionRepositoryImpl().findItems().map { it.id }
        assertTrue(a !in rows)
        assertTrue(b !in rows, "the selected bookings are deleted")
        assertTrue(c in rows, "the unselected booking stays")
        assertTrue(vm.state.value.selectedIds.isEmpty(), "the selection is cleared")
        val entries = audit.findByAction("DELETE_TRANSACTIONS")
        assertEquals(1, entries.size, "one audit entry for the batch, not one per booking")
        val details = entries.single().details.orEmpty()
        assertTrue(details.contains("2"), "the entry names the count: $details")
        assertFalse(details.contains("Secret"), "no partner text in the audit entry: $details")
        assertFalse(details.contains("purpose"), "no purpose text in the audit entry: $details")
        assertEquals(AppStrings.format(StringKey.MessageTransactionsDeleted, 2), vm.state.value.message)
        vm.close()
    }

    @Test
    fun `a selected id that disappears on refresh is dropped from the selection`() = runBlocking {
        val account = data.account()
        val a = data.txn(account, today.minusDays(3), "-10.00", partner = "One")
        val b = data.txn(account, today.minusDays(2), "-20.00", partner = "Two")
        val c = data.txn(account, today.minusDays(1), "-30.00", partner = "Three")
        val vm = vm()
        shown(vm)
        vm.toggleSelection(a)
        vm.toggleSelection(b)
        vm.toggleSelection(c)
        assertEquals(setOf(a, b, c), vm.state.value.selectedIds)

        // The booking is deleted elsewhere (e.g. through its account); the next refresh drops it
        TransactionRepositoryImpl().delete(c)
        vm.loadTransactions().join()
        assertEquals(setOf(a, b), vm.state.value.selectedIds)
        vm.close()
    }

    @Test
    fun `deleteSelected never deletes a booking that is hidden or not in the passed set`() = runBlocking {
        val account = data.account()
        val a = data.txn(account, today.minusDays(3), "-10.00", partner = "Alpha")
        val b = data.txn(account, today.minusDays(2), "-20.00", partner = "Beta")
        val hidden = data.txn(account, today.minusDays(1), "-30.00", partner = "Gamma")
        val vm = vm()
        shown(vm)
        // The search hides Gamma, so it is neither shown nor selected
        vm.updateSearchQuery("Alph").join()
        assertEquals(listOf(a), vm.state.value.transactions.map { it.id })
        vm.toggleSelection(a)

        vm.deleteSelected(setOf(a, hidden)).join()

        val rows = TransactionRepositoryImpl().findItems().map { it.id }
        assertTrue(a !in rows, "the passed, shown, selected booking is deleted")
        assertTrue(hidden in rows, "a booking the list does not show is never deleted")
        assertTrue(b in rows, "a booking not in the passed set is never deleted")
        vm.close()
    }

    @Test
    fun `selected totals keep currencies separate`() = runBlocking {
        val account = data.account()
        data.txn(account, today.minusDays(3), "-10.00", partner = "One")
        data.txn(account, today.minusDays(2), "-2.50", partner = "Two")
        data.txn(account, today.minusDays(1), "5.00", partner = "USD Partner", currency = "USD")
        val vm = vm()
        shown(vm)
        vm.selectAllShown()
        assertEquals(3, vm.state.value.selectedIds.size)

        val totals = vm.state.value.selectedTotals
        assertEquals(2, totals.size, "two currencies, never added together")
        assertEquals(BigDecimal("-12.50"), totals["EUR"])
        assertEquals(BigDecimal("5.00"), totals["USD"])
        vm.close()
    }
}
