package de.findesk.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import de.findesk.data.database.DatabaseFactory
import de.findesk.i18n.AppLanguage
import de.findesk.i18n.LocalStrings
import de.findesk.i18n.Translations
import de.findesk.ui.components.Sidebar
import de.findesk.ui.components.TopBar
import de.findesk.ui.navigation.Screen
import de.findesk.ui.screens.*
import de.findesk.ui.theme.FinDeskTheme
import org.jetbrains.skia.EncodedImageFormat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import java.io.File
import java.nio.file.Files

/**
 * Renders every screen offscreen to PNG. Not part of the normal suite; run with
 * `./gradlew test --tests '*ScreenshotTest' -Pscreenshots=<out-dir> [-PscreenshotData=<FINDESK_HOME with findesk.db>]`.
 */
@EnabledIfSystemProperty(named = "findesk.screenshots", matches = ".+")
class ScreenshotTest {
    private val outDir = File(System.getProperty("findesk.screenshots", "build/screenshots")).apply { mkdirs() }

    @Test
    fun `render all screens`() {
        val dataDir = System.getProperty("findesk.screenshotData")?.let(::File)
            ?: Files.createTempDirectory("findesk-shots").toFile()
        DatabaseFactory.init(dataDir.absolutePath)

        Screen.entries.forEach { screen ->
            render("${screen.ordinal}-${screen.name.lowercase()}", screen, dark = false)
        }
        render("9-dashboard-dark", Screen.DASHBOARD, dark = true)
        render("8-dashboard-full", Screen.DASHBOARD, dark = false, height = 1800)
    }

    private fun render(name: String, screen: Screen, dark: Boolean, height: Int = 900) {
        val scene = ImageComposeScene(width = 1440, height = height, density = Density(1f)) {
            Frame(screen, dark)
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
    private fun Frame(screen: Screen, dark: Boolean) {
        CompositionLocalProvider(LocalStrings provides Translations.of(AppLanguage.EN)) {
            FinDeskTheme(darkTheme = dark) {
                Row(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    Sidebar(currentScreen = screen, onScreenSelected = {})
                    Column(Modifier.fillMaxSize()) {
                        TopBar(title = stringsFor(screen), isDarkTheme = dark, onThemeToggle = {})
                        HorizontalDivider()
                        Box(Modifier.fillMaxSize()) {
                            when (screen) {
                                Screen.DASHBOARD -> DashboardScreen()
                                Screen.ACCOUNTS -> AccountsScreen()
                                Screen.TRANSACTIONS -> TransactionsScreen()
                                Screen.PAYMENTS -> PaymentsScreen()
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
