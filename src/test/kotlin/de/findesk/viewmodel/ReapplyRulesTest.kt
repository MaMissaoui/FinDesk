package de.findesk.viewmodel

import de.findesk.TestData
import de.findesk.TestDatabase
import de.findesk.model.Direction
import de.findesk.model.RuleDirection
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
import java.time.LocalDate

/** "Re-apply rules to shown bookings": preview, confirm, undo. */
class ReapplyRulesTest {
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

    private val groceries get() = data.category("Groceries")
    private val loans get() = data.category("Loans")
    private val salary get() = data.category("Salary")

    private fun changeOf(preview: ReapplyPreview?, transactionId: Int): de.findesk.viewmodel.ReapplyChange? = preview?.changes?.firstOrNull { it.transactionId == transactionId }

    @Test
    fun `a higher-priority rule moves a booking from its current category to the rule's category`() = runBlocking {
        val loansId = loans
        val groceriesId = groceries
        data.ruleRepo.create("SPARKASSE", loansId, priority = 1)
        data.ruleRepo.create("SPARKASSE", groceriesId, priority = 5)
        val booking = data.txn(data.account(), today.minusDays(1), "-9.90", partner = "Sparkasse", categoryId = loansId)
        val vm = vm()
        shown(vm)

        vm.previewReapplyRules().join()
        val preview = vm.state.value.reapplyPreview
        assertEquals(de.findesk.viewmodel.ReapplyChange(booking, loansId, groceriesId), changeOf(preview, booking))

        vm.confirmReapplyRules().join()
        assertEquals(
            groceriesId,
            TransactionRepositoryImpl().findItems().first { it.id == booking }.categoryId,
            "confirm applies the previewed change",
        )
        vm.close()
    }

    @Test
    fun `a booking no rule matches keeps its category and a category is never set to null`() = runBlocking {
        val categoryId = groceries
        val booking = data.txn(data.account(), today.minusDays(1), "-3.20", partner = "Wonder.Category", categoryId = categoryId)
        val vm = vm()
        shown(vm)

        vm.previewReapplyRules().join()
        assertNull(vm.state.value.reapplyPreview, "nothing would change, so no dialog")
        vm.confirmReapplyRules().join()
        assertEquals(categoryId, TransactionRepositoryImpl().findItems().first { it.id == booking }.categoryId)
        vm.close()
    }

    @Test
    fun `a booking whose matched category equals its current one is not counted as a change`() = runBlocking {
        val categoryId = groceries
        data.ruleRepo.create("NETFLIX", categoryId)
        data.txn(data.account(), today.minusDays(1), "-12.00", partner = "Netflix", categoryId = categoryId)
        val vm = vm()
        shown(vm)

        vm.previewReapplyRules().join()
        val preview = vm.state.value.reapplyPreview
        assertNull(preview, "the only shown booking already sits on the rule's category")
        assertEquals(0, audit.findByAction("REAPPLY_RULES").size)
        vm.close()
    }

    @Test
    fun `an expense-only rule does not change an income booking`() = runBlocking {
        val categoryId = salary
        data.ruleRepo.create("PARKING", categoryId, priority = 1, direction = RuleDirection.EXPENSE)
        val booking = data.txn(data.account(), today.minusDays(1), "10.00", partner = "Parking fee")
        val vm = vm()
        shown(vm)

        vm.previewReapplyRules().join()
        assertNull(vm.state.value.reapplyPreview)
        vm.confirmReapplyRules().join()
        assertNull(TransactionRepositoryImpl().findItems().first { it.id == booking }.categoryId)
        vm.close()
    }

    @Test
    fun `bookings outside the current filter are untouched`() = runBlocking {
        val categoryId = groceries
        data.ruleRepo.create("NETFLIX", categoryId)
        val inside = data.txn(data.account(), today.minusDays(1), "-12.00", partner = "Netflix Shops")
        val outside = data.txn(data.account(), today.minusDays(2), "-7.00", partner = "Netflix Vacations")
        val vm = vm()
        shown(vm)
        // The search shows only one of the two matching bookings
        vm.updateSearchQuery("Vacations").join()

        vm.previewReapplyRules().join()
        val preview = vm.state.value.reapplyPreview
        assertEquals(outside, changeOf(preview, outside)?.transactionId)
        assertNull(changeOf(preview, inside), "a booking the filters hide is not part of the preview")

        vm.confirmReapplyRules().join()
        val rows = TransactionRepositoryImpl().findItems().associate { it.id to it.categoryId }
        assertNull(rows[inside], "the hidden booking stays uncategorized")
        assertEquals(categoryId, rows[outside])
        vm.close()
    }

    @Test
    fun `confirm applies exactly the previewed set even when a new rule would match more bookings`() = runBlocking {
        val categoryId = groceries
        data.ruleRepo.create("NETFLIX", categoryId)
        val previewed = data.txn(data.account(), today.minusDays(1), "-12.00", partner = "Netflix")
        val extra = data.txn(data.account(), today.minusDays(2), "-15.00", partner = "Disneyplus")
        val vm = vm()
        shown(vm)

        vm.previewReapplyRules().join()
        val preview = vm.state.value.reapplyPreview ?: run {
            vm.close()
            return@runBlocking
        }
        assertEquals(listOf(previewed), preview.changes.map { it.transactionId })
        assertEquals(2, preview.shownCount, "the preview checked every shown booking, not only the changes")

        // A better rule appeared after the preview; confirm must still apply only the previewed change
        data.ruleRepo.create("DISNEYPLUS", categoryId, priority = 9)
        vm.confirmReapplyRules().join()
        val rows = TransactionRepositoryImpl().findItems().associate { it.id to it.categoryId }
        assertEquals(categoryId, rows[previewed])
        assertNull(rows[extra], "a rule added after the preview does not sneak in")
        assertEquals(1, audit.findByAction("REAPPLY_RULES").size)
        vm.close()
    }

    @Test
    fun `a new rule added after the preview does not sneak in like the previewed one`() = runBlocking {
        val categoryId = groceries
        data.ruleRepo.create("NETFLIX", categoryId)
        val previewed = data.txn(data.account(), today.minusDays(1), "-12.00", partner = "Netflix")
        val vm = vm()
        shown(vm)
        vm.previewReapplyRules().join()
        // Same scenario two ways: the previewed booking would also be caught by a later rule
        data.ruleRepo.create("NETFLIX", loans, priority = 5)
        vm.confirmReapplyRules().join()
        val row = TransactionRepositoryImpl().findItems().first { it.id == previewed }
        assertEquals(categoryId, row.categoryId, "the preview's target category wins, not the later rule's")
        vm.close()
    }

    @Test
    fun `excluding a group leaves its bookings unchanged`() = runBlocking {
        val categoryId = groceries
        val other = loans
        data.ruleRepo.create("NETFLIX", categoryId)
        data.ruleRepo.create("SPARKASSE", categoryId)
        val fromCategory = data.txn(data.account(), today.minusDays(1), "-12.00", partner = "Netflix", categoryId = other)
        val fromUncategorized = data.txn(data.account(), today.minusDays(2), "-7.85", partner = "Sparkasse")
        val vm = vm()
        shown(vm)

        vm.previewReapplyRules().join()
        val preview = vm.state.value.reapplyPreview ?: run {
            vm.close()
            return@runBlocking
        }
        assertEquals(2, preview.groups.size)
        vm.confirmReapplyRules(excluded = setOf(other to categoryId)).join()
        val rows = TransactionRepositoryImpl().findItems().associate { it.id to it.categoryId }
        assertEquals(other, rows[fromCategory], "the excluded group's booking keeps its category")
        assertEquals(categoryId, rows[fromUncategorized])
        vm.close()
    }

    @Test
    fun `a booking changed by hand between preview and confirm is skipped`() = runBlocking {
        val categoryId = groceries
        data.ruleRepo.create("NETFLIX", categoryId)
        val handSet = salary
        val booking = data.txn(data.account(), today.minusDays(1), "-12.00", partner = "Netflix", categoryId = loans)
        val vm = vm()
        shown(vm)
        vm.previewReapplyRules().join()

        vm.updateTransactionCategory(booking, handSet).join()
        vm.confirmReapplyRules().join()
        assertEquals(
            handSet,
            TransactionRepositoryImpl().findItems().first { it.id == booking }.categoryId,
            "the hand-picked category survives the confirm",
        )
        vm.close()
    }

    @Test
    fun `undo restores the previous values including null and then cannot undo again`() = runBlocking {
        val categoryId = groceries
        val other = loans
        data.ruleRepo.create("NETFLIX", categoryId)
        data.ruleRepo.create("SPARKASSE", categoryId)
        val fromCategory = data.txn(data.account(), today.minusDays(1), "-12.00", partner = "Netflix", categoryId = other)
        val fromUncategorized = data.txn(data.account(), today.minusDays(2), "-7.85", partner = "Sparkasse")
        val vm = vm()
        shown(vm)
        vm.previewReapplyRules().join()
        vm.confirmReapplyRules().join()
        val rowsAfter = TransactionRepositoryImpl().findItems().associate { it.id to it.categoryId }
        assertEquals(categoryId, rowsAfter[fromCategory])
        assertEquals(categoryId, rowsAfter[fromUncategorized])
        assertTrue(vm.state.value.canUndoReapply)

        vm.undoReapplyRules().join()
        val rows = TransactionRepositoryImpl().findItems().associate { it.id to it.categoryId }
        assertEquals(other, rows[fromCategory])
        assertNull(rows[fromUncategorized], "an uncategorized booking goes back to null")
        assertFalse(vm.state.value.canUndoReapply)
        assertEquals(1, audit.findByAction("REAPPLY_RULES").size)
        assertEquals(1, audit.findByAction("UNDO_REAPPLY_RULES").size)
        vm.close()
    }

    @Test
    fun `undo keeps a booking that was moved by hand after the re-apply`() = runBlocking {
        val categoryId = groceries
        val other = loans
        val handSet = salary
        data.ruleRepo.create("NETFLIX", categoryId)
        val movedAgain = data.txn(data.account(), today.minusDays(1), "-12.00", partner = "Netflix", categoryId = other)
        val untouched = data.txn(data.account(), today.minusDays(2), "-8.00", partner = "Netflix", categoryId = other)
        val vm = vm()
        shown(vm)
        vm.previewReapplyRules().join()
        vm.confirmReapplyRules().join()

        vm.updateTransactionCategory(movedAgain, handSet).join()
        vm.undoReapplyRules().join()
        val rows = TransactionRepositoryImpl().findItems().associate { it.id to it.categoryId }
        assertEquals(handSet, rows[movedAgain], "the later hand-picked category survives the undo")
        assertEquals(other, rows[untouched], "a booking still on the re-apply's category goes back")
        assertFalse(vm.state.value.canUndoReapply)
        vm.close()
    }

    @Test
    fun `a confirm that changes nothing keeps the previous undo`() = runBlocking {
        val categoryId = groceries
        val other = loans
        data.ruleRepo.create("NETFLIX", categoryId)
        val first = data.txn(data.account(), today.minusDays(1), "-12.00", partner = "Netflix", categoryId = other)
        val vm = vm()
        shown(vm)
        vm.previewReapplyRules().join()
        vm.confirmReapplyRules().join()
        assertTrue(vm.state.value.canUndoReapply)

        val second = data.txn(data.account(), today.minusDays(3), "-5.00", partner = "Netflix", categoryId = other)
        vm.loadTransactions().join()
        vm.previewReapplyRules().join()
        vm.updateTransactionCategory(second, categoryId).join()
        vm.confirmReapplyRules().join()
        assertTrue(vm.state.value.canUndoReapply, "the first re-apply can still be undone")

        vm.undoReapplyRules().join()
        assertEquals(other, TransactionRepositoryImpl().findItems().first { it.id == first }.categoryId)
        vm.close()
    }

    @Test
    fun `no audit entry and no dialog when nothing would change`() = runBlocking {
        val categoryId = groceries
        data.ruleRepo.create("NETFLIX", categoryId)
        data.txn(data.account(), today.minusDays(1), "-12.00", partner = "Netflix", categoryId = categoryId)
        data.txn(data.account(), today.minusDays(2), "-3.20", partner = "Wonder.Category", categoryId = categoryId)
        val vm = vm()
        shown(vm)

        vm.previewReapplyRules().join()
        assertFalse(vm.state.value.canUndoReapply)
        vm.confirmReapplyRules().join()
        assertEquals(0, audit.findByAction("REAPPLY_RULES").size)
        vm.close()
    }

    @Test
    fun `the preview groups bookings by from to pair with a sum only in the primary currency`() = runBlocking {
        val categoryId = groceries
        data.ruleRepo.create("NETFLIX", categoryId)
        val account = data.account()
        val eur = data.txn(account, today.minusDays(1), "-12.00", partner = "Netflix")
        val usd = data.txn(account, today.minusDays(2), "-5.00", partner = "Netflix USD", currency = "USD")
        val vm = vm()
        shown(vm)

        vm.previewReapplyRules().join()
        val preview = vm.state.value.reapplyPreview ?: run {
            vm.close()
            return@runBlocking
        }
        assertEquals(setOf(de.findesk.viewmodel.ReapplyChange(eur, null, categoryId), de.findesk.viewmodel.ReapplyChange(usd, null, categoryId)), preview.changes.toSet())
        // Both land on the same rule category, so they share one group row even across currencies
        assertEquals(1, preview.groups.size)
        assertEquals(2, preview.groups.single().count, "one group per (from, to) pair, whatever the currencies")
        assertNull(preview.groups.single().sum, "the sum is omitted when the bookings do not share the primary currency")
        assertEquals("EUR", preview.groups.single().currency)
        vm.close()
    }

    @Test
    fun `direction and category filters limit the preview to the shown bookings`() = runBlocking {
        val categoryId = groceries
        data.ruleRepo.create("NETFLIX", categoryId)
        val expense = data.txn(data.account(), today.minusDays(1), "-12.00", partner = "Netflix")
        data.txn(data.account(), today.minusDays(2), "8.00", partner = "Netflix plus")
        val vm = vm()
        shown(vm)
        vm.updateDirection(Direction.EXPENSES).join()

        vm.previewReapplyRules().join()
        val preview = vm.state.value.reapplyPreview ?: run {
            vm.close()
            return@runBlocking
        }
        assertEquals(listOf(expense), preview.changes.map { it.transactionId })
        assertEquals(1, preview.shownCount)
        vm.close()
    }
}
