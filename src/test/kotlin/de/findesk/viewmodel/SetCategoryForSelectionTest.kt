package de.findesk.viewmodel

import de.findesk.TestData
import de.findesk.TestDatabase
import de.findesk.i18n.AppStrings
import de.findesk.i18n.StringKey
import de.findesk.repository.AuditLogRepositoryImpl
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.LocalDate

/** The batch "Category" menu of the selection bar: what it changes, audits and counts. */
class SetCategoryForSelectionTest {
    @TempDir
    lateinit var tmp: File

    private val today = LocalDate.of(2026, 6, 15)
    private lateinit var data: TestData
    private val audit = AuditLogRepositoryImpl()
    private var normalCat = 0

    @BeforeEach
    fun setup() {
        TestDatabase.init()
        data = TestData()
        normalCat = data.category("Groceries")
    }

    private fun vm() = TransactionsViewModel(today = { today })

    /** Loads with all time, so the booking list shows every booking of the test. */
    private suspend fun shown(vm: TransactionsViewModel) {
        vm.start(period = Period.ALL).join()
    }

    @Test
    fun `setCategoryForSelection changes only selected and shown bookings with one count-only audit entry`() = runBlocking {
        val account = data.account()
        val office = data.category("Office Supplies")
        val a = data.txn(account, today.minusDays(3), "-10.00", partner = "Secret Partner", purpose = "Secret purpose", categoryId = normalCat)
        val b = data.txn(account, today.minusDays(2), "-20.00", partner = "Other Partner", categoryId = normalCat)
        val c = data.txn(account, today.minusDays(1), "-30.00", partner = "Third Partner", categoryId = normalCat)
        val vm = vm()
        shown(vm)
        vm.toggleSelection(a)
        vm.toggleSelection(b)
        assertEquals(setOf(a, b), vm.state.value.selectedIds)

        vm.setCategoryForSelection(office).join()

        val byId = data.transactionRepo.findItems().associateBy { it.id }
        assertEquals(office, byId[a]!!.categoryId)
        assertEquals(office, byId[b]!!.categoryId)
        assertEquals(normalCat, byId[c]!!.categoryId, "a shown booking that is not selected keeps its category")
        assertEquals(setOf(a, b), vm.state.value.selectedIds, "the selection is kept")

        val entries = audit.findByAction("UPDATE_CATEGORIES")
        assertEquals(1, entries.size, "one audit entry for the batch, not one per booking")
        val details = entries.single().details.orEmpty()
        assertTrue(details.contains("2"), "the entry names the count: $details")
        assertFalse(details.contains("Secret"), "no partner text in the audit entry: $details")
        assertFalse(details.contains("purpose"), "no purpose text in the audit entry: $details")
        assertFalse(details.contains("-10.00"), "no amounts in the audit entry: $details")
        assertEquals(AppStrings.format(StringKey.MessageCategorySet, 2), vm.state.value.message)
        vm.close()
    }

    @Test
    fun `setCategoryForSelection never touches a booking the list does not show`() = runBlocking {
        val account = data.account()
        val office = data.category("Office Supplies")
        val a = data.txn(account, today.minusDays(2), "-10.00", partner = "One", categoryId = normalCat)
        val hidden = data.txn(account, LocalDate.of(2027, 1, 15), "-99.00", partner = "Far Future", categoryId = normalCat)
        val vm = vm()
        // The default period (this month) hides the far-future booking; the default is not Period.ALL here on purpose
        vm.start().join()
        assertEquals(listOf(a), vm.state.value.transactions.map { it.id })
        vm.toggleSelection(a)

        vm.setCategoryForSelection(office).join()

        val byId = data.transactionRepo.findItems().associateBy { it.id }
        assertEquals(office, byId[a]!!.categoryId)
        assertEquals(normalCat, byId[hidden]!!.categoryId, "a booking the list does not show is never changed")
        val entries = audit.findByAction("UPDATE_CATEGORIES")
        assertEquals(1, entries.size)
        assertTrue(entries.single().details.orEmpty().contains("1"), "the entry names the count of changed bookings")
        vm.close()
    }

    @Test
    fun `null makes the selected bookings uncategorized and names it in the audit entry`() = runBlocking {
        val account = data.account()
        val a = data.txn(account, today.minusDays(1), "-10.00", partner = "One", categoryId = normalCat)
        val b = data.txn(account, today.minusDays(2), "-20.00", partner = "Two", categoryId = normalCat)
        val vm = vm()
        shown(vm)
        vm.toggleSelection(a)
        vm.toggleSelection(b)

        vm.setCategoryForSelection(null).join()

        val byId = data.transactionRepo.findItems().associateBy { it.id }
        assertEquals(null, byId[a]!!.categoryId, "null means Uncategorized")
        assertEquals(null, byId[b]!!.categoryId)
        assertEquals(setOf(a, b), vm.state.value.selectedIds, "the selection is kept")
        val details = audit.findByAction("UPDATE_CATEGORIES").single().details.orEmpty()
        assertTrue(details.contains("uncategorized"), "null is recorded as 'uncategorized': $details")
        vm.close()
    }

    @Test
    fun `bookings already on the target category are not counted`() = runBlocking {
        val account = data.account()
        val office = data.category("Office Supplies")
        val already = data.txn(account, today.minusDays(2), "-10.00", partner = "One", categoryId = office)
        val other = data.txn(account, today.minusDays(1), "-20.00", partner = "Two", categoryId = normalCat)
        val vm = vm()
        shown(vm)
        vm.toggleSelection(already)
        vm.toggleSelection(other)

        vm.setCategoryForSelection(office).join()

        assertEquals(AppStrings.format(StringKey.MessageCategorySet, 1), vm.state.value.message, "only the booking that actually moved is counted")
        assertEquals(1, audit.findByAction("UPDATE_CATEGORIES").size)

        // Selecting only bookings already on the target: nothing changes, and neither the audit trail nor the
        // message may react
        vm.clearSelection()
        vm.toggleSelection(already)
        vm.setCategoryForSelection(office).join()

        assertEquals(1, audit.findByAction("UPDATE_CATEGORIES").size, "no audit entry when nothing changed")
        assertEquals(AppStrings.format(StringKey.MessageCategorySet, 1), vm.state.value.message, "the previous message is kept")
        assertEquals(office, data.transactionRepo.findItems().first { it.id == already }.categoryId)
        vm.close()
    }
}
