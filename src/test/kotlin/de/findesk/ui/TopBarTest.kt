package de.findesk.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import de.findesk.i18n.AppLanguage
import de.findesk.i18n.LocalStrings
import de.findesk.i18n.Translations
import de.findesk.ui.components.ProfileMenuModel
import de.findesk.ui.components.TopBar
import de.findesk.ui.theme.FinDeskTheme
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

@OptIn(ExperimentalTestApi::class)
class TopBarTest {
    @Test
    fun `the language menu lists every language and switches to the chosen one`() = runComposeUiTest {
        val chosen = mutableListOf<AppLanguage>()
        setContent {
            CompositionLocalProvider(LocalStrings provides Translations.of(AppLanguage.EN)) {
                FinDeskTheme {
                    TopBar(title = "Accounts", isDarkTheme = false, onThemeToggle = {}, language = AppLanguage.EN, onLanguageChange = { chosen += it })
                }
            }
        }
        onNodeWithContentDescription("Change language").performClick()
        onNodeWithText("English").assertExists()
        onNodeWithText("Français").assertExists()
        onNodeWithText("Deutsch").performClick()
        assertEquals(listOf(AppLanguage.DE), chosen)
    }

    @Test
    fun `while busy the language button is disabled and changes nothing`() = runComposeUiTest {
        val chosen = mutableListOf<AppLanguage>()
        setContent {
            FinDeskTheme {
                TopBar(title = "Payments", isDarkTheme = false, onThemeToggle = {}, onLanguageChange = { chosen += it }, languageEnabled = false)
            }
        }
        onNodeWithContentDescription("Change language").assertIsNotEnabled().performClick()
        onNodeWithText("Deutsch").assertDoesNotExist()
        assertEquals(emptyList<AppLanguage>(), chosen)
    }

    @Test
    fun `the profile menu switches to another profile and opens Settings to manage them`() = runComposeUiTest {
        val switched = mutableListOf<String?>()
        var managed = 0
        val profiles = listOf<Pair<String?, String>>("mamsoft" to "MAMSoft", null to "Personal")
        setContent {
            FinDeskTheme {
                TopBar(
                    title = "Dashboard",
                    isDarkTheme = false,
                    onThemeToggle = {},
                    profileMenu = ProfileMenuModel(current = "mamsoft", load = { profiles }, onSwitch = { switched += it }, onManage = { managed++ }),
                )
            }
        }
        onNodeWithContentDescription("Switch profile").performClick()
        onAllNodesWithText("MAMSoft", useUnmergedTree = true).assertCountEquals(2) // the button and the menu row
        onNodeWithText("Personal").performClick()
        assertEquals(listOf<String?>(null), switched)

        onNodeWithContentDescription("Switch profile").performClick()
        onNodeWithText("Manage profiles…").performClick()
        assertEquals(1, managed)
    }

    @Test
    fun `picking the open profile or switching while busy does nothing`() = runComposeUiTest {
        val switched = mutableListOf<String?>()
        var busy by mutableStateOf(false)
        setContent {
            FinDeskTheme {
                TopBar(
                    title = "Dashboard",
                    isDarkTheme = false,
                    onThemeToggle = {},
                    languageEnabled = !busy,
                    profileMenu = ProfileMenuModel(current = null, load = { listOf<Pair<String?, String>>(null to "Personal", "b" to "Business") }, onSwitch = { switched += it }, onManage = {}),
                )
            }
        }
        onNodeWithContentDescription("Switch profile").performClick()
        onAllNodesWithText("Personal")[1].performClick()
        assertEquals(emptyList<String?>(), switched, "the open profile is not switched to again")

        busy = true
        onNodeWithContentDescription("Switch profile").assertIsNotEnabled()
    }

    @Test
    fun `the sidebar button names what a click does`() = runComposeUiTest {
        var collapsed by mutableStateOf(false)
        setContent {
            FinDeskTheme {
                TopBar(title = "Dashboard", isDarkTheme = false, onThemeToggle = {}, sidebarCollapsed = collapsed, onToggleSidebar = { collapsed = !collapsed })
            }
        }
        onNodeWithContentDescription("Collapse sidebar").performClick()
        onNodeWithContentDescription("Expand sidebar").performClick()
        onNodeWithContentDescription("Collapse sidebar").assertExists()
    }
}
