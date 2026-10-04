package de.findesk.util

import java.io.File

/** Where FinDesk keeps its data. Override with the FINDESK_HOME environment variable (e.g. for a portable install). */
object AppPaths {
    val dataDir: File
        get() = System.getenv("FINDESK_HOME")?.takeIf { it.isNotBlank() }?.let(::File)
            ?: File(System.getProperty("user.home"), ".findesk")

    val documentsDir: File get() = File(dataDir, "documents")
}
