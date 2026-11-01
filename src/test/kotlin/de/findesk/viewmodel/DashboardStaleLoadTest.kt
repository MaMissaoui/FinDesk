package de.findesk.viewmodel

import de.findesk.TestData
import de.findesk.TestDatabase
import de.findesk.repository.TransactionRepository
import de.findesk.repository.TransactionRepositoryImpl
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.YearMonth
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** A load that finishes after a newer one must not overwrite the newer month or its figures. */
class DashboardStaleLoadTest {
    private val today = LocalDate.of(2026, 10, 15)

    private lateinit var data: TestData

    @BeforeEach
    fun setup() {
        TestDatabase.init()
        data = TestData()
    }

    @Test
    fun `an older load cannot overwrite a newer month and its figures`() = runBlocking {
        val account = data.account(balance = "1000.00")
        val food = data.category("Food")
        data.txn(account, LocalDate.of(2026, 8, 2), "-120.00", partner = "August", categoryId = food)
        data.txn(account, LocalDate.of(2026, 9, 4), "-60.00", partner = "September", categoryId = food)

        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val repository = BlockingOldestDateRepository(entered, release)
        val vm = DashboardViewModel(transactionRepository = repository, today = { today })

        // Load 1 is for September and stops in oldestDate before it computes anything.
        val older = vm.selectMonth(YearMonth.of(2026, 9))
        assertTrue(entered.await(5, TimeUnit.SECONDS), "the first load reached oldestDate")

        // Load 2 is for August and finishes while load 1 is still blocked.
        vm.selectMonth(YearMonth.of(2026, 8)).join()
        assertEquals(YearMonth.of(2026, 8), vm.state.value.selectedMonth)
        assertEquals(BigDecimal("120.00"), vm.state.value.monthlyExpenses, "August's booking only")

        release.countDown()
        older.join()

        val state = vm.state.value
        assertEquals(YearMonth.of(2026, 8), state.selectedMonth, "the newer selection survives")
        assertEquals(BigDecimal("120.00"), state.monthlyExpenses, "the newer month's figures survive")
        assertFalse(state.isLoading, "the newest load has finished")
        vm.close()
    }

    /** Blocks the first [oldestDate] call so a load can be overtaken by a newer one. */
    private class BlockingOldestDateRepository(
        private val entered: CountDownLatch,
        private val release: CountDownLatch,
        private val delegate: TransactionRepository = TransactionRepositoryImpl(),
    ) : TransactionRepository by delegate {
        private val first = AtomicBoolean(true)

        override fun oldestDate(currency: String?): LocalDate? {
            if (first.compareAndSet(true, false)) {
                entered.countDown()
                release.await()
            }
            return delegate.oldestDate(currency)
        }
    }
}
