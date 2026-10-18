package de.findesk.ui.platform

import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import javax.swing.UIManager

/** What differs between macOS, Windows and Linux at the window level: menus, title bar, look and feel. */
object Platform {
    private val osName = System.getProperty("os.name").orEmpty().lowercase()
    val isMac: Boolean = osName.contains("mac")
    val isWindows: Boolean = osName.startsWith("windows")

    /** Height of the title bar strip the content runs under on macOS (see [unifyTitleBar]); the window buttons sit in it. */
    val titleBarHeight: Dp = if (isMac) 28.dp else 0.dp

    /** Must run before the first AWT or Compose window is created: these settings are read once. */
    fun configureProcess() {
        if (isMac) {
            // Compose's MenuBar is a Swing menu bar; this puts it at the top of the screen instead of inside the window
            System.setProperty("apple.laf.useScreenMenuBar", "true")
            // The app menu's name under `./gradlew run` (the installed app takes it from its bundle)
            System.setProperty("apple.awt.application.name", "FinDesk")
            // Windows follow the system's light/dark appearance unless a window sets its own (see setDarkAppearance)
            System.setProperty("apple.awt.application.appearance", "system")
        } else {
            // The in-window menu bar is Swing: without this it is drawn in Java's own "Metal" look, not the system's
            runCatching { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()) }
        }
    }

    /** macOS: lets the content run under a transparent title bar, as in Finder or Mail; the title text is hidden. */
    fun unifyTitleBar(window: ComposeWindow) {
        if (!isMac) return
        window.rootPane.putClientProperty("apple.awt.fullWindowContent", true)
        window.rootPane.putClientProperty("apple.awt.transparentTitleBar", true)
        window.rootPane.putClientProperty("apple.awt.windowTitleVisible", false)
    }

    /** macOS: window buttons, menus and dialogs of [window] follow the app's theme rather than the system's. */
    fun setDarkAppearance(window: ComposeWindow, dark: Boolean) {
        if (!isMac) return
        window.rootPane.putClientProperty("apple.awt.windowAppearance", if (dark) "NSAppearanceNameDarkAqua" else "NSAppearanceNameAqua")
    }
}
