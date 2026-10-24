package de.findesk.viewmodel

import de.findesk.TestDatabase
import de.findesk.model.Direction
import de.findesk.viewmodel.CategoryFilter
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.math.BigDecimal
import java.time.LocalDate

/** Deep links from the new category split land in the category's bookings of the given period. */
class TransactionsStartCategoryTest {
    @TempDir
    lateinit var tmp: File

    private val today = LocalDate.of(2026, 6, 15)

    @BeforeEach
    fun setup() {
        TestDatabase.init()
    }

    private fun vm() = TransactionsViewModel(today = { today })

    @Test
    fun `start with a category and this month sets the filters and clears a previous search`() = runBlocking {
        val vm = vm()
        vm.loadTransactions().join()
        vm.updateSearchQuery("Erika Mustermann").join()
        vm.start(period = de.findesk.viewmodel.Period.THIS_MONTH, categoryId = 7).join()
        val state = vm.state.value
        assertEquals("", state.searchQuery)
        assertEquals(CategoryFilter.Only(7), state.categoryFilter)
        assertEquals(de.findesk.viewmodel.Period.THIS_MONTH, state.period)
        assertEquals(Direction.ALL, state.direction)
        assertEquals(AccountSelection.All, state.selection)
        assertNull(state.accountId)
        assertNull(state.minAmount)
        assertNull(state.maxAmount)
        vm.close()
    }

    @Test
    fun `a category link clears a direction filter and an amount range`() = runBlocking {
        val vm = vm()
        vm.loadTransactions().join()
        vm.updateDirection(Direction.INCOME).join()
        vm.updateAmountRange(BigDecimal("12.00"), BigDecimal("100.00")).join()
        vm.updateCategoryFilter(CategoryFilter.None).join()
        vm.start(categoryId = 3).join()

        val state = vm.state.value
        assertEquals(CategoryFilter.Only(3), state.categoryFilter)
        assertEquals(Direction.ALL, state.direction)
        assertNull(state.minAmount)
        assertNull(state.maxAmount)
        assertEquals(de.findesk.viewmodel.Period.ALL, state.period, "without a period the link shows all time")
        vm.close()
    }

    @Test
    fun `uncategorizedOnly together with a period uses that period instead of ALL`() = runBlocking {
        val vm = vm()
        vm.loadTransactions().join()
        vm.start(uncategorizedOnly = true, period = de.findesk.viewmodel.Period.THIS_MONTH).join()

        val state = vm.state.value
        assertEquals(CategoryFilter.None, state.categoryFilter)
        assertEquals(de.findesk.viewmodel.Period.THIS_MONTH, state.period)
        vm.close()
    }
}
