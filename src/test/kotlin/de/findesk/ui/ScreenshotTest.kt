package de.findesk.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.Density
import de.findesk.banking.AccountSnapshot
import de.findesk.banking.BankCapabilities
import de.findesk.banking.BankingCancelledException
import de.findesk.banking.BankingInteraction
import de.findesk.banking.BankingService
import de.findesk.banking.FinTSClient
import de.findesk.banking.FinTSConnection
import de.findesk.banking.FinTSGateway
import de.findesk.banking.ScheduledTransfer
import de.findesk.banking.StandingOrder
import de.findesk.banking.StandingOrderInterval
import de.findesk.banking.StandingOrderSchedule
import de.findesk.banking.TransferOrder
import de.findesk.data.database.DatabaseFactory
import de.findesk.document.ReportService
import de.findesk.document.TaxReportExporter
import de.findesk.i18n.AppLanguage
import de.findesk.i18n.AppStrings
import de.findesk.i18n.LocalStrings
import de.findesk.i18n.StringKey
import de.findesk.i18n.Translations
import de.findesk.model.AccountItem
import de.findesk.model.PaymentAction
import de.findesk.model.PaymentOrderStatus
import de.findesk.model.TaxSource
import de.findesk.model.TransactionItem
import de.findesk.plaintextDatabase
import de.findesk.repository.AccountGroupRepositoryImpl
import de.findesk.repository.AccountRepositoryImpl
import de.findesk.repository.BankAccessRepositoryImpl
import de.findesk.repository.NewTransaction
import de.findesk.repository.PaymentOrderRepositoryImpl
import de.findesk.repository.SettingsRepositoryImpl
import de.findesk.repository.TransactionRepositoryImpl
import de.findesk.ui.components.ProfileMenuModel
import de.findesk.ui.components.Sidebar
import de.findesk.ui.components.TopBar
import de.findesk.ui.navigation.NavigationArgs
import de.findesk.ui.navigation.Screen
import de.findesk.ui.navigation.SettingsSection
import de.findesk.ui.screens.*
import de.findesk.ui.theme.FinDeskTheme
import de.findesk.util.GroupKey
import de.findesk.viewmodel.AccountPanelSettings
import de.findesk.viewmodel.AccountSelection
import de.findesk.viewmodel.AccountsViewModel
import de.findesk.viewmodel.DashboardViewModel
import de.findesk.viewmodel.PaymentsViewModel
import de.findesk.viewmodel.ReportsViewModel
import de.findesk.viewmodel.TransactionsViewModel
import de.findesk.viewmodel.UnlockMode
import de.findesk.viewmodel.UnlockViewModel
import de.findesk.viewmodel.ViewModelWork
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.runBlocking
import org.apache.pdfbox.rendering.PDFRenderer
import org.jetbrains.skia.EncodedImageFormat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import java.io.File
import java.math.BigDecimal
import java.nio.file.Files
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.coroutines.CoroutineContext

/**
 * Renders every screen offscreen to PNG. Not part of the normal suite; run with
 * `./gradlew test --tests '*ScreenshotTest' -Pscreenshots=<out-dir> [-PscreenshotData=<FINDESK_HOME with findesk.db>]`.
 * An encrypted findesk.db needs its passphrase in the environment variable FINDESK_SCREENSHOT_PASSPHRASE.
 */
@EnabledIfSystemProperty(named = "findesk.screenshots", matches = ".+")
class ScreenshotTest {
    private val outDir = File(System.getProperty("findesk.screenshots", "build/screenshots")).apply { mkdirs() }

    @Test
    fun `render all screens`() {
        val dataDir = System.getProperty("findesk.screenshotData")?.let(::File)
            ?: Files.createTempDirectory("findesk-shots").toFile()
        DatabaseFactory.init(dataDir.absolutePath, System.getenv("FINDESK_SCREENSHOT_PASSPHRASE")?.takeIf { it.isNotEmpty() })

        // Every screen in both themes: dark mode regressions (black text on black) only show up when rendered
        Screen.entries.forEach { screen ->
            render("${screen.ordinal}-${screen.name.lowercase()}", screen, dark = false)
            render("${screen.ordinal}-${screen.name.lowercase()}-dark", screen, dark = true)
        }
        // The first-run dashboard: only the three-step getting-started card, no empty stat cards or charts
        render("dashboard-first-run-dark", Screen.DASHBOARD, dark = true)
        // The narrowest common window: stat-card subtitles wrap here, so the cards must still line up
        render("11-dashboard-narrow", 1024, 900) { Frame(Screen.DASHBOARD, dark = false) }
        render("8-dashboard-full", Screen.DASHBOARD, dark = false, height = 1800)
        render("8-dashboard-full-dark", Screen.DASHBOARD, dark = true, height = 1800)
        // One pane per Settings section; the two that carry the most content also render dark
        SettingsSection.entries.forEach { section ->
            render("settings-${section.name.lowercase()}", 1440, 900) {
                Frame(Screen.SETTINGS, dark = false, settingsArgs = NavigationArgs(settingsSection = section))
            }
        }
        render("settings-profiles-dark", 1440, 900) {
            Frame(Screen.SETTINGS, dark = true, settingsArgs = NavigationArgs(settingsSection = SettingsSection.PROFILES))
        }
        render("settings-backup-dark", 1440, 900) {
            Frame(Screen.SETTINGS, dark = true, settingsArgs = NavigationArgs(settingsSection = SettingsSection.BACKUP))
        }
        // Below 840 dp the section list becomes a dropdown
        render("settings-narrow", 800, 900) { Frame(Screen.SETTINGS, dark = false) }
    }

    @Test
    fun `render the unlock screens`() {
        fun gate(name: String, mode: UnlockMode, language: AppLanguage = AppLanguage.EN, dark: Boolean = false, wrongAttempt: Boolean = false, leftovers: Boolean = false, back: String? = null) {
            val dir = Files.createTempDirectory("findesk-gate").toFile()
            when (mode) {
                UnlockMode.CREATE -> {}
                UnlockMode.ENCRYPT -> DatabaseFactory.init(dir.absolutePath)
                UnlockMode.UNLOCK, UnlockMode.CHANGE -> DatabaseFactory.init(dir.absolutePath, "screenshot passphrase")
            }
            DatabaseFactory.init(Files.createTempDirectory("findesk-gate-other").toFile().absolutePath) // release the file
            if (leftovers) {
                plaintextDatabase(File(dir, "pre-restore-20260101-120000/findesk.db"))
                plaintextDatabase(File(dir, "profiles-deleted/old-20260101-120000/findesk.db"))
            }
            val vm = UnlockViewModel(dir, rootDir = dir)
            if (mode == UnlockMode.CHANGE) vm.showChange(true)
            if (wrongAttempt) runBlocking { vm.unlock("wrong passphrase").join() }
            render(name, width = 560, height = 720) {
                CompositionLocalProvider(LocalStrings provides Translations.of(language)) {
                    FinDeskTheme(darkTheme = dark) { UnlockScreen(vm, profileName = "Business", back = back?.let { it to {} }) }
                }
            }
            vm.close()
        }
        gate("gate-1-create", UnlockMode.CREATE)
        gate("gate-2-encrypt-de", UnlockMode.ENCRYPT, language = AppLanguage.DE)
        gate("gate-3-unlock-wrong", UnlockMode.UNLOCK, wrongAttempt = true)
        gate("gate-4-change-dark", UnlockMode.CHANGE, dark = true)
        gate("gate-5-unlock-fr", UnlockMode.UNLOCK, language = AppLanguage.FR)
        gate("gate-6-leftover-copies", UnlockMode.UNLOCK, leftovers = true)
        // After a live switch from the top bar: the way back to the profile that was open
        gate("gate-7-switched-back", UnlockMode.UNLOCK, back = "Personal")
        gate("gate-8-switched-new-de", UnlockMode.CREATE, language = AppLanguage.DE, back = "Personal")
    }

    /**
     * The payment review dialog warning about an earlier order with no confirmed outcome (the bank's answer was lost),
     * so sending it again may pay twice. The order history behind it shows that order as "Outcome unknown".
     */
    @Test
    fun `render the payment review with an unconfirmed earlier order`() {
        DatabaseFactory.init(Files.createTempDirectory("findesk-payments").toFile().absolutePath)
        val bank = BankAccessRepositoryImpl().create("Sparkasse KölnBonn", "37050198", "COLSDE33XXX", "demo-user").id.value
        val accountId = AccountRepositoryImpl().create(bank, "DE89370400440532013000", balance = BigDecimal("2450.37")).id.value
        AccountRepositoryImpl().rename(accountId, "Haushalt")
        val order = TransferOrder("Vermieter GmbH", "DE02120300000000202051", null, BigDecimal("850.00"), "Miete Oktober")
        val payments = PaymentOrderRepositoryImpl()
        val earlier = payments.create(PaymentAction.TRANSFER, "DE89370400440532013000", order.recipientName, order.recipientIban, order.amount, "EUR", order.purpose, null, null)
        payments.complete(earlier, PaymentOrderStatus.UNKNOWN, message = "Read timed out")

        fun reviewing(): PaymentsViewModel = PaymentsViewModel().apply {
            runBlocking { load().join() }
            review(state.value.accounts.single(), order)
            check(state.value.review?.unconfirmed != null) { "the review should point at the unconfirmed order" }
        }
        render("payments-review-unconfirmed", 1440, 900) { Frame(Screen.PAYMENTS, dark = false, payments = ::reviewing) }
        render("payments-review-unconfirmed-de-dark", 1440, 900) { Frame(Screen.PAYMENTS, dark = true, language = AppLanguage.DE, payments = ::reviewing) }
        // While an order is in flight the sidebar keeps the user on Payments
        render("payments-sending-locked-sidebar", 1440, 900) { Frame(Screen.PAYMENTS, dark = false, lockedOn = Screen.PAYMENTS) }
    }

    /**
     * The instant transfer (Echtzeitüberweisung): the form with Instant selected, and its review step, which warns
     * that the money arrives within seconds and cannot be recalled.
     */
    @Test
    fun `render the instant transfer form and review`() {
        DatabaseFactory.init(Files.createTempDirectory("findesk-instant").toFile().absolutePath)
        val bank = BankAccessRepositoryImpl().create("Sparkasse KölnBonn", "37050198", "COLSDE33XXX", "demo-user").id.value
        val account = AccountRepositoryImpl().create(bank, "DE89370400440532013000", balance = BigDecimal("2450.37")).id.value
        AccountRepositoryImpl().rename(account, "Haushalt")
        BankAccessRepositoryImpl().updateCapabilities(
            bank,
            "${BankCapabilities.JOB_TRANSFER},${BankCapabilities.JOB_INSTANT_TRANSFER},${BankCapabilities.JOB_SCHEDULED_TRANSFER},${BankCapabilities.JOB_STANDING_NEW}",
        )
        val order = TransferOrder("Vermieter GmbH", "DE02120300000000202051", null, BigDecimal("850.00"), "Miete Oktober")

        fun reviewing(): PaymentsViewModel = PaymentsViewModel().apply {
            runBlocking { load().join() }
            review(state.value.accounts.single(), order, instant = true)
            check(state.value.review != null) { "the review should be open" }
        }
        render("payments-instant-review", 1440, 900) { Frame(Screen.PAYMENTS, dark = false, payments = ::reviewing) }
        render("payments-instant-review-de-dark", 1440, 900) { Frame(Screen.PAYMENTS, dark = true, language = AppLanguage.DE, payments = ::reviewing) }
        // Instant is the second segment of the execution row (the capabilities above keep it enabled)
        render("payments-form-instant", 1440, 900, clickAt = Offset(500f, 689f)) { Frame(Screen.PAYMENTS, dark = false) }
    }

    /** Editing a standing order: the prefilled form with a read-only recipient, and the change review of a running order. */
    @Test
    fun `render the standing order edit form and change review`() {
        DatabaseFactory.init(Files.createTempDirectory("findesk-standing-edit").toFile().absolutePath)
        val bank = BankAccessRepositoryImpl().create("Sparkasse KölnBonn", "37050198", "COLSDE33XXX", "demo-user").id.value
        val account = AccountRepositoryImpl().create(bank, "DE89370400440532013000", balance = BigDecimal("2450.37")).id.value
        AccountRepositoryImpl().rename(account, "Haushalt")
        val order = TransferOrder("Vermieter GmbH", "DE02120300000000202051", null, BigDecimal("850.00"), "Miete Oktober")
        val schedule = StandingOrderSchedule.of(java.time.LocalDate.of(2026, 11, 1), StandingOrderInterval.MONTHLY, 1)
        val existing = StandingOrder(
            orderId = "so-42",
            fromIban = "DE89370400440532013000",
            order = order,
            schedule = schedule,
            nextDate = java.time.LocalDate.of(2026, 11, 1),
            canChange = true,
        )

        fun editing(): PaymentsViewModel = PaymentsViewModel().apply {
            runBlocking { load().join() }
            requestEdit(existing)
            check(state.value.editReview != null) { "the edit form should be open" }
        }

        fun reviewing(): PaymentsViewModel = PaymentsViewModel().apply {
            runBlocking { load().join() }
            val from = state.value.accounts.single()
            requestEdit(existing)
            // A running order: amount, purpose and schedule change, the recipient and the start stay
            reviewEdit(
                from,
                existing,
                order.copy(amount = BigDecimal("875.00"), purpose = "Miete November"),
                StandingOrderSchedule.of(schedule.firstDate, StandingOrderInterval.MONTHLY, 1, java.time.LocalDate.of(2027, 12, 31)),
            )
            check(state.value.review?.editOf != null) { "the change review should be open" }
        }
        render("payments-standing-edit-form", 1440, 900) { Frame(Screen.PAYMENTS, dark = false, payments = ::editing) }
        render("payments-standing-edit-review", 1440, 900) { Frame(Screen.PAYMENTS, dark = false, payments = ::reviewing) }
        render("payments-standing-edit-review-de-dark", 1440, 900) { Frame(Screen.PAYMENTS, dark = true, language = AppLanguage.DE, payments = ::reviewing) }
    }

    /** The scheduled transfers (Terminüberweisungen) list with two rows, loaded from a fake gateway. */
    @Test
    fun `render the scheduled transfers list`() {
        DatabaseFactory.init(Files.createTempDirectory("findesk-scheduled").toFile().absolutePath)
        val bank = BankAccessRepositoryImpl().create("Sparkasse KölnBonn", "37050198", "COLSDE33XXX", "demo-user").id.value
        val account = AccountRepositoryImpl().create(bank, "DE89370400440532013000", balance = BigDecimal("2450.37")).id.value
        AccountRepositoryImpl().rename(account, "Haushalt")

        fun listing(): PaymentsViewModel = PaymentsViewModel(
            bankingService = BankingService(
                finTSClient = FinTSClient(
                    gateway = ScheduledGateway(
                        ScheduledTransfer(
                            orderId = "t-1",
                            fromIban = "DE89370400440532013000",
                            order = TransferOrder("Vermieter GmbH", "DE02120300000000202051", null, BigDecimal("850.00"), "Miete November"),
                            date = java.time.LocalDate.of(2026, 11, 1),
                        ),
                        ScheduledTransfer(
                            orderId = "t-2",
                            fromIban = "DE89370400440532013000",
                            order = TransferOrder("Stadtwerke GmbH", "DE02100500000054540402", null, BigDecimal("42.50"), "Strom"),
                            date = java.time.LocalDate.of(2026, 12, 15),
                            canDelete = false,
                        ),
                    ),
                    passportDir = File(Files.createTempDirectory("findesk-scheduled-passports").toFile().absolutePath),
                ),
            ),
        ).apply {
            runBlocking {
                load().join()
                loadScheduledTransfers(state.value.accounts.single()).join()
            }
            check(state.value.scheduledTransfers?.size == 2) { "the fake bank should report two scheduled transfers (error: ${state.value.error}, listed: ${state.value.scheduledTransfers})" }
        }
        render("payments-scheduled-transfers", 1440, 900) { Frame(Screen.PAYMENTS, dark = false, payments = ::listing) }
        render("payments-scheduled-transfers-de-dark", 1440, 900) { Frame(Screen.PAYMENTS, dark = true, language = AppLanguage.DE, payments = ::listing) }
    }

    /** A fake bank for the scheduled transfers render: reports the given entries (no PIN prompt, no dialog). */
    private class ScheduledGateway(private vararg val entries: ScheduledTransfer) : FinTSGateway {
        override fun fetchAll(connection: FinTSConnection, interaction: BankingInteraction, since: (String) -> java.time.LocalDate?, to: java.time.LocalDate): List<AccountSnapshot> = emptyList()

        override fun transfer(connection: FinTSConnection, interaction: BankingInteraction, fromIban: String, order: TransferOrder): FinTSClient.TransferResult = FinTSClient.TransferResult("job-1", "ACCEPTED")

        override fun instantTransfer(connection: FinTSConnection, interaction: BankingInteraction, fromIban: String, order: TransferOrder): FinTSClient.TransferResult = FinTSClient.TransferResult("inst-1", "ACCEPTED")

        override fun listScheduledTransfers(connection: FinTSConnection, interaction: BankingInteraction, fromIban: String): List<ScheduledTransfer> = entries.filter { it.fromIban == fromIban }

        override fun deleteScheduledTransfer(connection: FinTSConnection, interaction: BankingInteraction, existing: ScheduledTransfer) = Unit
    }

    /** The Payments "From" picker open: accounts grouped by bank on two readable lines instead of one long string. */
    @Test
    fun `render the open account picker on payments`() {
        DatabaseFactory.init(Files.createTempDirectory("findesk-picker").toFile().absolutePath)
        val banks = BankAccessRepositoryImpl()
        val spark = banks.create("Sparkasse KölnBonn", "37050198", "COLSDE33XXX", "demo-user").id.value
        val dkb = banks.create("DKB", "12030000", "BYLADEM1001", "demo-user").id.value
        val accounts = AccountRepositoryImpl()
        val household = accounts.create(spark, "DE89370400440532013000", balance = BigDecimal("2450.37")).id.value
        accounts.rename(household, "Haushalt")
        accounts.create(spark, "DE44500105175407324931", balance = BigDecimal("15000.00"), accountType = "savings")
        accounts.create(dkb, "DE02120300000000202051", balance = BigDecimal("812.40"))
        // A loan can't send a SEPA transfer, so the view model never offers it in the picker
        accounts.create(dkb, "DE44500105175407324001", balance = BigDecimal("-12000.00"), accountType = "loan")

        // The "From" pill sits under the From label at the top of the SEPA transfer card
        render("payments-account-picker", 1440, 900, clickAt = Offset(400f, 192f)) { Frame(Screen.PAYMENTS, dark = false) }
        render("payments-account-picker-dark", 1440, 900, clickAt = Offset(400f, 192f)) { Frame(Screen.PAYMENTS, dark = true) }
    }

    /** Adding a manual booking identical to one already on that day asks before adding it (two coffees are real). */
    @Test
    fun `render the confirmation for an identical manual entry`() {
        DatabaseFactory.init(Files.createTempDirectory("findesk-duplicate").toFile().absolutePath)
        val bank = BankAccessRepositoryImpl().create("Sparkasse KölnBonn", "37050198", "COLSDE33XXX", "demo-user").id.value
        val account = AccountRepositoryImpl().create(bank, "DE89370400440532013000", balance = BigDecimal("2450.37")).id.value
        AccountRepositoryImpl().rename(account, "Haushalt")
        val today = java.time.LocalDate.now()

        fun confirming(): TransactionsViewModel = TransactionsViewModel().apply {
            repeat(2) { runBlocking { addManualTransaction(account, today, BigDecimal("-3.50"), "Café Fleur", "Cappuccino", null).join() } }
            check(state.value.pendingDuplicate != null) { "the second entry should wait for confirmation" }
        }
        render("transactions-duplicate-confirm", 1440, 900) { Frame(Screen.TRANSACTIONS, dark = false, transactions = ::confirming) }
        render("transactions-duplicate-confirm-de-dark", 1440, 900) { Frame(Screen.TRANSACTIONS, dark = true, language = AppLanguage.DE, transactions = ::confirming) }
    }

    /** The sync's PIN prompt with the opt-in to remember the PIN (unchecked), as macOS and Linux show it. */
    @Test
    fun `render the PIN prompt with the remember option`() {
        fun prompt(name: String, dark: Boolean, language: AppLanguage) {
            val ui = de.findesk.viewmodel.UiBankingInteraction()
            val asking = kotlin.concurrent.thread { ui.pin("DKB Business", "max.mustermann", offerRemember = true) }
            while (ui.prompt.value == null) Thread.sleep(10)
            render(name, 720, 560) {
                CompositionLocalProvider(LocalStrings provides Translations.of(language)) {
                    FinDeskTheme(darkTheme = dark) {
                        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                            de.findesk.ui.components.BankingPromptHost(ui)
                        }
                    }
                }
            }
            ui.cancelPending()
            asking.join()
        }
        prompt("pin-remember", dark = false, language = AppLanguage.EN)
        prompt("pin-remember-de-dark", dark = true, language = AppLanguage.DE)
    }

    /** The running balance column, with one row whose bank balance disagrees with the reconstructed chain. */
    @Test
    fun `render the transaction list with a running balance column`() {
        DatabaseFactory.init(Files.createTempDirectory("findesk-balance").toFile().absolutePath)
        val bank = BankAccessRepositoryImpl().create("Sparkasse KölnBonn", "37050198", "COLSDE33XXX", "demo-user").id.value
        val account = AccountRepositoryImpl().create(bank, "DE89370400440532013000", balance = BigDecimal("2450.37")).id.value
        val today = java.time.LocalDate.now()
        TransactionRepositoryImpl().createAll(
            listOf(
                NewTransaction(account, today.minusDays(4), BigDecimal("-20.00"), "EUR", "Bäckerei", "Brötchen", null, "b-1", balanceAfter = BigDecimal("2380.00")),
                NewTransaction(account, today.minusDays(3), BigDecimal("-80.00"), "EUR", "Stadtwerke", "Abschlag", null, "b-2", balanceAfter = BigDecimal("2300.00")),
                NewTransaction(account, today.minusDays(2), BigDecimal("120.00"), "EUR", "Employer GmbH", "Gehalt", null, "b-3", balanceAfter = BigDecimal("2500.00")),
                NewTransaction(account, today.minusDays(1), BigDecimal("-49.63"), "EUR", "Kiosk", "Wasser", null, "b-4"),
            ),
        )

        fun showing(): TransactionsViewModel = TransactionsViewModel()
        render("transactions-balance", 1440, 900) { Frame(Screen.TRANSACTIONS, dark = false, transactions = ::showing, transactionArgs = NavigationArgs(accountId = account)) }
        render("transactions-balance-hover", 1440, 900, hoverAt = Offset(980f, 760f)) { Frame(Screen.TRANSACTIONS, dark = false, transactions = ::showing, transactionArgs = NavigationArgs(accountId = account)) }
        render("transactions-balance-dark", 1440, 900) { Frame(Screen.TRANSACTIONS, dark = true, transactions = ::showing, transactionArgs = NavigationArgs(accountId = account)) }
        // Arrived through a link: the top bar offers Back to the screen the link left
        render("transactions-back", 1440, 900) { Frame(Screen.TRANSACTIONS, dark = false, transactions = ::showing, transactionArgs = NavigationArgs(accountId = account), backTarget = Screen.DASHBOARD) }
        render("transactions-back-de-dark", 1440, 900) { Frame(Screen.TRANSACTIONS, dark = true, language = AppLanguage.DE, transactions = ::showing, transactionArgs = NavigationArgs(accountId = account), backTarget = Screen.DASHBOARD) }
        // The chart folded away: only its header row remains, restored from the stored setting
        SettingsRepositoryImpl().setValue(TransactionsViewModel.KEY_CHART_COLLAPSED, "true")
        render("transactions-chart-collapsed", 1440, 900) { Frame(Screen.TRANSACTIONS, dark = false, transactions = ::showing, transactionArgs = NavigationArgs(accountId = account)) }
    }

    /** The toolbar's overflow menu open over the (empty) transaction list. */
    @Test
    fun `render the open transactions overflow menu`() {
        DatabaseFactory.init(Files.createTempDirectory("findesk-more-menu").toFile().absolutePath)
        render("transactions-more-menu", 1440, 900, clickAt = Offset(1283f, 108f)) { Frame(Screen.TRANSACTIONS, dark = false) }
    }

    /**
     * The Reports "Tax bookings" tab: an income group, two deduction groups, one row without a receipt and two
     * keyword suggestions. The tab is a compose scene without test tags, so it is opened by clicking its place
     * in the TabRow (content starts after the 240 dp sidebar; five equal tabs).
     */
    @Test
    fun `render the reports tax bookings tab`() {
        DatabaseFactory.init(Files.createTempDirectory("findesk-taxbookings").toFile().absolutePath)
        val bank = BankAccessRepositoryImpl().create("Sparkasse KölnBonn", "37050198", "COBADEFFXXX", "demo-user").id.value
        val account = AccountRepositoryImpl().create(bank, "DE89370400440532013000", balance = BigDecimal("2450.37")).id.value
        AccountRepositoryImpl().rename(account, "Haushalt")
        val categories = de.findesk.repository.CategoryRepositoryImpl()
        val salary = categories.create("Gehalt", "#4CAF50", isTaxRelevant = true).id.value
        val donations = categories.create("Spenden", "#9C27B0", isTaxRelevant = true).id.value
        val craft = categories.create("Handwerk", "#FF5722", isTaxRelevant = true).id.value
        val documents = de.findesk.repository.DocumentRepositoryImpl()
        val transactions = TransactionRepositoryImpl()
        val today = java.time.LocalDate.now()
        var seed = 0

        fun booked(date: java.time.LocalDate, amount: String, partner: String, purpose: String, categoryId: Int?): Int = transactions.create(account, date, BigDecimal(amount), currency = "EUR", partnerName = partner, purpose = purpose, categoryId = categoryId, hash = "tax-shot-${seed++}").id.value

        booked(today.minusDays(80), "2600.00", "Beispiel AG", "Gehalt", salary)
        val receipt1 = booked(today.minusDays(70), "-60.00", "Beispielhilfe e. V.", "Spende Bezirksfest", donations)
        documents.create("beispielbedank-1.pdf", "documents/beispielbedank-1.pdf", "application/pdf", linkedTransactionId = receipt1)
        // The "No receipt" row
        booked(today.minusDays(60), "-45.00", "Beispielhilfe e. V.", "Spendemonat", donations)
        val receipt2 = booked(today.minusDays(50), "-320.00", "Beispiel Werk GmbH", "Renovierung Bad", craft)
        documents.create("beispielbedank-2.pdf", "documents/beispielbedank-2.pdf", "application/pdf", linkedTransactionId = receipt2)
        // Two suggestions: not tax relevant, matched by keyword
        booked(today.minusDays(40), "-25.50", "Apotheke am Ring", "Medikamente", null)
        booked(today.minusDays(30), "-98.90", "Beispiel-Krankenkasse", "Beitrag", null)
        booked(today.minusDays(20), "-4.50", "Bäckerei Beispiel", "Brötchen", null)

        // Second tab in the row of five; 5 tabs over 1200/784 dp content width
        render("reports-tax-bookings", 1440, 900, clickAt = Offset(600f, 193f)) { Frame(Screen.REPORTS, dark = false) }
        render("reports-tax-bookings-de", 1440, 900, clickAt = Offset(600f, 193f)) { Frame(Screen.REPORTS, dark = false, language = AppLanguage.DE) }
        render("reports-tax-bookings-dark", 1440, 900, clickAt = Offset(600f, 193f)) { Frame(Screen.REPORTS, dark = true) }
        render("reports-tax-bookings-narrow", 1024, 800, clickAt = Offset(475f, 193f)) { Frame(Screen.REPORTS, dark = false) }
    }

    /** The tab's empty state: bookings exist, none of them tax relevant; no suggestions either. */
    @Test
    fun `render the tax bookings empty state`() {
        DatabaseFactory.init(Files.createTempDirectory("findesk-tax-empty").toFile().absolutePath)
        val bank = BankAccessRepositoryImpl().create("Sparkasse KölnBonn", "37050198", "COBADEFFXXX", "demo-user").id.value
        val account = AccountRepositoryImpl().create(bank, "DE89370400440532013000", balance = BigDecimal("2450.37")).id.value
        val today = java.time.LocalDate.now()
        TransactionRepositoryImpl().createAll(
            listOf(
                NewTransaction(account, today.minusDays(3), BigDecimal("-20.00"), "EUR", "Bäckerei Beispiel", "Brötchen", null, "tax-empty-1"),
                NewTransaction(account, today.minusDays(1), BigDecimal("120.00"), "EUR", "Beispiel AG", "Überweisung", null, "tax-empty-2"),
            ),
        )
        render("reports-tax-bookings-empty", 1440, 900, clickAt = Offset(600f, 193f)) { Frame(Screen.REPORTS, dark = false) }
    }

    /** The Settings keyword card behind the report's suggestions, in the categories section. */
    @Test
    fun `render the settings tax suggestion keywords card`() {
        DatabaseFactory.init(Files.createTempDirectory("findesk-keywords").toFile().absolutePath)
        val bank = BankAccessRepositoryImpl().create("Sparkasse KölnBonn", "37050198", "COBADEFFXXX", "demo-user").id.value
        AccountRepositoryImpl().create(bank, "DE89370400440532013000", balance = BigDecimal("2450.37"))
        SettingsRepositoryImpl().setValue("tax.suggestionKeywords", "Spende\nHandwerker\nSteuerberater")
        render("settings-tax-suggestion-keywords", 1440, 900) {
            Frame(Screen.SETTINGS, dark = false, settingsArgs = NavigationArgs(settingsSection = SettingsSection.CATEGORIES))
        }
    }

    /**
     * Page 1 and the last page of the tax report PDF, rendered straight from the in-memory document, so the
     * print/export layout can be checked visually next to the screenshots. Synthetic bookings only; runs
     * only with -Pscreenshots like the rest of this class.
     */
    @Test
    fun `render the tax report pdf pages`() {
        val day = java.time.LocalDate.of(2026, 6, 1)
        fun item(id: Int, date: java.time.LocalDate, amount: String, category: String, partner: String): TransactionItem = TransactionItem(
            id = id,
            accountId = 1,
            accountIban = "DE89370400440532013000",
            date = date,
            amount = BigDecimal(amount),
            currency = "EUR",
            partnerName = partner,
            purpose = "Buchung $id",
            categoryId = 1,
            categoryName = category,
            categoryColor = "#9C27B0",
            isTaxRelevant = true,
            documentCount = if (id % 4 == 0) 1 else 0,
            taxSource = TaxSource.CATEGORY,
        )
        val income = (1..20).map { item(it, day.minusDays(it.toLong()), "1200.00", "Gehalt", "Beispiel AG") }
        val donations = (1..30).map { item(100 + it, day.minusDays(it.toLong()), "-30.00", "Spenden", "Beispielhilfe e. V.") }
        fun group(name: String, color: String, list: List<TransactionItem>, isIncome: Boolean) = ReportService.TaxGroup(
            categoryId = 1,
            name = name,
            color = color,
            bookings = list,
            income = if (isIncome) list.sumOf { it.amount } else BigDecimal.ZERO,
            expenses = if (isIncome) BigDecimal.ZERO else list.sumOf { it.amount.abs() },
        )
        val bookings = ReportService.TaxBookings(
            from = java.time.LocalDate.of(2026, 1, 1),
            to = java.time.LocalDate.of(2026, 12, 31),
            currency = "EUR",
            incomeGroups = listOf(group("Gehalt", "#4CAF50", income, isIncome = true)),
            deductionGroups = listOf(group("Spenden", "#9C27B0", donations, isIncome = false)),
            bookingCount = 50,
        )
        TaxReportExporter().buildPdf(bookings).use { doc ->
            val renderer = PDFRenderer(doc)
            listOf(0, doc.numberOfPages - 1).forEach { index ->
                val image = renderer.renderImage(index, 1.5f)
                check(javax.imageio.ImageIO.write(image, "png", File(outDir, "tax-report-pdf-page-${index + 1}.png"))) {
                    "rendering page ${index + 1} of the tax report failed"
                }
            }
        }
    }

    /** Three rows selected for a batch delete: the selection bar with count and per-currency totals. */
    @Test
    fun `render the transactions screen with three rows selected`() {
        DatabaseFactory.init(Files.createTempDirectory("findesk-select").toFile().absolutePath)
        val bank = BankAccessRepositoryImpl().create("Sparkasse KölnBonn", "37050198", "COLSDE33XXX", "demo-user").id.value
        val account = AccountRepositoryImpl().create(bank, "DE89370400440532013000", balance = BigDecimal("2450.37")).id.value
        AccountRepositoryImpl().rename(account, "Haushalt")
        val today = java.time.LocalDate.now()
        TransactionRepositoryImpl().createAll(
            listOf(
                NewTransaction(account, today.minusDays(4), BigDecimal("-20.00"), "EUR", "Bäckerei", "Brötchen", null, "sel-1"),
                NewTransaction(account, today.minusDays(3), BigDecimal("-80.00"), "EUR", "Stadtwerke", "Abschlag", null, "sel-2"),
                NewTransaction(account, today.minusDays(2), BigDecimal("120.00"), "EUR", "Employer GmbH", "Gehalt", null, "sel-3"),
                NewTransaction(account, today.minusDays(1), BigDecimal("-49.63"), "EUR", "Kiosk", "Wasser", null, "sel-4"),
            ),
        )

        // The screen's own start() clears a selection, so the three rows are selected after the screen settled
        val created = arrayOfNulls<TransactionsViewModel>(1)
        fun selecting(): TransactionsViewModel = TransactionsViewModel().also { created[0] = it }
        fun selectFirstThree() {
            val model = created[0] ?: error("the view model should exist once the screen settled")
            val rows = model.state.value.transactions
            check(rows.size == 4) { "the list should show the four bookings" }
            rows.take(3).forEach { model.toggleSelection(it.id) }
        }

        render("transactions-selection", 1440, 900, afterStable = ::selectFirstThree) { Frame(Screen.TRANSACTIONS, dark = false, transactions = ::selecting) }
        render("transactions-selection-dark", 1440, 900, afterStable = ::selectFirstThree) { Frame(Screen.TRANSACTIONS, dark = true, transactions = ::selecting) }
        render("transactions-selection-narrow", 1024, 800, afterStable = ::selectFirstThree) { Frame(Screen.TRANSACTIONS, dark = false, transactions = ::selecting) }
        render("transactions-selection-narrow-dark", 1024, 800, afterStable = ::selectFirstThree) { Frame(Screen.TRANSACTIONS, dark = true, transactions = ::selecting) }
    }

    /** The transactions screen filtered by a custom From/To range: the period button shows "from – to". */
    @Test
    fun `render the transactions screen with a custom date range`() {
        DatabaseFactory.init(Files.createTempDirectory("findesk-custom-range").toFile().absolutePath)
        val bank = BankAccessRepositoryImpl().create("Sparkasse KölnBonn", "37050198", "COLSDE33XXX", "demo-user").id.value
        val account = AccountRepositoryImpl().create(bank, "DE89370400440532013000", balance = BigDecimal("2450.37")).id.value
        AccountRepositoryImpl().rename(account, "Haushalt")
        val today = java.time.LocalDate.now()
        TransactionRepositoryImpl().createAll(
            listOf(
                NewTransaction(account, today.minusDays(4), BigDecimal("-20.00"), "EUR", "Bäckerei", "Brötchen", null, "rng-1"),
                NewTransaction(account, today.minusDays(3), BigDecimal("-80.00"), "EUR", "Stadtwerke", "Abschlag", null, "rng-2"),
                NewTransaction(account, today.minusDays(2), BigDecimal("120.00"), "EUR", "Employer GmbH", "Gehalt", null, "rng-3"),
                NewTransaction(account, today.minusDays(1), BigDecimal("-49.63"), "EUR", "Kiosk", "Wasser", null, "rng-4"),
            ),
        )

        // The range is applied after the screen settled, like the selection above: the view-model action reloads
        // the list itself, so the picture then shows exactly the four bookings inside the range
        val created = arrayOfNulls<TransactionsViewModel>(1)
        fun showing(): TransactionsViewModel = TransactionsViewModel().also { created[0] = it }
        fun applyCustomRange() {
            val model = created[0] ?: error("the view model should exist once the screen settled")
            model.setCustomRange(today.minusDays(4), today.minusDays(1))
        }

        render("transactions-custom-range", 1440, 900, afterStable = ::applyCustomRange) { Frame(Screen.TRANSACTIONS, dark = false, transactions = ::showing) }
    }

    /** The multi-delete confirmation: date range, per-currency total, the first bookings and the sync warning. */
    @Test
    fun `render the multi-delete confirmation`() {
        DatabaseFactory.init(Files.createTempDirectory("findesk-multi-delete").toFile().absolutePath)
        val bank = BankAccessRepositoryImpl().create("Sparkasse KölnBonn", "37050198", "COLSDE33XXX", "demo-user").id.value
        val account = AccountRepositoryImpl().create(bank, "DE89370400440532013000", balance = BigDecimal("2450.37")).id.value
        val today = java.time.LocalDate.now()
        TransactionRepositoryImpl().createAll(
            listOf(
                NewTransaction(account, today.minusDays(8), BigDecimal("-20.00"), "EUR", "Bäckerei", "Brötchen", null, "md-1"),
                NewTransaction(account, today.minusDays(7), BigDecimal("-80.00"), "EUR", "Stadtwerke", "Abschlag", null, "md-2"),
                NewTransaction(account, today.minusDays(6), BigDecimal("1200.00"), "EUR", "Employer GmbH", "Gehalt", null, "md-3"),
                NewTransaction(account, today.minusDays(5), BigDecimal("-49.63"), "EUR", "Kiosk", "Wasser", null, "md-4"),
                NewTransaction(account, today.minusDays(4), BigDecimal("-14.50"), "EUR", "Pflegekasse", "Getränke", null, "md-5"),
                NewTransaction(account, today.minusDays(3), BigDecimal("-32.20"), "EUR", "Buchhandlung", "Roman", null, "md-6"),
            ),
        )
        // More bookings than the five lines shown, so the confirmation renders its "and N more" line
        val bookings = TransactionRepositoryImpl().findItems().take(6)
        val confirmation = MultiDeleteConfirmation(bookings.map { it.id }.toSet(), bookings)

        fun dialog(name: String, language: AppLanguage) {
            AppStrings.set(language)
            render(name, 720, 760) {
                CompositionLocalProvider(LocalStrings provides Translations.of(language)) {
                    FinDeskTheme(darkTheme = false) {
                        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                            Box(contentAlignment = Alignment.Center) {
                                MultiDeleteDialog(confirmation, onConfirm = {}, onDismiss = {})
                            }
                        }
                    }
                }
            }
            AppStrings.set(AppLanguage.EN)
        }
        dialog("transactions-multi-delete-confirm", AppLanguage.EN)
        dialog("transactions-multi-delete-confirm-de", AppLanguage.DE)
    }

    /** The details dialog of a booking whose tax state comes from a rule: the Tax row with its source and override. */
    @Test
    fun `render the details dialog of a rule-flagged booking`() {
        DatabaseFactory.init(Files.createTempDirectory("findesk-tax-details").toFile().absolutePath)
        val bank = BankAccessRepositoryImpl().create("Sparkasse KölnBonn", "37050198", "COLSDE33XXX", "demo-user").id.value
        val account = AccountRepositoryImpl().create(bank, "DE89370400440532013000", balance = BigDecimal("2450.37")).id.value
        AccountRepositoryImpl().rename(account, "Haushalt")
        val office = de.findesk.repository.CategoryRepositoryImpl().create("Bürobedarf", "#3BC7B0")
        de.findesk.repository.RuleRepositoryImpl().create("Beispiel Büro", office.id.value, taxRelevant = true)
        val today = java.time.LocalDate.now()
        TransactionRepositoryImpl().createAll(
            listOf(
                NewTransaction(account, today.minusDays(2), BigDecimal("-19.99"), "EUR", "Beispiel Büro GmbH", "Rechnung 4711", office.id.value, "tax-1"),
                NewTransaction(account, today.minusDays(1), BigDecimal("-4.50"), "EUR", "Bäckerei", "Brötchen", null, "tax-2"),
            ),
        )

        val created = arrayOfNulls<TransactionsViewModel>(1)
        fun showing(): TransactionsViewModel = TransactionsViewModel().also { created[0] = it }
        fun openDetails() {
            val model = created[0] ?: error("the view model should exist once the screen settled")
            val rows = model.state.value.transactions
            check(rows.size == 2) { "the list should show the two bookings" }
            model.selectTransaction(rows.first { it.partnerName == "Beispiel Büro GmbH" })
        }

        render("transactions-tax-details", 1440, 900, afterStable = ::openDetails) { Frame(Screen.TRANSACTIONS, dark = false, transactions = ::showing) }
        render("transactions-tax-details-de", 1440, 900, afterStable = ::openDetails) { Frame(Screen.TRANSACTIONS, dark = false, language = AppLanguage.DE, transactions = ::showing) }
    }

    /** The rule dialog with its Tax selector, editing a rule that carries a tax flag. */
    @Test
    fun `render the rule dialog with the tax selector`() {
        DatabaseFactory.init(Files.createTempDirectory("findesk-rule-tax").toFile().absolutePath)
        val category = de.findesk.repository.CategoryRepositoryImpl().create("Bürobedarf", "#3BC7B0")
        val existing = de.findesk.model.RuleItem(
            id = 1,
            pattern = "Beispiel Büro",
            categoryId = category.id.value,
            categoryName = "Bürobedarf",
            priority = 5,
            autoApply = true,
            matchField = de.findesk.model.RuleField.ANY,
            direction = de.findesk.model.RuleDirection.ANY,
            taxRelevant = true,
        )
        render("settings-rule-dialog-tax", 720, 800) {
            CompositionLocalProvider(LocalStrings provides Translations.of(AppLanguage.EN)) {
                FinDeskTheme(darkTheme = false) {
                    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        Box(contentAlignment = Alignment.Center) {
                            RuleDialog(
                                existing = existing,
                                categories = listOf(de.findesk.model.CategoryItem(category.id.value, "Bürobedarf", "#3BC7B0", budgetLimit = null, isTaxRelevant = false)),
                                validate = { null },
                                onDismiss = {},
                                onConfirm = { _, _, _, _, _, _, _ -> },
                            )
                        }
                    }
                }
            }
        }
    }

    @Test
    fun `render accounts with a custom account name`() {
        DatabaseFactory.init(Files.createTempDirectory("findesk-names").toFile().absolutePath)
        val bank = BankAccessRepositoryImpl().create("DKB", "12030000", "BYLADEM1001", "demo-user").id.value
        val accounts = AccountRepositoryImpl()
        val named = accounts.create(bank, "DE89370400440532013000", balance = BigDecimal("2450.37")).id.value
        accounts.create(bank, "DE02120300000000202051", balance = BigDecimal("-45.10"))
        accounts.rename(named, "Haushalt")

        render("accounts-named", Screen.ACCOUNTS, dark = false)
        render("accounts-named-dark", Screen.ACCOUNTS, dark = true)

        // The same bank, with its account rows folded away
        SettingsRepositoryImpl().setValue(AccountsViewModel.KEY_COLLAPSED_BANKS, bank.toString())
        render("accounts-collapsed", Screen.ACCOUNTS, dark = false)
    }

    /** A bank whose loan accounts have no bank balance and therefore carry the negative amounts the user entered. */
    @Test
    fun `render accounts with loan accounts`() {
        DatabaseFactory.init(Files.createTempDirectory("findesk-loans").toFile().absolutePath)
        val bank = BankAccessRepositoryImpl().create("UniCredit Bank - HypoVereinsbank AG", "70020270", "HYVEDEMMXXX", "demo-user").id.value
        val accounts = AccountRepositoryImpl()
        accounts.create(bank, "DE89370400440532013000", balance = BigDecimal("2450.37"))
        accounts.create(bank, "DE44500105175407324001", balance = BigDecimal("-12000.00"), accountType = "loan")
        accounts.create(bank, "DE02120300000000202052", balance = BigDecimal("-84500.00"), accountType = "loan")

        render("accounts-loans", Screen.ACCOUNTS, dark = false)
    }

    /** With an account the Dashboard leaves the getting-started card for its stat cards and charts. */
    @Test
    fun `render the dashboard with an account`() {
        DatabaseFactory.init(Files.createTempDirectory("findesk-dashboard").toFile().absolutePath)
        val bank = BankAccessRepositoryImpl().create("DKB", "12030000", "BYLADEM1001", "demo-user").id.value
        val accountId = AccountRepositoryImpl().create(bank, "DE89370400440532013000", balance = BigDecimal("2450.37")).id.value
        val today = java.time.LocalDate.now()
        TransactionRepositoryImpl().createAll(
            listOf(
                NewTransaction(accountId, today.minusMonths(1).withDayOfMonth(3), BigDecimal("-120.00"), "EUR", "Supermarket", "Weekly shop", null, "dash-1"),
                NewTransaction(accountId, today.minusMonths(1).withDayOfMonth(10), BigDecimal("2000.00"), "EUR", "Employer GmbH", "Salary", null, "dash-2"),
                NewTransaction(accountId, today.minusDays(3), BigDecimal("-20.00"), "EUR", "Bäckerei", "Brötchen", null, "dash-3"),
            ),
        )

        render("dashboard-with-account", Screen.DASHBOARD, dark = false)
        render("dashboard-with-account-dark", Screen.DASHBOARD, dark = true)
        // The narrowest common window: stat-card subtitles wrap here, so the cards must still line up
        render("dashboard-with-account-narrow", 1024, 900) { Frame(Screen.DASHBOARD, dark = false) }

        // The dashboard at a past month: the header row (arrows, month list, "Current month") and the month titles
        fun pastMonth(): DashboardViewModel = DashboardViewModel().apply {
            runBlocking {
                loadDashboard().join()
                selectMonth(java.time.YearMonth.from(today).minusMonths(1)).join()
            }
        }
        render("dashboard-past-month", 1440, 900) { Frame(Screen.DASHBOARD, dark = false, dashboard = ::pastMonth) }
        render("dashboard-past-month-de", 1440, 900) { Frame(Screen.DASHBOARD, dark = false, language = AppLanguage.DE, dashboard = ::pastMonth) }
        render("dashboard-past-month-dark", 1440, 900) { Frame(Screen.DASHBOARD, dark = true, dashboard = ::pastMonth) }
    }

    /** An HVB connection whose branch BLZ only the central code's FinTS server accepts shows the central-BLZ hint. */
    @Test
    fun `render accounts with a central bank code hint`() {
        DatabaseFactory.init(Files.createTempDirectory("findesk-central-blz").toFile().absolutePath)
        val bank = BankAccessRepositoryImpl().create("UniCredit Bank - HypoVereinsbank AG", "79020076", "HYVEDEMM455", "demo-user").id.value
        AccountRepositoryImpl().create(bank, "DE89370400440532013000", balance = BigDecimal("2450.37"))

        render("accounts-central-blz", Screen.ACCOUNTS, dark = false)
        render("accounts-central-blz-dark", Screen.ACCOUNTS, dark = true)
    }

    /** The account panel on Accounts: selecting the savings group leaves only the bank card that holds it. */
    @Test
    fun `render accounts with the account panel`() {
        DatabaseFactory.init(Files.createTempDirectory("findesk-accounts-panel").toFile().absolutePath)
        val banks = BankAccessRepositoryImpl()
        val dkb = banks.create("DKB", "12030000", "BYLADEM1001", "demo-user").id.value
        val hvb = banks.create("UniCredit Bank - HypoVereinsbank AG", "70020270", "HYVEDEMMXXX", "demo-user").id.value
        val accounts = AccountRepositoryImpl()
        val household = accounts.create(dkb, "DE89370400440532013000", balance = BigDecimal("2450.37")).id.value
        accounts.rename(household, "Haushalt")
        accounts.create(dkb, "DE44500105175407324931", balance = BigDecimal("15000.00"), accountType = "savings")
        val hvbGiro = accounts.create(hvb, "DE02120300000000202051", balance = BigDecimal("-45.10")).id.value
        SettingsRepositoryImpl().setValue(AccountPanelSettings.KEY_SELECTION, "group:type:savings")

        render("accounts-panel", 1440, 900) { Frame(Screen.ACCOUNTS, dark = false) }
        render("accounts-panel-dark", 1440, 900) { Frame(Screen.ACCOUNTS, dark = true) }
        render("accounts-panel-narrow", 1024, 800) { Frame(Screen.ACCOUNTS, dark = false) }

        // A selected account row with its "last fetched" line: the grey lines must stay readable on the highlight
        accounts.updateBalance(hvbGiro, BigDecimal("-45.10"))
        SettingsRepositoryImpl().setValue(AccountPanelSettings.KEY_SELECTION, "account:$hvbGiro")
        render("accounts-panel-account", 1440, 900) { Frame(Screen.ACCOUNTS, dark = false) }
        render("accounts-panel-account-dark", 1440, 900) { Frame(Screen.ACCOUNTS, dark = true) }
    }

    /** The account panel on Transactions: type groups, a custom group, mixed currencies and a selection. */
    @Test
    fun `render transactions with the account panel`() {
        DatabaseFactory.init(Files.createTempDirectory("findesk-panel").toFile().absolutePath)
        val bank = BankAccessRepositoryImpl().create("Sparkasse KölnBonn", "37050198", "COLSDE33XXX", "demo-user").id.value
        val accounts = AccountRepositoryImpl()
        val household = accounts.create(bank, "DE89370400440532013000", balance = BigDecimal("2450.37")).id.value
        accounts.rename(household, "Haushalt")
        accounts.create(bank, "DE02120300000000202051", balance = BigDecimal("812.40"))
        val savings = accounts.create(bank, "DE44500105175407324931", balance = BigDecimal("15000.00"), accountType = "savings").id.value
        accounts.create(bank, "DE02120300000000202052", balance = BigDecimal("-5400.00"), currency = "USD", accountType = "depot")
        val reisen = AccountGroupRepositoryImpl().create("Reisen")
        accounts.setGroup(savings, reisen.id)

        val today = java.time.LocalDate.now()
        TransactionRepositoryImpl().createAll(
            listOf(
                NewTransaction(household, today.minusDays(4), BigDecimal("-20.00"), "EUR", "Bäckerei", "Brötchen", null, "panel-1"),
                NewTransaction(household, today.minusDays(2), BigDecimal("120.00"), "EUR", "Employer GmbH", "Gehalt", null, "panel-2"),
                NewTransaction(savings, today.minusDays(1), BigDecimal("-40.00"), "EUR", "Sparkasse", "Zinsen", null, "panel-3"),
            ),
        )

        // The screen's own start() restores this persisted selection when it opens.
        fun panel(): TransactionsViewModel = TransactionsViewModel().apply {
            runBlocking { selectAccounts(AccountSelection.Group(GroupKey.Type("checking"))).join() }
        }
        render("transactions-panel", 1440, 900) { Frame(Screen.TRANSACTIONS, dark = false, transactions = ::panel) }
        render("transactions-panel-dark", 1440, 900) { Frame(Screen.TRANSACTIONS, dark = true, transactions = ::panel) }
        render("transactions-panel-mid", 1280, 800) { Frame(Screen.TRANSACTIONS, dark = false, transactions = ::panel) }
        render("transactions-panel-narrow", 1024, 800) { Frame(Screen.TRANSACTIONS, dark = false, transactions = ::panel) }
        // The icon-only sidebar frees 168 dp: at the minimum window width the account panel then fits again
        render("sidebar-collapsed", 1440, 900) { Frame(Screen.TRANSACTIONS, dark = false, transactions = ::panel, sidebarCollapsed = true) }
        render("sidebar-collapsed-dark", 1440, 900) { Frame(Screen.TRANSACTIONS, dark = true, transactions = ::panel, sidebarCollapsed = true) }
        render("sidebar-collapsed-narrow", 1024, 800) { Frame(Screen.TRANSACTIONS, dark = false, transactions = ::panel, sidebarCollapsed = true) }
        render("topbar-profiles", 1440, 900) { Frame(Screen.TRANSACTIONS, dark = false, transactions = ::panel, profiles = listOf("mamsoft" to "MAMSoft", null to "Personal")) }
        render("topbar-profiles-dark", 1440, 900) { Frame(Screen.TRANSACTIONS, dark = true, transactions = ::panel, profiles = listOf("mamsoft" to "MAMSoft", null to "Personal"), sidebarCollapsed = true) }
        render("sidebar-collapsed-de", 1440, 900) { Frame(Screen.TRANSACTIONS, dark = false, language = AppLanguage.DE, transactions = ::panel, sidebarCollapsed = true) }
    }

    /** Two connections, one excluded: the header's "Sync all" and the grey hint on the excluded card. */
    @Test
    fun `render accounts with Sync all and an excluded connection`() {
        DatabaseFactory.init(Files.createTempDirectory("findesk-sync-all").toFile().absolutePath)
        val banks = BankAccessRepositoryImpl()
        val dkb = banks.create("DKB", "12030000", "BYLADEM1001", "demo-user").id.value
        val hvb = banks.create("UniCredit Bank - HypoVereinsbank AG", "70020270", "HYVEDEMMXXX", "demo-user").id.value
        val accounts = AccountRepositoryImpl()
        accounts.create(dkb, "DE89370400440532013000", balance = BigDecimal("2450.37"))
        accounts.create(hvb, "DE02120300000000202051", balance = BigDecimal("-45.10"))
        banks.setIncludeInSyncAll(hvb, false)

        render("accounts-sync-all", 1440, 900) { Frame(Screen.ACCOUNTS, dark = false) }
    }

    /** The unread-booking counter: panel badges, a bold account name and bold rows for a sync's fresh bookings. */
    @Test
    fun `render transactions with new bookings`() {
        DatabaseFactory.init(Files.createTempDirectory("findesk-new").toFile().absolutePath)
        val bank = BankAccessRepositoryImpl().create("Sparkasse KölnBonn", "37050198", "COLSDE33XXX", "demo-user").id.value
        val accounts = AccountRepositoryImpl()
        val household = accounts.create(bank, "DE89370400440532013000", balance = BigDecimal("2450.37")).id.value
        accounts.rename(household, "Haushalt")
        val joint = accounts.create(bank, "DE44500105175407324931", balance = BigDecimal("812.40")).id.value
        val today = java.time.LocalDate.now()
        TransactionRepositoryImpl().createAll(
            listOf(
                NewTransaction(household, today.minusDays(2), BigDecimal("-20.00"), "EUR", "Bäckerei", "Brötchen", null, "new-1", isNew = true),
                NewTransaction(household, today.minusDays(1), BigDecimal("120.00"), "EUR", "Employer GmbH", "Gehalt", null, "new-2", isNew = true),
                NewTransaction(joint, today, BigDecimal("-9.99"), "EUR", "Kiosk", "Zeitung", null, "new-3", isNew = true),
            ),
        )

        // A group selection keeps its badge while highlighted; selecting one account would clear its own badge,
        // so the group shows the badge-on-highlight case (readable in dark mode) plus the bold rows underneath.
        fun panel(): TransactionsViewModel = TransactionsViewModel().apply {
            runBlocking { selectAccounts(AccountSelection.Group(GroupKey.Type("checking"))).join() }
        }
        render("transactions-new", 1440, 900) { Frame(Screen.TRANSACTIONS, dark = false, transactions = ::panel) }
        render("transactions-new-dark", 1440, 900) { Frame(Screen.TRANSACTIONS, dark = true, transactions = ::panel) }
    }

    /** An offline bank that never goes online, with one card account imported from statement files. */
    @Test
    fun `render accounts with an offline bank`() {
        DatabaseFactory.init(Files.createTempDirectory("findesk-offline").toFile().absolutePath)
        val offlineBank = BankAccessRepositoryImpl().createOffline("Bank Norwegian Visa").id.value
        AccountRepositoryImpl().create(offlineBank, "CARD4321", balance = BigDecimal("-45.10"), accountType = "credit_card")

        render("accounts-offline", Screen.ACCOUNTS, dark = false)
        render("accounts-offline-dark", Screen.ACCOUNTS, dark = true)
    }

    /** Three synthetic CSV files at once (one without bookings) plus one unreadable file, in the import dialog. */
    @Test
    fun `render the multi-file import dialog`() {
        DatabaseFactory.init(Files.createTempDirectory("findesk-import").toFile().absolutePath)
        val bank = BankAccessRepositoryImpl().create("Sparkasse KölnBonn", "37050198", "COLSDE33XXX", "demo-user").id.value
        AccountRepositoryImpl().create(bank, "DE89370400440532013000", balance = BigDecimal("2450.37"))
        val dir = Files.createTempDirectory("findesk-import-files").toFile()
        val header = "Buchung;Valuta;Auftraggeber/Empfänger;Buchungstext;Verwendungszweck;Saldo;Währung;Betrag;Währung\n"
        fun csv(name: String, rows: String) = File(dir, name).apply { writeText("IBAN;DE89 3704 0044 0532 0130 00\n\n$header$rows") }
        val october = csv("oktober.csv", "02.10.2025;02.10.2025;Stadtwerke;Lastschrift;Strom;2430,37;EUR;-20,00;EUR\n15.10.2025;15.10.2025;Obststand;Kartenzahlung;Obst;2400,37;EUR;-30,00;EUR\n")
        val november = csv("november.csv", "05.11.2025;05.11.2025;Arbeitgeber GmbH;Gutschrift;Gehalt;2500,37;EUR;100,00;EUR\n20.11.2025;20.11.2025;Kino;Kartenzahlung;Tickets;2490,37;EUR;-10,00;EUR\n")
        val empty = csv("empty.csv", "")
        val junk = File(dir, "statement.zip").apply { writeText("this is not a CAMT.053 ZIP file") }

        fun importing(): TransactionsViewModel = TransactionsViewModel().apply {
            runBlocking {
                start().join()
                openImport(listOf(november, october, empty, junk)).join()
            }
            check(state.value.importPreview != null) { "the preview should open for the readable files" }
        }
        render("import-multi-file", 1440, 900) { Frame(Screen.TRANSACTIONS, dark = false, transactions = ::importing) }
        render("import-multi-file-dark", 1440, 900) { Frame(Screen.TRANSACTIONS, dark = true, transactions = ::importing) }
        render("import-multi-file-de", 1440, 900) { Frame(Screen.TRANSACTIONS, dark = false, language = AppLanguage.DE, transactions = ::importing) }
    }

    /** The add-account dialog of an offline bank, with the new optional account-name field first. */
    @Test
    fun `render the add-account dialog with the name field`() {
        DatabaseFactory.init(Files.createTempDirectory("findesk-add-account").toFile().absolutePath)
        val bank = BankAccessRepositoryImpl().createOffline("Cards Bank").let { BankAccessRepositoryImpl().findItem(it.id.value)!! }
        render("add-account-named", 1440, 900) {
            CompositionLocalProvider(LocalStrings provides Translations.of(AppLanguage.DE)) {
                FinDeskTheme(darkTheme = false) {
                    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        AddAccountDialog(
                            bank = bank,
                            validate = { identifier ->
                                de.findesk.viewmodel.AccountsViewModel().validateAccount(identifier, offline = true)
                            },
                            onDismiss = {},
                            onConfirm = { _, _, _, _, _ -> },
                        )
                    }
                }
            }
        }
    }

    private fun render(name: String, screen: Screen, dark: Boolean, height: Int = 900) = render(name, 1440, height) { Frame(screen, dark) }

    private fun render(name: String, width: Int, height: Int, clickAt: Offset? = null, hoverAt: Offset? = null, afterStable: (() -> Unit)? = null, content: @Composable () -> Unit) {
        // ImageComposeScene runs effects on Dispatchers.Unconfined by default, so collectAsState resumed on the view model's IO
        // thread and wrote state from there while this thread rendered; some of those writes never reached the picture.
        // Effects are queued instead and run here, on the rendering thread, around every frame.
        val tasks = ConcurrentLinkedQueue<Runnable>()
        val sceneDispatcher = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) {
                tasks.add(block)
            }
        }
        fun runQueued() {
            while (true) (tasks.poll() ?: break).run()
        }
        val scene = ImageComposeScene(width = width, height = height, density = Density(1f), coroutineContext = sceneDispatcher) {
            content()
        }
        try {
            // Let LaunchedEffects start and IO-dispatched view model loads finish. A fixed frame count raced cold
            // loads (state landing in the last frame, half the screen recomposed), so wait until 10 frames in a
            // row are identical, at least 1 s, at most 15 s. Identical pixels alone are not enough: a loading
            // screen is as still as a loaded one (the dashboard painted its €0.00 stat cards while
            // loadDashboard() ran on IO, so the harness saved that state in 1 of 20 renders). A frame therefore
            // only counts as stable when no view-model work is in flight, and running work at the limit fails
            // instead of writing a stale picture. The stillness rule plus ViewModelWork handle slow loads; the
            // dispatcher above handles updates that would otherwise be lost to the Unconfined IO thread.
            var frame = 0L

            fun next(): ByteArray {
                runQueued()
                val picture = scene.render(frame++ * 16_000_000L)
                runQueued()
                return picture.encodeToData(EncodedImageFormat.PNG)?.bytes ?: error("encode failed")
            }
            fun settle(png: ByteArray, limit: Long = 150, minimumFrames: Long = 10): ByteArray {
                var currentPng = png
                var stable = 0
                while (frame < limit && (frame < minimumFrames || stable < 10 || ViewModelWork.active > 0)) {
                    Thread.sleep(100)
                    val current = next()
                    stable = if (current.contentEquals(currentPng) && ViewModelWork.active == 0) stable + 1 else 0
                    currentPng = current
                }
                if (ViewModelWork.active > 0) {
                    throw IllegalStateException("$name: view-model work still running after $frame frames")
                }
                return currentPng
            }
            var png = settle(next())
            if (afterStable != null) {
                // A view-model change applied by the test after the screen settled (e.g. a multi-selection the
                // screen's own start() would clear); let the next frames render it before saving
                afterStable()
                png = settle(png)
            }
            if (clickAt != null) {
                // Overlay menus are separate popup layers in the scene, so click the button and let the menu open
                scene.sendPointerEvent(PointerEventType.Press, clickAt)
                scene.sendPointerEvent(PointerEventType.Release, clickAt)
                val clickedAt = frame
                png = settle(png, limit = 260, minimumFrames = clickedAt + 10)
            }
            if (hoverAt != null) {
                // Enter and Move must land on different points: the scene drops a Move that repeats the hover position
                scene.sendPointerEvent(PointerEventType.Enter, hoverAt - Offset(12f, 0f))
                scene.sendPointerEvent(PointerEventType.Move, hoverAt)
                val hoveredAt = frame
                png = settle(png, limit = hoveredAt + 30, minimumFrames = hoveredAt + 3)
            }
            File(outDir, "$name.png").writeBytes(png)
        } finally {
            scene.close()
        }
    }

    @Composable
    private fun Frame(
        screen: Screen,
        dark: Boolean,
        language: AppLanguage = AppLanguage.EN,
        payments: (() -> PaymentsViewModel)? = null,
        transactions: (() -> TransactionsViewModel)? = null,
        transactionArgs: NavigationArgs = NavigationArgs(),
        lockedOn: Screen? = null,
        sidebarCollapsed: Boolean = false,
        profiles: List<Pair<String?, String>>? = null,
        settingsArgs: NavigationArgs = NavigationArgs(),
        dashboard: (() -> DashboardViewModel)? = null,
        reports: (() -> ReportsViewModel)? = null,
        backTarget: Screen? = null,
    ) {
        CompositionLocalProvider(LocalStrings provides Translations.of(language)) {
            FinDeskTheme(darkTheme = dark) {
                // Surface sets the content colour too: text drawn straight on the background is otherwise black in dark mode
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Row(Modifier.fillMaxSize()) {
                        Sidebar(currentScreen = screen, onScreenSelected = {}, lockedOn = lockedOn, collapsed = sidebarCollapsed)
                        Column(Modifier.fillMaxSize()) {
                            TopBar(
                                title = stringsFor(screen),
                                isDarkTheme = dark,
                                onThemeToggle = {},
                                sidebarCollapsed = sidebarCollapsed,
                                language = language,
                                profileMenu = profiles?.let { list -> ProfileMenuModel(current = list.first().first, load = { list }, onSwitch = {}, onManage = {}) },
                                backTarget = backTarget,
                            )
                            HorizontalDivider()
                            Box(Modifier.fillMaxSize()) {
                                when (screen) {
                                    Screen.DASHBOARD -> if (dashboard != null) DashboardScreen(createViewModel = dashboard) else DashboardScreen()
                                    Screen.ACCOUNTS -> AccountsScreen()
                                    Screen.TRANSACTIONS -> if (transactions != null) TransactionsScreen(transactionArgs, createViewModel = transactions) else TransactionsScreen()
                                    Screen.PAYMENTS -> if (payments != null) PaymentsScreen(payments) else PaymentsScreen()
                                    Screen.DOCUMENTS -> DocumentsScreen()
                                    Screen.REPORTS -> if (reports != null) ReportsScreen(createViewModel = reports) else ReportsScreen()
                                    Screen.SETTINGS -> SettingsScreen(darkTheme = dark, onDarkThemeChange = {}, language = AppLanguage.EN, onLanguageChange = {}, args = settingsArgs)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun stringsFor(screen: Screen): String = when (screen) {
        Screen.DASHBOARD -> "Dashboard"
        Screen.ACCOUNTS -> "Accounts"
        Screen.TRANSACTIONS -> "Transactions"
        Screen.PAYMENTS -> "Payments"
        Screen.DOCUMENTS -> "Documents"
        Screen.REPORTS -> "Reports"
        Screen.SETTINGS -> "Settings"
    }
}
