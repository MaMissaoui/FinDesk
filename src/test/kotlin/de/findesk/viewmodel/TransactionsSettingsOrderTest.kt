package de.findesk.viewmodel

import de.findesk.TestData
import de.findesk.TestDatabase
import de.findesk.data.entity.Setting
import de.findesk.repository.SettingsRepository
import de.findesk.repository.SettingsRepositoryImpl
import de.findesk.util.GroupKey
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Quick panel toggles are written one at a time, each with the state it finally settled on. */
class TransactionsSettingsOrderTest {
    private lateinit var data: TestData

    @BeforeEach
    fun setup() {
        TestDatabase.init()
        data = TestData()
    }

    @Test
    fun `two chart toggles and two group toggles store the final state`() = runBlocking {
        data.account()
        val slow = SlowFirstWriteSettingsRepository()
        val vm = TransactionsViewModel(today = { LocalDate.of(2026, 10, 15) }, settingsRepository = slow)
        vm.start().join()

        // The first write is still running when the other three are started, so without serialization the
        // older chart value could be committed last.
        val first = vm.toggleChart()
        assertTrue(slow.firstWriteStarted.await(5, TimeUnit.SECONDS), "the first chart write started")
        val jobs = listOf(
            first,
            vm.toggleChart(),
            vm.toggleCollapsed(GroupKey.Type("checking")),
            vm.toggleCollapsed(GroupKey.Type("savings")),
        )
        jobs.forEach { it.join() }

        assertFalse(vm.state.value.chartCollapsed, "two toggles leave the chart expanded")
        assertEquals(setOf("type:checking", "type:savings"), vm.state.value.collapsed)

        val stored = SettingsRepositoryImpl()
        assertEquals("false", stored.getValue(TransactionsViewModel.KEY_CHART_COLLAPSED), "the stored chart state is the final one")
        assertEquals(vm.state.value.collapsed, AccountPanelSettings.loadCollapsed(stored), "the stored groups are the final ones")
        vm.close()
    }

    /** Slows only the first write, so the remaining toggles are started while it is still running. */
    private class SlowFirstWriteSettingsRepository(
        private val delegate: SettingsRepository = SettingsRepositoryImpl(),
    ) : SettingsRepository by delegate {
        val firstWriteStarted = CountDownLatch(1)
        private val first = AtomicBoolean(true)

        override fun setValue(key: String, value: String): Setting {
            if (first.compareAndSet(true, false)) {
                firstWriteStarted.countDown()
                Thread.sleep(750)
            }
            return delegate.setValue(key, value)
        }
    }
}
