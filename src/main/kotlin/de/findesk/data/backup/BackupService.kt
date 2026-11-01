package de.findesk.data.backup

import de.findesk.data.database.DatabaseCrypto
import de.findesk.data.database.DatabaseFactory
import de.findesk.util.FileSecurity
import java.io.File
import java.io.InputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.sql.Connection
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Properties
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Backups are ZIP files with a consistent copy of the database (`VACUUM INTO`), the stored documents and
 * the FinTS passport files, plus a manifest.
 *
 * Writing starts at a private temp file that is owner-only from the first byte and is only moved into place
 * once the backup is complete; a failed run leaves no partial or world-readable ZIP behind.
 *
 * Restoring replaces the live database, so it is two-phase: [stageRestore] validates the backup and unpacks
 * it next to the data; [applyPendingRestore] (called at startup before the database opens) moves the current
 * data aside into `pre-restore-<timestamp>/` and puts the backup in place.
 *
 * An encrypted database stays encrypted in the backup (`VACUUM INTO` keeps the key), so a backup can only be
 * restored with the passphrase that was in effect when it was made.
 */
class BackupService(
    private val dataDir: File,
    /** Opens a database file with the open database's key (or none for a plaintext file). */
    private val connect: (File) -> Connection = { DatabaseFactory.rawConnection(it) },
    /** Upper bound for everything a restore unpacks, whatever sizes the ZIP declares. */
    private val maxRestoreBytes: Long = MAX_RESTORE_BYTES,
) {
    data class BackupInfo(val created: String, val appVersion: String, val transactions: Long, val documents: Int)

    /** The backup's database is encrypted with a passphrase other than the current one; pass it to [stageRestore]. */
    class BackupPassphraseRequiredException(val wrongPassphraseGiven: Boolean) : Exception("This backup was made with a different passphrase")

    companion object {
        const val DB_NAME = "findesk.db"
        internal const val MANIFEST = "findesk-backup.properties"

        /** Holds a staged restore until the next start; see `PlaintextLeftovers`. */
        const val PENDING_DIR = "restore-pending"

        /** `pre-restore-<timestamp>` folders hold the data a restore replaced. */
        const val PRE_RESTORE_PREFIX = "pre-restore-"
        private val CONTENT_DIRS = listOf("documents", "passports")
        private const val MAX_ENTRY_BYTES = 2L * 1024 * 1024 * 1024
        private const val MAX_RESTORE_BYTES = 8L * 1024 * 1024 * 1024
        private val STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
    }

    val hasPendingRestore: Boolean get() = File(dataDir, PENDING_DIR).isDirectory

    /** Writes a backup of the current data to [target]. Safe while the app is running. */
    fun createBackup(target: File, appVersion: String = "0.1.0"): BackupInfo {
        // Next to the data rather than in the shared temp dir: a plaintext database's copy is readable there,
        // and one left behind by a crash would outlive the data it came from
        val tmpDb = File.createTempFile("findesk-backup", ".db.tmp", dataDir).apply { delete() }
        val targetDir = target.absoluteFile.parentFile.apply { mkdirs() }
        val tmpZip = Files.createTempFile(targetDir.toPath(), "findesk-backup", ".zip.tmp")
        try {
            FileSecurity.secureFile(tmpZip.toFile())

            // VACUUM INTO gives a consistent, compacted copy even while the app has the database open.
            // It can't run inside a transaction, so use a plain autocommit connection.
            connect(File(dataDir, DB_NAME)).use { c ->
                c.createStatement().use { it.execute("VACUUM INTO '${tmpDb.absolutePath.replace("'", "''")}'") }
            }
            // Listed once, before the temp ZIP is opened for writing: when the target lies in documents/ or
            // passports/, a second listing would include the ZIP that is being written. The target and the temp
            // ZIP are excluded by path so an earlier backup at the same target is not packed into the new one.
            val excluded = setOf(tmpZip.toFile().canonicalFile, target.absoluteFile.canonicalFile)
            val contentByDir = CONTENT_DIRS.associateWith { dirName ->
                contentFiles(dirName).filter { it.canonicalFile !in excluded }
            }
            // Refused up front rather than written: stageRestore would reject it, leaving a backup that can't be restored
            val files = listOf(tmpDb) + contentByDir.values.flatten()
            require(files.all { it.length() <= MAX_ENTRY_BYTES } && files.sumOf { it.length() } <= maxRestoreBytes) {
                "Backup is too large to restore"
            }
            val transactions = countRows(tmpDb, "transactions")
            val documentFiles = contentByDir.getValue("documents").size
            val info = BackupInfo(LocalDateTime.now().toString(), appVersion, transactions, documentFiles)

            Files.newOutputStream(tmpZip).use { output ->
                ZipOutputStream(output).use { zip ->
                    zip.putNextEntry(ZipEntry(MANIFEST))
                    Properties().apply {
                        setProperty("created", info.created)
                        setProperty("appVersion", info.appVersion)
                        setProperty("transactions", info.transactions.toString())
                        setProperty("documents", info.documents.toString())
                    }.store(zip, "FinDesk backup")
                    zip.closeEntry()

                    zip.putNextEntry(ZipEntry(DB_NAME))
                    tmpDb.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()

                    CONTENT_DIRS.forEach { dirName ->
                        contentByDir.getValue(dirName).forEach { file ->
                            zip.putNextEntry(ZipEntry("$dirName/${file.name}"))
                            file.inputStream().use { it.copyTo(zip) }
                            zip.closeEntry()
                        }
                    }
                }
            }
            moveTmpZipIntoPlace(tmpZip, target.toPath())
            // A backup holds the encrypted database plus plaintext documents/passports; keep it owner-only
            FileSecurity.secureFile(target)
            return info
        } finally {
            tmpDb.delete()
            Files.deleteIfExists(tmpZip)
        }
    }

    private fun moveTmpZipIntoPlace(tmpZip: Path, target: Path) {
        try {
            Files.move(tmpZip, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: AtomicMoveNotSupportedException) {
            Files.move(tmpZip, target, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun contentFiles(dirName: String): List<File> {
        val dir = File(dataDir, dirName)
        return dir.listFiles()
            ?.filter { Files.isRegularFile(it.toPath(), LinkOption.NOFOLLOW_LINKS) }
            ?.sortedBy { it.name }
            .orEmpty()
    }

    /** Reads the manifest of a backup without changing anything. Throws if the file isn't a FinDesk backup. */
    fun inspect(backup: File): BackupInfo = ZipFile(backup).use { zip ->
        val manifest = zip.getEntry(MANIFEST) ?: throw IllegalArgumentException("Not a FinDesk backup (no manifest)")
        require(zip.getEntry(DB_NAME) != null) { "Backup contains no database" }
        val p = Properties().apply { zip.getInputStream(manifest).use { load(it) } }
        BackupInfo(p.getProperty("created", "?"), p.getProperty("appVersion", "?"), p.getProperty("transactions", "0").toLong(), p.getProperty("documents", "0").toInt())
    }

    /**
     * Validates [backup] and unpacks it into the pending-restore directory. Nothing live is touched; the restore
     * happens on the next start, where the startup gate asks for the backup's passphrase (or sets one for a
     * plaintext backup). Throws [BackupPassphraseRequiredException] when the backup can't be opened with the current
     * passphrase and [backupPassphrase] is missing or wrong, so a backup that can't be opened is never staged.
     */
    fun stageRestore(backup: File, backupPassphrase: String? = null): BackupInfo {
        val info = inspect(backup)
        val pending = File(dataDir, PENDING_DIR)
        pending.deleteRecursively()
        FileSecurity.secureDirectory(pending)
        try {
            ZipFile(backup).use { zip ->
                var budget = maxRestoreBytes
                zip.entries().asSequence().filter { !it.isDirectory && it.name != MANIFEST }.forEach { entry ->
                    val out = safeTarget(pending, entry.name)
                    require(entry.size <= MAX_ENTRY_BYTES) { "Entry ${entry.name} is too large" }
                    FileSecurity.secureDirectory(out.parentFile)
                    // Counted while copying: the sizes in the ZIP directory are the archive's own claim
                    val limit = minOf(MAX_ENTRY_BYTES, budget)
                    val written = zip.getInputStream(entry).use { input -> copyAtMost(input, out, limit) }
                    require(written <= limit) { "Backup is too large to restore" }
                    FileSecurity.secureFile(out)
                    budget -= written
                }
            }
            val db = File(pending, DB_NAME)
            val readable = try {
                countRows(db, "accounts")
            } catch (e: DatabaseCrypto.WrongPassphraseException) {
                if (backupPassphrase == null) throw BackupPassphraseRequiredException(wrongPassphraseGiven = false)
                try {
                    DatabaseCrypto.open(db, backupPassphrase).use { c -> countRows(c, "accounts") }
                } catch (e: DatabaseCrypto.WrongPassphraseException) {
                    throw BackupPassphraseRequiredException(wrongPassphraseGiven = true)
                }
            }
            require(readable >= 0) { "Backup database is unreadable" }
        } catch (e: Exception) {
            pending.deleteRecursively()
            throw e
        }
        return info
    }

    fun cancelPendingRestore() {
        File(dataDir, PENDING_DIR).deleteRecursively()
    }

    /**
     * If a restore is staged, moves the current database, documents and passports into `pre-restore-<timestamp>/`
     * and puts the staged ones in place. Must run before the database is opened. Returns the folder holding the
     * previous data, or null if there was nothing to restore.
     */
    fun applyPendingRestore(): File? {
        val pending = File(dataDir, PENDING_DIR)
        if (!File(pending, DB_NAME).isFile) {
            pending.deleteRecursively()
            return null
        }
        val previous = File(dataDir, "$PRE_RESTORE_PREFIX${LocalDateTime.now().format(STAMP)}").apply { mkdirs() }
        (listOf(DB_NAME, "$DB_NAME-wal", "$DB_NAME-shm", "$DB_NAME-journal") + CONTENT_DIRS).forEach { name ->
            val current = File(dataDir, name)
            if (current.exists()) Files.move(current.toPath(), File(previous, name).toPath())
        }
        (listOf(DB_NAME) + CONTENT_DIRS).forEach { name ->
            val staged = File(pending, name)
            if (staged.exists()) Files.move(staged.toPath(), File(dataDir, name).toPath())
        }
        pending.deleteRecursively()
        return previous
    }

    /** Resolves a ZIP entry inside [root], rejecting path traversal ("zip slip") and unexpected locations. */
    private fun safeTarget(root: File, name: String): File {
        val normalized = name.replace('\\', '/')
        require(!normalized.startsWith("/") && normalized.split('/').none { it == ".." }) { "Unsafe path in backup: $name" }
        require(normalized == DB_NAME || CONTENT_DIRS.any { normalized.startsWith("$it/") && normalized.count { c -> c == '/' } == 1 }) {
            "Unexpected file in backup: $name"
        }
        val target = File(root, normalized).canonicalFile
        require(target.path.startsWith(root.canonicalPath + File.separator)) { "Unsafe path in backup: $name" }
        return target
    }

    /** Copies up to [limit] bytes; returns more than [limit] if [input] had more, so the caller can reject it. */
    private fun copyAtMost(input: InputStream, out: File, limit: Long): Long = out.outputStream().use { output ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        var n = input.read(buffer)
        while (n >= 0 && total + n <= limit) {
            output.write(buffer, 0, n)
            total += n
            n = input.read(buffer)
        }
        if (n >= 0) total + n else total
    }

    private fun countRows(db: File, table: String): Long = connect(db).use { c -> countRows(c, table) }

    private fun countRows(c: Connection, table: String): Long = c.createStatement().use { st ->
        st.executeQuery("SELECT COUNT(*) FROM $table").use { rs -> if (rs.next()) rs.getLong(1) else 0 }
    }
}
