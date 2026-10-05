package de.findesk.data.database

import org.sqlite.SQLiteErrorCode
import org.sqlite.SQLiteException
import org.sqlite.mc.SQLiteMCSqlCipherConfig
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.sql.Connection
import java.sql.DriverManager
import java.text.Normalizer
import java.util.HexFormat
import java.util.Properties

/**
 * Database encryption at rest (SQLite3MultipleCiphers in the SQLCipher v4 scheme: AES-256, PBKDF2-SHA512 with
 * 256 000 iterations). The passphrase is passed as connection properties only: never in a URL (URLs end up in
 * exception messages) and never as SQL through Exposed (its logger prints statements).
 *
 * There is no recovery: without the passphrase the data can't be read.
 */
object DatabaseCrypto {
    const val MIN_PASSPHRASE_LENGTH = 10
    private val PLAIN_HEADER = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)

    class WrongPassphraseException : Exception("Wrong passphrase")

    enum class State { NEW, PLAINTEXT, ENCRYPTED }

    fun state(db: File): State {
        if (!db.isFile || db.length() == 0L) return State.NEW
        val header = ByteArray(PLAIN_HEADER.size)
        val read = db.inputStream().use { it.readNBytes(header, 0, header.size) }
        return if (read == header.size && header.contentEquals(PLAIN_HEADER)) State.PLAINTEXT else State.ENCRYPTED
    }

    /** NFC, so the same passphrase typed with composed or decomposed umlauts gives the same key. */
    fun normalize(passphrase: String): String = Normalizer.normalize(passphrase, Normalizer.Form.NFC)

    fun connectionProperties(passphrase: String?): Properties = if (passphrase == null) {
        Properties()
    } else {
        SQLiteMCSqlCipherConfig.getV4Defaults().withKey(normalize(passphrase)).build().toProperties()
    }

    fun url(db: File) = "jdbc:sqlite:${db.absolutePath}"

    /** Opens [db] (plaintext when [passphrase] is null) and reads from it, so a wrong key fails here. */
    fun open(db: File, passphrase: String?): Connection {
        val connection = try {
            DriverManager.getConnection(url(db), connectionProperties(passphrase))
        } catch (e: SQLiteException) {
            throw if (e.resultCode == SQLiteErrorCode.SQLITE_NOTADB) WrongPassphraseException() else e
        }
        try {
            connection.createStatement().use { it.executeQuery("SELECT count(*) FROM sqlite_master").use { rs -> rs.next() } }
        } catch (e: SQLiteException) {
            connection.close()
            throw if (e.resultCode == SQLiteErrorCode.SQLITE_NOTADB) WrongPassphraseException() else e
        } catch (e: Exception) {
            connection.close()
            throw e
        }
        return connection
    }

    /** Throws [WrongPassphraseException] unless [passphrase] opens [db]. */
    fun verify(db: File, passphrase: String) {
        open(db, passphrase).close()
    }

    /**
     * Encrypts a plaintext [db] with [newPassphrase] ([oldPassphrase] null), or changes its passphrase. Works on a
     * copy: `VACUUM INTO` a temp file next to it, rekey the copy, check every table's row count against the source,
     * then atomically replace the original. The original is untouched if anything fails.
     *
     * Must run while nothing else has the database open (the startup gate, before [DatabaseFactory.init]).
     */
    fun reencrypt(db: File, oldPassphrase: String?, newPassphrase: String) {
        validateNew(newPassphrase)
        val tmp = File(db.parentFile, "${db.name}.encrypting")
        Files.deleteIfExists(tmp.toPath())
        try {
            val counts = open(db, oldPassphrase).use { source ->
                source.exec("VACUUM INTO '${tmp.absolutePath.replace("'", "''")}'")
                rowCounts(source)
            }
            // The copy has the source's key (or none); rekeying a plaintext file needs rollback journaling
            open(tmp, oldPassphrase).use { copy ->
                copy.exec("PRAGMA journal_mode = DELETE")
                if (oldPassphrase == null) {
                    copy.exec("PRAGMA cipher = 'sqlcipher'")
                    copy.exec("PRAGMA legacy = 4")
                }
                // Hex keeps quotes in the passphrase out of the SQL; hexrekey takes the passphrase, not a raw key
                copy.exec("PRAGMA hexrekey = '${HexFormat.of().formatHex(normalize(newPassphrase).toByteArray(Charsets.UTF_8))}'")
            }
            check(state(tmp) == State.ENCRYPTED) { "Encryption did not take effect" }
            open(tmp, newPassphrase).use { copy ->
                check(copy.queryString("PRAGMA quick_check") == "ok") { "Encrypted copy failed the integrity check" }
                check(rowCounts(copy) == counts) { "Encrypted copy does not match the original" }
            }
            Files.move(tmp.toPath(), db.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            listOf("-journal", "-wal", "-shm").forEach { File(db.parentFile, db.name + it).delete() }
        } finally {
            Files.deleteIfExists(tmp.toPath())
        }
    }

    fun validateNew(passphrase: String) {
        require(normalize(passphrase).length >= MIN_PASSPHRASE_LENGTH) { "The passphrase needs at least $MIN_PASSPHRASE_LENGTH characters" }
        require(passphrase.isNotBlank() && passphrase.trim() == passphrase) { "The passphrase can't start or end with a space" }
    }

    private fun rowCounts(c: Connection): Map<String, Long> {
        val tables = c.createStatement().use { st ->
            st.executeQuery("SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%'").use { rs ->
                generateSequence { if (rs.next()) rs.getString(1) else null }.toList()
            }
        }
        return tables.associateWith { name -> c.queryString("SELECT count(*) FROM \"${name.replace("\"", "\"\"")}\"")!!.toLong() }
    }

    private fun Connection.exec(sql: String) {
        createStatement().use { it.execute(sql) }
    }

    private fun Connection.queryString(sql: String): String? = createStatement().use { st -> st.executeQuery(sql).use { rs -> if (rs.next()) rs.getString(1) else null } }
}
