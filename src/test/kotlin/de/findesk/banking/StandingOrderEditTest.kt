package de.findesk.banking

import de.findesk.TestData
import de.findesk.TestDatabase
import de.findesk.i18n.AppStrings
import de.findesk.i18n.StringKey
import de.findesk.repository.AuditLogRepositoryImpl
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
 * Editing a standing order (DauerSEPAEdit) moves real money repeatedly, so every guard of
 * [BankingService.sendOrder] holds, the recipient is fixed and a running order keeps its start date.
 */
class StandingOrderEditTest {
    @TempDir
    lateinit var tmp: File

    private lateinit var data: TestData
    private lateinit var gateway: EditGateway
    private lateinit var service: BankingService
    private val today = LocalDate.of(2026, 10, 25)
    private val giro = "DE89370400440532013000"
    private val savings = "DE44500105175407324931"
    private val order = TransferOrder("Max Mustermann", savings, null, BigDecimal("10.00"), "Rent October")

    /** A standing order as the bank reports it, editable by default. */
    private fun standingOrder(firstDate: LocalDate = LocalDate.of(2026, 11, 1)) = StandingOrder(
        orderId = "so-1",
        fromIban = giro,
        order = order,
        schedule = StandingOrderSchedule(firstDate, "M", 1, 1, null),
        nextDate = LocalDate.of(2026, 11, 1),
        canChange = true,
    )

    /** The same schedule as [standingOrder] reports, with the edited values a user would enter. */
    private fun editedSchedule(existing: StandingOrder = standingOrder(), firstDate: LocalDate? = null, lastDate: LocalDate? = null) = StandingOrderSchedule(firstDate ?: existing.schedule.firstDate, "M", 1, 1, lastDate)

    private fun editedOrder(amount: BigDecimal = BigDecimal("25.00")) = order.copy(amount = amount)

    /** A gateway that answers the PIN itself and records what was sent to the bank. */
    private class EditGateway : FinTSGateway {
        /** The order as handed in, plus the changed values. */
        val edits = mutableListOf<Triple<StandingOrder, TransferOrder, StandingOrderSchedule>>()

        override fun fetchAll(connection: FinTSConnection, interaction: BankingInteraction, since: (String) -> LocalDate?, to: LocalDate): List<AccountSnapshot> = emptyList()

        override fun transfer(connection: FinTSConnection, interaction: BankingInteraction, fromIban: String, order: TransferOrder): FinTSClient.TransferResult {
            interaction.pin(connection.bankName, connection.userId) ?: throw BankingCancelledException()
            return FinTSClient.TransferResult("job-1", "ACCEPTED")
        }

        override fun instantTransfer(connection: FinTSConnection, interaction: BankingInteraction, fromIban: String, order: TransferOrder): FinTSClient.TransferResult {
            interaction.pin(connection.bankName, connection.userId) ?: throw BankingCancelledException()
            return FinTSClient.TransferResult("inst-1", "ACCEPTED")
        }

        override fun editStandingOrder(connection: FinTSConnection, interaction: BankingInteraction, existing: StandingOrder, order: TransferOrder, schedule: StandingOrderSchedule): String? {
            interaction.pin(connection.bankName, connection.userId) ?: throw BankingCancelledException()
            edits += Triple(existing, order, schedule)
            return "so-2"
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
        gateway = EditGateway()
        service = BankingService(finTSClient = FinTSClient(gateway = gateway, passportDir = File(tmp, "passports")), today = { today })
    }

    /** An EUR account on a bank access whose stored capabilities are [capabilities] (null = never synced). */
    private fun account(capabilities: String? = null): Int {
        val bankId = data.bankAccessRepo.create("Test Bank", "37040044", "COBADEFFXXX", "user1").id.value
        capabilities?.let { data.bankAccessRepo.updateCapabilities(bankId, it) }
        return data.accountRepo.create(bankId, giro, balance = BigDecimal("500.00"), currency = "EUR").id.value
    }

    @Test
    fun `an unconfirmed edit sends nothing and the gateway is not called`() = runBlocking {
        val result = service.editStandingOrder(account(), standingOrder(), editedOrder(), editedSchedule(), AskPin(), confirmed = false)

        assertEquals("NOT_CONFIRMED", (result as BankingResult.Error).code)
        assertTrue(gateway.edits.isEmpty(), "nothing may be sent without confirmation")
    }

    @Test
    fun `a valid edit calls the gateway once with the existing id and the new values, audited with codes only`() = runBlocking {
        val existing = standingOrder()
        val schedule = editedSchedule(lastDate = LocalDate.of(2027, 12, 31))

        val result = service.editStandingOrder(account(), existing, editedOrder(), schedule, AskPin(), confirmed = true)

        val sent = result as BankingResult.Success
        assertEquals("so-2", sent.data)
        assertEquals(1, gateway.edits.size, "the gateway is called exactly once")
        val (sentExisting, sentOrder, sentSchedule) = gateway.edits.single()
        assertEquals("so-1", sentExisting.orderId, "the bank identifies the order by its id")
        assertEquals(BigDecimal("25.00"), sentOrder.amount)
        assertEquals(LocalDate.of(2027, 12, 31), sentSchedule.lastDate)

        val audit = AuditLogRepositoryImpl().findByAction("STANDING_ORDER_EDIT").single()
        assertTrue(audit.details!!.startsWith("25.00 EUR from …3000 to Max Mustermann"), audit.details)
        assertTrue(audit.details!!.endsWith(": sent"), "the audit records the FinDesk outcome and codes, not bank text: ${audit.details}")
        val recorded = PaymentOrderRepositoryImpl().findRecentItems().single()
        assertEquals(de.findesk.model.PaymentOrderStatus.ACCEPTED, recorded.status)
        assertEquals("so-2", recorded.bankOrderId)
    }

    @Test
    fun `a changed recipient is denied with ErrorEditRecipientFixed, before the gateway`() = runBlocking {
        val existing = standingOrder()
        val changed = existing.order.copy(recipientIban = "DE02120300000000202051")

        val result = service.editStandingOrder(account(), existing, changed, editedSchedule(), AskPin(), confirmed = true)

        val error = result as BankingResult.Error
        assertEquals("INVALID", error.code)
        assertEquals(AppStrings[StringKey.ErrorEditRecipientFixed], error.message)
        assertTrue(gateway.edits.isEmpty(), "a changed recipient must not reach the bank")
    }

    @Test
    fun `an order the bank does not allow changing is denied before the gateway`() = runBlocking {
        val existing = standingOrder()
        val notChangeable = service.editStandingOrder(account(), existing.copy(canChange = false), editedOrder(), editedSchedule(), AskPin(), confirmed = true)
        val noId = service.editStandingOrder(account(), existing.copy(orderId = null), editedOrder(), editedSchedule(), AskPin(), confirmed = true)

        assertEquals("INVALID", (notChangeable as BankingResult.Error).code)
        assertEquals(AppStrings[StringKey.ErrorCannotEditStandingOrder], notChangeable.message)
        assertEquals("INVALID", (noId as BankingResult.Error).code)
        assertEquals(AppStrings[StringKey.ErrorCannotEditStandingOrder], noId.message)
        assertTrue(gateway.edits.isEmpty())
    }

    @Test
    fun `a login known not to offer DauerSEPAEdit is refused before the gateway`() = runBlocking {
        val id = account("${BankCapabilities.JOB_STANDING_LIST},${BankCapabilities.JOB_STANDING_NEW},${BankCapabilities.JOB_STANDING_DELETE}")

        val result = service.editStandingOrder(id, standingOrder(), editedOrder(), editedSchedule(), AskPin(), confirmed = true)

        val error = result as BankingResult.Error
        assertEquals("INVALID", error.code)
        assertEquals(AppStrings[StringKey.ErrorOrderNotOffered], error.message)
        assertTrue(gateway.edits.isEmpty())
    }

    @Test
    fun `an order of another account is denied before the gateway`() = runBlocking {
        val result = service.editStandingOrder(account(), standingOrder().copy(fromIban = savings), editedOrder(), editedSchedule(), AskPin(), confirmed = true)

        val error = result as BankingResult.Error
        assertEquals("INVALID", error.code)
        assertEquals(AppStrings[StringKey.ErrorStandingOrderOtherAccount], error.message)
        assertTrue(gateway.edits.isEmpty())
    }

    @Test
    fun `an invalid schedule (weekly execution day 9) is denied before the gateway`() = runBlocking {
        val bad = StandingOrderSchedule(standingOrder().schedule.firstDate, "W", 1, 9, null)

        val result = service.editStandingOrder(account(), standingOrder(), editedOrder(), bad, AskPin(), confirmed = true)

        val error = result as BankingResult.Error
        assertEquals("INVALID", error.code)
        assertEquals(AppStrings[StringKey.ErrorExecutionDayWeek], error.message)
        assertTrue(gateway.edits.isEmpty())
    }

    @Test
    fun `a running order keeps its start in the past and succeeds`() = runBlocking {
        // Started three months ago, still running: the first date may not be changed, but it may stay in the past
        val running = standingOrder(firstDate = today.minusMonths(3))

        val result = service.editStandingOrder(account(), running, editedOrder(), editedSchedule(running), AskPin(), confirmed = true)

        assertTrue(result is BankingResult.Success, "an unchanged past start is a running order: $result")
        assertEquals(1, gateway.edits.size)
    }

    @Test
    fun `a changed first date or a last date in the past is denied`() = runBlocking {
        val running = standingOrder(firstDate = today.minusMonths(3))
        val movedIntoPast = service.editStandingOrder(account(), running, editedOrder(), editedSchedule(running, firstDate = today.minusDays(1)), AskPin(), confirmed = true)
        val lastInPast = service.editStandingOrder(account(), running, editedOrder(), editedSchedule(running, lastDate = today.minusDays(2)), AskPin(), confirmed = true)

        assertEquals(AppStrings[StringKey.ErrorFirstExecutionFuture], (movedIntoPast as BankingResult.Error).message)
        assertEquals(AppStrings[StringKey.ErrorLastDateFuture], (lastInPast as BankingResult.Error).message)
        assertTrue(gateway.edits.isEmpty())
    }

    @Test
    fun `whitespace in the recomposed form values is not a recipient change`() {
        // The form hands in the reported name with an extra space at the end and the IBAN spaced in groups of four
        val tidied = TransferOrder(" ${order.recipientName} ", "DE44 5001 0517 5407 3249 31", order.recipientBic, BigDecimal("25.00"), "Rent October")

        assertNull(service.validateEditedOrder(standingOrder(), tidied, editedSchedule()), "spaces alone must not count as a recipient change")
    }

    @Test
    fun `the DauerSEPAEdit job round-trips through detect, serialize and parse`() {
        val caps = BankCapabilities.detect { it == BankCapabilities.JOB_STANDING_DELETE || it == BankCapabilities.JOB_STANDING_EDIT }
        assertTrue(caps.supports(BankCapabilities.JOB_STANDING_EDIT))
        assertFalse(caps.supports(BankCapabilities.JOB_STANDING_NEW))

        val roundTrip = BankCapabilities.parse(caps.serialize())!!
        assertEquals(caps, roundTrip)
        assertTrue(roundTrip.supports(BankCapabilities.JOB_STANDING_EDIT))
    }

    @Test
    fun `a sent edit closes the review and the edit form, so the form does not reopen`() = runBlocking {
        val id = account()
        val scripted = object : FinTSGateway by gateway {
            override fun editStandingOrder(connection: FinTSConnection, interaction: BankingInteraction, existing: StandingOrder, order: TransferOrder, schedule: StandingOrderSchedule) = gateway.editStandingOrder(connection, AskPin(), existing, order, schedule)
        }
        val vm = PaymentsViewModel(bankingService = BankingService(finTSClient = FinTSClient(gateway = scripted, passportDir = File(tmp, "passports-vm")), today = { today }))
        vm.load().join()
        val from = vm.state.value.accounts.single { it.id == id }
        val existing = standingOrder()

        vm.requestEdit(existing)
        vm.reviewEdit(from, existing, editedOrder(), editedSchedule())
        assertNotNull(vm.state.value.review?.editOf)
        vm.confirmAndSend().join()

        assertEquals(1, gateway.edits.size)
        assertNull(vm.state.value.review)
        assertNull(vm.state.value.editReview, "the edit form must not reopen after the order was sent")
        assertNull(vm.state.value.editDraft, "the draft is cleared with the edit")
        vm.close()
    }

    @Test
    fun `editing only the amount of an every-2-months order keeps the bank's step and time unit`() {
        val vm = PaymentsViewModel(bankingService = service)
        // "M"/2 is not a StandingOrderInterval FinDesk offers; the form's dropdown falls back to monthly for it
        val existing = standingOrder().copy(schedule = StandingOrderSchedule(LocalDate.of(2026, 11, 1), "M", 2, 1, null))

        val schedule = vm.editedSchedule(existing, existing.schedule.firstDate, StandingOrderInterval.MONTHLY, "1", null)!!

        assertEquals(2, schedule.step, "the gateway must receive the bank's step, not the dropdown's fallback")
        assertEquals("M", schedule.timeUnit, "the gateway must receive the bank's time unit")
        assertNull(schedule.interval)
        vm.close()
    }

    @Test
    fun `back from the change review keeps the entered values`() = runBlocking {
        val id = account()
        val vm = PaymentsViewModel(bankingService = service)
        vm.load().join()
        val from = vm.state.value.accounts.single { it.id == id }
        val existing = standingOrder()

        vm.requestEdit(existing)
        vm.updateEditDraft(vm.state.value.editDraft!!.copy(amountText = "42.00"))
        vm.reviewEdit(from, existing, editedOrder(BigDecimal("42.00")), editedSchedule())
        assertNotNull(vm.state.value.review?.editOf)
        vm.cancelReview()

        assertEquals("42.00", vm.state.value.editDraft?.amountText, "Back from the review must restore the form's values")
        vm.cancelEdit()
        assertNull(vm.state.value.editDraft, "closing the edit clears the draft")
        vm.close()
    }

    @Test
    fun `an edit that changes nothing sets ErrorEditNothingChanged and opens no review`() = runBlocking {
        val id = account()
        val vm = PaymentsViewModel(bankingService = service)
        vm.load().join()
        val from = vm.state.value.accounts.single { it.id == id }
        val existing = standingOrder()

        vm.reviewEdit(from, existing, existing.order, existing.schedule)

        assertEquals(AppStrings[StringKey.ErrorEditNothingChanged], vm.state.value.error)
        assertNull(vm.state.value.review, "an unchanged edit opens no review")
        assertTrue(gateway.edits.isEmpty())
        vm.close()
    }

    @Test
    fun `the edit action is shown as words, not the raw code`() {
        val action = AppStrings.current.paymentAction(de.findesk.model.PaymentAction.STANDING_ORDER_EDIT)

        assertNotEquals(de.findesk.model.PaymentAction.STANDING_ORDER_EDIT, action, "the recent transfers list must not show the raw code")
        assertEquals("Standing order changed", action)
    }
}
