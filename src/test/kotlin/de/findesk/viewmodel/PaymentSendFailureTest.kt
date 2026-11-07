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
import de.findesk.banking.PinEntry
import de.findesk.banking.TanChallenge
import de.findesk.banking.TanMethod
import de.findesk.banking.TransferOrder
import de.findesk.i18n.AppStrings
import de.findesk.i18n.StringKey
import de.findesk.repository.PaymentOrderRepository
import de.findesk.repository.PaymentOrderRepositoryImpl
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime

/** A failed send never leaves the payment review sendable, and a post-send refresh failure is not an unknown outcome. */
class PaymentSendFailureTest {
    @TempDir
    lateinit var tmp: File

    private lateinit var data: TestData
    private val order = TransferOrder("Max Mustermann", "DE44500105175407324931", null, BigDecimal("10.00"), "Test")

    @BeforeEach
    fun setup() {
        TestDatabase.init()
        data = TestData()
        data.account()
    }

    /** Answers the bank's PIN prompt without any UI. */
    private class AskPin : BankingInteraction {
        override fun pin(bankName: String, userId: String, offerRemember: Boolean) = PinEntry("12345")
        override fun selectTanMethod(options: List<TanMethod>) = options.firstOrNull()?.id
        override fun selectTanMedium(options: List<String>) = options.firstOrNull()
        override fun tan(challenge: TanChallenge) = "999999"
        override fun confirmDecoupled(message: String) = true
        override fun confirmPayee(message: String) = true
    }

    /** Records accepted transfers; the bank always accepts. */
    private class CountingGateway : FinTSGateway {
        val transfers = mutableListOf<Pair<String, TransferOrder>>()

        override fun fetchAll(connection: FinTSConnection, interaction: BankingInteraction, since: (String) -> LocalDate?, to: LocalDate): List<AccountSnapshot> = emptyList()

        override fun transfer(connection: FinTSConnection, interaction: BankingInteraction, fromIban: String, order: TransferOrder): FinTSClient.TransferResult {
            AskPin().pin(connection.bankName, connection.userId)
            transfers += fromIban to order
            return FinTSClient.TransferResult("job-1", "ACCEPTED")
        }

        override fun instantTransfer(connection: FinTSConnection, interaction: BankingInteraction, fromIban: String, order: TransferOrder): FinTSClient.TransferResult = throw UnsupportedOperationException()
    }

    private class NoStore : CredentialStore {
        override val isAvailable: Boolean = false
        override fun read(key: String): String? = null
        override fun write(key: String, secret: String) = Unit
        override fun delete(key: String) = Unit
    }

    /** Delegates to the real repository, but can fail `complete` or `markInterrupted` on demand. */
    private class FailingPaymentOrderRepository(
        private val delegate: PaymentOrderRepository,
    ) : PaymentOrderRepository by delegate {
        var failCompleteOnce = false
        var failMarkInterrupted = false

        override fun complete(id: Int, status: String, bankOrderId: String?, message: String?) {
            if (failCompleteOnce) {
                failCompleteOnce = false
                throw IllegalStateException("audit store unavailable")
            }
            delegate.complete(id, status, bankOrderId, message)
        }

        override fun markInterrupted(startedBefore: LocalDateTime): Int {
            if (failMarkInterrupted) throw IllegalStateException("refresh failed")
            return delegate.markInterrupted(startedBefore)
        }
    }

    private fun viewModel(gateway: CountingGateway, repository: FailingPaymentOrderRepository): PaymentsViewModel {
        val service = BankingService(
            finTSClient = FinTSClient(
                gateway = gateway,
                passportDir = File(tmp, "passports"),
                credentialStore = { NoStore() },
            ),
            paymentOrderRepository = repository,
        )
        return PaymentsViewModel(bankingService = service, paymentOrderRepository = repository)
    }

    @Test
    fun `a failed order completion closes the review and blocks a second send`() = runBlocking {
        val gateway = CountingGateway()
        val repository = FailingPaymentOrderRepository(PaymentOrderRepositoryImpl())
        repository.failCompleteOnce = true
        val vm = viewModel(gateway, repository)
        vm.load().join()
        val account = vm.state.value.accounts.single()

        vm.review(account, order)
        vm.confirmAndSend().join()

        assertEquals(1, gateway.transfers.size, "the bank accepted the order once")
        assertNull(vm.state.value.review, "the review must not stay ready to send the same order again")
        assertEquals(
            AppStrings.format(StringKey.ErrorOrderOutcomeUnknown, "audit store unavailable"),
            vm.state.value.error,
        )

        vm.confirmAndSend().join()
        assertEquals(1, gateway.transfers.size, "a second confirm sends nothing")
        vm.close()
    }

    @Test
    fun `a refresh failure after a successful send keeps the bank's answer`() = runBlocking {
        val gateway = CountingGateway()
        val repository = FailingPaymentOrderRepository(PaymentOrderRepositoryImpl())
        val vm = viewModel(gateway, repository)
        vm.load().join()
        val account = vm.state.value.accounts.single()

        vm.review(account, order)
        repository.failMarkInterrupted = true
        vm.confirmAndSend().join()

        assertEquals(1, gateway.transfers.size)
        assertNull(vm.state.value.review)
        assertEquals(AppStrings.format(StringKey.MessageTransferSent, order.recipientName), vm.state.value.message)
        assertEquals("refresh failed", vm.state.value.error, "the refresh failure is reported, not an unknown outcome")
        assertTrue(vm.state.value.error != AppStrings.format(StringKey.ErrorOrderOutcomeUnknown, "refresh failed"))
        vm.close()
    }
}
