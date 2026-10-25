package de.findesk.viewmodel

import de.findesk.TestData
import de.findesk.TestDatabase
import de.findesk.i18n.AppStrings
import de.findesk.i18n.StringKey
import de.findesk.model.RuleDirection
import de.findesk.model.RuleField
import de.findesk.model.TaxSource
import de.findesk.repository.AuditLogRepositoryImpl
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.LocalDate

/** The manual tax override from the view models: the selection, the details dialog and the rule dialog's save. */
class TaxOverrideTest {
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
    fun `setTaxOverrideForSelection changes only selected and shown bookings with one count-only audit entry`() = runBlocking {
        val account = data.account()
        val a = data.txn(account, today.minusDays(3), "-10.00", partner = "Secret Partner", purpose = "Secret purpose", categoryId = normalCat)
        val b = data.txn(account, today.minusDays(2), "-20.00", partner = "Other Partner", categoryId = normalCat)
        val c = data.txn(account, today.minusDays(1), "-30.00", partner = "Third Partner", categoryId = normalCat)
        val vm = vm()
        shown(vm)
        vm.toggleSelection(a)
        vm.toggleSelection(b)
        assertEquals(setOf(a, b), vm.state.value.selectedIds)

        vm.setTaxOverrideForSelection(true).join()

        val byId = data.transactionRepo.findItems().associateBy { it.id }
        with(byId[a]!!) {
            assertTrue(isTaxRelevant)
            assertEquals(TaxSource.MANUAL, taxSource)
            assertEquals(true, taxOverride)
        }
        with(byId[b]!!) {
            assertTrue(isTaxRelevant)
            assertEquals(TaxSource.MANUAL, taxSource)
        }
        with(byId[c]!!) {
            assertFalse(isTaxRelevant, "a shown booking that is not selected stays automatic")
            assertEquals(TaxSource.CATEGORY, taxSource)
            assertNull(taxOverride)
        }
        assertEquals(setOf(a, b), vm.state.value.selectedIds, "the selection is kept")

        val entries = audit.findByAction("SET_TAX_OVERRIDE")
        assertEquals(1, entries.size, "one audit entry for the batch, not one per booking")
        val details = entries.single().details.orEmpty()
        assertTrue(details.contains("2"), "the entry names the count: $details")
        assertTrue(details.contains("relevant"), "the entry names the value: $details")
        assertFalse(details.contains("Secret"), "no partner text in the audit entry: $details")
        assertFalse(details.contains("purpose"), "no purpose text in the audit entry: $details")
        assertFalse(details.contains("-10.00"), "no amounts in the audit entry: $details")
        assertEquals(AppStrings.format(StringKey.MessageTaxOverrideSet, 2), vm.state.value.message)

        vm.setTaxOverrideForSelection(null).join()
        val reset = data.transactionRepo.findItems().associateBy { it.id }
        with(reset[a]!!) {
            assertFalse(isTaxRelevant, "null resets to automatic")
            assertEquals(TaxSource.CATEGORY, taxSource)
            assertNull(taxOverride)
        }
        assertNull(reset[b]!!.taxOverride)
        assertEquals(setOf(a, b), vm.state.value.selectedIds, "the selection survives the reset too")
        vm.close()
    }

    @Test
    fun `setTaxOverrideForSelection never touches a booking the list does not show`() = runBlocking {
        val account = data.account()
        val a = data.txn(account, today.minusDays(2), "-10.00", partner = "One", categoryId = normalCat)
        val hidden = data.txn(account, LocalDate.of(2027, 1, 15), "-99.00", partner = "Far Future", categoryId = normalCat)
        val vm = vm()
        // The default period (this month) hides the far-future booking; the default is not Period.ALL here on purpose
        vm.start().join()
        assertEquals(listOf(a), vm.state.value.transactions.map { it.id })
        vm.toggleSelection(a)

        vm.setTaxOverrideForSelection(true).join()

        val byId = data.transactionRepo.findItems().associateBy { it.id }
        assertEquals(TaxSource.MANUAL, byId[a]!!.taxSource)
        assertNull(byId[hidden]!!.taxOverride, "a booking the list does not show is never changed")
        val entries = audit.findByAction("SET_TAX_OVERRIDE")
        assertEquals(1, entries.size)
        assertTrue(entries.single().details.orEmpty().contains("1"), "the entry names the count of changed bookings")
        vm.close()
    }

    @Test
    fun `setTaxOverride from the details path updates the item the state shows`() = runBlocking {
        val account = data.account()
        val a = data.txn(account, today.minusDays(1), "-10.00", partner = "One", categoryId = normalCat)
        val vm = vm()
        shown(vm)
        vm.selectTransaction(vm.state.value.transactions.first { it.id == a }).join()
        assertEquals(a, vm.state.value.selected?.id)

        vm.setTaxOverride(a, false).join()

        val selected = vm.state.value.selected
        assertEquals(a, selected?.id, "the details stay open")
        assertFalse(selected!!.isTaxRelevant)
        assertEquals(TaxSource.MANUAL, selected.taxSource)
        assertEquals(false, selected.taxOverride)

        val entries = audit.findByAction("SET_TAX_OVERRIDE")
        assertEquals(1, entries.size)
        val details = entries.single().details.orEmpty()
        assertTrue(details.contains("1"), "the entry names the count: $details")
        assertTrue(details.contains("not relevant"), "the entry names the value: $details")
        assertFalse(details.contains("One"), "no partner text in the audit entry: $details")
        vm.close()
    }

    @Test
    fun `saveRule stores the tax flag for a new rule and editing it to null stores null`() = runBlocking {
        val vm = SettingsViewModel(dataDirectory = tmp.absolutePath)
        vm.load().join()

        vm.saveRule(id = null, pattern = "Beispiel Shop", categoryId = normalCat, priority = 5, autoApply = true, matchField = RuleField.ANY, direction = RuleDirection.ANY, taxRelevant = true).join()
        val item = data.ruleRepo.findAllItems().single()
        assertTrue(item.taxRelevant == true, "the flag is stored with the new rule")
        val ruleId = item.id

        vm.saveRule(id = ruleId, pattern = "Beispiel Shop", categoryId = normalCat, priority = 5, autoApply = true, matchField = RuleField.ANY, direction = RuleDirection.ANY, taxRelevant = null).join()
        val after = data.ruleRepo.findAllItems().single()
        assertNull(after.taxRelevant, "explicit null stores 'as the category'")
        assertEquals(ruleId, after.id, "the edit updated the same rule")
        vm.close()
    }

    @Test
    fun `a rule flag alone changes the shown bookings without touching the override`() = runBlocking {
        val account = data.account()
        val a = data.txn(account, today.minusDays(1), "-10.00", partner = "Beispiel Shop", categoryId = normalCat)
        data.ruleRepo.create("Beispiel", normalCat, taxRelevant = true)
        val vm = vm()
        shown(vm)

        val shownItem = vm.state.value.transactions.first { it.id == a }
        assertTrue(shownItem.isTaxRelevant)
        assertEquals(TaxSource.RULE, shownItem.taxSource)
        assertEquals("Beispiel", shownItem.taxRulePattern)
        assertNull(shownItem.taxOverride, "no override stored: the rule layer is read-time only")
        vm.close()
    }
}
