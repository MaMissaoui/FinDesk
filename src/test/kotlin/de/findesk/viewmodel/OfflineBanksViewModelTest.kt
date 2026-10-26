package de.findesk.viewmodel

import de.findesk.TestData
import de.findesk.TestDatabase
import de.findesk.banking.AccountSnapshot
import de.findesk.banking.BankingInteraction
import de.findesk.banking.BankingService
import de.findesk.banking.FinTSClient
import de.findesk.banking.FinTSConnection
import de.findesk.banking.FinTSGateway
import de.findesk.banking.TanChallenge
import de.findesk.banking.TransferOrder
import de.findesk.i18n.AppStrings
import de.findesk.i18n.StringKey
import de.findesk.importer.StatementImportService
import de.findesk.repository.AccountRepositoryImpl
import de.findesk.repository.AuditLogRepositoryImpl
import de.findesk.repository.BankAccessRepositoryImpl
import de.findesk.util.AccountIds
import de.findesk.util.Formats
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

/**
 * Offline banks in the view models: excluded from "Sync all" and single syncs, excluded as payment
 * sources, identifiers validated as IBAN or the card's last 4 digits, and statements imported.
 */
class OfflineBanksViewModelTest {
    private val today = LocalDate.of(2026, 10, 24)

    @TempDir
    lateinit var tmp: File

    private lateinit var data: TestData
    private var offlineBankId = 0
    private val giro = "DE89370400440532013000"

    /** Records the connections whose dialog was opened, so an offline bank must never appear. */
    private class RecordingGateway : FinTSGateway {
        val calls = mutableListOf<String>()

        override fun fetchAll(connection: FinTSConnection, interaction: BankingInteraction, since: (String) -> LocalDate?, to: LocalDate): List<AccountSnapshot> {
            calls += connection.blz
            return emptyList()
        }

        override fun transfer(connection: FinTSConnection, interaction: BankingInteraction, fromIban: String, order: TransferOrder): FinTSClient.TransferResult = throw UnsupportedOperationException()

        override fun instantTransfer(connection: FinTSConnection, interaction: BankingInteraction, fromIban: String, order: TransferOrder): FinTSClient.TransferResult = throw UnsupportedOperationException()
    }

    @BeforeEach
    fun setup() {
        TestDatabase.init()
        data = TestData()
        offlineBankId = data.bankAccessRepo.createOffline("Norwegian Card").id.value
    }

    /** An AccountsViewModel over a counting gateway. */
    private fun accountsViewModel(gateway: FinTSGateway) = AccountsViewModel(
        bankingService = BankingService(
            finTSClient = FinTSClient(gateway = gateway, passportDir = File(tmp, "passports-${System.nanoTime()}")),
            today = { today },
        ),
    )

    @Test
    fun `syncAll syncs only online banks, never an offline one`() = runBlocking {
        val gateway = RecordingGateway()
        data.bankAccessRepo.create("Sparkasse", "37050198", "", "user1")
        data.bankAccessRepo.setIncludeInSyncAll(offlineBankId, true) // even if the stored flag says include
        val vm = accountsViewModel(gateway)
        vm.loadAccounts().join()

        vm.syncAll().join()

        assertEquals(1, gateway.calls.size)
        assertFalse(gateway.calls.contains(""), "the offline bank (no BLZ) is never opened")
        assertTrue(vm.state.value.message.orEmpty().contains("Sparkasse"), "only the online bank synced: ${vm.state.value.message}")
        assertNull(vm.state.value.error)
        vm.close()
    }

    @Test
    fun `an offline bank is not synced and shows the bank-offline error`() = runBlocking {
        val gateway = RecordingGateway()
        val vm = accountsViewModel(gateway)
        vm.loadAccounts().join()

        vm.syncBankAccess(offlineBankId).join()

        assertEquals(AppStrings[StringKey.ErrorBankOffline], vm.state.value.error)
        assertNull(vm.state.value.syncingBankId, "no sync spinner, no error dialog loop")
        assertTrue(gateway.calls.isEmpty(), "the offline bank's gateway is never called")
        vm.close()
    }

    @Test
    fun `payment source accounts exclude offline banks' accounts and non-IBAN identifiers`() = runBlocking {
        val banks = BankAccessRepositoryImpl()
        val online = banks.create("Sparkasse", "37050198", "", "user1").id.value
        val accounts = AccountRepositoryImpl()
        val onlineAccount = accounts.create(online, giro, balance = BigDecimal("100.00")).id.value
        // The offline bank's card carries a valid Norwegian IBAN, so the bank filter is required as well
        val offlineValidIban = accounts.create(offlineBankId, "NO9386011117947", balance = BigDecimal("200.00")).id.value
        accounts.create(offlineBankId, "CARD4321", balance = BigDecimal("0.00"))

        val vm = PaymentsViewModel()
        vm.load().join()

        assertEquals(listOf(onlineAccount), vm.state.value.accounts.map { it.id }, "offline banks and non-IBAN accounts are excluded")
        assertFalse(vm.state.value.accounts.any { it.id == offlineValidIban })
        vm.close()
    }

    @Test
    fun `an offline bank account accepts an IBAN or exactly 4 digits`() = runBlocking {
        val vm = accountsViewModel(RecordingGateway())
        vm.loadAccounts().join()

        // Both forms of identifier are accepted, 4 digits also with surrounding spaces
        assertNull(vm.validateAccount("4321", offline = true))
        assertNull(vm.validateAccount(" 4321 ", offline = true))
        assertNull(vm.validateAccount("NO9386011117947", offline = true))
        assertNull(vm.validateAccount(giro, offline = true))

        // A pasted card number (with or without spaces) is refused without truncation
        assertEquals(AppStrings[StringKey.CardLast4Only], vm.validateAccount("5555 44xx xxxx 4321", offline = true))
        assertEquals(AppStrings[StringKey.CardLast4Only], vm.validateAccount("1234567812345678", offline = true))

        // For an online bank a bare 4 digits stays the IBAN error
        assertEquals(AppStrings[StringKey.IbanChecksumFailed], vm.validateAccount("4321", offline = false))
        vm.close()
    }

    @Test
    fun `adding a card account stores the synthetic identifier and audits its last 4 digits`() = runBlocking {
        val vm = accountsViewModel(RecordingGateway())
        vm.loadAccounts().join()

        vm.addAccount(offlineBankId, " 4321 ", "credit_card", BigDecimal("50.00")).join()

        val account = AccountRepositoryImpl().findAllItems().single()
        assertEquals("CARD4321", account.iban)
        assertTrue(AccountIds.isCard(account.iban))
        assertEquals("Added account 4321", AuditLogRepositoryImpl().findByAction("ADD_ACCOUNT").single().details)

        // The same card again is a duplicate
        assertEquals(AppStrings[StringKey.AccountIbanExists], vm.validateAccount("4321", offline = true))
        vm.close()
    }

    @Test
    fun `adding an offline bank creates it offline with no sync participation`() = runBlocking {
        val vm = accountsViewModel(RecordingGateway())
        vm.loadAccounts().join()

        vm.addOfflineBank("Bank Norwegian").join()

        val bank = BankAccessRepositoryImpl().findAllItems().single { it.bankName == "Bank Norwegian" }
        assertTrue(bank.offline)
        assertFalse(bank.includeInSyncAll)
        assertEquals("Added offline bank: Bank Norwegian", AuditLogRepositoryImpl().findByAction("ADD_BANK_ACCESS").single().details)
        assertEquals(AppStrings[StringKey.BankNameRequired], vm.validateOfflineBank(""))
        vm.close()
    }

    @Test
    fun `renaming an offline bank changes only its name`() = runBlocking {
        val vm = accountsViewModel(RecordingGateway())
        vm.loadAccounts().join()

        vm.updateConnection(offlineBankId, "Bank Norwegian Visa", "12345678", "user", "https://attacker.example").join()

        val bank = BankAccessRepositoryImpl().findItem(offlineBankId)!!
        assertEquals("Bank Norwegian Visa", bank.bankName)
        assertEquals("", bank.blz)
        assertNull(bank.serverUrl)
        assertTrue(bank.offline)
        vm.close()
    }

    @Test
    fun `statements are imported into a CARD account`() = runBlocking {
        AccountRepositoryImpl().create(offlineBankId, "CARD4321", balance = BigDecimal("0.00")).also {
            val csv = "Buchungstag;Betrag;Beguenstigter/Zahlungspflichtiger;Verwendungszweck\n23.10.2026;-4,50;Baecker;Kaffee\n"
            val file = File(tmp, "card.csv").apply { writeText(csv) }
            val service = StatementImportService()

            val report = service.import(it.id.value, service.read(file))
            assertTrue(report.imported == 1, "the statement's booking lands in the card account")
        }
    }

    @Test
    fun `a card identifier keeps its display in the bank card`() {
        assertEquals("•••• 4321", AccountIds.display("CARD4321"))
        assertEquals(Formats.iban(giro), AccountIds.display(giro))
    }
}
