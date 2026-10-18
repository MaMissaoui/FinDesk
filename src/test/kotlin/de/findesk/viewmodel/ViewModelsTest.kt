package de.findesk.viewmodel

import de.findesk.TestData
import de.findesk.TestDatabase
import de.findesk.banking.AccountSnapshot
import de.findesk.banking.BankingInteraction
import de.findesk.banking.BankingService
import de.findesk.banking.FinTSClient
import de.findesk.banking.FinTSConnection
import de.findesk.banking.FinTSGateway
import de.findesk.banking.TransferOrder
import de.findesk.i18n.AppLanguage
import de.findesk.i18n.AppStrings
import de.findesk.i18n.StringKey
import de.findesk.i18n.Translations
import de.findesk.model.Direction
import de.findesk.repository.AccountRepositoryImpl
import de.findesk.repository.AuditLogRepositoryImpl
import de.findesk.repository.MAX_BANK_NAME_LENGTH
import de.findesk.ui.components.accountLabel
import de.findesk.util.Formats
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.math.BigDecimal
import java.time.LocalDate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ViewModelsTest {
    private lateinit var data: TestData
    private val today = LocalDate.of(2026, 3, 20)
    private var account = 0
    private var food = 0

    @TempDir
    lateinit var tmp: File

    @BeforeEach
    fun setup() {
        TestDatabase.init()
        data = TestData()
        account = data.account(balance = "1500.00")
        food = data.category("Food", color = "#4CAF50")
        val tax = data.category("Spenden", taxRelevant = true)
        data.txn(account, LocalDate.of(2026, 3, 2), "-30.00", partner = "REWE", categoryId = food)
        data.txn(account, LocalDate.of(2026, 3, 5), "-20.00", partner = "Kiosk")
        data.txn(account, LocalDate.of(2026, 3, 1), "2500.00", partner = "Employer")
        data.txn(account, LocalDate.of(2026, 1, 15), "-100.00", partner = "UNICEF", categoryId = tax)
    }

    @Test
    fun `dashboard separates income from expenses (regression)`() = runBlocking {
        val vm = DashboardViewModel(today = { today })
        vm.loadDashboard().join()
        val s = vm.state.value

        assertNull(s.error)
        assertEquals(BigDecimal("1500.00"), s.totalBalance)
        assertEquals(BigDecimal("2500.00"), s.monthlyIncome)
        assertEquals(BigDecimal("50.00"), s.monthlyExpenses)
        assertEquals(2, s.uncategorizedCount, "Kiosk and the uncategorized salary")
        assertEquals(listOf("Food", "Uncategorized"), s.expensesByCategory.map { it.label })
        assertEquals("#4CAF50", s.expensesByCategory.first().color)
        // Uncategorized is always neutral grey, never a palette colour a category could also have
        assertEquals(DashboardViewModel.UNCATEGORIZED_COLOR, s.expensesByCategory.last().color)
        assertEquals(listOf("Spenden"), s.taxDeductionsByCategory.map { it.label })
        assertEquals(6, s.lastMonths.size)
        assertEquals(BigDecimal("100.00"), s.lastMonths.first { it.month == 1 }.expenses)
        vm.close()
    }

    @Test
    fun `transactions filters apply and category filter is honoured`() = runBlocking {
        val vm = TransactionsViewModel(today = { today })
        vm.loadTransactions().join()
        assertEquals(3, vm.state.value.transactions.size, "this month")

        vm.updateDirection(Direction.EXPENSES).join()
        assertEquals(2, vm.state.value.transactions.size)

        vm.updateCategoryFilter(CategoryFilter.Only(food)).join()
        assertEquals(listOf("REWE"), vm.state.value.transactions.map { it.partnerName })

        vm.updateCategoryFilter(CategoryFilter.None).join()
        assertEquals(listOf("Kiosk"), vm.state.value.transactions.map { it.partnerName })

        vm.updateCategoryFilter(CategoryFilter.Any).join()
        vm.updatePeriod(Period.THIS_YEAR).join()
        vm.updateSearchQuery("unic").join()
        assertEquals(listOf("UNICEF"), vm.state.value.transactions.map { it.partnerName })
        vm.close()
    }

    @Test
    fun `manual transaction gets auto-categorized and an identical one waits for confirmation`() = runBlocking {
        data.ruleRepo.create("edeka", food)
        val vm = TransactionsViewModel(today = { today })
        vm.addManualTransaction(account, today, BigDecimal("-12.34"), "EDEKA Center", null, null).join()
        val added = vm.state.value.transactions.first { it.partnerName == "EDEKA Center" }
        assertEquals("Food", added.categoryName)

        vm.addManualTransaction(account, today, BigDecimal("-12.34"), "EDEKA Center", null, null).join()
        assertEquals(1, vm.state.value.transactions.count { it.partnerName == "EDEKA Center" }, "not added before confirming")
        assertEquals(BigDecimal("-12.34"), vm.state.value.pendingDuplicate?.amount)

        vm.dismissDuplicate()
        assertNull(vm.state.value.pendingDuplicate)
        assertEquals(1, vm.state.value.transactions.count { it.partnerName == "EDEKA Center" })
        vm.close()
    }

    @Test
    fun `a confirmed identical manual entry is added as a second booking`() = runBlocking {
        val vm = TransactionsViewModel(today = { today })
        repeat(2) { vm.addManualTransaction(account, today, BigDecimal("-3.50"), "Café", "Coffee", null).join() }

        vm.confirmDuplicate()!!.join()

        val coffees = vm.state.value.transactions.filter { it.partnerName == "Café" }
        assertEquals(2, coffees.size)
        assertNull(vm.state.value.pendingDuplicate)
        assertNull(vm.state.value.error)
        assertEquals(2, data.transactionRepo.findAll().filter { it.partnerName == "Café" }.map { it.hash }.distinct().size, "each entry has its own hash")
        assertNull(vm.confirmDuplicate(), "nothing left to confirm")
        vm.close()
    }

    @Test
    fun `show all from a partner clears the narrowing filters`() = runBlocking {
        val other = data.account(iban = "DE44500105175407324931", balance = "0")
        data.txn(other, LocalDate.of(2025, 11, 3), "-12.00", partner = "REWE")
        val vm = TransactionsViewModel(today = { today })
        vm.start().join()
        vm.updateDirection(Direction.INCOME).join()
        vm.updateAccountFilter(account).join()

        vm.showPartner("REWE").join()

        val s = vm.state.value
        assertEquals(listOf("REWE", "REWE"), s.transactions.map { it.partnerName }, "this month and last year, both accounts")
        assertEquals(Period.ALL, s.period)
        assertEquals(Direction.ALL, s.direction)
        assertNull(s.accountId)
        vm.close()
    }

    @Test
    fun `the manual duplicate check is per account`() = runBlocking {
        val other = data.account(iban = "DE44500105175407324931", balance = "0")
        val vm = TransactionsViewModel(today = { today })
        vm.addManualTransaction(account, today, BigDecimal("-4.50"), "Bäckerei", "Brötchen", null).join()

        // The same booking on another account is a different booking
        vm.addManualTransaction(other, today, BigDecimal("-4.50"), "Bäckerei", "Brötchen", null).join()
        assertNull(vm.state.value.pendingDuplicate)
        assertEquals(2, data.transactionRepo.findItems().count { it.partnerName == "Bäckerei" })

        // Typed again on the first account, even with stray spaces, it is caught
        vm.addManualTransaction(account, today, BigDecimal("-4.5"), " Bäckerei ", "Brötchen ", null).join()
        assertEquals(account, vm.state.value.pendingDuplicate?.accountId)
        assertEquals(2, data.transactionRepo.findItems().count { it.partnerName == "Bäckerei" })
        vm.close()
    }

    @Test
    fun `statement import previews, picks the account from the file and imports`() = runBlocking {
        val other = data.account(iban = "DE44500105175407324931", balance = "0")
        val csv = File(tmp, "ing.csv").apply {
            writeText(
                "IBAN;DE44 5001 0517 5407 3249 31\n\nBuchung;Valuta;Auftraggeber/Empfänger;Buchungstext;Verwendungszweck;Saldo;Währung;Betrag;Währung\n" +
                    "02.10.2025;02.10.2025;Stadtwerke;Lastschrift;Strom;80,00;EUR;-20,00;EUR\n",
            )
        }
        val vm = TransactionsViewModel(today = { today })
        vm.loadTransactions().join()
        vm.openImport(csv).join()

        val preview = vm.state.value.importPreview!!
        assertEquals(other, preview.accountId, "matched by the IBAN in the file")
        assertEquals(1, preview.statement.bookings.size)
        assertEquals(0, data.transactionRepo.findItems().count { it.partnerName == "Stadtwerke" }, "nothing stored before confirming")

        vm.confirmImport(updateBalance = true).join()
        assertNull(vm.state.value.importPreview)
        assertEquals(Period.ALL, vm.state.value.period, "imported history is visible even if older than this month")
        assertEquals(listOf("Stadtwerke"), vm.state.value.transactions.map { it.partnerName })
        assertEquals(BigDecimal("80.00"), data.accountRepo.findAllItems().first { it.id == other }.balance)
        vm.close()
    }

    @Test
    fun `running balance column shows for one checking account and hides for all or a depot`() = runBlocking {
        val vm = TransactionsViewModel(today = { today })
        vm.start(accountId = account).join()
        assertNotNull(vm.state.value.balances, "a single checking account shows the column")
        assertEquals(BigDecimal("1500.00"), vm.state.value.currentBalance)

        vm.start().join()
        assertNull(vm.state.value.balances, "All accounts never shows it")
        assertNull(vm.state.value.currentBalance)

        val depotBank = data.bankAccessRepo.create("Depot Bank", "12345678", "", "depot-user").id.value
        val depot = data.accountRepo.create(depotBank, "DE02120300000000202051", balance = BigDecimal("5000.00"), accountType = "depot").id.value
        vm.start(accountId = depot).join()
        assertNull(vm.state.value.balances, "a depot's balance is a market value, not the sum of its bookings")
        vm.close()
    }

    @Test
    fun `a filter hides rows but the remaining balances are unchanged`() = runBlocking {
        val vm = TransactionsViewModel(today = { today })
        vm.start(accountId = account).join()
        val all = vm.state.value.balances!!
        val rewe = vm.state.value.transactions.first { it.partnerName == "REWE" }

        vm.updateSearchQuery("rewe").join()

        assertEquals(1, vm.state.value.transactions.size)
        assertEquals(all[rewe.id], vm.state.value.balances!![rewe.id], "the balance after a booking never changes with a filter")
        vm.close()
    }

    @Test
    fun `exportCsv writes the balance column only when it is shown`() = runBlocking {
        val vm = TransactionsViewModel(today = { today })
        val withBalance = File(tmp, "with-balance.csv")
        vm.start(accountId = account).join()
        vm.exportCsv(withBalance).join()
        assertTrue(withBalance.readLines().first().endsWith(",Balance"), "shown column is exported")

        val withoutBalance = File(tmp, "without-balance.csv")
        vm.start().join()
        vm.exportCsv(withoutBalance).join()
        assertFalse(withoutBalance.readLines().first().contains("Balance"), "hidden column stays out of the export")
        vm.close()
    }

    @Test
    fun `amount range matches absolute values in both directions`() = runBlocking {
        val vm = TransactionsViewModel(today = { today })
        vm.updatePeriod(Period.THIS_YEAR).join()
        vm.updateAmountRange(BigDecimal("25"), BigDecimal("150")).join()
        assertEquals(setOf("REWE", "UNICEF"), vm.state.value.transactions.map { it.partnerName }.toSet())
        vm.updateAmountRange(BigDecimal("1000"), null).join()
        assertEquals(listOf("Employer"), vm.state.value.transactions.map { it.partnerName }, "income matched by absolute amount")
        vm.close()
    }

    @Test
    fun `rule from a transaction escapes regex and drops reference numbers`() = runBlocking {
        val vm = TransactionsViewModel(today = { today })
        val acc = account
        data.txn(acc, LocalDate.of(2026, 3, 6), "-12.99", partner = "NETFLIX.COM 4711-2026")
        data.txn(acc, LocalDate.of(2026, 2, 6), "-12.99", partner = "NETFLIX.COM 4711-2025")
        vm.loadTransactions().join()
        val netflix = vm.state.value.transactions.first { it.partnerName!!.startsWith("NETFLIX") }

        val pattern = vm.rulePatternFor(netflix)
        assertEquals("NETFLIX\\.COM", pattern)

        vm.createRule(pattern, food).join()
        assertEquals("Rule created and applied to 2 uncategorized transactions", vm.state.value.message)
        assertEquals(2, data.transactionRepo.findItems().count { it.partnerName!!.startsWith("NETFLIX") && it.categoryId == food })
        vm.close()
    }

    @Test
    fun `receipts attached from the detail panel are linked to the transaction`() = runBlocking {
        val docsDir = File(tmp, "docs")
        val documentService = de.findesk.document.DocumentService(ocrService = de.findesk.document.OcrService(engine = null), documentsDir = docsDir)
        val vm = TransactionsViewModel(today = { today }, documentService = documentService)
        vm.loadTransactions().join()
        val rewe = vm.state.value.transactions.first { it.partnerName == "REWE" }
        val png = File(tmp, "bon.png").also { javax.imageio.ImageIO.write(java.awt.image.BufferedImage(5, 5, java.awt.image.BufferedImage.TYPE_INT_RGB), "png", it) }

        vm.selectTransaction(rewe).join()
        assertTrue(vm.state.value.selectedDocuments.isEmpty())
        vm.attachDocuments(rewe.id, listOf(png)).join()

        assertEquals(listOf("bon.png"), vm.state.value.selectedDocuments.map { it.fileName })
        assertEquals(1, vm.state.value.transactions.first { it.id == rewe.id }.documentCount)
        assertEquals(1, vm.state.value.selected!!.documentCount, "open panel refreshed")
        vm.close()
    }

    @Test
    fun `auto categorize applies rules to uncategorized transactions`() = runBlocking {
        data.ruleRepo.create("kiosk", food)
        val vm = TransactionsViewModel(today = { today })
        vm.autoCategorizeTransactions().join()
        assertEquals("Categorized 1 of 2 uncategorized transactions", vm.state.value.message)
        vm.close()
    }

    @Test
    fun `accounts validate iban and cascade deletes`() = runBlocking {
        val vm = AccountsViewModel()
        vm.loadAccounts().join()
        val bankId = vm.state.value.bankAccesses.first().id

        vm.addAccount(bankId, "DE00 1234 5678 9012 3456 78", "checking", BigDecimal.ZERO).join()
        assertEquals("IBAN is not valid (checksum failed)", vm.state.value.error)

        vm.addAccount(bankId, "de44 5001 0517 5407 3249 31", "savings", BigDecimal("10")).join()
        assertEquals(2, vm.state.value.accounts.size)
        assertEquals("DE44500105175407324931", vm.state.value.accounts.last().iban)

        vm.deleteBankAccess(bankId).join()
        assertTrue(vm.state.value.accounts.isEmpty())
        assertTrue(data.transactionRepo.findItems().isEmpty())
        vm.close()
    }

    @Test
    fun `renaming one of two bank accesses at the same bank changes only that one`() = runBlocking {
        val personal = data.bankAccessRepo.create("DKB", "12030000", "BYLADEM1001", "personal").id.value
        val business = data.bankAccessRepo.create("DKB", "12030000", "BYLADEM1001", "business").id.value
        data.accountRepo.create(personal, "DE02120300000000202051")
        data.accountRepo.create(business, "DE02120300000000202052")

        val vm = AccountsViewModel()
        vm.loadAccounts().join()
        vm.updateConnection(business, "DKB Business", "12030000", "business", "").join()

        val names = vm.state.value.bankAccesses.associate { it.id to it.bankName }
        assertEquals("DKB", names[personal])
        assertEquals("DKB Business", names[business])

        val accounts = AccountRepositoryImpl().findAllItems()
        assertEquals("DKB", accounts.single { it.bankAccessId == personal }.bankName)
        assertEquals("DKB Business", accounts.single { it.bankAccessId == business }.bankName)
        assertEquals(1, AuditLogRepositoryImpl().findByAction("RENAME_BANK_ACCESS").size)
        vm.close()
    }

    @Test
    fun `a blank connection name is rejected and the stored name is unchanged`() = runBlocking {
        val bankId = data.bankAccessRepo.create("DKB", "12030000", "BYLADEM1001", "user").id.value
        val vm = AccountsViewModel()
        vm.loadAccounts().join()

        vm.updateConnection(bankId, "   ", "12030000", "user", "").join()

        assertEquals(AppStrings[StringKey.BankNameRequired], vm.state.value.error)
        assertEquals("DKB", data.bankAccessRepo.findItem(bankId)!!.bankName)
        assertTrue(AuditLogRepositoryImpl().findByAction("RENAME_BANK_ACCESS").isEmpty())
        vm.close()
    }

    @Test
    fun `a connection name is trimmed and capped at the column length`() = runBlocking {
        val bankId = data.bankAccessRepo.create("DKB", "12030000", "BYLADEM1001", "user").id.value
        val vm = AccountsViewModel()
        vm.loadAccounts().join()

        vm.updateConnection(bankId, "  DKB Business  ", "12030000", "user", "").join()
        assertEquals("DKB Business", data.bankAccessRepo.findItem(bankId)!!.bankName)

        vm.updateConnection(bankId, "x".repeat(MAX_BANK_NAME_LENGTH + 45), "12030000", "user", "").join()
        val stored = data.bankAccessRepo.findItem(bankId)!!.bankName
        assertEquals(MAX_BANK_NAME_LENGTH, stored.length)
        vm.close()
    }

    @Test
    fun `a renamed connection survives a restart`() = runBlocking {
        val bankId = data.bankAccessRepo.create("DKB", "12030000", "BYLADEM1001", "user").id.value
        val before = AccountsViewModel()
        before.loadAccounts().join()
        before.updateConnection(bankId, "DKB Business", "12030000", "user", "").join()
        before.close()

        val restarted = AccountsViewModel()
        restarted.loadAccounts().join()
        assertEquals("DKB Business", restarted.state.value.bankAccesses.single { it.id == bankId }.bankName)
        restarted.close()
    }

    @Test
    fun `sync start date is validated and can be cleared`() = runBlocking {
        val vm = AccountsViewModel(today = { today })
        vm.loadAccounts().join()

        assertNotNull(vm.validateSyncFrom("31.02.2026"), "an impossible calendar date is rejected")
        assertNotNull(vm.validateSyncFrom("abc"), "a non-date is rejected")
        assertNotNull(vm.validateSyncFrom("21.03.2026"), "a date after today is rejected")
        assertNull(vm.validateSyncFrom("01.03.2026"), "a past date is accepted")

        val bankId = vm.state.value.bankAccesses.first().id
        data.bankAccessRepo.setSyncFrom(bankId, today.minusDays(30))
        assertNotNull(data.bankAccessRepo.findItem(bankId)!!.syncFrom)

        vm.clearSyncFrom(bankId).join()
        assertNull(data.bankAccessRepo.findItem(bankId)!!.syncFrom)
        assertTrue(AuditLogRepositoryImpl().findByAction("SET_SYNC_FROM").isNotEmpty())
        vm.close()
    }

    @Test
    fun `renameAccount stores the name and audits only a change`() = runBlocking {
        val vm = AccountsViewModel()
        vm.loadAccounts().join()
        val audit = AuditLogRepositoryImpl()

        vm.renameAccount(account, "Haushalt").join()
        assertEquals("Haushalt", data.accountRepo.findAllItems().first { it.id == account }.name)
        assertEquals(1, audit.findByAction("RENAME_ACCOUNT").size, "one entry for the first name")

        vm.renameAccount(account, "Haushalt").join()
        assertEquals(1, audit.findByAction("RENAME_ACCOUNT").size, "renaming to the same name writes nothing")

        vm.renameAccount(account, "   ").join()
        assertNull(data.accountRepo.findAllItems().first { it.id == account }.name, "blank resets to null")
        assertEquals(2, audit.findByAction("RENAME_ACCOUNT").size, "clearing writes one entry")

        vm.openDetail(account).join()
        vm.renameAccount(account, "Reisekasse").join()
        assertEquals("Reisekasse", vm.state.value.detail?.account?.name, "an open detail view shows the new name")
        vm.close()
    }

    @Test
    fun `account name and label fall back to the localized type`() {
        val s = Translations.of(AppLanguage.EN)
        val plain = data.accountRepo.findAllItems().first { it.id == account }
        assertEquals("Checking", s.accountName(plain), "no name -> the localized type")
        assertEquals("Test Bank ${Formats.iban(plain.iban)}", accountLabel(plain))

        data.accountRepo.rename(account, "Haushalt")
        val named = data.accountRepo.findAllItems().first { it.id == account }
        assertEquals("Haushalt", s.accountName(named))
        assertEquals("Test Bank · Haushalt ${Formats.iban(named.iban)}", accountLabel(named))
    }

    @Test
    fun `reports view model loads and exports`() = runBlocking {
        val vm = ReportsViewModel()
        vm.loadTaxReport(2026).join()
        assertEquals(BigDecimal("2500.00"), vm.state.value.taxReport?.totalIncome)

        val pdf = File(tmp, "r.pdf")
        vm.exportToPdf(pdf).join()
        assertTrue(pdf.length() > 0)
        assertEquals("Saved r.pdf", vm.state.value.message)
        vm.close()
    }

    @Test
    fun `settings validates rules and deleting a category unassigns transactions`() = runBlocking {
        val vm = SettingsViewModel(dataDirectory = tmp.absolutePath)
        vm.load().join()
        assertNotNull(vm.validateRule("(unclosed"))
        assertNull(vm.validateRule("rewe|edeka"))
        assertEquals("Category already exists", vm.validateCategory("food", "#000000"))

        vm.setDarkTheme(true).join()
        assertTrue(SettingsViewModel(dataDirectory = tmp.absolutePath).also { it.load().join() }.state.value.darkTheme)

        vm.deleteCategory(food).join()
        assertNull(data.transactionRepo.findItems().first { it.partnerName == "REWE" }.categoryId)
        vm.close()
    }

    @Test
    fun `protocol trace setting persists and saves the captured trace`() = runBlocking {
        de.findesk.banking.ProtocolTraceStore.clear()
        val vm = SettingsViewModel(dataDirectory = tmp.absolutePath)
        vm.load().join()
        try {
            vm.setProtocolTraceEnabled(true).join()
            assertTrue(vm.state.value.protocolTraceEnabled)
            assertTrue(de.findesk.banking.ProtocolTraceStore.enabled)
            assertTrue(SettingsViewModel(dataDirectory = tmp.absolutePath).also { it.load().join() }.state.value.protocolTraceEnabled)

            val out = File(tmp, "trace.txt")
            vm.saveProtocolTrace(out).join()
            assertFalse(out.exists())
            assertNotNull(vm.state.value.error)

            de.findesk.banking.ProtocolTraceStore.publish(
                de.findesk.banking.ProtocolTrace(
                    java.time.LocalDateTime.of(2026, 1, 1, 0, 0),
                    "Test Bank",
                    "12345678",
                    "300",
                    listOf(de.findesk.banking.TraceLine(1, "userId=1111111111")),
                ),
            )
            vm.saveProtocolTrace(out).join()
            assertTrue(out.readText().contains("bank: Test Bank"))
        } finally {
            de.findesk.banking.ProtocolTraceStore.clear()
            de.findesk.banking.ProtocolTraceStore.enabled = false
            vm.close()
        }
    }

    @Test
    fun `updateConnection rejects a bank code that is not 8 digits`() = runBlocking {
        val bankId = data.bankAccessRepo.create("DKB", "12030000", "BYLADEM1001", "user").id.value
        val vm = AccountsViewModel()
        vm.loadAccounts().join()

        vm.updateConnection(bankId, "DKB", "1203000", "user", "").join()

        assertEquals(AppStrings[StringKey.BlzMustBe8Digits], vm.state.value.error)
        assertEquals("12030000", data.bankAccessRepo.findItem(bankId)!!.blz)
        vm.close()
    }

    @Test
    fun `updateConnection refuses while the bank is syncing`() = runBlocking {
        val bankId = data.bankAccessRepo.create("DKB", "12030000", "BYLADEM1001", "user").id.value
        data.bankAccessRepo.updateConnection(bankId, serverUrl = "https://fints.example/pintan")
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val gateway = object : FinTSGateway {
            override fun fetchAll(connection: FinTSConnection, interaction: BankingInteraction, since: (String) -> LocalDate?, to: LocalDate): List<AccountSnapshot> {
                entered.countDown()
                release.await(5, TimeUnit.SECONDS)
                return emptyList()
            }

            override fun transfer(connection: FinTSConnection, interaction: BankingInteraction, fromIban: String, order: TransferOrder): FinTSClient.TransferResult = throw UnsupportedOperationException()
        }
        val service = BankingService(finTSClient = FinTSClient(gateway = gateway, passportDir = File(tmp, "sync-passports")), today = { today })
        val vm = AccountsViewModel(bankingService = service)
        vm.loadAccounts().join()

        val sync = vm.syncBankAccess(bankId)
        assertTrue(entered.await(5, TimeUnit.SECONDS), "the sync reached the gateway")
        assertEquals(bankId, vm.state.value.syncingBankId)

        vm.updateConnection(bankId, "DKB", "12030000", "user", "").join()

        assertEquals(AppStrings[StringKey.ErrorConnectionBusy], vm.state.value.error)

        release.countDown()
        sync.join()
        vm.close()
    }
}
