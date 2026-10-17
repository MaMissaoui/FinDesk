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
import androidx.compose.ui.unit.Density
import de.findesk.banking.TransferOrder
import de.findesk.data.database.DatabaseFactory
import de.findesk.i18n.AppLanguage
import de.findesk.i18n.LocalStrings
import de.findesk.i18n.Translations
import de.findesk.model.PaymentAction
import de.findesk.model.PaymentOrderStatus
import de.findesk.plaintextDatabase
import de.findesk.repository.AccountRepositoryImpl
import de.findesk.repository.BankAccessRepositoryImpl
import de.findesk.repository.PaymentOrderRepositoryImpl
import de.findesk.ui.components.Sidebar
import de.findesk.ui.components.TopBar
import de.findesk.ui.navigation.Screen
import de.findesk.ui.screens.*
import de.findesk.ui.theme.FinDeskTheme
import de.findesk.viewmodel.PaymentsViewModel
import de.findesk.viewmodel.UnlockMode
import de.findesk.viewmodel.UnlockViewModel
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.EncodedImageFormat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import java.io.File
import java.math.BigDecimal
import java.nio.file.Files

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

        Screen.entries.forEach { screen ->
            render("${screen.ordinal}-${screen.name.lowercase()}", screen, dark = false)
        }
        render("9-dashboard-dark", Screen.DASHBOARD, dark = true)
        // Page headings sit straight on the background, so they show whether the content colour follows the theme
        render("10-transactions-dark", Screen.TRANSACTIONS, dark = true)
        render("8-dashboard-full", Screen.DASHBOARD, dark = false, height = 1800)
        render("7-settings-full", Screen.SETTINGS, dark = false, height = 2400)
    }

    @Test
    fun `render the unlock screens`() {
        fun gate(name: String, mode: UnlockMode, language: AppLanguage = AppLanguage.EN, dark: Boolean = false, wrongAttempt: Boolean = false, leftovers: Boolean = false) {
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
                    FinDeskTheme(darkTheme = dark) { UnlockScreen(vm, profileName = "Business") }
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
    }

    /**
     * The payment review dialog warning about an earlier order with no confirmed outcome (the bank's answer was lost),
     * so sending it again may pay twice. The order history behind it shows that order as "Outcome unknown".
     */
    @Test
    fun `render the payment review with an unconfirmed earlier order`() {
        DatabaseFactory.init(Files.createTempDirectory("findesk-payments").toFile().absolutePath)
        val bank = BankAccessRepositoryImpl().create("Sparkasse KölnBonn", "37050198", "COLSDE33XXX", "demo-user").id.value
        AccountRepositoryImpl().create(bank, "DE89370400440532013000", balance = BigDecimal("2450.37"))
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

    private fun render(name: String, screen: Screen, dark: Boolean, height: Int = 900) = render(name, 1440, height) { Frame(screen, dark) }

    private fun render(name: String, width: Int, height: Int, content: @Composable () -> Unit) {
        val scene = ImageComposeScene(width = width, height = height, density = Density(1f)) {
            content()
        }
        try {
            // Let LaunchedEffects start and IO-dispatched view model loads finish. A fixed frame count raced cold
            // loads (state landing in the last frame, half the screen recomposed), so wait until 10 frames in a
            // row are identical, at least 1 s, at most 15 s.
            var frame = 0L
            fun next() = scene.render(frame++ * 16_000_000L).encodeToData(EncodedImageFormat.PNG)?.bytes ?: error("encode failed")
            var png = next()
            var stable = 0
            while (frame < 150 && (frame < 10 || stable < 10)) {
                Thread.sleep(100)
                val current = next()
                stable = if (current.contentEquals(png)) stable + 1 else 0
                png = current
            }
            File(outDir, "$name.png").writeBytes(png)
        } finally {
            scene.close()
        }
    }

    @Composable
    private fun Frame(screen: Screen, dark: Boolean, language: AppLanguage = AppLanguage.EN, payments: (() -> PaymentsViewModel)? = null, lockedOn: Screen? = null) {
        CompositionLocalProvider(LocalStrings provides Translations.of(language)) {
            FinDeskTheme(darkTheme = dark) {
                // Surface sets the content colour too: text drawn straight on the background is otherwise black in dark mode
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Row(Modifier.fillMaxSize()) {
                        Sidebar(currentScreen = screen, onScreenSelected = {}, lockedOn = lockedOn)
                        Column(Modifier.fillMaxSize()) {
                            TopBar(title = stringsFor(screen), isDarkTheme = dark, onThemeToggle = {})
                            HorizontalDivider()
                            Box(Modifier.fillMaxSize()) {
                                when (screen) {
                                    Screen.DASHBOARD -> DashboardScreen()
                                    Screen.ACCOUNTS -> AccountsScreen()
                                    Screen.TRANSACTIONS -> TransactionsScreen()
                                    Screen.PAYMENTS -> if (payments != null) PaymentsScreen(payments) else PaymentsScreen()
                                    Screen.DOCUMENTS -> DocumentsScreen()
                                    Screen.REPORTS -> ReportsScreen()
                                    Screen.SETTINGS -> SettingsScreen(darkTheme = dark, onDarkThemeChange = {}, language = AppLanguage.EN, onLanguageChange = {})
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
        Screen.REPORTS -> "Reports/Tax"
        Screen.SETTINGS -> "Settings"
    }
}
