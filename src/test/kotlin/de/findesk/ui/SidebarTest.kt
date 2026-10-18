package de.findesk.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import de.findesk.ui.components.Sidebar
import de.findesk.ui.navigation.Screen
import de.findesk.ui.theme.FinDeskTheme
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

@OptIn(ExperimentalTestApi::class)
class SidebarTest {
    @Test
    fun `a collapsed sidebar keeps every screen reachable by its title`() = runComposeUiTest {
        val selected = mutableListOf<Screen>()
        setContent {
            FinDeskTheme { Sidebar(currentScreen = Screen.DASHBOARD, onScreenSelected = { selected += it }, collapsed = true) }
        }
        onNodeWithText("Transactions").assertDoesNotExist()
        listOf("Dashboard", "Accounts", "Transactions", "Payments", "Documents", "Settings").forEach { title ->
            onNodeWithContentDescription(title).assertExists()
        }
        onNodeWithContentDescription("Transactions").performClick()
        assertEquals(listOf(Screen.TRANSACTIONS), selected)
    }

    @Test
    fun `a locked screen keeps the others disabled when collapsed`() = runComposeUiTest {
        setContent {
            FinDeskTheme { Sidebar(currentScreen = Screen.PAYMENTS, onScreenSelected = {}, collapsed = true, lockedOn = Screen.PAYMENTS) }
        }
        onNodeWithContentDescription("Accounts").assertIsNotEnabled()
    }
}
