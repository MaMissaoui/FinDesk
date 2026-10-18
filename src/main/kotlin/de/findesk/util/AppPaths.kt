package de.findesk.util

import java.io.File

/**
 * Where FinDesk keeps its data: the `FINDESK_HOME` environment variable or `findesk.home` system property
 * (e.g. for a portable install or tests), otherwise `~/.findesk`. Each profile has its own data directory.
 */
object AppPaths {
    /** Holds the default profile's data and `profiles/` with the others (see [ProfileStore]). */
    val rootDir: File
        get() = (System.getProperty("findesk.home") ?: System.getenv("FINDESK_HOME"))?.takeIf { it.isNotBlank() }?.let(::File)
            ?: File(System.getProperty("user.home"), ".findesk")

    /**
     * The open profile; null is the default profile. Set in `main` before the database is opened, and changed only by
     * [ProfileSwitch.closeAndActivate], after the previous profile's database is closed and before the next one is
     * unlocked.
     */
    @Volatile
    var activeProfile: String? = null

    /** The active profile's data: findesk.db, documents/ and passports/. */
    val dataDir: File
        get() = activeProfile?.let { ProfileStore.profileDir(rootDir, it) } ?: rootDir

    val documentsDir: File get() = File(dataDir, "documents")

    /**
     * Stored document paths are absolute. After a restore into another data directory (or on another machine) they
     * point to the old location, so fall back to the current documents folder; stored names are unique (UUID prefix).
     */
    fun resolveDocument(storedPath: String): File {
        val stored = File(storedPath)
        if (stored.exists()) return stored
        val relocated = File(documentsDir, stored.name)
        return if (relocated.exists()) relocated else stored
    }
}
