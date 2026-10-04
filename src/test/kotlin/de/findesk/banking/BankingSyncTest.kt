package de.findesk.banking

import de.findesk.TestData
import de.findesk.TestDatabase
import de.findesk.data.database.DatabaseFactory
import de.findesk.repository.AuditLogRepositoryImpl
import de.findesk.repository.SettingsRepositoryImpl
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
) : BankingInteraction {
    var pinAsked = 0
    var payeeAsked = 0
    val tanChallenges = mutableListOf<TanChallenge>()
    var offeredMethods: List<TanMethod> = emptyList()

    override fun pin(bankName: String, userId: String): String? = pinValue.also { pinAsked++ }
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
        ask(cb, HBCICallback.HAVE_VOP_RESULT, msg = "Name stimmt überein")
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

    @Test
    fun `closing the screen unblocks a waiting prompt and refuses new ones`() {
        val ui = UiBankingInteraction()
        var result: String? = "unset"
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
        var result: String? = "unset"
        val worker = thread { result = ui.pin("Bank", "user") }
        while (ui.prompt.value == null) Thread.sleep(5)
        assertEquals(BankingPrompt.Pin("Bank", "user"), ui.prompt.value)
        ui.answer("0000")
        worker.join(2000)
        assertEquals("0000", result)
        assertNull(ui.prompt.value)

        val cancel = thread { result = ui.tan(TanChallenge("x")) }
        while (ui.prompt.value == null) Thread.sleep(5)
        ui.answer(null)
        cancel.join(2000)
        assertNull(result)
    }
}
