package de.findesk.viewmodel

import de.findesk.TestData
import de.findesk.TestDatabase
import de.findesk.banking.AccountSnapshot
import de.findesk.banking.BankingCancelledException
import de.findesk.banking.BankingInteraction
import de.findesk.banking.BankingService
import de.findesk.banking.FinTSClient
import de.findesk.banking.FinTSConnection
import de.findesk.banking.FinTSGateway
import de.findesk.banking.TransferOrder
import de.findesk.i18n.AppStrings
import de.findesk.i18n.StringKey
import de.findesk.repository.AuditLogRepositoryImpl
import de.findesk.repository.BankAccessRepositoryImpl
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.LocalDate
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class SyncAllTest {
    private lateinit var data: TestData
    private val today = LocalDate.of(2026, 3, 20)

    @TempDir
    lateinit var tmp: File

    @BeforeEach
    fun setup() {
        TestDatabase.init()
        data = TestData()
    }

    /** Records each dialog's BLZ in call order and runs [onFetch], so a test can fail, block or inspect one bank. */
    private class RecordingGateway(private val onFetch: (FinTSConnection) -> Unit = {}) : FinTSGateway {
        val calls: MutableList<String> = Collections.synchronizedList(mutableListOf())

        override fun fetchAll(connection: FinTSConnection, interaction: BankingInteraction, since: (String) -> LocalDate?, to: LocalDate): List<AccountSnapshot> {
            calls += connection.blz
            onFetch(connection)
            return emptyList()
        }

        override fun transfer(connection: FinTSConnection, interaction: BankingInteraction, fromIban: String, order: TransferOrder): FinTSClient.TransferResult = throw UnsupportedOperationException()
    }

    private fun connection(name: String, blz: String): Int = data.bankAccessRepo.create(name, blz, "", "user").id.value.also {
        data.bankAccessRepo.updateConnection(it, serverUrl = "https://fints.example/pintan")
    }

    private fun viewModel(gateway: FinTSGateway): AccountsViewModel = AccountsViewModel(
        bankAccessRepository = BankAccessRepositoryImpl(),
        bankingService = BankingService(
            finTSClient = FinTSClient(gateway = gateway, passportDir = File(tmp, "passports-${System.nanoTime()}")),
            today = { today },
        ),
    )

    @Test
    fun `syncAll syncs only the included connections in bank card order`() = runBlocking {
        val gateway = RecordingGateway()
        val b = connection("Bank B", "10000002")
        connection("Bank A", "10000001")
        connection("Bank C", "10000003")
        data.bankAccessRepo.setIncludeInSyncAll(b, false)
        val vm = viewModel(gateway)

        vm.syncAll().join()

        assertEquals(listOf("10000001", "10000003"), gateway.calls)
        val message = vm.state.value.message.orEmpty()
        assertTrue(message.contains("Bank A:"), message)
        assertTrue(message.contains("Bank C:"), message)
        assertFalse(message.contains("Bank B:"), message)
        assertNull(vm.state.value.error)
        assertNull(vm.state.value.syncAll)
        vm.close()
    }

    @Test
    fun `a failed connection is recorded and the run continues`() = runBlocking {
        val gateway = RecordingGateway { connection -> if (connection.blz == "10000002") throw IllegalStateException("bank broke") }
        connection("Bank A", "10000001")
        connection("Bank B", "10000002")
        connection("Bank C", "10000003")
        val vm = viewModel(gateway)

        vm.syncAll().join()

        assertEquals(listOf("10000001", "10000002", "10000003"), gateway.calls)
        val error = vm.state.value.error.orEmpty()
        assertTrue(error.contains("Bank B: bank broke"), error)
        assertFalse(error.contains("Bank A:"), error)
        assertTrue(vm.state.value.message.orEmpty().contains("Bank C:"))
        vm.close()
    }

    @Test
    fun `a cancelled connection stops the run and names it`() = runBlocking {
        val gateway = RecordingGateway { connection -> if (connection.blz == "10000002") throw BankingCancelledException("cancelled") }
        connection("Bank A", "10000001")
        connection("Bank B", "10000002")
        connection("Bank C", "10000003")
        val vm = viewModel(gateway)

        vm.syncAll().join()

        assertEquals(listOf("10000001", "10000002"), gateway.calls, "the third connection is not synced")
        val message = vm.state.value.message.orEmpty()
        assertTrue(message.contains(AppStrings.format(StringKey.SyncAllStopped, "Bank B")), message)
        assertNull(vm.state.value.error)
        assertNull(vm.state.value.syncAll)
        vm.close()
    }

    @Test
    fun `a run holds the screen and refuses another sync between connections`() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        var busyDuringA = false
        var busyDuringB = false
        val gateway = RecordingGateway { connection ->
            when (connection.blz) {
                "10000001" -> {
                    busyDuringA = Busy.active.value
                    entered.countDown()
                    release.await(5, TimeUnit.SECONDS)
                }

                "10000002" -> busyDuringB = Busy.active.value
            }
        }
        connection("Bank A", "10000001")
        connection("Bank B", "10000002")
        val other = connection("Bank C", "10000003")
        data.bankAccessRepo.setIncludeInSyncAll(other, false)
        val vm = viewModel(gateway)

        val run = vm.syncAll()
        assertTrue(entered.await(5, TimeUnit.SECONDS), "the run reached the gateway")
        assertEquals(SyncAllProgress(1, 2, "Bank A"), vm.state.value.syncAll)
        assertTrue(vm.state.value.isSyncing)

        vm.syncBankAccess(other).join()
        assertEquals(AppStrings[StringKey.ErrorConnectionBusy], vm.state.value.error)
        assertFalse(gateway.calls.contains("10000003"), "the refused sync never reached its gateway")

        release.countDown()
        run.join()

        assertTrue(busyDuringA, "the run is busy inside the first connection")
        assertTrue(busyDuringB, "the same busy section spans the second connection")
        assertNull(vm.state.value.syncAll)
        assertEquals(listOf("10000001", "10000002"), gateway.calls)
        vm.close()
    }

    @Test
    fun `closing the screen between two connections never syncs the next one`() = runBlocking {
        lateinit var vm: AccountsViewModel
        // Closing from inside the first dialog: the run must stop before the second connection, not count it as failed
        val gateway = RecordingGateway { connection -> if (connection.blz == "10000001") vm.close() }
        connection("Bank A", "10000001")
        connection("Bank B", "10000002")
        vm = viewModel(gateway)

        vm.syncAll().join()

        assertEquals(listOf("10000001"), gateway.calls)
        // The run stopped rather than reporting the next connection as failed
        assertFalse(vm.state.value.error.orEmpty().contains("Bank B"), vm.state.value.error)
    }

    @Test
    fun `no included connection reports the hint and calls no gateway`() = runBlocking {
        val gateway = RecordingGateway()
        val a = connection("Bank A", "10000001")
        val b = connection("Bank B", "10000002")
        data.bankAccessRepo.setIncludeInSyncAll(a, false)
        data.bankAccessRepo.setIncludeInSyncAll(b, false)
        val vm = viewModel(gateway)

        vm.syncAll().join()

        assertEquals(AppStrings[StringKey.SyncAllNothingIncluded], vm.state.value.error)
        assertTrue(gateway.calls.isEmpty())
        assertNull(vm.state.value.syncAll)
        vm.close()
    }

    @Test
    fun `setIncludeInSyncAll persists, audits once and is refused while syncing`() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val gateway = RecordingGateway {
            entered.countDown()
            release.await(5, TimeUnit.SECONDS)
        }
        val bank = connection("Bank A", "10000001")
        val vm = viewModel(gateway)
        vm.loadAccounts().join()

        vm.setIncludeInSyncAll(bank, false).join()

        assertFalse(data.bankAccessRepo.findItem(bank)!!.includeInSyncAll)
        val audits = AuditLogRepositoryImpl().findByAction("SET_SYNC_ALL_INCLUDED")
        assertEquals(1, audits.size)
        assertEquals("Bank access $bank: included in Sync all = false", audits.single().details)

        val sync = vm.syncBankAccess(bank)
        assertTrue(entered.await(5, TimeUnit.SECONDS), "the sync reached the gateway")
        vm.setIncludeInSyncAll(bank, true).join()

        assertEquals(AppStrings[StringKey.ErrorConnectionBusy], vm.state.value.error)
        assertFalse(data.bankAccessRepo.findItem(bank)!!.includeInSyncAll, "nothing is stored while a sync runs")
        assertEquals(1, AuditLogRepositoryImpl().findByAction("SET_SYNC_ALL_INCLUDED").size)

        release.countDown()
        sync.join()
        vm.close()
    }

    @Test
    fun `two back-to-back syncAll calls sync every connection once`() = runBlocking {
        val gateway = RecordingGateway()
        connection("Bank A", "10000001")
        connection("Bank B", "10000002")
        val vm = viewModel(gateway)

        val first = vm.syncAll()
        val second = vm.syncAll()
        first.join()
        second.join()

        assertEquals(listOf("10000001", "10000002"), gateway.calls)
        vm.close()
    }

    @Test
    fun `a new connection is included in Sync all`() {
        val bank = connection("Bank A", "10000001")

        assertTrue(data.bankAccessRepo.findItem(bank)!!.includeInSyncAll)
    }
}
