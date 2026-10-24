package de.findesk.repository

import de.findesk.TestData
import de.findesk.TestDatabase
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.LocalDate

/**
 * The one batch call behind "re-apply rules": every booking in the given map changes in one call, existing
 * values (including uncategorized) are replaced, and it reports how many rows it touched.
 */
class UpdateCategoriesTest {
    @TempDir
    lateinit var tmp: File

    private lateinit var data: TestData

    @BeforeEach
    fun setup() {
        TestDatabase.init()
        data = TestData()
    }

    private fun rows(): Map<Int, Int?> = data.transactionRepo.findItems().associate { it.id to it.categoryId }

    @Test
    fun `updates all given rows in one call and returns the count`() {
        val account = data.account()
        val groceries = data.category("Groceries")
        val loans = data.category("Loans")
        val transfer = data.category("Transfer")
        val first = data.txn(account, LocalDate.of(2026, 6, 1), "-9.90", partner = "Store", categoryId = groceries)
        val second = data.txn(account, LocalDate.of(2026, 6, 2), "-45.00", partner = "Sparkasse")
        val third = data.txn(account, LocalDate.of(2026, 6, 3), "-120.00", partner = "Loan instalment 0001", categoryId = loans)

        val count = data.transactionRepo.updateCategories(
            mapOf(
                first to transfer,
                second to groceries,
                third to null,
            ),
        )
        assertEquals(3, count)
        val rows = rows()
        assertEquals(transfer, rows[first])
        assertEquals(groceries, rows[second])
        assertNull(rows[third], "null restores the uncategorized state")
    }

    @Test
    fun `a booking already sitting on its target is not overwritten and not counted`() {
        val account = data.account()
        val groceries = data.category("Groceries")
        val loans = data.category("Loans")
        val alreadyThere = data.txn(account, LocalDate.of(2026, 6, 1), "-9.90", partner = "Store", categoryId = groceries)
        val toMove = data.txn(account, LocalDate.of(2026, 6, 2), "-45.00", partner = "Sparkasse", categoryId = loans)

        val count = data.transactionRepo.updateCategories(mapOf(alreadyThere to groceries, toMove to groceries))
        assertEquals(1, count)
        assertEquals(groceries, rows()[toMove])
    }

    @Test
    fun `an unknown booking id is skipped`() {
        val account = data.account()
        val groceries = data.category("Groceries")
        val first = data.txn(account, LocalDate.of(2026, 6, 1), "-9.90", partner = "Store")

        val count = data.transactionRepo.updateCategories(mapOf(first to groceries, 99999 to groceries))
        assertEquals(1, count)
        assertEquals(groceries, rows()[first])
    }
}
