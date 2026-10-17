package de.findesk.ui.screens

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import de.findesk.TestDatabase
import de.findesk.i18n.AppLanguage
import de.findesk.i18n.LocalStrings
import de.findesk.i18n.Translations
import de.findesk.ui.theme.FinDeskTheme
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalTestApi::class)
class SettingsSectionsTest {
    @Test
    fun `a section shortcut scrolls its section into view below the shortcut row`() = runComposeUiTest {
        TestDatabase.init()
        setContent {
            CompositionLocalProvider(LocalStrings provides Translations.of(AppLanguage.EN)) {
                FinDeskTheme { SettingsScreen(darkTheme = false, onDarkThemeChange = {}, language = AppLanguage.EN, onLanguageChange = {}) }
            }
        }
        waitForIdle()
        // Before: only the shortcut chip carries the label; the section itself is far below and not composed
        assertTrue(onAllNodesWithText("Audit log (latest 100)").fetchSemanticsNodes().size == 1)
        val chipBottom = onAllNodesWithText("Audit log (latest 100)")[0].getBoundsInRoot().bottom

        onAllNodesWithText("Audit log (latest 100)")[0].performClick()
        waitForIdle()

        val title = onAllNodesWithText("Audit log (latest 100)")
        assertTrue(title.fetchSemanticsNodes().size == 2, "section composed after the jump")
        val sectionTop = title[1].getBoundsInRoot().top
        assertTrue(sectionTop > chipBottom, "section title at $sectionTop is below the shortcut row ending at $chipBottom")
    }
}
