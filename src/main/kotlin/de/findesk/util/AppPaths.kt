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
     * Profile opened at startup; null is the default profile. Set once in `main` before the database is opened and
     * never changed while the app runs (switching applies at the next start).
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
