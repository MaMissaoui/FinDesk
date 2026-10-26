package de.findesk.banking

import de.findesk.TestData
import de.findesk.TestDatabase
import de.findesk.i18n.AppStrings
import de.findesk.i18n.StringKey
import de.findesk.repository.AccountRepositoryImpl
import de.findesk.repository.AuditLogRepositoryImpl
import de.findesk.repository.BankAccessRepositoryImpl
import de.findesk.repository.PaymentOrderRepositoryImpl
import de.findesk.viewmodel.PaymentsViewModel
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.math.BigDecimal
import java.time.LocalDate

/**
 * SEPA instant transfers (Echtzeitüberweisung): the money moves within seconds, so every guard of
 * [BankingService.sendOrder] must hold and there must never be a fallback to a normal transfer.
 */
class InstantTransferTest {
    @TempDir
    lateinit var tmp: File

    private lateinit var data: TestData
    private lateinit var gateway: InstantGateway
    private lateinit var service: BankingService
    private val today = LocalDate.of(2026, 10, 25)
    private val giro = "DE89370400440532013000"
    private val savings = "DE44500105175407324931"
    private val order = TransferOrder("Max Mustermann", savings, null, BigDecimal("10.00"), "Test")

    /** A gateway that answers the PIN itself, so no dialog is needed. */
    private class AskPin : BankingInteraction {
        override fun pin(bankName: String, userId: String, offerRemember: Boolean) = PinEntry("12345")
        override fun selectTanMethod(options: List<TanMethod>) = options.firstOrNull()?.id
        override fun selectTanMedium(options: List<String>) = options.firstOrNull()
        override fun tan(challenge: TanChallenge) = "999999"
        override fun confirmDecoupled(message: String) = true
        override fun confirmPayee(message: String) = true
    }

    /** Records both send paths separately, so a test can assert the normal transfer was never used. */
    private class InstantGateway : FinTSGateway {
        val instantCalls = mutableListOf<Pair<String, TransferOrder>>()
        val normalCalls = mutableListOf<Pair<String, TransferOrder>>()
        var instantResult: FinTSClient.TransferResult = FinTSClient.TransferResult("inst-1", "ACCEPTED", bankStatusCode = "2")
        var instantError: Exception? = null

        override fun fetchAll(connection: FinTSConnection, interaction: BankingInteraction, since: (String) -> LocalDate?, to: LocalDate): List<AccountSnapshot> = emptyList()

        override fun transfer(connection: FinTSConnection, interaction: BankingInteraction, fromIban: String, order: TransferOrder): FinTSClient.TransferResult {
            normalCalls += fromIban to order
            return FinTSClient.TransferResult("job-1", "ACCEPTED")
        }

        override fun instantTransfer(connection: FinTSConnection, interaction: BankingInteraction, fromIban: String, order: TransferOrder): FinTSClient.TransferResult {
            interaction.pin(connection.bankName, connection.userId) ?: throw BankingCancelledException()
            instantCalls += fromIban to order
            instantError?.let { throw it }
            return instantResult
        }
    }

    @BeforeEach
    fun setup() {
        TestDatabase.init()
        data = TestData()
        gateway = InstantGateway()
        service = BankingService(finTSClient = FinTSClient(gateway = gateway, passportDir = File(tmp, "passports")), today = { today })
    }

    /** An EUR account on a bank access whose stored capabilities are [capabilities] (null = never synced). */
    private fun account(capabilities: String? = null, currency: String = "EUR"): Int {
        val bankId = data.bankAccessRepo.create("Test Bank", "37040044", "COBADEFFXXX", "user1").id.value
        capabilities?.let { data.bankAccessRepo.updateCapabilities(bankId, it) }
        return data.accountRepo.create(bankId, giro, balance = BigDecimal("500.00"), currency = currency).id.value
    }

    @Test
    fun `an unconfirmed instant transfer sends nothing and is audited as failed`() = runBlocking {
        val result = service.initiateInstantTransfer(account(), order, AskPin(), confirmed = false)

        assertEquals("NOT_CONFIRMED", (result as BankingResult.Error).code)
        assertTrue(gateway.instantCalls.isEmpty() && gateway.normalCalls.isEmpty(), "nothing may be sent without confirmation")
        val audit = AuditLogRepositoryImpl().findByAction("INSTANT_TRANSFER").single()
        assertTrue(audit.details!!.contains("failed: " + AppStrings[StringKey.ErrorOrderNotConfirmed]), audit.details)
    }

    @Test
    fun `a confirmed instant transfer reaches the gateway once and is recorded as accepted`() = runBlocking {
        val result = service.initiateInstantTransfer(account(), order, AskPin(), confirmed = true)

        val sent = result as BankingResult.Success
        assertEquals("inst-1", sent.data.transactionId)
        assertEquals("ACCEPTED", sent.data.status)
        assertEquals(listOf(giro to order), gateway.instantCalls, "the gateway is called once with the order")
        assertTrue(gateway.normalCalls.isEmpty())

        val audit = AuditLogRepositoryImpl().findByAction("INSTANT_TRANSFER").single()
        assertTrue(audit.details!!.contains(" (instant): sent"), audit.details)
        assertTrue(!audit.details!!.contains("status 2"), "the audit keeps codes only, not the bank's answer: ${audit.details}")
        // The raw order status is stored with the order record, not in the audit
        val recorded = PaymentOrderRepositoryImpl().findRecentItems().single()
        assertEquals(de.findesk.model.PaymentOrderStatus.ACCEPTED, recorded.status)
        assertEquals("2", recorded.message)
    }

    @Test
    fun `a login known not to offer instant transfers is refused before the gateway`() = runBlocking {
        val id = account("${BankCapabilities.JOB_TRANSFER},${BankCapabilities.JOB_SCHEDULED_TRANSFER}")

        val result = service.initiateInstantTransfer(id, order, AskPin(), confirmed = true)

        assertEquals("INVALID", (result as BankingResult.Error).code)
        assertEquals(AppStrings[StringKey.ErrorOrderNotOffered], result.message)
        assertTrue(gateway.instantCalls.isEmpty() && gateway.normalCalls.isEmpty(), "nothing may reach a bank that doesn't offer it")
    }

    @Test
    fun `unknown capabilities leave the decision to the bank`() = runBlocking {
        val result = service.initiateInstantTransfer(account(capabilities = null), order, AskPin(), confirmed = true)

        assertTrue(result is BankingResult.Success, "an unknown set is attempted, the bank decides")
        assertEquals(1, gateway.instantCalls.size)
    }

    @Test
    fun `a broken dialog is outcome unknown and never falls back to a normal transfer`() = runBlocking {
        gateway.instantError = IllegalStateException("Read timed out")

        val result = service.initiateInstantTransfer(account(), order, AskPin(), confirmed = true)

        assertEquals(BankingService.OUTCOME_UNKNOWN, (result as BankingResult.Error).code)
        assertEquals(1, gateway.instantCalls.size, "the instant order is sent exactly once")
        assertTrue(gateway.normalCalls.isEmpty(), "a fallback could pay twice, so none may be attempted")
    }

    @Test
    fun `a cancellation code from the bank is an error and never falls back to a normal transfer`() = runBlocking {
        gateway.instantError = OrderNotExecutedException(AppStrings.format(StringKey.ErrorInstantCancelled, "XY04"))

        val result = service.initiateInstantTransfer(account(), order, AskPin(), confirmed = true)

        val error = result as BankingResult.Error
        assertEquals(AppStrings.format(StringKey.ErrorInstantCancelled, "XY04"), error.message)
        assertTrue(gateway.normalCalls.isEmpty(), "a cancelled instant order is not sent again as a normal one")
    }

    @Test
    fun `a non-EUR account and an offline bank are refused like every other order`() = runBlocking {
        assertEquals("CURRENCY", (service.initiateInstantTransfer(account(currency = "USD"), order, AskPin(), confirmed = true) as BankingResult.Error).code)
        val offline = data.bankAccessRepo.createOffline("Norwegian Card").id.value
        val offlineAccount = AccountRepositoryImpl().create(offline, giro, balance = BigDecimal("100.00")).id.value
        assertEquals("OFFLINE", (service.initiateInstantTransfer(offlineAccount, order, AskPin(), confirmed = true) as BankingResult.Error).code)
        assertTrue(gateway.instantCalls.isEmpty() && gateway.normalCalls.isEmpty())
    }

    @Test
    fun `capabilities detect and round-trip the instant transfer job`() {
        val caps = BankCapabilities.detect { it == BankCapabilities.JOB_INSTANT_TRANSFER || it == BankCapabilities.JOB_TRANSFER }
        assertTrue(caps.supportsInstantTransfer)
        assertFalse(caps.supportsScheduledTransfer)

        val roundTrip = BankCapabilities.parse(caps.serialize())!!
        assertEquals(caps, roundTrip)
        assertTrue(roundTrip.supportsInstantTransfer)
    }

    /** The same gateway, but the dialogs are answered by the test instead of the view model's UI interaction. */
    private fun scriptedService(): BankingService {
        val scripted = object : FinTSGateway by gateway {
            override fun instantTransfer(connection: FinTSConnection, interaction: BankingInteraction, fromIban: String, order: TransferOrder) = gateway.instantTransfer(connection, AskPin(), fromIban, order)
        }
        return BankingService(finTSClient = FinTSClient(gateway = scripted, passportDir = File(tmp, "passports-vm")), today = { today })
    }

    @Test
    fun `the payments view model sends an instant order through the instant path only`() = runBlocking {
        val id = account("${BankCapabilities.JOB_TRANSFER},${BankCapabilities.JOB_INSTANT_TRANSFER},${BankCapabilities.JOB_SCHEDULED_TRANSFER}")
        val vm = PaymentsViewModel(bankingService = scriptedService())
        vm.load().join()
        val from = vm.state.value.accounts.single { it.id == id }

        vm.review(from, order, instant = true)
        vm.confirmAndSend().join()

        assertEquals(listOf(giro to order), gateway.instantCalls, "the instant path is used")
        assertTrue(gateway.normalCalls.isEmpty(), "the normal transfer is not sent")
        assertEquals(AppStrings[StringKey.MessageInstantTransferAccepted], vm.state.value.message)
        vm.close()
    }
}
