package de.findesk.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.Density
import de.findesk.banking.TransferOrder
import de.findesk.data.database.DatabaseFactory
import de.findesk.i18n.AppLanguage
import de.findesk.i18n.LocalStrings
import de.findesk.i18n.StringKey
import de.findesk.i18n.Translations
import de.findesk.model.AccountItem
import de.findesk.model.PaymentAction
import de.findesk.model.PaymentOrderStatus
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
import de.findesk.viewmodel.TransactionsViewModel
import de.findesk.viewmodel.UnlockMode
import de.findesk.viewmodel.UnlockViewModel
import de.findesk.viewmodel.ViewModelWork
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.runBlocking
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

    private fun render(name: String, width: Int, height: Int, clickAt: Offset? = null, hoverAt: Offset? = null, content: @Composable () -> Unit) {
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
                            )
                            HorizontalDivider()
                            Box(Modifier.fillMaxSize()) {
                                when (screen) {
                                    Screen.DASHBOARD -> if (dashboard != null) DashboardScreen(createViewModel = dashboard) else DashboardScreen()
                                    Screen.ACCOUNTS -> AccountsScreen()
                                    Screen.TRANSACTIONS -> if (transactions != null) TransactionsScreen(transactionArgs, createViewModel = transactions) else TransactionsScreen()
                                    Screen.PAYMENTS -> if (payments != null) PaymentsScreen(payments) else PaymentsScreen()
                                    Screen.DOCUMENTS -> DocumentsScreen()
                                    Screen.REPORTS -> ReportsScreen()
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
