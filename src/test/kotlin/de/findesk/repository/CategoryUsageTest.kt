package de.findesk.repository

import de.findesk.TestData
import de.findesk.TestDatabase
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.LocalDate

/**
 * Settings shows each category's booking count. [CategoryRepository.usageCounts] groups the `transactions` table
 * by `category_id` (one query) and leaves uncategorized bookings out.
 */
class CategoryUsageTest {
    @BeforeEach
    fun setup() {
        TestDatabase.init()
    }

    @Test
    fun `counts bookings per category and skips uncategorized ones`() {
        val data = TestData()
        val food = data.category("Food")
        val travel = data.category("Travel")
        val account = data.account()

        data.txn(account, LocalDate.of(2026, 10, 1), "-5.00", categoryId = food)
        data.txn(account, LocalDate.of(2026, 10, 2), "-6.00", categoryId = food)
        data.txn(account, LocalDate.of(2026, 10, 3), "-12.50", categoryId = travel)
        data.txn(account, LocalDate.of(2026, 10, 4), "-2.00", categoryId = null)
        data.txn(account, LocalDate.of(2026, 10, 5), "-1.00", categoryId = null)

        val counts = data.categoryRepo.usageCounts()

        assertEquals(2, counts[food], "two bookings categorised as Food")
        assertEquals(1, counts[travel])
        assertEquals(setOf(food, travel), counts.keys, "and nothing else: uncategorized bookings belong to no category")
    }
}
