package de.findesk.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.ui.graphics.vector.ImageVector

enum class Screen(val title: String, val icon: ImageVector) {
    DASHBOARD("Dashboard", Icons.Default.Home),
    ACCOUNTS("Accounts", Icons.Default.AccountBox),
    TRANSACTIONS("Transactions", Icons.Default.List),
    PAYMENTS("Payments", Icons.Default.ShoppingCart),
    DOCUMENTS("Documents", Icons.Default.Menu),
    REPORTS("Reports/Tax", Icons.Default.Info),
    SETTINGS("Settings", Icons.Default.Settings),
}

class NavigationState {
    var currentScreen: Screen = Screen.DASHBOARD
        private set

    fun navigateTo(screen: Screen) {
        currentScreen = screen
    }
}
