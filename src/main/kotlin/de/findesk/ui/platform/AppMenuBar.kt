package de.findesk.ui.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyShortcut
import androidx.compose.ui.window.FrameWindowScope
import androidx.compose.ui.window.MenuBar
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowState
import de.findesk.i18n.StringKey
import de.findesk.i18n.Strings
import de.findesk.ui.navigation.NavigationState
import de.findesk.ui.navigation.Screen
import de.findesk.ui.navigation.ScreenRequest
import de.findesk.ui.navigation.screenShortcutKeys
import de.findesk.util.AppInfo
import java.awt.Desktop
import java.net.URI

/**
 * The menu bar, following each platform's conventions. On macOS it is the screen menu bar, shortcuts use ⌘, and
 * About, Settings… and Quit live in the system's app menu ([MacAppMenuHandlers]). On Windows and Linux it sits in the
 * window, shortcuts use Ctrl, File ends with Settings… and Exit, and Help holds About. Navigation goes through
 * [NavigationState.navigateTo], so the menu respects the lock while a payment is being sent.
 */
@Composable
fun FrameWindowScope.AppMenuBar(
    strings: Strings,
    navigation: NavigationState,
    windowState: WindowState,
    darkTheme: Boolean,
    onDarkThemeChange: (Boolean) -> Unit,
    onRequest: (ScreenRequest) -> Unit,
    onSettings: () -> Unit,
    onAbout: () -> Unit,
    onExit: () -> Unit,
) {
    val mac = Platform.isMac
    fun shortcut(key: Key, shift: Boolean = false) = if (mac) KeyShortcut(key, meta = true, shift = shift) else KeyShortcut(key, ctrl = true, shift = shift)
    MenuBar {
        Menu(strings[StringKey.MenuFile]) {
            Item(strings[StringKey.MenuNewTransaction], shortcut = shortcut(Key.N), onClick = { onRequest(ScreenRequest.NEW_TRANSACTION) })
            Item(strings[StringKey.MenuImportStatement], shortcut = shortcut(Key.I, shift = true), onClick = { onRequest(ScreenRequest.IMPORT_STATEMENT) })
            if (!mac) {
                Separator()
                Item(strings[StringKey.MenuSettings], shortcut = shortcut(Key.Comma), onClick = onSettings)
                Separator()
                // Windows closes with Alt+F4, Linux desktops with Ctrl+Q
                Item(
                    strings[StringKey.MenuExit],
                    shortcut = if (Platform.isWindows) KeyShortcut(Key.F4, alt = true) else KeyShortcut(Key.Q, ctrl = true),
                    onClick = onExit,
                )
            }
        }
        Menu(strings[StringKey.MenuView]) {
            Screen.entries.forEachIndexed { i, screen ->
                Item(
                    screen.title(strings),
                    shortcut = shortcut(screenShortcutKeys[i]),
                    enabled = navigation.lockedOn == null || navigation.lockedOn == screen,
                    onClick = { navigation.navigateTo(screen) },
                )
            }
            Separator()
            CheckboxItem(strings[StringKey.DarkTheme], checked = darkTheme, shortcut = shortcut(Key.D, shift = true), onCheckedChange = onDarkThemeChange)
        }
        // A macOS convention; Windows and Linux windows carry these buttons in their title bar
        if (mac) {
            Menu(strings[StringKey.MenuWindow]) {
                Item(strings[StringKey.MenuMinimize], shortcut = shortcut(Key.M), onClick = { windowState.isMinimized = true })
                Item(strings[StringKey.MenuZoom], onClick = {
                    windowState.placement = if (windowState.placement == WindowPlacement.Maximized) WindowPlacement.Floating else WindowPlacement.Maximized
                })
            }
        }
        Menu(strings[StringKey.MenuHelp]) {
            Item(strings[StringKey.MenuGitHub], onClick = { browse(AppInfo.HOMEPAGE) })
            Item(strings[StringKey.MenuReportProblem], onClick = { browse("${AppInfo.HOMEPAGE}/issues/new") })
            if (!mac) {
                Separator()
                Item(strings[StringKey.AboutTitle], onClick = onAbout)
            }
        }
    }
}

/** macOS: hooks About and Settings… in the system's app menu (⌘, opens Settings) for as long as the caller is composed. */
@Composable
fun MacAppMenuHandlers(onAbout: () -> Unit, onSettings: () -> Unit) {
    if (!Platform.isMac || !Desktop.isDesktopSupported()) return
    val about by rememberUpdatedState(onAbout)
    val settings by rememberUpdatedState(onSettings)
    DisposableEffect(Unit) {
        val desktop = Desktop.getDesktop()
        if (desktop.isSupported(Desktop.Action.APP_ABOUT)) desktop.setAboutHandler { about() }
        if (desktop.isSupported(Desktop.Action.APP_PREFERENCES)) desktop.setPreferencesHandler { settings() }
        onDispose {
            if (desktop.isSupported(Desktop.Action.APP_ABOUT)) desktop.setAboutHandler(null)
            if (desktop.isSupported(Desktop.Action.APP_PREFERENCES)) desktop.setPreferencesHandler(null)
        }
    }
}

private fun browse(url: String) {
    runCatching { Desktop.getDesktop().browse(URI(url)) }
}
