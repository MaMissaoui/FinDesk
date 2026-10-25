package de.findesk.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.graphics.vector.ImageVector
import de.findesk.i18n.StringKey
import de.findesk.i18n.Strings
import de.findesk.ui.components.AppIcons
import de.findesk.viewmodel.Period
import java.time.YearMonth

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

/** Settings' sections, in the order the pane lists them; the title key is also the section header's title. */
enum class SettingsSection(val title: StringKey) {
    APPEARANCE(StringKey.Appearance),
    PROFILES(StringKey.Profiles),
    CATEGORIES(StringKey.Categories),
    RULES(StringKey.AutoCategorizationRules),
    DATA(StringKey.DataAndOcr),
    BACKUP(StringKey.BackupAndRestore),
    TRACE(StringKey.ProtocolTraceTitle),
    AUDIT(StringKey.AuditLogShort),
}

/** Optional context passed when navigating, e.g. "show transactions of this account". */
data class NavigationArgs(
    val accountId: Int? = null,
    val uncategorizedOnly: Boolean = false,
    /** Show every booking from this partner (search set to the name, other filters cleared). */
    val partner: String? = null,
    /** Shows transactions of this category (null id = uncategorized); null keeps the screen's own filter. */
    val categoryId: Int? = null,
    /** The period the arriving transactions screen filters by; null keeps the screen's own period. */
    val period: Period? = null,

    /** Shows exactly this month; wins over [period]. */
    val month: YearMonth? = null,
    val request: ScreenRequest? = null,
    /** Makes a repeated request a new value, so the screen sees it again. */
    val requestId: Long = 0,
    /** Settings opens on this section; null starts on Appearance. */
    val settingsSection: SettingsSection? = null,
    /** Reports opens on the Tax bookings tab for this year; null keeps the screen's own start. */
    val taxYear: Int? = null,

    /** The args to reopen the screen this link leaves with when the user goes Back; null reopens it with the args it arrived with. */
    val backArgs: NavigationArgs? = null,
)

/** One step on the back stack: the screen a link left and the args to reopen it with. */
data class BackEntry(val screen: Screen, val args: NavigationArgs)

class NavigationState {
    // Compose state: plain vars don't trigger recomposition, so the sidebar used to do nothing visible
    var currentScreen: Screen by mutableStateOf(Screen.DASHBOARD)
        private set

    var args: NavigationArgs by mutableStateOf(NavigationArgs())
        private set

    /** Screen that must stay open, because an order is being sent from it; null while navigation is free. */
    var lockedOn: Screen? by mutableStateOf(null)
        private set

    /** Where links inside a screen have led, oldest first; at most [MAX_BACK] entries. Read-only: only [back] pops. */
    private val backStack: SnapshotStateList<BackEntry> = mutableStateListOf()

    /** The screen [back] would open, or null when the back stack is empty (then no Back button shows). */
    val backTarget: Screen?
        get() = backStack.lastOrNull()?.screen

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
        // Choosing a screen directly (sidebar, shortcuts, menus) starts a new trail
        backStack.clear()
    }

    /**
     * Navigation for links inside a screen. Pushes where the user leaves from ([NavigationArgs.backArgs] may name the
     * args to reopen that screen with), then navigates without clearing the stack.
     */
    fun follow(screen: Screen, args: NavigationArgs = NavigationArgs()) {
        // Same refusal as navigateTo: leaving mid-send would cancel the bank's PIN/TAN prompts
        if (lockedOn != null && screen != lockedOn) return
        // A link to the screen already shown changes only its args, so going back would reopen the same screen
        if (screen != currentScreen) {
            if (backStack.size == MAX_BACK) backStack.removeAt(0)
            // A menu request (e.g. File → New Transaction) is one-shot: Back must not open its dialog again
            backStack.add(BackEntry(currentScreen, (args.backArgs ?: this.args).copy(request = null)))
        }
        this.args = args
        currentScreen = screen
    }

    /** Opens the screen the last link left. False (and no change) when the stack is empty or a screen is locked. */
    fun back(): Boolean {
        val entry = backStack.lastOrNull() ?: return false
        if (lockedOn != null && entry.screen != lockedOn) return false
        backStack.removeAt(backStack.lastIndex)
        args = entry.args
        currentScreen = entry.screen
        return true
    }

    private companion object {
        /** Depth of the back stack; when full, the oldest entry is dropped. */
        const val MAX_BACK = 10
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
