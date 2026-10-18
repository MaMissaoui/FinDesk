package de.findesk.ui

import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.isPopup
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.test.runComposeUiTest
import de.findesk.i18n.AppLanguage
import de.findesk.i18n.LocalStrings
import de.findesk.i18n.Translations
import de.findesk.ui.components.RowContextMenu
import de.findesk.ui.components.menuItem
import de.findesk.ui.theme.FinDeskTheme
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

@OptIn(ExperimentalTestApi::class)
class ContextMenuTest {
    @Test
    fun `right-click opens the row's menu and runs the chosen action`() = runComposeUiTest {
        val chosen = mutableListOf<String>()
        setContent {
            CompositionLocalProvider(LocalStrings provides Translations.of(AppLanguage.EN)) {
                FinDeskTheme(darkTheme = true) {
                    RowContextMenu(menuItem("Open details") { chosen += "open" }, null, menuItem("Delete") { chosen += "delete" }) { Text("REWE Markt") }
                }
            }
        }
        onNodeWithText("Delete").assertDoesNotExist()

        onAllNodes(isPopup()).assertCountEquals(0)
        onNodeWithText("REWE Markt").performMouseInput { rightClick(center) }
        onAllNodes(isPopup()).assertCountEquals(1)
        onNodeWithText("Open details").assertExists()
        onNodeWithText("Delete").performClick()

        assertEquals(listOf("delete"), chosen)
        onNodeWithText("Open details").assertDoesNotExist()
    }

    @Test
    fun `a row without applicable actions opens no menu`() = runComposeUiTest {
        setContent {
            FinDeskTheme { RowContextMenu(null) { Text("Spotify AB") } }
        }
        onNodeWithText("Spotify AB").performMouseInput { rightClick(center) }
        waitForIdle()
        onAllNodes(isPopup()).assertCountEquals(0)
    }
}
