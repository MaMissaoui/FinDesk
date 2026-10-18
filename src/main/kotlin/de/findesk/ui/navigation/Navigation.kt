package de.findesk.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.vector.ImageVector
import de.findesk.i18n.StringKey
import de.findesk.i18n.Strings
import de.findesk.ui.components.AppIcons

enum class Screen(val icon: ImageVector) {
    DASHBOARD(Icons.Default.Home),
    ACCOUNTS(AppIcons.AccountBalance),
    TRANSACTIONS(Icons.Default.List),
    PAYMENTS(AppIcons.Send),
    DOCUMENTS(AppIcons.Description),
    REPORTS(AppIcons.BarChart),
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

/** Something a menu asks the target screen to open on arrival, e.g. File → New Transaction. */
enum class ScreenRequest { NEW_TRANSACTION, IMPORT_STATEMENT }

/** Optional context passed when navigating, e.g. "show transactions of this account". */
data class NavigationArgs(
    val accountId: Int? = null,
    val uncategorizedOnly: Boolean = false,
    /** Show every booking from this partner (search set to the name, other filters cleared). */
    val partner: String? = null,
    val request: ScreenRequest? = null,
    /** Makes a repeated request a new value, so the screen sees it again. */
    val requestId: Long = 0,
)

class NavigationState {
    // Compose state: plain vars don't trigger recomposition, so the sidebar used to do nothing visible
    var currentScreen: Screen by mutableStateOf(Screen.DASHBOARD)
        private set

    var args: NavigationArgs by mutableStateOf(NavigationArgs())
        private set

    /** Screen that must stay open, because an order is being sent from it; null while navigation is free. */
    var lockedOn: Screen? by mutableStateOf(null)
        private set

    fun lock(screen: Screen) {
        lockedOn = screen
    }

    fun unlock(screen: Screen) {
        if (lockedOn == screen) lockedOn = null
    }

    fun navigateTo(screen: Screen, args: NavigationArgs = NavigationArgs()) {
        // Leaving mid-send would cancel the bank's PIN/TAN prompts and hide the bank's answer
        if (lockedOn != null && screen != lockedOn) return
        this.args = args
        currentScreen = screen
    }
}

/** ⌘/Ctrl + 1…7 open the screens in sidebar order; the macOS View menu lists the same keys. */
val screenShortcutKeys: List<androidx.compose.ui.input.key.Key> = listOf(
    androidx.compose.ui.input.key.Key.One,
    androidx.compose.ui.input.key.Key.Two,
    androidx.compose.ui.input.key.Key.Three,
    androidx.compose.ui.input.key.Key.Four,
    androidx.compose.ui.input.key.Key.Five,
    androidx.compose.ui.input.key.Key.Six,
    androidx.compose.ui.input.key.Key.Seven,
)

/**
 * Global shortcuts: Ctrl/Cmd+1..7 open the screens in sidebar order, Ctrl/Cmd+, opens Settings.
 * Returns the screen to open, or null if the key isn't a shortcut.
 */
fun shortcutScreen(key: androidx.compose.ui.input.key.Key, ctrlOrMeta: Boolean): Screen? {
    if (!ctrlOrMeta) return null
    if (key == androidx.compose.ui.input.key.Key.Comma) return Screen.SETTINGS
    return screenShortcutKeys.indexOf(key).takeIf { it >= 0 }?.let { Screen.entries.getOrNull(it) }
}
