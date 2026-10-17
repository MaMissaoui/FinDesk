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

/**
 * Writes a file that looks like an unencrypted SQLite database. [de.findesk.data.database.DatabaseCrypto.state]
 * only reads the header, which is exactly what "is this copy encrypted" comes down to.
 */
fun plaintextDatabase(file: File): File {
    file.parentFile?.mkdirs()
    return file.apply { writeBytes("SQLite format 3\u0000".toByteArray(Charsets.US_ASCII) + ByteArray(64)) }
}
