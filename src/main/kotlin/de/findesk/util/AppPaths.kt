package de.findesk.util

import java.io.File

/**
 * Where FinDesk keeps its data: the `FINDESK_HOME` environment variable or `findesk.home` system property
 * (e.g. for a portable install or tests), otherwise `~/.findesk`.
 */
object AppPaths {
    val dataDir: File
        get() = (System.getProperty("findesk.home") ?: System.getenv("FINDESK_HOME"))?.takeIf { it.isNotBlank() }?.let(::File)
            ?: File(System.getProperty("user.home"), ".findesk")

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
