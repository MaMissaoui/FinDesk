package de.findesk.banking

import de.findesk.TestData
import de.findesk.TestDatabase
import de.findesk.viewmodel.PaymentsViewModel
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.math.BigDecimal
import java.time.LocalDate

/**
 * Switching the account while a bank prompt is open (or cancelling it) must never leave the previous account's
 * standing orders or scheduled transfers on screen under the new account.
 */
class PaymentListStaleAccountTest {
    @TempDir
    lateinit var tmp: File

    private lateinit var data: TestData
    private lateinit var gateway: ListGateway
    private lateinit var vm: PaymentsViewModel

    private val giro = "DE89370400440532013000"
    private val savings = "DE44500105175407324931"
    private val today = LocalDate.of(2026, 10, 25)

    private fun transfer(recipient: String = "Vermieter GmbH") = TransferOrder(
        recipient,
        "DE02120300000000202051",
        null,
        BigDecimal("10.00"),
        "Test",
    )

    private fun standingOrder(fromIban: String) = StandingOrder(
        orderId = "so-$fromIban",
        fromIban = fromIban,
        order = transfer(),
        schedule = StandingOrderSchedule(LocalDate.of(2026, 11, 1), "M", 1, 1),
    )

    private fun scheduledTransfer(fromIban: String) = ScheduledTransfer(
        orderId = "t-$fromIban",
        fromIban = fromIban,
        order = transfer(),
        date = LocalDate.of(2026, 11, 1),
    )

    /** Reports what the test put in; [fail] makes the list request end like a cancelled PIN/TAN prompt. */
    private class ListGateway : FinTSGateway {
        val fail = mutableSetOf<String>()
        val standing = mutableMapOf<String, List<StandingOrder>>()
        val scheduled = mutableMapOf<String, List<ScheduledTransfer>>()

        override fun fetchAll(
            connection: FinTSConnection,
            interaction: BankingInteraction,
            since: (String) -> LocalDate?,
            to: LocalDate,
        ): List<AccountSnapshot> = emptyList()

        override fun transfer(connection: FinTSConnection, interaction: BankingInteraction, fromIban: String, order: TransferOrder): FinTSClient.TransferResult = throw UnsupportedOperationException()

        override fun instantTransfer(connection: FinTSConnection, interaction: BankingInteraction, fromIban: String, order: TransferOrder): FinTSClient.TransferResult = throw UnsupportedOperationException()

        override fun listStandingOrders(connection: FinTSConnection, interaction: BankingInteraction, fromIban: String): List<StandingOrder> {
            if (fromIban in fail) throw BankingCancelledException()
            return standing[fromIban].orEmpty()
        }

        override fun listScheduledTransfers(connection: FinTSConnection, interaction: BankingInteraction, fromIban: String): List<ScheduledTransfer> {
            if (fromIban in fail) throw BankingCancelledException()
            return scheduled[fromIban].orEmpty()
        }
    }

    @BeforeEach
    fun setup() {
        TestDatabase.init()
        data = TestData()
        gateway = ListGateway()
        val service = BankingService(
            finTSClient = FinTSClient(gateway = gateway, passportDir = File(tmp, "passports")),
            today = { today },
        )
        vm = PaymentsViewModel(bankingService = service)
    }

    @Test
    fun `another account's standing orders are cleared when its prompt is cancelled`() = runBlocking {
        val a = data.account(giro)
        val b = data.account(savings)
        gateway.standing[giro] = listOf(standingOrder(giro))
        vm.load().join()

        vm.loadStandingOrders(vm.state.value.accounts.single { it.id == a }).join()
        assertEquals(1, vm.state.value.standingOrders?.size, "A's orders load first")

        gateway.fail += savings
        vm.loadStandingOrders(vm.state.value.accounts.single { it.id == b }).join()

        assertEquals(b, vm.state.value.standingOrdersAccount)
        assertNull(vm.state.value.standingOrders, "A's orders must not stay listed under B after a cancelled prompt")
        vm.close()
    }

    @Test
    fun `another account's scheduled transfers are cleared when its prompt is cancelled`() = runBlocking {
        val a = data.account(giro)
        val b = data.account(savings)
        gateway.scheduled[giro] = listOf(scheduledTransfer(giro))
        vm.load().join()

        vm.loadScheduledTransfers(vm.state.value.accounts.single { it.id == a }).join()
        assertEquals(1, vm.state.value.scheduledTransfers?.size, "A's transfers load first")

        gateway.fail += savings
        vm.loadScheduledTransfers(vm.state.value.accounts.single { it.id == b }).join()

        assertEquals(b, vm.state.value.scheduledTransfersAccount)
        assertNull(vm.state.value.scheduledTransfers, "A's transfers must not stay listed under B after a cancelled prompt")
        vm.close()
    }

    @Test
    fun `a failed reload of the same account keeps its standing orders`() = runBlocking {
        val a = data.account(giro)
        gateway.standing[giro] = listOf(standingOrder(giro))
        vm.load().join()

        vm.loadStandingOrders(vm.state.value.accounts.single { it.id == a }).join()
        gateway.fail += giro
        vm.loadStandingOrders(vm.state.value.accounts.single { it.id == a }).join()

        assertEquals(a, vm.state.value.standingOrdersAccount)
        assertEquals(1, vm.state.value.standingOrders?.size, "a reload of the same account keeps its list until the new answer arrives")
        vm.close()
    }

    @Test
    fun `a failed reload of the same account keeps its scheduled transfers`() = runBlocking {
        val a = data.account(giro)
        gateway.scheduled[giro] = listOf(scheduledTransfer(giro))
        vm.load().join()

        vm.loadScheduledTransfers(vm.state.value.accounts.single { it.id == a }).join()
        gateway.fail += giro
        vm.loadScheduledTransfers(vm.state.value.accounts.single { it.id == a }).join()

        assertEquals(a, vm.state.value.scheduledTransfersAccount)
        assertEquals(1, vm.state.value.scheduledTransfers?.size, "a reload of the same account keeps its list until the new answer arrives")
        vm.close()
    }
}
