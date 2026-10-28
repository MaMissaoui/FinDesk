package de.findesk.banking

import de.findesk.TestData
import de.findesk.TestDatabase
import de.findesk.model.PendingBookingItem
import de.findesk.repository.PendingBookingRepository
import de.findesk.repository.PendingBookingRepositoryImpl
import de.findesk.viewmodel.AccountSelection
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.kapott.hbci.GV_Result.GVRKUms
import org.kapott.hbci.structures.Konto
import org.kapott.hbci.structures.Value
import java.io.File
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Date

/**
 * Pending (vorgemerkte) bank lines: how a statement line is mapped, how a sync stores and replaces them, and
 * that they never reach the bookings, the totals, the dashboard or the CSV export. Synthetic data only.
 */
class PendingBookingsSyncTest {
    @TempDir
    lateinit var tmp: File

    private lateinit var data: TestData
    private lateinit var gateway: FakeGateway
    private lateinit var svc: BankingService
    private var bankId: Int = 0
    private val today = LocalDate.of(2026, 10, 4)
    private val giro = "DE89370400440532013000"
    private val employer = "Beispiel AG"
    private val tank = "Beispiel Tank GmbH"

    @BeforeEach
    fun setup() {
        TestDatabase.init()
        data = TestData()
        bankId = data.bankAccessRepo.create("Sparkasse KölnBonn", "37050198", "COLSDE33XXX", "demo-user").id.value
        gateway = FakeGateway()
        svc = BankingService(finTSClient = FinTSClient(gateway = gateway, passportDir = File(tmp, "passports")), today = { today })
    }

    /** Minimal gateway fake: the PIN flow plus whatever [snapshots] report. */
    private class FakeGateway : FinTSGateway {
        var snapshots: List<AccountSnapshot> = emptyList()

        override fun fetchAll(connection: FinTSConnection, interaction: BankingInteraction, since: (String) -> LocalDate?, to: LocalDate): List<AccountSnapshot> {
            interaction.pin(connection.bankName, connection.userId) ?: throw BankingCancelledException()
            return snapshots
        }

        override fun transfer(connection: FinTSConnection, interaction: BankingInteraction, fromIban: String, order: TransferOrder): FinTSClient.TransferResult = throw UnsupportedOperationException()
        override fun instantTransfer(connection: FinTSConnection, interaction: BankingInteraction, fromIban: String, order: TransferOrder): FinTSClient.TransferResult = throw UnsupportedOperationException()
    }

    private class FakeInteraction : BankingInteraction {
        override fun pin(bankName: String, userId: String, offerRemember: Boolean): PinEntry? = PinEntry("12345", false)
        override fun selectTanMethod(options: List<TanMethod>): String? = null
        override fun selectTanMedium(options: List<String>): String? = null
        override fun tan(challenge: TanChallenge): String? = null
        override fun confirmDecoupled(message: String): Boolean = true
        override fun confirmPayee(message: String): Boolean = true
    }

    private fun account(iban: String) = FinTSAccount(iban, "COLSDE33XXX", iban.takeLast(10), bankName = "Sparkasse", blz = "37050198", accountType = "checking")

    private fun booking(date: LocalDate, amount: String, partner: String?, purpose: String?) = FinTSTransaction(
        account = account(giro),
        date = date,
        valueDate = date,
        amount = BigDecimal(amount),
        partnerName = partner,
        partnerIban = null,
        partnerBic = null,
        purpose = purpose,
        transactionCode = null,
        reference = null,
    )

    private fun snapshot(balance: String, vararg txns: FinTSTransaction) = AccountSnapshot(
        account = account(giro),
        balance = FinTSBalance(account(giro), BigDecimal(balance)),
        transactions = txns.toList(),
        pending = null,
    )

    /** The pending rows of [accountId] without the fetch stamp on the row (it is the sync's own `now`). */
    private fun stored(pending: PendingBookingRepository, accountId: Int): List<PendingBookingItem> = pending.findItems(accountId).map { it.copy(accountId = accountId, fetchedAt = LocalDateTime.MIN) }

    private fun sync() {
        val result = runBlocking { svc.syncBankConnection(bankId, FakeInteraction()) }
        assertTrue(result is BankingResult.Success, result.toString())
    }

    private fun item(accountId: Int, date: LocalDate, amount: String, partner: String?, purpose: String?) = PendingBookingItem(
        accountId = accountId,
        date = date,
        valueDate = date,
        amount = BigDecimal(amount),
        currency = "EUR",
        partnerName = partner,
        purpose = purpose,
        fetchedAt = LocalDateTime.MIN,
    )

    @Test
    fun `a statement line maps to a transaction and a line without dates is skipped`() {
        val account = account(giro)
        val at = { d: LocalDate -> Date.from(d.atStartOfDay(ZoneId.systemDefault()).toInstant()) }
        val line = GVRKUms.UmsLine().apply {
            bdate = at(LocalDate.of(2026, 10, 20))
            valuta = at(LocalDate.of(2026, 10, 21))
            value = Value(BigDecimal("-49.80"), "EUR")
            other = Konto().apply {
                name = "Beispiel Tank GmbH"
                iban = "DE02120300000000202051"
            }
            usage = mutableListOf("Kraftstoff Musterstrasse 12")
            gvcode = "9680"
            id = "line-1"
        }
        val mapped = Hbci4JavaGateway.toTransaction(line, account)!!

        assertEquals(LocalDate.of(2026, 10, 20), mapped.date)
        assertEquals(LocalDate.of(2026, 10, 21), mapped.valueDate)
        assertEquals(0, mapped.amount.compareTo(BigDecimal("-49.80")))
        assertEquals("EUR", mapped.currency)
        assertEquals("Beispiel Tank GmbH", mapped.partnerName)
        assertEquals("Kraftstoff Musterstrasse 12", mapped.purpose)
        assertEquals("9680", mapped.transactionCode)
        assertEquals("line-1", mapped.bankId)

        // Neither booking date nor valuta: null. fetchAll maps pending lines with mapNotNull, so this is the
        // "keep what can be shown" path; the booked path (untestable without a bank) now fails loudly on such
        // a line instead, so a real booking is never dropped silently — this null return is what it relies on
        assertEquals(null, Hbci4JavaGateway.toTransaction(GVRKUms.UmsLine(), account))
        // A pending line with only a valuta (the booking date unknown) still maps, dated with the valuta
        val onlyValuta = GVRKUms.UmsLine().apply {
            valuta = line.valuta
            value = line.value
        }
        assertEquals(LocalDate.of(2026, 10, 21), Hbci4JavaGateway.toTransaction(onlyValuta, account)!!.date)
    }

    @Test
    fun `a sync stores pending lines apart from the bookings and clears them once booked`() {
        val pending = PendingBookingRepositoryImpl()
        val booked = booking(today.minusDays(3), "-120.00", employer, "Gehalt")
        val pendingLine = booking(today, "-49.80", tank, "Kraftstoff")
        gateway.snapshots = listOf(snapshot("1000.00", booked).copy(pending = listOf(pendingLine)))

        // Run 1: one booked line and one pending line
        sync()

        val accountId = data.accountRepo.findAllItems().single().id
        val items = data.transactionRepo.findItems()
        assertEquals(1, items.size, "only the booked line is a booking")
        assertEquals("-120.00", items.single().amount.toPlainString())
        assertEquals(employer, items.single().partnerName)
        assertEquals(listOf(item(accountId, today, "-49.80", tank, "Kraftstoff")), stored(pending, accountId), "the pending line is stored, not a booking")
        assertFalse(items.any { it.amount == BigDecimal("-49.80") }, "the pending line is not in the transactions table")
        assertEquals(BigDecimal("1000.00"), data.accountRepo.findAllItems().single().balance, "the stored balance is the snapshot's")

        // Run 2: the pending line has arrived booked and none are pending any more
        gateway.snapshots = listOf(snapshot("950.20", booked, pendingLine).copy(pending = emptyList()))
        sync()

        val items2 = data.transactionRepo.findItems()
        assertEquals(2, items2.size, "the ex-pending line is imported exactly once")
        assertEquals(1, items2.count { it.amount == BigDecimal("-49.80") && it.partnerName == tank })
        assertEquals(emptyList<PendingBookingItem>(), stored(pending, accountId), "fetched and none pending clears the rows")

        // Run 3: the statement job failed (pending = null): the stored rows stay
        val kept = item(accountId, today.plusDays(2), "-10.00", tank, "Kraftstoff")
        pending.replaceForAccount(accountId, listOf(kept), LocalDateTime.of(2026, 10, 4, 9, 30))
        gateway.snapshots = listOf(snapshot("950.20", booked).copy(pending = null))
        sync()
        assertEquals(listOf(kept), stored(pending, accountId), "not fetched keeps the stored rows")
    }

    @Test
    fun `pending rows never change the totals, the dashboard or the csv export`() {
        val pending = PendingBookingRepositoryImpl()
        val accountId = data.account(giro, balance = "1000.00")
        data.txn(accountId, today.minusDays(3), "-120.00", partner = employer, purpose = "Gehalt", categoryId = null)
        val vm = de.findesk.viewmodel.TransactionsViewModel(pendingRepository = pending)
        val dashboard = de.findesk.viewmodel.DashboardViewModel(today = { today })
        val totalsBefore: Map<String, BigDecimal>
        val monthsBefore: Pair<BigDecimal, BigDecimal>
        runBlocking {
            vm.start(accountId).join()
            dashboard.loadDashboard().join()
            totalsBefore = vm.state.value.selectedTotals
            monthsBefore = dashboard.state.value.let { it.monthlyIncome to it.monthlyExpenses }
        }

        pending.replaceForAccount(accountId, listOf(item(accountId, today, "-49.80", tank, "Kraftstoff")), LocalDateTime.of(2026, 10, 4, 9, 30))
        runBlocking {
            vm.loadTransactions().join()
            dashboard.loadDashboard().join()
            assertEquals(totalsBefore, vm.state.value.selectedTotals, "the totals must not count the pending line")
            assertEquals(monthsBefore, dashboard.state.value.let { it.monthlyIncome to it.monthlyExpenses }, "neither must the dashboard's month totals")

            val csv = File(tmp, "pending-export.csv")
            vm.exportCsv(csv).join()
            val text = csv.readText()
            assertTrue(text.contains(employer), text)
            assertFalse(text.contains(tank), "the CSV export never exports pending lines")
        }
    }
}
