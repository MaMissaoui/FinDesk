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

/**
 * Global shortcuts: Ctrl/Cmd+1..7 open the screens in sidebar order, Ctrl/Cmd+, opens Settings.
 * Returns the screen to open, or null if the key isn't a shortcut.
 */
fun shortcutScreen(key: androidx.compose.ui.input.key.Key, ctrlOrMeta: Boolean): Screen? {
    if (!ctrlOrMeta) return null
    if (key == androidx.compose.ui.input.key.Key.Comma) return Screen.SETTINGS
    val digits = listOf(
        androidx.compose.ui.input.key.Key.One,
        androidx.compose.ui.input.key.Key.Two,
        androidx.compose.ui.input.key.Key.Three,
        androidx.compose.ui.input.key.Key.Four,
        androidx.compose.ui.input.key.Key.Five,
        androidx.compose.ui.input.key.Key.Six,
        androidx.compose.ui.input.key.Key.Seven,
    )
    return digits.indexOf(key).takeIf { it >= 0 }?.let { Screen.entries.getOrNull(it) }
}
