package de.findesk.banking

import de.findesk.TestData
import de.findesk.TestDatabase
import de.findesk.data.database.DatabaseFactory
import de.findesk.i18n.AppStrings
import de.findesk.i18n.StringKey
import de.findesk.model.TransactionFilter
import de.findesk.repository.AuditLogRepositoryImpl
import de.findesk.repository.SettingsRepositoryImpl
import de.findesk.util.RunningBalance
import de.findesk.viewmodel.BankingPrompt
import de.findesk.viewmodel.UiBankingInteraction
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import org.kapott.hbci.callback.HBCICallback
import java.io.File
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.concurrent.thread

private class ScriptedInteraction(
    var pinValue: String? = "12345",
    var tanValue: String? = "999999",
    var tanMethodChoice: String? = null,
    var payeeOk: Boolean = true,
    var decoupledOk: Boolean = true,
    var remember: Boolean = false,
) : BankingInteraction {
    var pinAsked = 0
    var payeeAsked = 0
    val tanChallenges = mutableListOf<TanChallenge>()
    var offeredMethods: List<TanMethod> = emptyList()
    val pinBankNames = mutableListOf<String>()
    val pinOfferedRemember = mutableListOf<Boolean>()

    override fun pin(bankName: String, userId: String, offerRemember: Boolean): PinEntry? = pinValue?.let { PinEntry(it, remember) }.also {
        pinAsked++
        pinBankNames += bankName
        pinOfferedRemember += offerRemember
    }
    override fun selectTanMethod(options: List<TanMethod>): String? = tanMethodChoice.also { offeredMethods = options }
    override fun selectTanMedium(options: List<String>): String? = options.last()
    override fun tan(challenge: TanChallenge): String? = tanValue.also { tanChallenges += challenge }
    override fun confirmDecoupled(message: String) = decoupledOk
    override fun confirmPayee(message: String) = payeeOk.also { payeeAsked++ }
}

private class FakeGateway : FinTSGateway {
    var snapshots: List<AccountSnapshot> = emptyList()
    val sinceRequests = mutableMapOf<String, LocalDate?>()
    val transfers = mutableListOf<Pair<String, TransferOrder>>()
    var failWith: Exception? = null
    override var lastTanMethod: String? = null
    override var lastCapabilities: BankCapabilities? = null

    override fun fetchAll(connection: FinTSConnection, interaction: BankingInteraction, since: (String) -> LocalDate?, to: LocalDate): List<AccountSnapshot> {
        failWith?.let { throw it }
        // Simulate the bank asking for the PIN once per dialog
        interaction.pin(connection.bankName, connection.userId) ?: throw BankingCancelledException()
        snapshots.forEach { sinceRequests[it.account.iban] = since(it.account.iban) }
        lastTanMethod = "921"
        return snapshots
    }

    override fun transfer(connection: FinTSConnection, interaction: BankingInteraction, fromIban: String, order: TransferOrder): FinTSClient.TransferResult {
        interaction.pin(connection.bankName, connection.userId) ?: throw BankingCancelledException()
        transfers += fromIban to order
        return FinTSClient.TransferResult("job-1", "ACCEPTED")
    }

    val scheduled = mutableListOf<Triple<String, TransferOrder, LocalDate>>()
    val created = mutableListOf<Triple<String, TransferOrder, StandingOrderSchedule>>()
    val deleted = mutableListOf<StandingOrder>()
    var standingOrders: List<StandingOrder> = emptyList()

    override fun scheduledTransfer(connection: FinTSConnection, interaction: BankingInteraction, fromIban: String, order: TransferOrder, date: LocalDate): FinTSClient.TransferResult {
        interaction.pin(connection.bankName, connection.userId) ?: throw BankingCancelledException()
        scheduled += Triple(fromIban, order, date)
        return FinTSClient.TransferResult("job-2", "SCHEDULED")
    }

    override fun listStandingOrders(connection: FinTSConnection, interaction: BankingInteraction, fromIban: String): List<StandingOrder> {
        interaction.pin(connection.bankName, connection.userId) ?: throw BankingCancelledException()
        return standingOrders.filter { it.fromIban == fromIban }
    }

    override fun createStandingOrder(connection: FinTSConnection, interaction: BankingInteraction, fromIban: String, order: TransferOrder, schedule: StandingOrderSchedule): String? {
        interaction.pin(connection.bankName, connection.userId) ?: throw BankingCancelledException()
        created += Triple(fromIban, order, schedule)
        return "order-${created.size}"
    }

    override fun deleteStandingOrder(connection: FinTSConnection, interaction: BankingInteraction, existing: StandingOrder) {
        interaction.pin(connection.bankName, connection.userId) ?: throw BankingCancelledException()
        deleted += existing
    }
}

class BankingSyncTest {
    @TempDir
    lateinit var tmp: File

    private lateinit var data: TestData
    private lateinit var gateway: FakeGateway
    private lateinit var service: BankingService
    private var bankId = 0
    private val today = LocalDate.of(2026, 10, 4)
    private val giro = "DE89370400440532013000"
    private val savings = "DE44500105175407324931"

    @BeforeEach
    fun setup() {
        TestDatabase.init()
        data = TestData()
        bankId = data.bankAccessRepo.create("Sparkasse KölnBonn", "37050198", "COLSDE33XXX", "demo-user").id.value
        gateway = FakeGateway()
        val client = FinTSClient(gateway = gateway, passportDir = File(tmp, "passports"))
        service = BankingService(finTSClient = client, today = { today })
    }

    private fun account(iban: String) = FinTSAccount(iban, "COLSDE33XXX", iban.takeLast(10), bankName = "Sparkasse", blz = "37050198", accountType = "checking")

    private fun booking(iban: String, date: LocalDate, amount: String, partner: String?, purpose: String?, balanceAfter: String? = null) = FinTSTransaction(
        account = account(iban), date = date, valueDate = date, amount = BigDecimal(amount), partnerName = partner,
        partnerIban = null, partnerBic = null, purpose = purpose, transactionCode = null, reference = null,
        balanceAfter = balanceAfter?.let(::BigDecimal),
    )

    private fun snapshot(iban: String, balance: String, vararg txns: FinTSTransaction) = AccountSnapshot(account(iban), FinTSBalance(account(iban), BigDecimal(balance)), txns.toList())

    @Test
    fun `first sync creates accounts, sets balances, imports and categorizes`() = runBlocking {
        val food = data.category("Groceries")
        data.ruleRepo.create("rewe", food)
        gateway.snapshots = listOf(
            snapshot(
                giro,
                "1234.56",
                booking(giro, today.minusDays(3), "-4.50", "REWE", "Kaffee", balanceAfter = "1239.06"),
                // Identical booking the same day: must not be dropped as a duplicate
                booking(giro, today.minusDays(3), "-4.50", "REWE", "Kaffee", balanceAfter = "1234.56"),
            ),
            snapshot(savings, "10000.00"),
        )
        val interaction = ScriptedInteraction()

        val result = service.syncBankConnection(bankId, interaction)

        val report = (result as BankingResult.Success).data
        assertEquals(2, report.accountsSynced)
        assertEquals(2, report.accountsAdded)
        assertEquals(2, report.imported)
        assertEquals(2, report.categorized)
        assertEquals(1, interaction.pinAsked, "one dialog, one PIN prompt")
        assertEquals(today.minusDays(90), gateway.sinceRequests[giro])

        val accounts = data.accountRepo.findAllItems()
        assertEquals(BigDecimal("1234.56"), accounts.first { it.iban == giro }.balance)
        assertEquals(2, data.transactionRepo.findItems().count { it.categoryName == "Groceries" })
        val bank = data.bankAccessRepo.findItem(bankId)!!
        assertNotNull(bank.lastSync)
        assertEquals("921", bank.tanMethod, "chosen TAN method is remembered")
    }

    @Test
    fun `same-day bookings keep their running balances and chain without a mismatch`() = runBlocking {
        gateway.snapshots = listOf(
            snapshot(
                giro,
                "40.00",
                booking(giro, today, "-10.00", "Shop", "A", balanceAfter = "90.00"),
                booking(giro, today, "-20.00", "Shop", "B", balanceAfter = "70.00"),
                booking(giro, today, "-30.00", "Shop", "C", balanceAfter = "40.00"),
            ),
        )
        service.syncBankConnection(bankId, ScriptedInteraction())

        val account = data.accountRepo.findAllItems().single { it.iban == giro }
        val items = data.transactionRepo.findItems(TransactionFilter(accountId = account.id))
        // date DESC, id DESC: the newest same-day booking (highest id) comes first and carries the account balance
        assertEquals(listOf(BigDecimal("40.00"), BigDecimal("70.00"), BigDecimal("90.00")), items.map { it.balanceAfter })

        val entries = RunningBalance.compute(account.balance, items)
        assertEquals(items.size, entries.size)
        assertTrue(entries.values.all { it.bankDifference == null }, "the stored bank balances match the reconstructed chain")
        assertEquals(BigDecimal("40.00"), entries[items.first().id]!!.balance, "the newest row is the account balance")
    }

    @Test
    fun `existing databases get the new balance_after column`() {
        val dir = File(tmp, "old-balance").apply { mkdirs() }
        val db = Database.connect("jdbc:sqlite:${File(dir, "findesk.db").absolutePath}", driver = "org.sqlite.JDBC")
        transaction(db) {
            exec("CREATE TABLE transactions (id INTEGER PRIMARY KEY AUTOINCREMENT, account_id INTEGER NOT NULL, date TEXT NOT NULL, amount DECIMAL(15,2) NOT NULL, currency VARCHAR(3) DEFAULT 'EUR' NOT NULL, partner_name VARCHAR(255) NULL, purpose TEXT NULL, category_id INTEGER NULL, document_id INTEGER NULL, hash VARCHAR(64) NOT NULL, created_at TEXT NOT NULL)")
            exec("INSERT INTO transactions (account_id, date, amount, hash, created_at) VALUES (1, '2026-10-01', -10.00, 'h1', '2026-10-01 10:00:00')")
        }
        TransactionManager.closeAndUnregister(db)

        DatabaseFactory.init(dir.absolutePath)

        val rows = transaction { de.findesk.data.entity.Transaction.all().map { it.id.value to it.balanceAfter } }
        assertEquals(listOf(1 to null), rows, "the old row is kept and the new column is null")
    }

    @Test
    fun `sync stores the login's supported transactions`() = runBlocking {
        gateway.lastCapabilities = BankCapabilities(setOf(BankCapabilities.JOB_BALANCE, BankCapabilities.JOB_STANDING_LIST))
        gateway.snapshots = listOf(snapshot(giro, "100.00"))

        service.syncBankConnection(bankId, ScriptedInteraction())

        val stored = BankCapabilities.parse(data.bankAccessRepo.findItem(bankId)!!.capabilities)
        assertNotNull(stored)
        assertTrue(stored!!.supportsBalance)
        assertTrue(stored.supportsStandingOrders)
        assertFalse(stored.supportsScheduledTransfer)
    }

    @Test
    fun `operations a known login does not offer are refused locally`() = runBlocking {
        // A login that reported only balance access when it synced
        data.bankAccessRepo.updateCapabilities(bankId, BankCapabilities.JOB_BALANCE)
        gateway.snapshots = listOf(snapshot(giro, "500.00"))
        service.syncBankConnection(bankId, ScriptedInteraction())
        val accountId = data.accountRepo.findAllItems().single().id
        val order = TransferOrder("Vermieter GmbH", "DE44500105175407324931", null, BigDecimal("850.00"), "Miete")
        val monthly = StandingOrderSchedule.of(LocalDate.of(2026, 11, 1), StandingOrderInterval.MONTHLY, 1)

        val scheduled = service.scheduleTransfer(accountId, order, today.plusDays(5), ScriptedInteraction(), confirmed = true)
        assertEquals("INVALID", (scheduled as BankingResult.Error).code)
        assertEquals(AppStrings[StringKey.ErrorOrderNotOffered], scheduled.message)

        val created = service.createStandingOrder(accountId, order, monthly, ScriptedInteraction(), confirmed = true)
        assertEquals("INVALID", (created as BankingResult.Error).code)

        val listed = service.loadStandingOrders(accountId, ScriptedInteraction())
        assertEquals("UNSUPPORTED", (listed as BankingResult.Error).code)

        assertTrue(gateway.scheduled.isEmpty() && gateway.created.isEmpty(), "nothing may reach a bank that doesn't offer it")

        // An immediate transfer is not gated by capabilities: it stays the bank's decision
        assertTrue(service.initiateTransfer(accountId, order, ScriptedInteraction(), confirmed = true) is BankingResult.Success)
    }

    @Test
    fun `payments view model exposes known capabilities for gating`() = runBlocking {
        data.bankAccessRepo.updateCapabilities(bankId, "${BankCapabilities.JOB_BALANCE},${BankCapabilities.JOB_SCHEDULED_TRANSFER}")
        gateway.snapshots = listOf(snapshot(giro, "500.00"))
        service.syncBankConnection(bankId, ScriptedInteraction())

        val vm = de.findesk.viewmodel.PaymentsViewModel(bankingService = service)
        vm.load().join()

        val caps = vm.state.value.bankCapabilities[bankId]
        assertNotNull(caps)
        assertTrue(caps!!.supportsScheduledTransfer)
        assertFalse(caps.supportsStandingOrders, "the screen hides standing orders this login does not offer")
        vm.close()
    }

    @Test
    fun `second sync overlaps the last one and skips known bookings`() = runBlocking {
        val first = booking(giro, today.minusDays(3), "-20.00", "Shop", "A", balanceAfter = "980.00")
        gateway.snapshots = listOf(snapshot(giro, "980.00", first))
        service.syncBankConnection(bankId, ScriptedInteraction())

        // pretend the last sync was 5 days ago
        transaction {
            de.findesk.data.entity.BankAccess.findById(bankId)!!.lastSync = LocalDateTime.of(2026, 9, 29, 12, 0)
        }
        gateway.snapshots = listOf(snapshot(giro, "950.00", first, booking(giro, today, "-30.00", "Shop", "B", balanceAfter = "950.00")))
        val report = (service.syncBankConnection(bankId, ScriptedInteraction()) as BankingResult.Success).data

        assertEquals(LocalDate.of(2026, 9, 19), gateway.sinceRequests[giro])
        assertEquals(1, report.imported)
        assertEquals(1, report.duplicates)
        assertEquals(0, report.accountsAdded)
        assertEquals(BigDecimal("950.00"), data.accountRepo.findAllItems().single().balance)
    }

    @Test
    fun `failed statements keep the old sync date so the window is fetched again`() = runBlocking {
        gateway.snapshots = listOf(snapshot(giro, "100.00"))
        service.syncBankConnection(bankId, ScriptedInteraction())
        val previous = LocalDateTime.of(2026, 9, 1, 8, 0)
        transaction { de.findesk.data.entity.BankAccess.findById(bankId)!!.lastSync = previous }

        gateway.snapshots = listOf(snapshot(giro, "100.00").copy(statementError = "9010 Auftrag abgelehnt"))
        val report = (service.syncBankConnection(bankId, ScriptedInteraction()) as BankingResult.Success).data

        assertEquals(listOf("3000: 9010 Auftrag abgelehnt"), report.warnings)
        assertEquals(previous, data.bankAccessRepo.findItem(bankId)!!.lastSync)
    }

    @Test
    fun `a chosen history start reaches known and new accounts and is cleared after a successful sync`() = runBlocking {
        gateway.snapshots = listOf(snapshot(giro, "100.00"))
        service.syncBankConnection(bankId, ScriptedInteraction())

        val from = today.minusDays(400)
        data.bankAccessRepo.setSyncFrom(bankId, from)
        gateway.snapshots = listOf(
            snapshot(giro, "95.00", booking(giro, from.plusDays(1), "-5.00", "Old Shop", "A", balanceAfter = "95.00")),
            snapshot(savings, "200.00", booking(savings, from.plusDays(2), "-2.00", "Old Shop", "B", balanceAfter = "198.00")),
        )
        val report = (service.syncBankConnection(bankId, ScriptedInteraction()) as BankingResult.Success).data

        assertEquals(from, gateway.sinceRequests[giro], "a known account uses the chosen date, not lastSync")
        assertEquals(from, gateway.sinceRequests[savings], "a new account uses the chosen date, not the 90-day default")
        assertEquals(from, report.historyFrom)
        assertEquals(2, report.imported)
        assertNull(data.bankAccessRepo.findItem(bankId)!!.syncFrom)
    }

    @Test
    fun `a chosen history start after the last sync never narrows the window`() = runBlocking {
        gateway.snapshots = listOf(snapshot(giro, "100.00"))
        service.syncBankConnection(bankId, ScriptedInteraction())
        transaction { de.findesk.data.entity.BankAccess.findById(bankId)!!.lastSync = LocalDateTime.of(2026, 9, 4, 12, 0) }

        // Starting at yesterday would skip everything booked since the last sync, yet lastSync would still advance
        data.bankAccessRepo.setSyncFrom(bankId, today.minusDays(1))
        service.syncBankConnection(bankId, ScriptedInteraction())

        assertEquals(LocalDate.of(2026, 8, 25), gateway.sinceRequests[giro], "last sync minus the overlap")
    }

    @Test
    fun `re-fetching bookings that were imported before imports none of them`() = runBlocking {
        val existing = booking(giro, today.minusDays(3), "-20.00", "Shop", "A", balanceAfter = "980.00")
        gateway.snapshots = listOf(snapshot(giro, "980.00", existing))
        service.syncBankConnection(bankId, ScriptedInteraction())

        data.bankAccessRepo.setSyncFrom(bankId, today.minusDays(400))
        gateway.snapshots = listOf(snapshot(giro, "980.00", existing))
        val report = (service.syncBankConnection(bankId, ScriptedInteraction()) as BankingResult.Success).data

        assertEquals(0, report.imported)
        assertEquals(1, report.duplicates)
    }

    @Test
    fun `a warning, an error or a cancel keeps the chosen history start for a retry`() = runBlocking {
        val from = today.minusDays(400)
        data.bankAccessRepo.setSyncFrom(bankId, from)

        gateway.snapshots = listOf(snapshot(giro, "100.00").copy(statementError = "9010 Auftrag abgelehnt"))
        service.syncBankConnection(bankId, ScriptedInteraction())
        assertEquals(from, data.bankAccessRepo.findItem(bankId)!!.syncFrom, "a statement warning keeps it")

        gateway.snapshots = emptyList()
        gateway.failWith = RuntimeException("9931 PIN gesperrt")
        service.syncBankConnection(bankId, ScriptedInteraction())
        assertEquals(from, data.bankAccessRepo.findItem(bankId)!!.syncFrom, "an error keeps it")

        gateway.failWith = null
        gateway.snapshots = listOf(snapshot(giro, "100.00"))
        service.syncBankConnection(bankId, ScriptedInteraction(pinValue = null))
        assertEquals(from, data.bankAccessRepo.findItem(bankId)!!.syncFrom, "a cancel keeps it")
    }

    @Test
    fun `existing databases get the new sync_from column`() {
        val dir = File(tmp, "old-sync-from").apply { mkdirs() }
        val db = Database.connect("jdbc:sqlite:${File(dir, "findesk.db").absolutePath}", driver = "org.sqlite.JDBC")
        transaction(db) {
            exec("CREATE TABLE bank_access (id INTEGER PRIMARY KEY AUTOINCREMENT, bank_name VARCHAR(255) NOT NULL, blz VARCHAR(20) NOT NULL, bic VARCHAR(20) NOT NULL, user_id VARCHAR(100) NOT NULL, hbci_version VARCHAR(10) DEFAULT '300' NOT NULL, tan_method VARCHAR(50) NULL, last_sync TEXT NULL)")
            exec("INSERT INTO bank_access (bank_name, blz, bic, user_id) VALUES ('Old Bank', '37050198', '', 'u')")
        }
        TransactionManager.closeAndUnregister(db)

        DatabaseFactory.init(dir.absolutePath)

        val bank = data.bankAccessRepo.findAllItems().single()
        assertEquals("Old Bank", bank.bankName)
        assertNull(bank.syncFrom)
        data.bankAccessRepo.setSyncFrom(bank.id, LocalDate.of(2025, 1, 3))
        assertEquals(LocalDate.of(2025, 1, 3), data.bankAccessRepo.findItem(bank.id)!!.syncFrom)
    }

    @Test
    fun `confirming twice sends one transfer`() = runBlocking {
        gateway.snapshots = listOf(snapshot(giro, "500.00"))
        service.syncBankConnection(bankId, ScriptedInteraction())
        val release = java.util.concurrent.CountDownLatch(1)
        val slowGateway = object : FinTSGateway by gateway {
            override fun transfer(connection: FinTSConnection, interaction: BankingInteraction, fromIban: String, order: TransferOrder): FinTSClient.TransferResult {
                release.await(5, java.util.concurrent.TimeUnit.SECONDS)
                return gateway.transfer(connection, ScriptedInteraction(), fromIban, order)
            }
        }
        val slowService = BankingService(finTSClient = FinTSClient(gateway = slowGateway, passportDir = File(tmp, "p2")), today = { today })
        val vm = de.findesk.viewmodel.PaymentsViewModel(bankingService = slowService)
        vm.load().join()
        vm.review(vm.state.value.accounts.single(), TransferOrder("Max Mustermann", savings, null, BigDecimal("10.00"), "Test"))

        val first = vm.confirmAndSend()
        val second = vm.confirmAndSend() // double click while the first is in flight
        release.countDown()
        first.join()
        second.join()

        assertEquals(1, gateway.transfers.size)
        assertEquals("Transfer to Max Mustermann sent", vm.state.value.message)
        vm.close()
    }

    @Test
    fun `payments screen sends standing orders and scheduled transfers only after review`() = runBlocking {
        gateway.snapshots = listOf(snapshot(giro, "500.00"))
        service.syncBankConnection(bankId, ScriptedInteraction())
        // The view model's interaction shows dialogs; answer the PIN here instead
        val scripted = object : FinTSGateway by gateway {
            override fun scheduledTransfer(connection: FinTSConnection, interaction: BankingInteraction, fromIban: String, order: TransferOrder, date: LocalDate) = gateway.scheduledTransfer(connection, ScriptedInteraction(), fromIban, order, date)
            override fun createStandingOrder(connection: FinTSConnection, interaction: BankingInteraction, fromIban: String, order: TransferOrder, schedule: StandingOrderSchedule) = gateway.createStandingOrder(connection, ScriptedInteraction(), fromIban, order, schedule)
        }
        val vm = de.findesk.viewmodel.PaymentsViewModel(bankingService = BankingService(finTSClient = FinTSClient(gateway = scripted, passportDir = File(tmp, "p3")), today = { today }))
        vm.load().join()
        val account = vm.state.value.accounts.single()
        val order = TransferOrder("Vermieter GmbH", savings, null, BigDecimal("850.00"), "Miete")

        vm.review(account, order, date = today)
        assertNull(vm.state.value.review, "a date that isn't in the future never reaches the review")
        assertEquals("The execution date must be after today", vm.state.value.error)

        val monthly = StandingOrderSchedule.of(LocalDate.of(2026, 11, 1), StandingOrderInterval.MONTHLY, 1)
        vm.review(account, order, schedule = monthly)
        assertTrue(gateway.created.isEmpty(), "reviewing sends nothing")
        vm.confirmAndSend().join()
        assertEquals(Triple(giro, order, monthly), gateway.created.single())
        assertEquals("Standing order to Vermieter GmbH set up", vm.state.value.message)

        vm.review(account, order, date = today.plusDays(3))
        vm.confirmAndSend().join()
        assertEquals(today.plusDays(3), gateway.scheduled.single().third)
        assertTrue(gateway.transfers.isEmpty(), "a scheduled transfer is not sent as an immediate one")
        assertEquals(listOf("SCHEDULED_TRANSFER", "STANDING_ORDER_CREATE").sorted(), vm.state.value.paymentOrders.map { it.action }.sorted())
        vm.close()
    }

    @Test
    fun `cancelled PIN and bank errors are reported, nothing imported`() = runBlocking {
        gateway.snapshots = listOf(snapshot(giro, "1.00", booking(giro, today, "-1.00", "X", "Y")))
        val cancelled = service.syncBankConnection(bankId, ScriptedInteraction(pinValue = null))
        assertEquals("CANCELLED", (cancelled as BankingResult.Error).code)

        gateway.failWith = IllegalStateException("9931 PIN gesperrt")
        val failed = service.syncBankConnection(bankId, ScriptedInteraction())
        assertEquals("9931 PIN gesperrt", (failed as BankingResult.Error).message)
        assertTrue(data.transactionRepo.findItems().isEmpty())
    }

    @Test
    fun `sync needs a user id and a known server`() = runBlocking {
        val noUser = data.bankAccessRepo.create("Sparkasse", "37050198", "", "").id.value
        assertEquals("NO_USER", (service.syncBankConnection(noUser, ScriptedInteraction()) as BankingResult.Error).code)
        val unknownBank = data.bankAccessRepo.create("Tiny Bank", "99999999", "", "u").id.value
        assertEquals("NO_SERVER", (service.syncBankConnection(unknownBank, ScriptedInteraction()) as BankingResult.Error).code)
        data.bankAccessRepo.updateConnection(unknownBank, serverUrl = "https://fints.tiny.example/pintan")
        gateway.snapshots = emptyList()
        assertTrue(service.syncBankConnection(unknownBank, ScriptedInteraction()) is BankingResult.Success)
    }

    @Test
    fun `transfers require confirmation and valid data`() = runBlocking {
        gateway.snapshots = listOf(snapshot(giro, "500.00"))
        service.syncBankConnection(bankId, ScriptedInteraction())
        val accountId = data.accountRepo.findAllItems().single().id
        val order = TransferOrder("Max Mustermann", "de44 5001 0517 5407 3249 31", null, BigDecimal("25.00"), "Miete")

        val unconfirmed = service.initiateTransfer(accountId, order, ScriptedInteraction(), confirmed = false)
        assertEquals("NOT_CONFIRMED", (unconfirmed as BankingResult.Error).code)
        assertTrue(gateway.transfers.isEmpty(), "nothing may be sent without confirmation")

        val invalid = service.initiateTransfer(accountId, order.copy(recipientIban = "DE00123"), ScriptedInteraction(), confirmed = true)
        assertEquals("INVALID", (invalid as BankingResult.Error).code)
        assertEquals("Amount must be greater than zero", service.validateTransfer(order.copy(amount = BigDecimal.ZERO)))
        assertTrue(gateway.transfers.isEmpty())

        val sent = service.initiateTransfer(accountId, order, ScriptedInteraction(), confirmed = true)
        assertTrue(sent is BankingResult.Success)
        assertEquals(giro to order.copy(recipientIban = "DE44500105175407324931"), gateway.transfers.single())
        assertTrue(AuditLogRepositoryImpl().findByAction("TRANSFER").any { it.details!!.contains("sent") })
    }

    @Test
    fun `a renamed bank connection still syncs, prompts with the new name and sends transfers`() = runBlocking {
        assertEquals("DKB Business", data.bankAccessRepo.updateConnection(bankId, bankName = "DKB Business")!!.bankName)
        gateway.snapshots = listOf(snapshot(giro, "500.00", booking(giro, today, "-30.00", "Shop", "B", balanceAfter = "470.00")))
        val interaction = ScriptedInteraction()

        val report = (service.syncBankConnection(bankId, interaction) as BankingResult.Success).data

        assertEquals(1, report.accountsAdded)
        assertEquals(1, report.imported)
        assertEquals(listOf("DKB Business"), interaction.pinBankNames, "the PIN prompt shows the new name")

        val accountId = data.accountRepo.findAllItems().single().id
        val order = TransferOrder("Max Mustermann", savings, null, BigDecimal("25.00"), "Miete")
        assertTrue(service.initiateTransfer(accountId, order, ScriptedInteraction(), confirmed = true) is BankingResult.Success)
        assertEquals(giro to order, gateway.transfers.single())

        // The name is only a label: the FinTS details are untouched by the rename
        val bank = data.bankAccessRepo.findItem(bankId)!!
        assertEquals("DKB Business", bank.bankName)
        assertEquals("37050198", bank.blz)
        assertEquals("COLSDE33XXX", bank.bic)
        assertEquals("demo-user", bank.userId)
    }

    /** A service whose gateway runs [transfer] (after asking for the PIN) instead of the fake's own transfer. */
    private fun serviceWithTransfer(name: String, transfer: (TransferOrder) -> FinTSClient.TransferResult): BankingService {
        val scripted = object : FinTSGateway by gateway {
            override fun transfer(connection: FinTSConnection, interaction: BankingInteraction, fromIban: String, order: TransferOrder): FinTSClient.TransferResult {
                ScriptedInteraction().pin(connection.bankName, connection.userId)
                return transfer(order)
            }
        }
        return BankingService(finTSClient = FinTSClient(gateway = scripted, passportDir = File(tmp, name)), today = { today })
    }

    @Test
    fun `an order whose dialog breaks off is recorded as outcome unknown, and the review closes`() = runBlocking {
        gateway.snapshots = listOf(snapshot(giro, "500.00"))
        service.syncBankConnection(bankId, ScriptedInteraction())
        val order = TransferOrder("Max Mustermann", savings, null, BigDecimal("10.00"), "Test")
        // The bank may have executed it before the answer was lost
        val vm = de.findesk.viewmodel.PaymentsViewModel(bankingService = serviceWithTransfer("p-unknown") { throw IllegalStateException("Read timed out") })
        vm.load().join()
        val account = vm.state.value.accounts.single()

        vm.review(account, order)
        assertNull(vm.state.value.review!!.unconfirmed)
        vm.confirmAndSend().join()

        assertNull(vm.state.value.review, "an unknown outcome must not leave a ready-to-send review open")
        assertTrue(vm.state.value.error!!.contains("may have been executed"), vm.state.value.error)
        assertEquals(de.findesk.model.PaymentOrderStatus.UNKNOWN, vm.state.value.paymentOrders.single().status)
        assertTrue(AuditLogRepositoryImpl().findByAction("TRANSFER").any { it.details!!.contains("outcome unknown") })

        // Sending it again takes a fresh review, which points at the unconfirmed order
        vm.review(account, order.copy(recipientIban = savings.lowercase()))
        assertEquals(vm.state.value.paymentOrders.single().id, vm.state.value.review!!.unconfirmed?.id)
        vm.review(account, order.copy(amount = BigDecimal("11.00")))
        assertNull(vm.state.value.review!!.unconfirmed, "a different amount is a different order")
        vm.close()
    }

    @Test
    fun `a definite rejection is recorded as rejected and keeps the review for a corrected retry`() = runBlocking {
        gateway.snapshots = listOf(snapshot(giro, "500.00"))
        service.syncBankConnection(bankId, ScriptedInteraction())
        val vm = de.findesk.viewmodel.PaymentsViewModel(bankingService = serviceWithTransfer("p-rejected") { throw OrderNotExecutedException("9050 Empfänger-IBAN ungültig") })
        vm.load().join()

        vm.review(vm.state.value.accounts.single(), TransferOrder("Max Mustermann", savings, null, BigDecimal("10.00"), "Test"))
        vm.confirmAndSend().join()

        assertNotNull(vm.state.value.review)
        assertEquals("9050 Empfänger-IBAN ungültig", vm.state.value.error)
        assertEquals(de.findesk.model.PaymentOrderStatus.REJECTED, vm.state.value.paymentOrders.single().status)
        vm.close()
    }

    @Test
    fun `closing the payments screen mid-send still records the bank's answer`() = runBlocking {
        gateway.snapshots = listOf(snapshot(giro, "500.00"))
        service.syncBankConnection(bankId, ScriptedInteraction())
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val vm = de.findesk.viewmodel.PaymentsViewModel(
            bankingService = serviceWithTransfer("p-close") { order ->
                entered.countDown()
                release.await(5, java.util.concurrent.TimeUnit.SECONDS)
                gateway.transfers += giro to order
                FinTSClient.TransferResult("job-9", "ACCEPTED")
            },
        )
        vm.load().join()
        vm.review(vm.state.value.accounts.single(), TransferOrder("Max Mustermann", savings, null, BigDecimal("10.00"), "Test"))

        vm.confirmAndSend()
        assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
        vm.close() // the user navigates away while the bank works on the order
        release.countDown()

        // The audit line is the last thing the send writes, after the order's status
        fun audited() = AuditLogRepositoryImpl().findByAction("TRANSFER").any { it.details!!.contains("sent") }
        val deadline = System.currentTimeMillis() + 5000
        while (!audited() && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertTrue(audited())
        val recorded = de.findesk.repository.PaymentOrderRepositoryImpl().findRecentItems().single()
        assertEquals(de.findesk.model.PaymentOrderStatus.ACCEPTED, recorded.status)
        assertEquals("job-9", recorded.bankOrderId)
        assertEquals(1, gateway.transfers.size)
    }

    @Test
    fun `an order the bank accepted counts as sent even if the dialog failed afterwards`() {
        val accepted = org.kapott.hbci.status.HBCIStatus().apply { addRetVal(retVal("0020", "Auftrag ausgeführt")) }
        assertNull(Hbci4JavaGateway.orderOutcome(accepted, "Dialogende fehlgeschlagen"))

        val rejected = org.kapott.hbci.status.HBCIStatus().apply { addRetVal(retVal("9050", "Auftrag abgelehnt")) }
        assertTrue(Hbci4JavaGateway.orderOutcome(rejected, null) is OrderNotExecutedException)

        // No answer for the job, or an exception while it was processed: the bank may have it
        assertTrue(Hbci4JavaGateway.orderOutcome(org.kapott.hbci.status.HBCIStatus(), "Read timed out") is IllegalStateException)
        val broken = org.kapott.hbci.status.HBCIStatus().apply {
            addRetVal(retVal("9050", "Auftrag abgelehnt"))
            addException(java.io.IOException("connection reset"))
        }
        assertFalse(Hbci4JavaGateway.orderOutcome(broken, null) is OrderNotExecutedException)
        assertFalse(Hbci4JavaGateway.orderOutcome(null, null) is OrderNotExecutedException)
    }

    private fun retVal(code: String, text: String) = org.kapott.hbci.status.HBCIRetVal(null, null, null, null, null, null).apply {
        this.code = code
        this.text = text
    }

    @Test
    fun `transfers are refused from accounts that are not in EUR`() = runBlocking {
        val usd = data.accountRepo.create(bankId, "DE89370400440532013000", balance = BigDecimal("900.00"), currency = "USD").id.value
        val order = TransferOrder("Max Mustermann", "DE44500105175407324931", null, BigDecimal("25.00"), "Miete")

        val result = service.initiateTransfer(usd, order, ScriptedInteraction(), confirmed = true)

        assertEquals("CURRENCY", (result as BankingResult.Error).code)
        assertTrue(gateway.transfers.isEmpty(), "a SEPA order in EUR must not leave a USD account")
    }

    @Test
    fun `scheduled transfers need confirmation and a future date`() = runBlocking {
        gateway.snapshots = listOf(snapshot(giro, "500.00"))
        service.syncBankConnection(bankId, ScriptedInteraction())
        val accountId = data.accountRepo.findAllItems().single().id
        val order = TransferOrder("Max Mustermann", "DE44500105175407324931", null, BigDecimal("25.00"), "Miete")

        assertEquals("NOT_CONFIRMED", (service.scheduleTransfer(accountId, order, today.plusDays(5), ScriptedInteraction(), confirmed = false) as BankingResult.Error).code)
        assertEquals("INVALID", (service.scheduleTransfer(accountId, order, today, ScriptedInteraction(), confirmed = true) as BankingResult.Error).code)
        assertEquals("INVALID", (service.scheduleTransfer(accountId, order.copy(amount = BigDecimal.ZERO), today.plusDays(5), ScriptedInteraction(), confirmed = true) as BankingResult.Error).code)
        assertTrue(gateway.scheduled.isEmpty())
        // Rejected attempts are audit-logged too, not just the sent ones
        assertTrue(AuditLogRepositoryImpl().findByAction("SCHEDULED_TRANSFER").any { it.details!!.contains("failed: The order was not confirmed") })

        assertTrue(service.scheduleTransfer(accountId, order, today.plusDays(5), ScriptedInteraction(), confirmed = true) is BankingResult.Success)
        assertEquals(Triple(giro, order, today.plusDays(5)), gateway.scheduled.single())
        assertTrue(AuditLogRepositoryImpl().findByAction("SCHEDULED_TRANSFER").any { it.details!!.contains("on ${today.plusDays(5)}: sent") })
    }

    @Test
    fun `standing orders are validated, confirmed and audit-logged`() = runBlocking {
        gateway.snapshots = listOf(snapshot(giro, "500.00"))
        service.syncBankConnection(bankId, ScriptedInteraction())
        val accountId = data.accountRepo.findAllItems().single().id
        val order = TransferOrder("Vermieter GmbH", "DE44500105175407324931", null, BigDecimal("850.00"), "Miete")
        val monthly = StandingOrderSchedule.of(LocalDate.of(2026, 11, 1), StandingOrderInterval.MONTHLY, 1)

        assertEquals("NOT_CONFIRMED", (service.createStandingOrder(accountId, order, monthly, ScriptedInteraction(), confirmed = false) as BankingResult.Error).code)
        listOf(
            monthly.copy(firstDate = today),
            monthly.copy(executionDay = 32),
            monthly.copy(lastDate = LocalDate.of(2026, 10, 31)),
            StandingOrderSchedule.of(LocalDate.of(2026, 11, 2), StandingOrderInterval.WEEKLY, 8),
            monthly.copy(step = 2),
        ).forEach { bad ->
            assertEquals("INVALID", (service.createStandingOrder(accountId, order, bad, ScriptedInteraction(), confirmed = true) as BankingResult.Error).code, bad.toString())
        }
        assertTrue(gateway.created.isEmpty(), "nothing reaches the bank without a valid, confirmed order")

        val created = service.createStandingOrder(accountId, order, monthly, ScriptedInteraction(), confirmed = true)
        assertEquals("order-1", (created as BankingResult.Success).data)
        assertEquals(Triple(giro, order, monthly), gateway.created.single())
        assertTrue(AuditLogRepositoryImpl().findByAction("STANDING_ORDER_CREATE").any { it.details!!.contains("monthly from 2026-11-01 on day 1: sent") })

        // Listing is read-only and needs no confirmation
        val existing = StandingOrder("order-1", giro, order, monthly, nextDate = LocalDate.of(2026, 11, 1))
        gateway.standingOrders = listOf(existing, existing.copy(orderId = "other", fromIban = savings))
        assertEquals(listOf(existing), (service.loadStandingOrders(accountId, ScriptedInteraction()) as BankingResult.Success).data)

        assertEquals("NOT_CONFIRMED", (service.deleteStandingOrder(accountId, existing, ScriptedInteraction(), confirmed = false) as BankingResult.Error).code)
        assertEquals("INVALID", (service.deleteStandingOrder(accountId, existing.copy(canDelete = false), ScriptedInteraction(), confirmed = true) as BankingResult.Error).code)
        assertEquals("INVALID", (service.deleteStandingOrder(accountId, existing.copy(fromIban = savings), ScriptedInteraction(), confirmed = true) as BankingResult.Error).code)
        assertTrue(gateway.deleted.isEmpty())
        assertTrue(service.deleteStandingOrder(accountId, existing, ScriptedInteraction(), confirmed = true) is BankingResult.Success)
        assertEquals(listOf(existing), gateway.deleted)
        assertTrue(AuditLogRepositoryImpl().findByAction("STANDING_ORDER_DELETE").any { it.details!!.contains("(id order-1)") })
    }

    @Test
    fun `standing orders are refused from accounts that are not in EUR`() = runBlocking {
        val usd = data.accountRepo.create(bankId, "DE89370400440532013000", currency = "USD").id.value
        val order = TransferOrder("Vermieter GmbH", "DE44500105175407324931", null, BigDecimal("850.00"), "Miete")
        val result = service.createStandingOrder(usd, order, StandingOrderSchedule.of(LocalDate.of(2026, 11, 1), StandingOrderInterval.MONTHLY, 1), ScriptedInteraction(), confirmed = true)
        assertEquals("CURRENCY", (result as BankingResult.Error).code)
        assertTrue(gateway.created.isEmpty())
    }

    @Test
    fun `passport passphrase is generated once and kept`() {
        val client = FinTSClient(gateway = gateway, passportDir = File(tmp, "p"))
        val bank = data.bankAccessRepo.findItem(bankId)!!
        val c1 = (client.connectionFor(bank) as BankingResult.Success).data
        val c2 = (client.connectionFor(bank) as BankingResult.Success).data
        assertEquals(c1.passportPassphrase, c2.passportPassphrase)
        assertTrue(c1.passportPassphrase.length >= 24)
        assertEquals("https://banking-rl7.s-fints-pt-rl.de/fints30", c1.serverUrl, "from bundled BLZ directory")
        assertNotNull(SettingsRepositoryImpl().getValue("fints.passport.$bankId"))
    }

    @Test
    fun `bank directory knows german banks`() {
        val entry = BankDirectory.lookup("37050198")!!
        assertEquals("Sparkasse KölnBonn", entry.name)
        assertEquals("COLSDE33XXX", entry.bic)
        assertNull(BankDirectory.lookup("00000000"))
    }

    @Test
    fun `existing databases get the new server_url column`() {
        val dir = File(tmp, "old").apply { mkdirs() }
        val db = Database.connect("jdbc:sqlite:${File(dir, "findesk.db").absolutePath}", driver = "org.sqlite.JDBC")
        transaction(db) {
            exec("CREATE TABLE bank_access (id INTEGER PRIMARY KEY AUTOINCREMENT, bank_name VARCHAR(255) NOT NULL, blz VARCHAR(20) NOT NULL, bic VARCHAR(20) NOT NULL, user_id VARCHAR(100) NOT NULL, hbci_version VARCHAR(10) DEFAULT '300' NOT NULL, tan_method VARCHAR(50) NULL, last_sync TEXT NULL)")
            exec("INSERT INTO bank_access (bank_name, blz, bic, user_id) VALUES ('Old Bank', '37050198', '', 'u')")
        }
        TransactionManager.closeAndUnregister(db)

        DatabaseFactory.init(dir.absolutePath)

        val bank = data.bankAccessRepo.findAllItems().single()
        assertEquals("Old Bank", bank.bankName)
        assertNull(bank.serverUrl)
        data.bankAccessRepo.updateConnection(bank.id, serverUrl = "https://x.example/fints")
        assertEquals("https://x.example/fints", data.bankAccessRepo.findItem(bank.id)!!.serverUrl)
    }

    @Test
    fun `a sync updates the balance and imports bookings but keeps the account's name`() = runBlocking {
        gateway.snapshots = listOf(snapshot(giro, "100.00"))
        service.syncBankConnection(bankId, ScriptedInteraction())
        val accountId = data.accountRepo.findAllItems().single().id
        data.accountRepo.rename(accountId, "Haushalt")

        gateway.snapshots = listOf(snapshot(giro, "120.00", booking(giro, today, "-5.00", "Shop", "A", balanceAfter = "120.00")))
        val report = (service.syncBankConnection(bankId, ScriptedInteraction()) as BankingResult.Success).data

        assertEquals(1, report.imported)
        val stored = data.accountRepo.findAllItems().single()
        assertEquals("Haushalt", stored.name, "a sync must not overwrite the user's name")
        assertEquals(BigDecimal("120.00"), stored.balance)
    }
}

class HbciCallbackTest {
    private val connection = FinTSConnection(
        bankName = "Bank",
        blz = "37050198",
        userId = "user",
        serverUrl = "https://fints.example.de/pintan",
        passportFile = File("unused"),
        passportPassphrase = "secret-pass",
    )

    private fun ask(cb: FinDeskHbciCallback, reason: Int, msg: String = "", ret: String = ""): String {
        val buf = StringBuffer(ret)
        cb.callback(null, reason, msg, HBCICallback.TYPE_TEXT, buf)
        return buf.toString()
    }

    @Test
    fun `connection data is answered without asking the user`() {
        val ui = ScriptedInteraction()
        val cb = FinDeskHbciCallback(connection, ui)
        assertEquals("secret-pass", ask(cb, HBCICallback.NEED_PASSPHRASE_LOAD))
        assertEquals("DE", ask(cb, HBCICallback.NEED_COUNTRY))
        assertEquals("37050198", ask(cb, HBCICallback.NEED_BLZ))
        assertEquals("fints.example.de/pintan", ask(cb, HBCICallback.NEED_HOST))
        assertEquals("443", ask(cb, HBCICallback.NEED_PORT))
        assertEquals("Base64", ask(cb, HBCICallback.NEED_FILTER))
        assertEquals("user", ask(cb, HBCICallback.NEED_USERID))
        assertEquals(0, ui.pinAsked)
    }

    @Test
    fun `pin is asked once per dialog and re-asked after a wrong pin`() {
        val ui = ScriptedInteraction(pinValue = "4711")
        val cb = FinDeskHbciCallback(connection, ui)
        assertEquals("4711", ask(cb, HBCICallback.NEED_PT_PIN))
        assertEquals("4711", ask(cb, HBCICallback.NEED_PT_PIN))
        assertEquals(1, ui.pinAsked)
        ask(cb, HBCICallback.WRONG_PIN)
        ask(cb, HBCICallback.NEED_PT_PIN)
        assertEquals(2, ui.pinAsked)
    }

    @Test
    fun `tan method selection keeps the remembered method`() {
        val ui = ScriptedInteraction(tanMethodChoice = "942")
        val offered = "921:pushTAN 2.0|942:chipTAN manuell"
        assertEquals("942", ask(FinDeskHbciCallback(connection, ui), HBCICallback.NEED_PT_SECMECH, ret = offered))
        assertEquals(listOf("921", "942"), ui.offeredMethods.map { it.id })

        val remembered = FinDeskHbciCallback(connection.copy(tanMethod = "921"), ScriptedInteraction(tanMethodChoice = null))
        assertEquals("921", ask(remembered, HBCICallback.NEED_PT_SECMECH, ret = offered))
        assertEquals("921", remembered.chosenTanMethod)

        assertEquals("911", ask(FinDeskHbciCallback(connection, ScriptedInteraction()), HBCICallback.NEED_PT_SECMECH, ret = "911:smsTAN"))
    }

    @Test
    fun `tan, decoupled and payee prompts`() {
        val ui = ScriptedInteraction(tanValue = "123456")
        val cb = FinDeskHbciCallback(connection, ui)
        assertEquals("123456", ask(cb, HBCICallback.NEED_PT_TAN, msg = "Bitte TAN eingeben"))
        assertEquals("Bitte TAN eingeben", ui.tanChallenges.single().message)

        ask(cb, HBCICallback.NEED_PT_DECOUPLED, msg = "Bitte in der App freigeben")
        // A confirmed payee must be written back as "true"; HBCI4Java aborts the order on an empty buffer.
        assertEquals("true", ask(cb, HBCICallback.HAVE_VOP_RESULT, msg = "Name stimmt überein"))
        assertEquals(1, ui.payeeAsked)

        assertThrows<BankingCancelledException> {
            ask(FinDeskHbciCallback(connection, ScriptedInteraction(payeeOk = false)), HBCICallback.HAVE_VOP_RESULT, msg = "Name weicht ab")
        }
        assertThrows<BankingCancelledException> {
            ask(FinDeskHbciCallback(connection, ScriptedInteraction(decoupledOk = false)), HBCICallback.NEED_PT_DECOUPLED)
        }
        assertThrows<BankingCancelledException> {
            ask(FinDeskHbciCallback(connection, ScriptedInteraction(tanValue = null)), HBCICallback.NEED_PT_TAN)
        }
    }

    @Test
    fun `chipTAN optical challenges carry a renderable flicker code`() {
        val ui = ScriptedInteraction()
        val cb = FinDeskHbciCallback(connection, ui)
        ask(cb, HBCICallback.NEED_PT_TAN, msg = "Bitte Karte einstecken", ret = "039870110490631098765432100812345678041,00")
        assertEquals("1784011049063F059876543210041234567844312C303019", ui.tanChallenges.single().flickerCode)

        ask(cb, HBCICallback.NEED_PT_TAN, msg = "TAN per SMS", ret = "")
        assertNull(ui.tanChallenges.last().flickerCode, "no flicker for other procedures")
        assertNull(FinDeskHbciCallback.flickerFor("not a code"))
    }

    @Test
    fun `flicker renderer produces changing frames until stopped`() {
        val frames = java.util.concurrent.CopyOnWriteArrayList<String>()
        val renderer = object : org.kapott.hbci.manager.FlickerRenderer("0D85012045201998041234567855") {
            override fun paint(b1: Boolean, b2: Boolean, b3: Boolean, b4: Boolean, b5: Boolean) {
                frames += listOf(b1, b2, b3, b4, b5).joinToString("") { if (it) "1" else "0" }
            }
            override fun done(iterations: Int) {}
        }
        renderer.setFrequency(40)
        renderer.start()
        Thread.sleep(500)
        renderer.stop()
        val count = frames.size
        Thread.sleep(150)
        assertTrue(count > 5, "frames: $count")
        assertTrue(frames.toSet().size > 2, "bars change")
        assertTrue(frames.size <= count + 1, "stopped")
    }

    @Test
    fun `bank errors abort the dialog and are kept for the user`() {
        val cb = FinDeskHbciCallback(connection, ScriptedInteraction())
        assertEquals("ERR", ask(cb, HBCICallback.HAVE_ERROR, msg = "9050 Teilweise fehlerhaft"))
        assertEquals(listOf("9050 Teilweise fehlerhaft"), cb.bankMessages)
    }

    @Test
    fun `server url is converted to the host form hbci4java expects`() {
        assertEquals("banking.example.de/fints30", FinDeskHbciCallback.hostFor("https://banking.example.de/fints30"))
        assertEquals("banking.example.de/fints30", FinDeskHbciCallback.hostFor("https://banking.example.de:443/fints30"))
        assertThrows<IllegalArgumentException> { FinDeskHbciCallback.hostFor("https://banking.example.de:8443/fints") }
    }

    @Test
    fun `fints account type codes`() {
        assertEquals("checking", Hbci4JavaGateway.accountTypeFor("1"))
        assertEquals("savings", Hbci4JavaGateway.accountTypeFor("10"))
        assertEquals("depot", Hbci4JavaGateway.accountTypeFor("30"))
        assertEquals("credit_card", Hbci4JavaGateway.accountTypeFor("50"))
        assertEquals("checking", Hbci4JavaGateway.accountTypeFor(null))
    }

    /** DKB's account data has no BIC, and HBCI4Java then refuses to queue CAMT statements ("KUmsZeitCamt1"). */
    @Test
    fun `an account without a BIC gets its bank's BIC`() {
        assertEquals("BYLADEM1001", Hbci4JavaGateway.bicFor(null, "12030000", "DE02120300000000202051", "12030000"))
        assertEquals("BYLADEM1001", Hbci4JavaGateway.bicFor("", null, "DE02120300000000202051", null))
        assertEquals("BYLADEM1001", Hbci4JavaGateway.bicFor(null, null, null, "12030000"))
        // A BIC from the bank always wins
        assertEquals("COBADEFFXXX", Hbci4JavaGateway.bicFor("COBADEFFXXX", "12030000", null, null))
        // Nothing to go on: left as it was, and HBCI4Java reports the missing BIC
        assertNull(Hbci4JavaGateway.bicFor(null, "99999999", "FR7630006000011234567890189", null))
    }

    @Test
    fun `closing the screen unblocks a waiting prompt and refuses new ones`() {
        val ui = UiBankingInteraction()
        var result: PinEntry? = null
        val worker = thread { result = ui.pin("Bank", "user") }
        while (ui.prompt.value == null) Thread.sleep(5)
        ui.cancelPending()
        worker.join(2000)
        assertFalse(worker.isAlive)
        assertNull(result)
        assertNull(ui.pin("Bank", "user"), "no new prompt after close")
    }

    @Test
    fun `ui interaction blocks until answered from another thread`() {
        val ui = UiBankingInteraction()
        var result: PinEntry? = null
        val worker = thread { result = ui.pin("Bank", "user") }
        while (ui.prompt.value == null) Thread.sleep(5)
        assertEquals(BankingPrompt.Pin("Bank", "user"), ui.prompt.value)
        ui.answerPin("0000", false)
        worker.join(2000)
        assertEquals(PinEntry("0000"), result)
        assertNull(ui.prompt.value)

        var tanResult: String? = "unset"
        val cancel = thread { tanResult = ui.tan(TanChallenge("x")) }
        while (ui.prompt.value == null) Thread.sleep(5)
        ui.answer(null)
        cancel.join(2000)
        assertNull(tanResult)
    }
}
