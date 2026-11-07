package de.findesk.viewmodel

import de.findesk.TestData
import de.findesk.TestDatabase
import de.findesk.banking.AccountSnapshot
import de.findesk.banking.BankingInteraction
import de.findesk.banking.BankingService
import de.findesk.banking.CredentialStore
import de.findesk.banking.FinTSClient
import de.findesk.banking.FinTSConnection
import de.findesk.banking.FinTSGateway
import de.findesk.banking.TransferOrder
import de.findesk.i18n.AppStrings
import de.findesk.i18n.StringKey
import de.findesk.repository.AccountRepositoryImpl
import de.findesk.repository.BankAccessRepositoryImpl
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
import java.math.BigDecimal
import java.time.LocalDate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** In-memory stand-in for the OS credential store; no test touches the real keychain. */
private class FakeDeleteCredentialStore : CredentialStore {
    private val entries = mutableMapOf<String, String>()

    override val isAvailable: Boolean = true

    override fun read(key: String): String? = entries[key]

    override fun write(key: String, secret: String) {
        entries[key] = secret
    }

    override fun delete(key: String) {
        entries.remove(key)
    }
}

/**
 * No account or bank may be deleted while a sync or an import runs: the sync's later inserts would be orphaned
 * (SQLite foreign keys are not enforced) and every later `findItems` would throw.
 */
class AccountsDeleteGuardTest {
    @TempDir
    lateinit var tmp: File

    private lateinit var data: TestData

    @BeforeEach
    fun setup() {
        TestDatabase.init()
        data = TestData()
    }

    /** Blocks inside `fetchAll` until the test releases it. */
    private class BlockingGateway(
        private val entered: CountDownLatch,
        private val release: CountDownLatch,
    ) : FinTSGateway {
        override fun fetchAll(connection: FinTSConnection, interaction: BankingInteraction, since: (String) -> LocalDate?, to: LocalDate): List<AccountSnapshot> {
            entered.countDown()
            release.await(5, TimeUnit.SECONDS)
            return emptyList()
        }

        override fun transfer(connection: FinTSConnection, interaction: BankingInteraction, fromIban: String, order: TransferOrder): FinTSClient.TransferResult = throw UnsupportedOperationException()

        override fun instantTransfer(connection: FinTSConnection, interaction: BankingInteraction, fromIban: String, order: TransferOrder): FinTSClient.TransferResult = throw UnsupportedOperationException()
    }

    /** A gateway that answers nothing and is never used. */
    private class IdleGateway : FinTSGateway {
        override fun fetchAll(connection: FinTSConnection, interaction: BankingInteraction, since: (String) -> LocalDate?, to: LocalDate): List<AccountSnapshot> = emptyList()

        override fun transfer(connection: FinTSConnection, interaction: BankingInteraction, fromIban: String, order: TransferOrder): FinTSClient.TransferResult = throw UnsupportedOperationException()

        override fun instantTransfer(connection: FinTSConnection, interaction: BankingInteraction, fromIban: String, order: TransferOrder): FinTSClient.TransferResult = throw UnsupportedOperationException()
    }

    private fun viewModel(gateway: FinTSGateway): AccountsViewModel = AccountsViewModel(
        bankingService = BankingService(
            finTSClient = FinTSClient(
                gateway = gateway,
                passportDir = File(tmp, "passports-${System.nanoTime()}"),
                credentialStore = { FakeDeleteCredentialStore() },
            ),
        ),
    )

    private fun bankWithBooking(): Triple<Int, Int, Int> {
        val bank = data.bankAccessRepo.create("Test Bank", "37040044", "COBADEFFXXX", "user1").id.value
        val account = data.accountRepo.create(bank, "DE89370400440532013000", balance = BigDecimal("1000.00")).id.value
        val txn = data.txn(account, LocalDate.now(), "-10.00", partner = "Shop")
        return Triple(bank, account, txn)
    }

    @Test
    fun `deletes are refused while a sync runs and work again afterwards`() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val (bank, account, txn) = bankWithBooking()
        val vm = viewModel(BlockingGateway(entered, release))
        vm.loadAccounts().join()

        val sync = vm.syncBankAccess(bank)
        assertTrue(entered.await(5, TimeUnit.SECONDS), "the sync reached the gateway")
        assertTrue(vm.state.value.isSyncing)

        val deleteAccount = vm.deleteAccount(account)
        assertTrue(deleteAccount.isCompleted, "a refused delete returns a completed job")
        assertEquals(AppStrings[StringKey.ErrorDeleteWhileBusy], vm.state.value.error)

        val deleteBank = vm.deleteBankAccess(bank)
        assertTrue(deleteBank.isCompleted, "a refused delete returns a completed job")
        assertEquals(AppStrings[StringKey.ErrorDeleteWhileBusy], vm.state.value.error)

        assertEquals(1, AccountRepositoryImpl().findAllItems().size, "the account is still there")
        assertEquals(1, BankAccessRepositoryImpl().findAllItems().size, "the bank is still there")
        assertEquals(1, TransactionRepositoryImpl().findItems().size, "its booking is still there")
        assertEquals(txn, TransactionRepositoryImpl().findItems().single().id)

        release.countDown()
        sync.join()

        vm.deleteAccount(account).join()
        assertTrue(AccountRepositoryImpl().findAllItems().isEmpty(), "a delete succeeds after the sync")
        vm.deleteBankAccess(bank).join()
        assertTrue(BankAccessRepositoryImpl().findAllItems().isEmpty())
        assertNull(vm.state.value.error)
        vm.close()
    }

    @Test
    fun `a fresh view model refuses both deletes while the process is busy`() = runBlocking {
        val (bank, account, _) = bankWithBooking()
        val vm = viewModel(IdleGateway())
        vm.loadAccounts().join()

        Busy.begin()
        try {
            val deleteAccount = vm.deleteAccount(account)
            assertTrue(deleteAccount.isCompleted)
            assertEquals(AppStrings[StringKey.ErrorDeleteWhileBusy], vm.state.value.error)

            val deleteBank = vm.deleteBankAccess(bank)
            assertTrue(deleteBank.isCompleted)
            assertEquals(AppStrings[StringKey.ErrorDeleteWhileBusy], vm.state.value.error)

            assertEquals(1, AccountRepositoryImpl().findAllItems().size)
            assertEquals(1, BankAccessRepositoryImpl().findAllItems().size)
        } finally {
            Busy.end()
        }
        vm.close()
    }

    @Test
    fun `a successful connection update clears an earlier validation error`() = runBlocking {
        val bank = data.bankAccessRepo.create("Test Bank", "37040044", "COBADEFFXXX", "user1").id.value
        data.accountRepo.create(bank, "DE89370400440532013000", balance = BigDecimal("1000.00"))
        val vm = viewModel(IdleGateway())
        vm.loadAccounts().join()

        vm.updateConnection(bank, "Test Bank", "1234567", "user1", "").join()
        assertEquals(AppStrings[StringKey.BlzMustBe8Digits], vm.state.value.error)

        vm.updateConnection(bank, "Test Bank", "37040044", "user1", "").join()
        assertNull(vm.state.value.error, "the old error must not hide the success")
        assertEquals(AppStrings[StringKey.MessageConnectionSaved], vm.state.value.message)
        assertFalse(vm.state.value.isSyncing)
        vm.close()
    }
}
