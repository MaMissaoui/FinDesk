package de.findesk.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.vector.ImageVector
import de.findesk.i18n.StringKey
import de.findesk.i18n.Strings

enum class Screen(val icon: ImageVector) {
    DASHBOARD(Icons.Default.Home),
    ACCOUNTS(Icons.Default.AccountBox),
    TRANSACTIONS(Icons.Default.List),
    PAYMENTS(Icons.Default.ShoppingCart),
    DOCUMENTS(Icons.Default.Menu),
    REPORTS(Icons.Default.Info),
    SETTINGS(Icons.Default.Settings),
    ;

    fun title(s: Strings): String = when (this) {
        DASHBOARD -> s[StringKey.NavDashboard]
        ACCOUNTS -> s[StringKey.NavAccounts]
        TRANSACTIONS -> s[StringKey.NavTransactions]
        PAYMENTS -> s[StringKey.NavPayments]
        DOCUMENTS -> s[StringKey.NavDocuments]
        REPORTS -> s[StringKey.NavReports]
        SETTINGS -> s[StringKey.NavSettings]
    }
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
