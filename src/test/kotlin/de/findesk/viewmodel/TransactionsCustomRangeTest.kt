package de.findesk.viewmodel

import de.findesk.TestData
import de.findesk.TestDatabase
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.LocalDate
import java.time.YearMonth

/** The custom From/To range of the transaction list (the period menu's "Custom range…" entry). */
class TransactionsCustomRangeTest {
    @TempDir
    lateinit var tmp: File

    private val today = LocalDate.of(2025, 6, 15)
    private lateinit var data: TestData
    private var yearEnd = 0
    private var newYear = 0
    private var marchEnd = 0
    private var aprilStart = 0

    @BeforeEach
    fun setup() {
        TestDatabase.init()
        data = TestData()
        val account = data.account()
        yearEnd = data.txn(account, LocalDate.of(2024, 12, 31), "-10.00", partner = "Year End")
        newYear = data.txn(account, LocalDate.of(2025, 1, 1), "-20.00", partner = "New Year")
        marchEnd = data.txn(account, LocalDate.of(2025, 3, 31), "-30.00", partner = "March End")
        aprilStart = data.txn(account, LocalDate.of(2025, 4, 1), "-40.00", partner = "April Start")
    }

    private fun vm() = TransactionsViewModel(today = { today })

    @Test
    fun `a custom range shows exactly the bookings inside it, bounds inclusive`() = runBlocking {
        val vm = vm()
        vm.start(period = Period.ALL).join()

        vm.setCustomRange(LocalDate.of(2025, 1, 1), LocalDate.of(2025, 3, 31)).join()

        assertEquals(listOf(marchEnd, newYear), vm.state.value.transactions.map { it.id }, "both bounds are included")
        assertEquals(LocalDate.of(2025, 1, 1) to LocalDate.of(2025, 3, 31), vm.state.value.customRange)
        assertNull(vm.state.value.month)
        vm.close()
    }

    @Test
    fun `a reversed range changes nothing`() = runBlocking {
        val vm = vm()
        vm.start(period = Period.ALL).join()

        vm.setCustomRange(LocalDate.of(2025, 3, 31), LocalDate.of(2025, 1, 1)).join()

        assertNull(vm.state.value.customRange, "no custom range was set")
        assertEquals(listOf(aprilStart, marchEnd, newYear, yearEnd), vm.state.value.transactions.map { it.id }, "all four are still shown")
        vm.close()
    }

    @Test
    fun `setting a custom range clears an active selection`() = runBlocking {
        val vm = vm()
        vm.start(period = Period.ALL).join()
        vm.toggleSelection(newYear)

        vm.setCustomRange(LocalDate.of(2025, 1, 1), LocalDate.of(2025, 3, 31)).join()

        assertNull(vm.state.value.selectionAnchorId, "the selection was cleared")
        assertEquals(emptySet<Int>(), vm.state.value.selectedIds)
        vm.close()
    }

    @Test
    fun `picking a period clears the custom range`() = runBlocking {
        val vm = vm()
        vm.start(period = Period.ALL).join()
        vm.setCustomRange(LocalDate.of(2025, 1, 1), LocalDate.of(2025, 3, 31)).join()

        vm.updatePeriod(Period.ALL).join()

        assertNull(vm.state.value.customRange)
        assertEquals(listOf(aprilStart, marchEnd, newYear, yearEnd), vm.state.value.transactions.map { it.id })
        vm.close()
    }

    @Test
    fun `a month deep link clears the custom range`() = runBlocking {
        val vm = vm()
        vm.start(period = Period.ALL).join()
        vm.setCustomRange(LocalDate.of(2025, 1, 1), LocalDate.of(2025, 3, 31)).join()

        vm.start(month = YearMonth.of(2024, 12)).join()

        assertNull(vm.state.value.customRange)
        assertEquals(YearMonth.of(2024, 12), vm.state.value.month)
        assertEquals(listOf(yearEnd), vm.state.value.transactions.map { it.id }, "exactly the December booking is shown")
        vm.close()
    }

    @Test
    fun `a plain start keeps an existing custom range`() = runBlocking {
        val vm = vm()
        vm.start(period = Period.ALL).join()
        vm.setCustomRange(LocalDate.of(2025, 1, 1), LocalDate.of(2025, 3, 31)).join()

        vm.start().join()

        assertEquals(LocalDate.of(2025, 1, 1) to LocalDate.of(2025, 3, 31), vm.state.value.customRange, "the custom range survives")
        assertEquals(listOf(marchEnd, newYear), vm.state.value.transactions.map { it.id }, "the list still matches the range")
        vm.close()
    }
}
