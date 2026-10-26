package de.findesk.banking

import de.findesk.TestDatabase
import de.findesk.i18n.AppStrings
import de.findesk.i18n.StringKey
import de.findesk.repository.AccountRepositoryImpl
import de.findesk.repository.AuditLogRepositoryImpl
import de.findesk.repository.BankAccessRepositoryImpl
import de.findesk.repository.SettingsRepositoryImpl
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.math.BigDecimal
import java.time.LocalDate

/**
 * An offline bank must never reach FinTS: no gateway call, no stored credentials, no dialog.
 * Every account identifier here is synthetic (e.g. IBAN NO9386011117947, card CARD4321).
 */
class OfflineBankGuardTest {
    private val today = LocalDate.of(2026, 10, 24)

    @TempDir
    lateinit var tmp: File

    private lateinit var gateway: RecordingGateway
    private lateinit var interaction: CatchingInteraction
    private lateinit var client: FinTSClient
    private lateinit var bankingService: BankingService
    private var offlineBankId = 0
    private var onlineBankId = 0
    private var ibanAccountId = 0
    private var cardAccountId = 0
    private val order = TransferOrder("Test GmbH", "DE89370400440532013000", null, BigDecimal("10.00"), "Test")

    /** Records every call, so a test can assert the fake was never reached. */
    private class RecordingGateway : FinTSGateway {
        var fetched = 0
        var resets = 0
        val transfers = mutableListOf<String>()
        val scheduled = mutableListOf<String>()
        val standingCreated = mutableListOf<String>()
        val standingDeleted = mutableListOf<StandingOrder>()
        val listed = mutableListOf<String>()

        override fun fetchAll(connection: FinTSConnection, interaction: BankingInteraction, since: (String) -> LocalDate?, to: LocalDate): List<AccountSnapshot> {
            fetched++
            return emptyList()
        }

        override fun transfer(connection: FinTSConnection, interaction: BankingInteraction, fromIban: String, order: TransferOrder): FinTSClient.TransferResult {
            transfers += fromIban
            throw IllegalStateException("reached the bank")
        }

        val instantTransfers = mutableListOf<String>()

        override fun instantTransfer(connection: FinTSConnection, interaction: BankingInteraction, fromIban: String, order: TransferOrder): FinTSClient.TransferResult {
            instantTransfers += fromIban
            throw IllegalStateException("reached the bank")
        }

        override fun scheduledTransfer(connection: FinTSConnection, interaction: BankingInteraction, fromIban: String, order: TransferOrder, date: LocalDate): FinTSClient.TransferResult {
            scheduled += fromIban
            throw IllegalStateException("reached the bank")
        }

        override fun listStandingOrders(connection: FinTSConnection, interaction: BankingInteraction, fromIban: String): List<StandingOrder> {
            listed += fromIban
            throw IllegalStateException("reached the bank")
        }

        override fun createStandingOrder(connection: FinTSConnection, interaction: BankingInteraction, fromIban: String, order: TransferOrder, schedule: StandingOrderSchedule): String? {
            standingCreated += fromIban
            throw IllegalStateException("reached the bank")
        }

        override fun deleteStandingOrder(connection: FinTSConnection, interaction: BankingInteraction, existing: StandingOrder) {
            standingDeleted += existing
            throw IllegalStateException("reached the bank")
        }

        override fun resetPassport(passportFile: File) {
            resets++
            super.resetPassport(passportFile)
        }
    }

    /** An interaction that must never be prompted for an offline bank: a call fails the test. */
    private class CatchingInteraction : BankingInteraction {
        var prompts = 0

        private fun fail(): Nothing {
            prompts++
            throw IllegalStateException("a dialog opened for an offline bank")
        }

        override fun pin(bankName: String, userId: String, offerRemember: Boolean): PinEntry? = fail()
        override fun selectTanMethod(options: List<TanMethod>): String? = fail()
        override fun selectTanMedium(options: List<String>): String? = fail()
        override fun tan(challenge: TanChallenge): String? = fail()
        override fun confirmDecoupled(message: String): Boolean = fail()
        override fun confirmPayee(message: String): Boolean = fail()
    }

    /** A credential store that counts every access, so a test can assert it was never touched. */
    private class CountingCredentialStore : CredentialStore {
        var touches = 0

        override val isAvailable: Boolean = true

        override fun read(key: String): String? {
            touches++
            return null
        }

        override fun write(key: String, secret: String) {
            touches++
            throw IllegalStateException("no credential is ever stored for an offline bank")
        }

        override fun delete(key: String) {
            touches++
        }
    }

    @BeforeEach
    fun setup() {
        TestDatabase.init()
        gateway = RecordingGateway()
        interaction = CatchingInteraction()
        client = FinTSClient(
            gateway = gateway,
            passportDir = File(tmp, "passports-${System.nanoTime()}"),
            settingsRepository = SettingsRepositoryImpl(),
            auditLogRepository = AuditLogRepositoryImpl(),
            credentialStore = { CountingCredentialStore() },
        )
        bankingService = BankingService(finTSClient = client)
        val accounts = AccountRepositoryImpl()
        val banks = BankAccessRepositoryImpl()
        offlineBankId = banks.createOffline("Norwegian Card").id.value
        onlineBankId = banks.create("Sparkasse KölnBonn", "37050198", "COLSDE33XXX", "demo-user").id.value
        // One card account by identifier, one with a valid (synthetic) Norwegian IBAN, both on the offline bank
        cardAccountId = accounts.create(offlineBankId, "CARD4321", balance = BigDecimal("100.00")).id.value
        ibanAccountId = accounts.create(offlineBankId, "NO9386011117947", balance = BigDecimal("500.00")).id.value
    }

    private fun assertNothingReachable() {
        assertEquals(0, interaction.prompts, "no dialog may open")
        assertTrue(
            gateway.transfers.isEmpty() && gateway.scheduled.isEmpty() && gateway.standingCreated.isEmpty() && gateway.instantTransfers.isEmpty() &&
                gateway.standingDeleted.isEmpty() && gateway.listed.isEmpty() && gateway.fetched == 0 && gateway.resets == 0,
        )
    }

    private fun assertAudited(action: String, attempts: Int) {
        val entries = AuditLogRepositoryImpl().findByAction(action)
        assertEquals(attempts, entries.size, "one audit entry per attempt for $action")
        entries.forEach {
            val details = it.details!!
            assertTrue(details.contains("failed: " + AppStrings[StringKey.ErrorOrderOfflineAccount]), details)
            assertFalse(details.contains("NO9386011117947") || details.contains("CARD4321"), "no full identifier in the audit: $details")
        }
    }

    @Test
    fun `connectionFor refuses an offline bank and creates no passport passphrase`() {
        val bank = BankAccessRepositoryImpl().findItem(offlineBankId)!!

        val error = client.connectionFor(bank) as BankingResult.Error

        assertEquals("OFFLINE", error.code)
        assertEquals(AppStrings[StringKey.ErrorBankOffline], error.message)
        assertNull(SettingsRepositoryImpl().getValue("fints.passport.$offlineBankId"), "no passport passphrase was created")
        assertNull(bank.capabilities)
        assertEquals(0, interaction.prompts)
    }

    @Test
    fun `syncBankConnection refuses an offline bank before any FinTS work`() = runBlocking {
        val result = bankingService.syncBankConnection(offlineBankId, interaction)

        val error = result as BankingResult.Error
        assertEquals("OFFLINE", error.code)
        assertEquals(0, gateway.fetched, "the fake gateway must never be called")
        assertEquals(0, interaction.prompts)
    }

    @Test
    fun `only offline banks are refused locally, an online bank still syncs`() = runBlocking {
        val result = bankingService.syncBankConnection(onlineBankId, interaction)

        assertTrue(result is BankingResult.Success, "the guard must not refuse an online bank")
        assertEquals(1, gateway.fetched, "an online bank's dialog reaches the gateway as before")
    }

    @Test
    fun `orders are refused from an offline bank's accounts, never sent and always audited`() = runBlocking {
        val schedule = StandingOrderSchedule.of(LocalDate.of(2026, 12, 1), StandingOrderInterval.MONTHLY, 1)

        for (accountId in listOf(ibanAccountId, cardAccountId)) {
            assertEquals("OFFLINE", (bankingService.initiateTransfer(accountId, order, interaction, confirmed = true) as BankingResult.Error).code)
            assertEquals("OFFLINE", (bankingService.scheduleTransfer(accountId, order, today.plusDays(5), interaction, confirmed = true) as BankingResult.Error).code)
            assertEquals("OFFLINE", (bankingService.loadStandingOrders(accountId, interaction) as BankingResult.Error).code)
            val existing = StandingOrder("order-1", order.recipientIban, order, schedule, nextDate = LocalDate.of(2026, 12, 1))
            assertEquals("OFFLINE", (bankingService.deleteStandingOrder(accountId, existing, interaction, confirmed = true) as BankingResult.Error).code, "deleteStandingOrder goes through sendOrder")

            val created = bankingService.createStandingOrder(accountId, order, schedule, interaction, confirmed = true)
            assertEquals("OFFLINE", (created as BankingResult.Error).code, "createStandingOrder is guarded like the other paths")
        }

        assertNothingReachable()
        assertAudited("TRANSFER", 2)
        assertAudited("SCHEDULED_TRANSFER", 2)
        assertAudited("STANDING_ORDER_CREATE", 2)
        assertAudited("STANDING_ORDER_DELETE", 2)
    }

    @Test
    fun `updateConnection for an offline bank only changes the name and never resets the passport`() = runBlocking {
        val updated = bankingService.updateConnection(offlineBankId, "Norwegian Visa", "12345678", "user", "https://attacker.example/fints")
        assertNotNull(updated)
        val bank = updated!!
        assertEquals("Norwegian Visa", bank.bankName, "only the label changes")
        assertEquals("", bank.blz, "an offline bank never gains a BLZ")
        assertEquals("", bank.userId, "an offline bank never gains a login")
        assertNull(bank.serverUrl)
        assertTrue(bank.offline, "an offline bank stays offline")
        assertEquals(0, gateway.resets, "resetLogin must never run for an offline bank")
        assertNull(SettingsRepositoryImpl().getValue("fints.passport.$offlineBankId"), "no passport passphrase was created")
    }

    @Test
    fun `an online bank's connection update still resets its passport when the login changed`() = runBlocking {
        bankingService.updateConnection(onlineBankId, "Sparkasse KölnBonn", "20050000", "other-user", "https://fints.example/pintan")

        assertEquals(1, gateway.resets, "the existing reset behaviour is unchanged")
    }

    @Test
    fun `remembering or forgetting a PIN for an offline bank touches no credentials`() {
        bankingService.forgetPin(offlineBankId)

        assertFalse(bankingService.hasSavedPin(offlineBankId))
    }
}
