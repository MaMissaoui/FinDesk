package de.findesk.data.backup

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.sql.DriverManager
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
 * Restoring replaces the live database, so it is two-phase: [stageRestore] validates the backup and unpacks
 * it next to the data; [applyPendingRestore] (called at startup before the database opens) moves the current
 * data aside into `pre-restore-<timestamp>/` and puts the backup in place.
 */
class BackupService(private val dataDir: File) {
    data class BackupInfo(val created: String, val appVersion: String, val transactions: Long, val documents: Int)

    companion object {
        const val DB_NAME = "findesk.db"
        private const val MANIFEST = "findesk-backup.properties"
        private const val PENDING_DIR = "restore-pending"
        private val CONTENT_DIRS = listOf("documents", "passports")
        private const val MAX_ENTRY_BYTES = 2L * 1024 * 1024 * 1024
        private val STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
    }

    val hasPendingRestore: Boolean get() = File(dataDir, PENDING_DIR).isDirectory

    /** Writes a backup of the current data to [target]. Safe while the app is running. */
    fun createBackup(target: File, appVersion: String = "0.1.0"): BackupInfo {
        val tmpDb = File.createTempFile("findesk-backup", ".db").apply { delete() }
        try {
            // VACUUM INTO gives a consistent, compacted copy even while the app has the database open.
            // It can't run inside a transaction, so use a plain autocommit connection.
            DriverManager.getConnection("jdbc:sqlite:${File(dataDir, DB_NAME).absolutePath}").use { c ->
                c.createStatement().use { it.execute("VACUUM INTO '${tmpDb.absolutePath.replace("'", "''")}'") }
            }
            val transactions = countRows(tmpDb, "transactions")
            val documentFiles = File(dataDir, "documents").listFiles()?.count { it.isFile } ?: 0
            val info = BackupInfo(LocalDateTime.now().toString(), appVersion, transactions, documentFiles)

            target.parentFile?.mkdirs()
            ZipOutputStream(FileOutputStream(target)).use { zip ->
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
                    File(dataDir, dirName).listFiles()?.filter { it.isFile }?.sortedBy { it.name }?.forEach { file ->
                        zip.putNextEntry(ZipEntry("$dirName/${file.name}"))
                        file.inputStream().use { it.copyTo(zip) }
                        zip.closeEntry()
                    }
                }
            }
            return info
        } finally {
            tmpDb.delete()
        }
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
     * happens on the next start.
     */
    fun stageRestore(backup: File): BackupInfo {
        val info = inspect(backup)
        val pending = File(dataDir, PENDING_DIR)
        pending.deleteRecursively()
        pending.mkdirs()
        try {
            ZipFile(backup).use { zip ->
                zip.entries().asSequence().filter { !it.isDirectory && it.name != MANIFEST }.forEach { entry ->
                    val out = safeTarget(pending, entry.name)
                    require(entry.size <= MAX_ENTRY_BYTES) { "Entry ${entry.name} is too large" }
                    out.parentFile.mkdirs()
                    zip.getInputStream(entry).use { input -> Files.copy(input, out.toPath(), StandardCopyOption.REPLACE_EXISTING) }
                }
            }
            val db = File(pending, DB_NAME)
            require(countRows(db, "accounts") >= 0) { "Backup database is unreadable" }
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
        val previous = File(dataDir, "pre-restore-${LocalDateTime.now().format(STAMP)}").apply { mkdirs() }
        (listOf(DB_NAME, "$DB_NAME-wal", "$DB_NAME-shm") + CONTENT_DIRS).forEach { name ->
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

    private fun countRows(db: File, table: String): Long = DriverManager.getConnection("jdbc:sqlite:${db.absolutePath}").use { c ->
        c.createStatement().use { st -> st.executeQuery("SELECT COUNT(*) FROM $table").use { rs -> if (rs.next()) rs.getLong(1) else 0 } }
    }
}
