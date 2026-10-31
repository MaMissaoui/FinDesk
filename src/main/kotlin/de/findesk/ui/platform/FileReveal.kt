package de.findesk.ui.platform

import de.findesk.i18n.StringKey
import java.awt.Desktop
import java.io.File

/** What the file manager is called here: Finder, Explorer, or a plain folder on Linux. */
internal fun revealLabel(): StringKey = when {
    Platform.isMac -> StringKey.ShowInFinder
    Platform.isWindows -> StringKey.ShowInExplorer
    else -> StringKey.ShowInFolder
}

/** Shows [file] selected in the file manager; where the JDK can't select it (Linux), opens its folder instead. */
internal fun revealInFileManager(file: File) {
    if (!file.exists()) return
    Thread {
        runCatching {
            val desktop = Desktop.getDesktop()
            when {
                desktop.isSupported(Desktop.Action.BROWSE_FILE_DIR) -> desktop.browseFileDirectory(file)
                Platform.isWindows -> ProcessBuilder("explorer.exe", "/select,", file.absolutePath).start()
                else -> desktop.open(file.parentFile)
            }
        }
    }.start()
}
