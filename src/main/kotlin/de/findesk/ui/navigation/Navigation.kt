package de.findesk.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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

/** Optional context passed when navigating, e.g. "show transactions of this account". */
data class NavigationArgs(val accountId: Int? = null, val uncategorizedOnly: Boolean = false)

class NavigationState {
    // Compose state: plain vars don't trigger recomposition, so the sidebar used to do nothing visible
    var currentScreen: Screen by mutableStateOf(Screen.DASHBOARD)
        private set

    var args: NavigationArgs by mutableStateOf(NavigationArgs())
        private set

    fun navigateTo(screen: Screen, args: NavigationArgs = NavigationArgs()) {
        this.args = args
        currentScreen = screen
    }
}
