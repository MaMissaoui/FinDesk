package de.findesk.banking

import de.findesk.TestData
import de.findesk.TestDatabase
import de.findesk.repository.AuditLogRepositoryImpl
import de.findesk.repository.SettingsRepositoryImpl
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.kapott.hbci.callback.HBCICallback
import java.io.File
import java.math.BigDecimal
import java.time.LocalDate

/** In-memory stand-in for the OS credential store; the real keychain is never touched in tests. */
private class FakeCredentialStore(
    override val isAvailable: Boolean = true,
) : CredentialStore {
    val entries = mutableMapOf<String, String>()
    var readFailure: Exception? = null
    var writeFailure: Exception? = null

    override fun read(key: String): String? {
        readFailure?.let { throw it }
        return entries[key]
    }

    override fun write(key: String, secret: String) {
        writeFailure?.let { throw it }
        entries[key] = secret
    }

    override fun delete(key: String) {
        entries.remove(key)
    }
}

/** Interaction double; records what the banking code asked and never keeps a PIN anywhere else. */
private class PinAnsweringInteraction(
    var pinValue: String? = "12345",
    var remember: Boolean = false,
) : BankingInteraction {
    var pinAsked = 0
    val offeredRemember = mutableListOf<Boolean>()
    val pinBankNames = mutableListOf<String>()

    override fun pin(bankName: String, userId: String, offerRemember: Boolean): PinEntry? = pinValue?.let { PinEntry(it, remember) }.also {
        pinAsked++
        offeredRemember += offerRemember
        pinBankNames += bankName
    }

    override fun selectTanMethod(options: List<TanMethod>): String? = options.firstOrNull()?.id
    override fun selectTanMedium(options: List<String>): String? = options.firstOrNull()
    override fun tan(challenge: TanChallenge): String? = "999999"
    override fun confirmDecoupled(message: String) = true
    override fun confirmPayee(message: String) = true
}

/**
 * Fake gateway whose [script] drives the real [FinDeskHbciCallback], so the PIN-caching and re-prompt logic is
 * exercised exactly as a bank would trigger it. [failWith] simulates the dialog breaking after the script ran.
 */
private class PinGateway : FinTSGateway {
    var snapshots: List<AccountSnapshot> = emptyList()
    var script: List<Int> = listOf(HBCICallback.NEED_PT_PIN)
    var failWith: Exception? = null
    override var lastTanMethod: String? = null
    override var lastCapabilities: BankCapabilities? = null

    override fun fetchAll(connection: FinTSConnection, interaction: BankingInteraction, since: (String) -> LocalDate?, to: LocalDate): List<AccountSnapshot> {
        val callback = FinDeskHbciCallback(connection, interaction)
        script.forEach { reason ->
            val buffer = StringBuffer()
            callback.callback(null, reason, "", HBCICallback.TYPE_TEXT, buffer)
        }
        failWith?.let { throw it }
        return snapshots
    }

    override fun transfer(connection: FinTSConnection, interaction: BankingInteraction, fromIban: String, order: TransferOrder): FinTSClient.TransferResult {
        // Like Hbci4JavaGateway: an order asks for the PIN without ever offering to remember it
        interaction.pin(connection.bankName, connection.userId) ?: throw BankingCancelledException()
        return FinTSClient.TransferResult("job-1", "ACCEPTED")
    }
}

class SavedPinTest {
    @TempDir
    lateinit var tmp: File

    private lateinit var data: TestData
    private lateinit var store: FakeCredentialStore
    private lateinit var gateway: PinGateway
    private lateinit var client: FinTSClient
    private lateinit var service: BankingService
    private var bankId = 0
    private val today = LocalDate.of(2026, 10, 4)
    private val giro = "DE89370400440532013000"

    @BeforeEach
    fun setup() {
        TestDatabase.init()
        data = TestData()
        bankId = data.bankAccessRepo.create("Sparkasse KölnBonn", "37050198", "COLSDE33XXX", "demo-user").id.value
        store = FakeCredentialStore()
        gateway = PinGateway()
        client = FinTSClient(gateway = gateway, passportDir = passportDir(), credentialStore = { store })
        service = BankingService(finTSClient = client, today = { today })
    }

    private fun passportDir() = File(tmp, "passports")

    /** The same key FinTSClient derives from the passport directory. */
    private fun pinKey() = "${passportDir().absoluteFile.parentFile.absolutePath}#bank-$bankId"

    private fun account(iban: String) = FinTSAccount(iban, "COLSDE33XXX", iban.takeLast(10), bankName = "Sparkasse", blz = "37050198", accountType = "checking")

    private fun snapshot(iban: String, balance: String) = AccountSnapshot(account(iban), FinTSBalance(account(iban), BigDecimal(balance)), emptyList())

    private fun storePin() = runBlocking {
        gateway.snapshots = listOf(snapshot(giro, "100.00"))
        service.syncBankConnection(bankId, PinAnsweringInteraction(remember = true))
        assertEquals("12345", store.entries[pinKey()], "precondition: the PIN was kept")
    }

    @Test
    fun `remembering the PIN stores it and audits it without the secret`() = runBlocking {
        gateway.snapshots = listOf(snapshot(giro, "100.00"))
        val report = (service.syncBankConnection(bankId, PinAnsweringInteraction(remember = true)) as BankingResult.Success).data

        assertEquals("12345", store.entries[pinKey()])
        assertTrue(service.hasSavedPin(bankId))
        assertEquals("37050198|demo-user", SettingsRepositoryImpl().getValue("fints.pinSaved.$bankId"))
        assertEquals(1, AuditLogRepositoryImpl().findByAction("PIN_SAVED").size)
        assertTrue(report.warnings.isEmpty())
        assertFalse(service.pinNotSaved)

        // The PIN must not leak into any text the user or a log sees
        val haystack = buildString {
            append(report.summary()).append('\n')
            append(AuditLogRepositoryImpl().findRecent(100).joinToString("\n") { "${it.action} ${it.details}" }).append('\n')
            append(SettingsRepositoryImpl().getValue("fints.pinSaved.$bankId"))
        }
        assertFalse(haystack.contains("12345"), "no PIN in the report, audit log or settings")
        assertFalse(PinEntry("12345", remember = true).toString().contains("12345"), "PinEntry hides the PIN")
    }

    @Test
    fun `a stored PIN answers the next sync without asking`() = runBlocking {
        storePin()
        val second = PinAnsweringInteraction(pinValue = "99999")

        service.syncBankConnection(bankId, second)

        assertEquals(0, second.pinAsked, "the stored PIN answers the dialog")
        assertEquals("12345", store.entries[pinKey()])
    }

    @Test
    fun `without the opt-in nothing is stored`() = runBlocking {
        gateway.snapshots = listOf(snapshot(giro, "100.00"))

        service.syncBankConnection(bankId, PinAnsweringInteraction(remember = false))

        assertTrue(store.entries.isEmpty())
        assertFalse(service.hasSavedPin(bankId))
    }

    @Test
    fun `a rejected stored PIN is deleted and the user is asked again`() = runBlocking {
        storePin()
        gateway.script = listOf(HBCICallback.NEED_PT_PIN, HBCICallback.WRONG_PIN, HBCICallback.NEED_PT_PIN)
        val second = PinAnsweringInteraction(pinValue = "54321")

        val report = (service.syncBankConnection(bankId, second) as BankingResult.Success).data

        assertEquals(1, second.pinAsked, "only the re-prompt reaches the user; the stored PIN was used first")
        assertTrue(store.entries.isEmpty(), "the rejected PIN is gone")
        assertFalse(service.hasSavedPin(bankId), "and so is its flag")
        assertTrue(report.warnings.isEmpty())
        assertEquals(1, AuditLogRepositoryImpl().findByAction("PIN_FORGOTTEN").size, "forgotten once, not per prompt")
    }

    @Test
    fun `cancelling the re-prompt after a rejected stored PIN forgets it`() = runBlocking {
        storePin()
        gateway.script = listOf(HBCICallback.NEED_PT_PIN, HBCICallback.WRONG_PIN, HBCICallback.NEED_PT_PIN)

        val result = service.syncBankConnection(bankId, PinAnsweringInteraction(pinValue = null))

        assertEquals("CANCELLED", (result as BankingResult.Error).code)
        assertTrue(store.entries.isEmpty())
        assertFalse(service.hasSavedPin(bankId))
    }

    @Test
    fun `a dialog failure after a stored PIN was used forgets it`() = runBlocking {
        storePin()
        gateway.failWith = IllegalStateException("connection lost")

        val result = service.syncBankConnection(bankId, PinAnsweringInteraction())

        assertEquals(FinTSClient.FINTS_ERROR, (result as BankingResult.Error).code)
        assertTrue(store.entries.isEmpty(), "a re-prompt is cheap, a locked login is not")
        assertFalse(service.hasSavedPin(bankId))
    }

    @Test
    fun `a cancel while the stored PIN was fine keeps it`() = runBlocking {
        storePin()
        // The stored PIN opened the dialog; the user then cancels a later prompt (e.g. a TAN)
        gateway.failWith = BankingCancelledException()

        val result = service.syncBankConnection(bankId, PinAnsweringInteraction())

        assertEquals("CANCELLED", (result as BankingResult.Error).code)
        assertEquals("12345", store.entries[pinKey()], "the PIN was not the problem, so it stays")
        assertTrue(service.hasSavedPin(bankId))
    }

    @Test
    fun `a flag for another login forgets the entry and asks again`() = runBlocking {
        storePin()
        SettingsRepositoryImpl().setValue("fints.pinSaved.$bankId", "37050198|someone-else")
        val interaction = PinAnsweringInteraction()

        service.syncBankConnection(bankId, interaction)

        assertEquals(1, interaction.pinAsked)
        assertTrue(store.entries.isEmpty(), "the entry belongs to the old login and is removed")
        assertFalse(service.hasSavedPin(bankId))
    }

    @Test
    fun `a flag whose store entry is gone is cleared and the user asked`() = runBlocking {
        SettingsRepositoryImpl().setValue("fints.pinSaved.$bankId", "37050198|demo-user")
        gateway.snapshots = listOf(snapshot(giro, "100.00"))
        val interaction = PinAnsweringInteraction()

        service.syncBankConnection(bankId, interaction)

        assertEquals(1, interaction.pinAsked)
        assertFalse(service.hasSavedPin(bankId), "the stale flag is cleared")
    }

    @Test
    fun `a store that cannot be read is not used and nothing is deleted`() = runBlocking {
        storePin()
        store.readFailure = IllegalStateException("keychain locked")
        val interaction = PinAnsweringInteraction()

        val report = (service.syncBankConnection(bankId, interaction) as BankingResult.Success).data

        assertEquals(1, interaction.pinAsked)
        assertEquals("12345", store.entries[pinKey()], "a read failure must not delete the entry")
        assertTrue(service.hasSavedPin(bankId))
        assertTrue(report.warnings.isEmpty())
    }

    @Test
    fun `a store that cannot be written does not fail the sync`() = runBlocking {
        store.writeFailure = IllegalStateException("keychain denied")
        gateway.snapshots = listOf(snapshot(giro, "100.00"))

        val report = (service.syncBankConnection(bankId, PinAnsweringInteraction(remember = true)) as BankingResult.Success).data

        assertTrue(report.warnings.isEmpty())
        assertNotNull(data.bankAccessRepo.findItem(bankId)!!.lastSync, "the sync completed and advanced lastSync")
        assertTrue(service.pinNotSaved, "the view model can tell the user the PIN wasn't saved")
        assertTrue(store.entries.isEmpty())
        assertFalse(service.hasSavedPin(bankId))
    }

    @Test
    fun `an order always asks and never offers to remember`() = runBlocking {
        storePin()
        val accountId = data.accountRepo.findAllItems().single().id
        val order = TransferOrder("Vermieter GmbH", "DE44500105175407324931", null, BigDecimal("850.00"), "Miete")
        val interaction = PinAnsweringInteraction()

        val result = service.initiateTransfer(accountId, order, interaction, confirmed = true)

        assertTrue(result is BankingResult.Success)
        assertEquals(1, interaction.pinAsked, "an order always asks for the PIN")
        assertEquals(listOf(false), interaction.offeredRemember, "an order never offers to remember")
        assertEquals("12345", store.entries[pinKey()], "the stored PIN is untouched")
    }

    @Test
    fun `removing the bank forgets the stored PIN`() = runBlocking {
        storePin()

        service.removeBankAccess(bankId)

        assertTrue(store.entries.isEmpty())
        assertFalse(service.hasSavedPin(bankId))
    }

    @Test
    fun `removing a bank without a remembered PIN logs no forgotten PIN`() = runBlocking {
        service.removeBankAccess(bankId)

        assertTrue(AuditLogRepositoryImpl().findByAction("PIN_FORGOTTEN").isEmpty())
    }

    @Test
    fun `saving the same user id with surrounding spaces keeps the PIN`() = runBlocking {
        storePin()
        val vm = de.findesk.viewmodel.AccountsViewModel(bankingService = service)
        vm.loadAccounts().join()

        vm.updateConnection(bankId, "Sparkasse", " demo-user ", "").join()

        assertTrue(service.hasSavedPin(bankId))
        assertEquals("12345", store.entries[pinKey()])
        vm.close()
    }

    @Test
    fun `changing the user id forgets the PIN`() = runBlocking {
        storePin()
        val vm = de.findesk.viewmodel.AccountsViewModel(bankingService = service)
        vm.loadAccounts().join()

        vm.updateConnection(bankId, "Sparkasse", "other-user", "").join()

        assertEquals("other-user", data.bankAccessRepo.findItem(bankId)!!.userId)
        assertTrue(store.entries.isEmpty())
        assertFalse(service.hasSavedPin(bankId))
        vm.close()
    }

    @Test
    fun `tests never reach a real credential store`() {
        assertSame(NoCredentialStore, CredentialStores.system())
        assertFalse(CredentialStores.system().isAvailable)
    }
}
