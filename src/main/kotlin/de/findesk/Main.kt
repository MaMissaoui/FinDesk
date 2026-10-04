package de.findesk

import androidx.compose.desktop.ui.tooling.preview.Preview
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import de.findesk.data.database.DatabaseFactory
import de.findesk.repository.SettingsRepositoryImpl
import de.findesk.ui.components.Sidebar
import de.findesk.ui.components.TopBar
import de.findesk.ui.navigation.NavigationArgs
import de.findesk.ui.navigation.NavigationState
import de.findesk.ui.navigation.Screen
import de.findesk.ui.screens.*
import de.findesk.ui.theme.FinDeskTheme
import de.findesk.viewmodel.SettingsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Composable
@Preview
fun App() {
    val navigationState = remember { NavigationState() }
    val settings = remember { SettingsRepositoryImpl() }
    val scope = rememberCoroutineScope()
    var isDarkTheme by remember {
        mutableStateOf(runCatching { settings.getValue(SettingsViewModel.KEY_DARK_THEME) == "true" }.getOrDefault(false))
    }
    val setDarkTheme: (Boolean) -> Unit = { enabled ->
        isDarkTheme = enabled
        scope.launch(Dispatchers.IO) { settings.setValue(SettingsViewModel.KEY_DARK_THEME, enabled.toString()) }
    }
    val navigate: (Screen, NavigationArgs) -> Unit = { screen, args -> navigationState.navigateTo(screen, args) }

    FinDeskTheme(darkTheme = isDarkTheme) {
        Row(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            Sidebar(
                currentScreen = navigationState.currentScreen,
                onScreenSelected = { navigationState.navigateTo(it) },
            )

            Column(modifier = Modifier.fillMaxSize()) {
                TopBar(
                    title = navigationState.currentScreen.title,
                    isDarkTheme = isDarkTheme,
                    onThemeToggle = { setDarkTheme(!isDarkTheme) },
                )

                HorizontalDivider()

                Box(modifier = Modifier.fillMaxSize()) {
                    // key() gives each visit a fresh screen (and view model) so navigation args are applied
                    key(navigationState.currentScreen, navigationState.args) {
                        when (navigationState.currentScreen) {
                            Screen.DASHBOARD -> DashboardScreen(onNavigate = navigate)
                            Screen.ACCOUNTS -> AccountsScreen(onNavigate = navigate)
                            Screen.TRANSACTIONS -> TransactionsScreen(args = navigationState.args)
                            Screen.PAYMENTS -> PaymentsPlaceholderScreen()
                            Screen.DOCUMENTS -> DocumentsScreen()
                            Screen.REPORTS -> ReportsScreen()
                            Screen.SETTINGS -> SettingsScreen(darkTheme = isDarkTheme, onDarkThemeChange = setDarkTheme)
                        }
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
        state = windowState,
    ) {
        App()
    }
}
