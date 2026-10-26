package de.findesk.banking

import de.findesk.TestData
import de.findesk.TestDatabase
import de.findesk.i18n.AppStrings
import de.findesk.i18n.StringKey
import de.findesk.repository.AccountRepositoryImpl
import de.findesk.repository.AuditLogRepositoryImpl
import de.findesk.repository.PaymentOrderRepositoryImpl
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.math.BigDecimal
import java.time.LocalDate

/**
 * Loading and cancelling scheduled transfers (TermUebSEPAList/TermUebSEPADel) moves real money, so every guard of
 * [BankingService.sendOrder] holds and the bank's `can_delete` answer is respected.
 */
class ScheduledTransferListTest {
    @TempDir
    lateinit var tmp: File

    private lateinit var data: TestData
    private lateinit var gateway: ScheduledGateway
    private lateinit var service: BankingService
    private val today = LocalDate.of(2026, 10, 25)
    private val giro = "DE89370400440532013000"
    private val savings = "DE44500105175407324931"
    private val order = TransferOrder("Vermieter GmbH", "DE02120300000000202051", null, BigDecimal("850.00"), "Miete November")

    /** A scheduled transfer as the bank reports it, deletable by default. */
    private fun scheduledTransfer(orderId: String? = "t-1", fromIban: String = giro, canDelete: Boolean = true) = ScheduledTransfer(
        orderId = orderId,
        fromIban = fromIban,
        order = order,
        date = LocalDate.of(2026, 11, 1),
        canDelete = canDelete,
    )

    /** A fake bank. Entries the bank reports without a date cannot be shown or cancelled: the list skips them. */
    private class ScheduledGateway : FinTSGateway {
        /** The bank's raw entry; `date == null` means the bank couldn't report a usable execution date. */
        class Raw(val orderId: String?, val fromIban: String, val order: TransferOrder, val date: LocalDate?, val canDelete: Boolean)

        val reported = mutableListOf<Raw>()
        val deleted = mutableListOf<ScheduledTransfer>()

        override fun fetchAll(connection: FinTSConnection, interaction: BankingInteraction, since: (String) -> LocalDate?, to: LocalDate): List<AccountSnapshot> = emptyList()

        override fun transfer(connection: FinTSConnection, interaction: BankingInteraction, fromIban: String, order: TransferOrder): FinTSClient.TransferResult {
            interaction.pin(connection.bankName, connection.userId) ?: throw BankingCancelledException()
            return FinTSClient.TransferResult("job-1", "ACCEPTED")
        }

        override fun instantTransfer(connection: FinTSConnection, interaction: BankingInteraction, fromIban: String, order: TransferOrder): FinTSClient.TransferResult {
            interaction.pin(connection.bankName, connection.userId) ?: throw BankingCancelledException()
            return FinTSClient.TransferResult("inst-1", "ACCEPTED")
        }

        override fun listScheduledTransfers(connection: FinTSConnection, interaction: BankingInteraction, fromIban: String): List<ScheduledTransfer> {
            interaction.pin(connection.bankName, connection.userId) ?: throw BankingCancelledException()
            return reported.filter { it.fromIban == fromIban && it.date != null }
                .map { ScheduledTransfer(it.orderId, it.fromIban, it.order, it.date!!, it.canDelete) }
        }

        override fun deleteScheduledTransfer(connection: FinTSConnection, interaction: BankingInteraction, existing: ScheduledTransfer) {
            interaction.pin(connection.bankName, connection.userId) ?: throw BankingCancelledException()
            deleted += existing
        }
    }

    /** Answers the PIN itself, so no dialog is needed. */
    private class AskPin : BankingInteraction {
        override fun pin(bankName: String, userId: String, offerRemember: Boolean) = PinEntry("12345")
        override fun selectTanMethod(options: List<TanMethod>) = options.firstOrNull()?.id
        override fun selectTanMedium(options: List<String>) = options.firstOrNull()
        override fun tan(challenge: TanChallenge) = "999999"
        override fun confirmDecoupled(message: String) = true
        override fun confirmPayee(message: String) = true
    }

    @BeforeEach
    fun setup() {
        TestDatabase.init()
        data = TestData()
        gateway = ScheduledGateway()
        service = BankingService(finTSClient = FinTSClient(gateway = gateway, passportDir = File(tmp, "passports")), today = { today })
    }

    /** An EUR account on a bank access whose stored capabilities are [capabilities] (null = never synced). */
    private fun account(capabilities: String? = null): Int {
        val bankId = data.bankAccessRepo.create("Test Bank", "37040044", "COBADEFFXXX", "user1").id.value
        capabilities?.let { data.bankAccessRepo.updateCapabilities(bankId, it) }
        return data.accountRepo.create(bankId, giro, balance = BigDecimal("500.00"), currency = "EUR").id.value
    }

    @Test
    fun `load maps the reported entries and skips the one without a date`() = runBlocking {
        val datedFirst = scheduledTransfer("t-1", canDelete = true)
        gateway.reported += listOf(
            ScheduledGateway.Raw(datedFirst.orderId, datedFirst.fromIban, datedFirst.order, datedFirst.date, true),
            ScheduledGateway.Raw(null, giro, order.copy(recipientName = "Ohne Datum"), null, true), // no date: not shown
            ScheduledGateway.Raw(null, giro, order.copy(recipientName = "Nicht löschbar"), LocalDate.of(2026, 12, 1), false),
        )

        val result = service.loadScheduledTransfers(account(), AskPin())

        val listed = (result as BankingResult.Success).data
        assertEquals(2, listed.size, "the undated entry must be skipped")
        assertEquals("t-1", listed[0].orderId)
        assertEquals(datedFirst.date, listed[0].date)
        assertTrue(listed[0].canDelete)
        assertEquals("Nicht löschbar", listed[1].order.recipientName)
        assertEquals(giro, listed[1].fromIban)
        assertFalse(listed[1].canDelete)
        assertNull(listed[1].orderId)
    }

    @Test
    fun `an unconfirmed cancellation sends nothing and the gateway is not called`() = runBlocking {
        val result = service.deleteScheduledTransfer(account(), scheduledTransfer(), AskPin(), confirmed = false)

        assertEquals("NOT_CONFIRMED", (result as BankingResult.Error).code)
        assertTrue(gateway.deleted.isEmpty(), "nothing may be sent without confirmation")
    }

    @Test
    fun `a transfer the bank does not allow cancelling is denied before the gateway`() = runBlocking {
        val notDeletable = service.deleteScheduledTransfer(account(), scheduledTransfer(canDelete = false), AskPin(), confirmed = true)
        val noId = service.deleteScheduledTransfer(account(), scheduledTransfer(orderId = null), AskPin(), confirmed = true)

        assertEquals("INVALID", (notDeletable as BankingResult.Error).code)
        assertEquals(AppStrings[StringKey.ErrorCannotDeleteScheduledTransfer], notDeletable.message)
        assertEquals("INVALID", (noId as BankingResult.Error).code)
        assertEquals(AppStrings[StringKey.ErrorCannotDeleteScheduledTransfer], noId.message)
        assertTrue(gateway.deleted.isEmpty())
    }

    @Test
    fun `a valid cancellation calls the gateway once and audits SCHEDULED_TRANSFER_DELETE`() = runBlocking {
        val existing = scheduledTransfer()

        val result = service.deleteScheduledTransfer(account(), existing, AskPin(), confirmed = true)

        assertTrue(result is BankingResult.Success)
        assertEquals(1, gateway.deleted.size, "the gateway is called exactly once")
        assertEquals("t-1", gateway.deleted.single().orderId)

        val audit = AuditLogRepositoryImpl().findByAction("SCHEDULED_TRANSFER_DELETE").single()
        assertTrue(audit.details!!.startsWith("850.00 EUR from …3000 to Vermieter GmbH"), audit.details)
        assertTrue(audit.details!!.endsWith(": sent"), "the audit records the FinDesk outcome and codes, not bank text: ${audit.details}")
        val recorded = PaymentOrderRepositoryImpl().findRecentItems().single()
        assertEquals(de.findesk.model.PaymentOrderStatus.ACCEPTED, recorded.status)
        assertEquals("SCHEDULED_TRANSFER_DELETE", recorded.action)
    }

    @Test
    fun `the cancellation action is labelled instead of shown as the raw code`() {
        val label = AppStrings.current.paymentAction(de.findesk.model.PaymentAction.SCHEDULED_TRANSFER_DELETE)

        assertEquals("Scheduled transfer cancelled", label)
        assertNotEquals(de.findesk.model.PaymentAction.SCHEDULED_TRANSFER_DELETE, label)
    }

    @Test
    fun `a transfer of another account is denied before the gateway`() = runBlocking {
        val result = service.deleteScheduledTransfer(account(), scheduledTransfer(fromIban = savings), AskPin(), confirmed = true)

        val error = result as BankingResult.Error
        assertEquals("INVALID", error.code)
        assertEquals(AppStrings[StringKey.ErrorScheduledTransferOtherAccount], error.message)
        assertTrue(gateway.deleted.isEmpty())
    }

    @Test
    fun `a login known not to offer TermUebSEPADel is refused before the gateway`() = runBlocking {
        val id = account(BankCapabilities.JOB_SCHEDULED_TRANSFER)

        val result = service.deleteScheduledTransfer(id, scheduledTransfer(), AskPin(), confirmed = true)

        val error = result as BankingResult.Error
        assertEquals("INVALID", error.code)
        assertEquals(AppStrings[StringKey.ErrorOrderNotOffered], error.message)
        assertTrue(gateway.deleted.isEmpty())
    }

    @Test
    fun `a login known not to offer TermUebSEPAList is refused before the gateway`() = runBlocking {
        val id = account(BankCapabilities.JOB_SCHEDULED_TRANSFER)

        val result = service.loadScheduledTransfers(id, AskPin())

        val error = result as BankingResult.Error
        assertEquals("UNSUPPORTED", error.code)
        assertEquals(AppStrings[StringKey.ErrorOrderNotOffered], error.message)
    }

    @Test
    fun `loading on an offline bank is denied before the gateway`() = runBlocking {
        val offlineBankId = data.bankAccessRepo.createOffline("Norwegian Card").id.value
        val accountId = AccountRepositoryImpl().create(offlineBankId, "NO9386011117947", balance = BigDecimal("100.00"), currency = "EUR").id.value
        val asked = mutableListOf<String>()
        val interaction = object : BankingInteraction {
            private fun fail(): Nothing {
                asked += "prompt"
                throw IllegalStateException("a dialog opened for an offline bank")
            }

            override fun pin(bankName: String, userId: String, offerRemember: Boolean): PinEntry? = fail()
            override fun selectTanMethod(options: List<TanMethod>): String? = fail()
            override fun selectTanMedium(options: List<String>): String? = fail()
            override fun tan(challenge: TanChallenge): String? = fail()
            override fun confirmDecoupled(message: String): Boolean = fail()
            override fun confirmPayee(message: String): Boolean = fail()
        }

        val result = service.loadScheduledTransfers(accountId, interaction)

        val error = result as BankingResult.Error
        assertEquals("OFFLINE", error.code)
        assertEquals(AppStrings[StringKey.ErrorOrderOfflineAccount], error.message)
        assertTrue(asked.isEmpty(), "no dialog may open")
        assertTrue(gateway.reported.isEmpty())
    }

    @Test
    fun `the TermUebSEPA list and delete jobs round-trip through detect, serialize and parse`() {
        val caps = BankCapabilities.detect { it == BankCapabilities.JOB_SCHEDULED_LIST || it == BankCapabilities.JOB_SCHEDULED_DELETE }
        assertTrue(caps.supports(BankCapabilities.JOB_SCHEDULED_LIST))
        assertTrue(caps.supports(BankCapabilities.JOB_SCHEDULED_DELETE))
        assertFalse(caps.supports(BankCapabilities.JOB_SCHEDULED_TRANSFER))
        assertFalse(caps.supports(BankCapabilities.JOB_STANDING_LIST))

        val roundTrip = BankCapabilities.parse(caps.serialize())!!
        assertEquals(caps, roundTrip)
        assertTrue(roundTrip.supports(BankCapabilities.JOB_SCHEDULED_LIST))
        assertTrue(roundTrip.supports(BankCapabilities.JOB_SCHEDULED_DELETE))
    }
}
