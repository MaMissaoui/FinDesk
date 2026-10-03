package de.findesk

import de.findesk.data.database.DatabaseFactory
import java.io.File
import java.nio.file.Files

/**
 * Connects to a fresh SQLite database in a temp directory.
 * Never call DatabaseFactory.init() without a dataDir in tests - it targets ~/.findesk.
 */
object TestDatabase {
    fun init(): File {
        val dir = Files.createTempDirectory("findesk-test").toFile()
        dir.deleteOnExit()
        DatabaseFactory.init(dir.absolutePath)
        return dir
    }
}
