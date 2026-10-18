package de.findesk.util

import de.findesk.banking.ProtocolTraceStore
import de.findesk.data.database.DatabaseFactory
import java.io.File

/**
 * Switching profiles while FinDesk runs. Each profile is its own encrypted database, so a switch always goes through
 * the unlock gate: the main window is gone, the open database is closed, and only then is the next profile active.
 */
object ProfileSwitch {
    /** The data directory of [slug] (null is the default profile). */
    fun dataDir(slug: String?, rootDir: File = AppPaths.rootDir): File = slug?.let { ProfileStore.profileDir(rootDir, it) } ?: rootDir

    /**
     * Closes the open database (dropping its key) and makes [target] the active profile, now and at the next start.
     * Also forgets the last bank protocol trace, which belongs to the previous profile.
     */
    fun closeAndActivate(target: String?, store: ProfileStore = ProfileStore()) {
        DatabaseFactory.close()
        ProtocolTraceStore.clear()
        AppPaths.activeProfile = target
        store.setActive(target)
    }
}
