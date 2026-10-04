package de.findesk.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import de.findesk.data.database.DatabaseFactory
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
    }

    private fun render(name: String, screen: Screen, dark: Boolean) {
        val scene = ImageComposeScene(width = 1440, height = 900, density = Density(1f)) {
            Frame(screen, dark)
        }
        try {
            // Let LaunchedEffects start and IO-dispatched view model loads finish
            var image = scene.render(0)
            repeat(30) { i ->
                Thread.sleep(100)
                image = scene.render((i + 1) * 16_000_000L)
            }
            val png = image.encodeToData(EncodedImageFormat.PNG) ?: error("encode failed")
            File(outDir, "$name.png").writeBytes(png.bytes)
        } finally {
            scene.close()
        }
    }

    @Composable
    private fun Frame(screen: Screen, dark: Boolean) {
        FinDeskTheme(darkTheme = dark) {
            Row(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                Sidebar(currentScreen = screen, onScreenSelected = {})
                Column(Modifier.fillMaxSize()) {
                    TopBar(title = screen.title, isDarkTheme = dark, onThemeToggle = {})
                    HorizontalDivider()
                    Box(Modifier.fillMaxSize()) {
                        when (screen) {
                            Screen.DASHBOARD -> DashboardScreen()
                            Screen.ACCOUNTS -> AccountsScreen()
                            Screen.TRANSACTIONS -> TransactionsScreen()
                            Screen.PAYMENTS -> PaymentsPlaceholderScreen()
                            Screen.DOCUMENTS -> DocumentsScreen()
                            Screen.REPORTS -> ReportsScreen()
                            Screen.SETTINGS -> SettingsScreen(darkTheme = dark, onDarkThemeChange = {})
                        }
                    }
                }
            }
        }
    }
}
