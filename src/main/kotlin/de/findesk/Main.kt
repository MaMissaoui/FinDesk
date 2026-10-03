package de.findesk

import androidx.compose.desktop.ui.tooling.preview.Preview
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import de.findesk.data.database.DatabaseFactory
import de.findesk.ui.components.Sidebar
import de.findesk.ui.components.TopBar
import de.findesk.ui.navigation.NavigationState
import de.findesk.ui.navigation.Screen
import de.findesk.ui.screens.*
import de.findesk.ui.theme.FinDeskTheme

@Composable
@Preview
fun App() {
    val navigationState = remember { NavigationState() }
    var isDarkTheme by remember { mutableStateOf(false) }

    FinDeskTheme(darkTheme = isDarkTheme) {
        Row(modifier = Modifier.fillMaxSize()) {
            // Sidebar
            Sidebar(
                currentScreen = navigationState.currentScreen,
                onScreenSelected = { navigationState.navigateTo(it) }
            )

            // Main Content
            Column(modifier = Modifier.fillMaxSize()) {
                // Top Bar
                TopBar(
                    title = navigationState.currentScreen.title,
                    isDarkTheme = isDarkTheme,
                    onThemeToggle = { isDarkTheme = !isDarkTheme }
                )

                HorizontalDivider()

                // Screen Content
                Box(modifier = Modifier.fillMaxSize()) {
                    when (navigationState.currentScreen) {
                        Screen.DASHBOARD -> DashboardScreen()
                        Screen.ACCOUNTS -> AccountsScreen()
                        Screen.TRANSACTIONS -> TransactionsScreen()
                        Screen.PAYMENTS -> PaymentsPlaceholderScreen()
                        Screen.DOCUMENTS -> DocumentsScreen()
                        Screen.REPORTS -> ReportsScreen()
                        Screen.SETTINGS -> SettingsPlaceholderScreen()
                    }
                }
            }
        }
    }
}

fun main() = application {
    // Initialize database
    DatabaseFactory.init()

    val windowState = rememberWindowState()
    Window(
        onCloseRequest = ::exitApplication,
        title = "FinDesk - Your Financial Workspace",
        state = windowState
    ) {
        App()
    }
}