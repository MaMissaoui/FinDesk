package de.findesk.data

import de.findesk.TestData
import de.findesk.data.backup.BackupService
import de.findesk.data.database.DatabaseFactory
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.FileOutputStream
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.time.LocalDate
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class BackupServiceTest {
    @TempDir
    lateinit var tmp: File

    @Test
    fun `backup and restore round trip keeps the replaced data`() {
        val dataDir = File(tmp, "data").apply { mkdirs() }
        DatabaseFactory.init(dataDir.absolutePath)
        val data = TestData()
        val acc = data.account()
        data.txn(acc, LocalDate.of(2026, 1, 1), "-10.00", partner = "Before backup")
        File(dataDir, "documents").mkdirs()
        File(dataDir, "documents/receipt.pdf").writeText("pdf-bytes")
        File(dataDir, "passports").mkdirs()
        File(dataDir, "passports/bank-1.dat").writeText("passport")

        val service = BackupService(dataDir)
        val backup = File(tmp, "backup.zip")
        val info = service.createBackup(backup)
        assertEquals(1, info.transactions)
        assertEquals(1, info.documents)
        assertEquals(info.transactions, service.inspect(backup).transactions)

        // Changes after the backup
        data.txn(acc, LocalDate.of(2026, 2, 1), "-20.00", partner = "After backup")
        File(dataDir, "documents/new.pdf").writeText("new")

        service.stageRestore(backup)
        assertTrue(service.hasPendingRestore)
        assertEquals(2, data.transactionRepo.findItems().size, "nothing changes before restart")

        // Restart
        DatabaseFactory.init(dataDir.absolutePath)
        val previous = DatabaseFactory.restoredFrom!!
        assertFalse(service.hasPendingRestore)
        assertEquals(listOf("Before backup"), TestData().transactionRepo.findItems().map { it.partnerName })
        assertEquals("pdf-bytes", File(dataDir, "documents/receipt.pdf").readText())
        assertFalse(File(dataDir, "documents/new.pdf").exists())
        assertEquals("passport", File(dataDir, "passports/bank-1.dat").readText())
        assertTrue(File(previous, "documents/new.pdf").exists(), "replaced data is kept")
        assertTrue(File(previous, BackupService.DB_NAME).exists())
    }

    @Test
    fun `documents still work after restoring into a different data directory`() {
        val oldDir = File(tmp, "old-home").apply { mkdirs() }
        val newDir = File(tmp, "new-home").apply { mkdirs() }
        val previous = System.getProperty("findesk.home")
        try {
            System.setProperty("findesk.home", oldDir.absolutePath)
            DatabaseFactory.init(oldDir.absolutePath)
            val oldDocs = File(oldDir, "documents").apply { mkdirs() }
            val stored = File(oldDocs, "1234-uuid_receipt.png").apply { writeText("png") }
            TestData().documentRepo.create("receipt.png", stored.absolutePath, "image/png")
            val backup = File(tmp, "move.zip")
            BackupService(oldDir).createBackup(backup)
            oldDir.deleteRecursively() // e.g. a new machine

            System.setProperty("findesk.home", newDir.absolutePath)
            BackupService(newDir).stageRestore(backup)
            DatabaseFactory.init(newDir.absolutePath)

            val doc = TestData().documentRepo.findItems().single()
            assertTrue(File(doc.filePath).exists(), "resolved to ${doc.filePath}")
            assertTrue(doc.filePath.startsWith(newDir.canonicalPath) || doc.filePath.startsWith(newDir.absolutePath))
            assertNotNull(de.findesk.document.DocumentPreview.render(File(doc.filePath)) ?: Unit)

            val service = de.findesk.document.DocumentService(
                ocrService = de.findesk.document.OcrService(engine = null),
                documentsDir = File(newDir, "documents"),
            )
            kotlinx.coroutines.runBlocking { assertTrue(service.deleteDocument(doc.id)) }
            assertFalse(File(doc.filePath).exists(), "file deleted from the new location")
        } finally {
            if (previous == null) System.clearProperty("findesk.home") else System.setProperty("findesk.home", previous)
        }
    }

    private fun zip(file: File, entries: Map<String, String>) = file.also {
        ZipOutputStream(FileOutputStream(it)).use { z ->
            entries.forEach { (name, content) ->
                z.putNextEntry(ZipEntry(name))
                z.write(content.toByteArray())
                z.closeEntry()
            }
        }
    }

    @Test
    fun `rejects foreign zips and path traversal`() {
        val dataDir = File(tmp, "d2").apply { mkdirs() }
        val service = BackupService(dataDir)

        assertThrows<IllegalArgumentException> { service.inspect(zip(File(tmp, "random.zip"), mapOf("a.txt" to "x"))) }

        val evil = zip(
            File(tmp, "evil.zip"),
            mapOf("findesk-backup.properties" to "created=x", "findesk.db" to "x", "documents/../../escape.txt" to "pwned"),
        )
        assertThrows<IllegalArgumentException> { service.stageRestore(evil) }
        assertFalse(File(tmp, "escape.txt").exists())
        assertFalse(service.hasPendingRestore, "failed staging leaves nothing behind")

        val corrupt = zip(File(tmp, "corrupt.zip"), mapOf("findesk-backup.properties" to "created=x", "findesk.db" to "not a database"))
        assertThrows<Exception> { service.stageRestore(corrupt) }
        assertFalse(service.hasPendingRestore)
    }

    @Test
    fun `a staged restore can be cancelled`() {
        val dataDir = File(tmp, "d3").apply { mkdirs() }
        DatabaseFactory.init(dataDir.absolutePath)
        TestData().account()
        val service = BackupService(dataDir)
        val backup = File(tmp, "b.zip")
        service.createBackup(backup)
        service.stageRestore(backup)
        service.cancelPendingRestore()
        assertFalse(service.hasPendingRestore)
        DatabaseFactory.init(dataDir.absolutePath)
        assertNull(DatabaseFactory.restoredFrom)
    }

    @Test
    fun `a restore that unpacks more than the cap is rejected`() {
        val dataDir = File(tmp, "d4").apply { mkdirs() }
        val big = zip(
            File(tmp, "big.zip"),
            mapOf("findesk-backup.properties" to "created=x", "findesk.db" to "x", "documents/a.pdf" to "a".repeat(600), "documents/b.pdf" to "b".repeat(600)),
        )

        val error = assertThrows<IllegalArgumentException> { BackupService(dataDir, maxRestoreBytes = 1_000).stageRestore(big) }

        assertEquals("Backup is too large to restore", error.message)
        assertFalse(BackupService(dataDir).hasPendingRestore, "failed staging leaves nothing behind")
    }

    @Test
    fun `a backup that could not be restored is refused`() {
        val dataDir = File(tmp, "d6").apply { mkdirs() }
        DatabaseFactory.init(dataDir.absolutePath)
        TestData().account()
        File(dataDir, "documents").mkdirs()
        File(dataDir, "documents/scan.pdf").writeBytes(ByteArray(64 * 1024))
        val target = File(tmp, "b6.zip")

        val error = assertThrows<IllegalArgumentException> { BackupService(dataDir, maxRestoreBytes = 32 * 1024).createBackup(target) }

        assertEquals("Backup is too large to restore", error.message)
        assertFalse(target.exists())
        assertTrue(dataDir.listFiles()!!.none { it.name.endsWith(".db.tmp") }, "temporary copy removed")
    }

    @Test
    fun `the backup's temporary database copy stays out of the shared temp dir`() {
        val dataDir = File(tmp, "d5").apply { mkdirs() }
        DatabaseFactory.init(dataDir.absolutePath)
        TestData().account()
        val seen = mutableListOf<String>()
        val service = BackupService(dataDir, connect = { file ->
            DatabaseFactory.rawConnection(file).also { seen += file.parentFile.canonicalPath }
        })

        service.createBackup(File(tmp, "b5.zip"))

        assertTrue(seen.isNotEmpty())
        assertTrue(seen.all { it == dataDir.canonicalPath }, "copies opened in $seen")
        assertTrue(dataDir.listFiles()!!.none { it.name.endsWith(".db.tmp") }, "temporary copy removed")
    }

    @Test
    fun `backup file is owner-only on POSIX`() {
        assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"))

        val dataDir = File(tmp, "d-owner").apply { mkdirs() }
        DatabaseFactory.init(dataDir.absolutePath)
        TestData().account()
        val target = File(tmp, "owner-existing.zip")
        target.writeText("old")
        Files.setPosixFilePermissions(target.toPath(), setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.GROUP_READ, PosixFilePermission.OTHERS_READ))

        BackupService(dataDir).createBackup(target)

        val perms = Files.getPosixFilePermissions(target.toPath())
        assertEquals(setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE), perms)
    }

    @Test
    fun `symlinks in content directories are skipped`() {
        val dataDir = File(tmp, "d-symlink").apply { mkdirs() }
        DatabaseFactory.init(dataDir.absolutePath)
        TestData().account()
        val documentsDir = File(dataDir, "documents").apply { mkdirs() }
        val outside = File(tmp, "outside-secret.txt").apply { writeText("secret") }
        val link = File(documentsDir, "link.txt")
        try {
            Files.createSymbolicLink(link.toPath(), outside.toPath())
        } catch (e: Exception) {
            assumeTrue(false, "symbolic links not supported")
        }
        File(documentsDir, "regular.txt").writeText("regular")

        val target = File(tmp, "symlink.zip")
        val info = BackupService(dataDir).createBackup(target)

        assertEquals(1, info.documents)
        val entries = ZipFile(target).use { zip -> zip.entries().toList().map { it.name } }
        assertTrue(entries.contains("documents/regular.txt"))
        assertFalse(entries.contains("documents/link.txt"))
    }

    @Test
    fun `failed backup leaves no target and no temp zip on POSIX`() {
        assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"))
        assumeTrue(System.getProperty("user.name") != "root")

        val dataDir = File(tmp, "d-fail").apply { mkdirs() }
        DatabaseFactory.init(dataDir.absolutePath)
        TestData().account()
        val documentsDir = File(dataDir, "documents").apply { mkdirs() }
        val bad = File(documentsDir, "unreadable.pdf").apply { writeText("x") }
        val target = File(tmp, "fail.zip")

        try {
            Files.setPosixFilePermissions(bad.toPath(), emptySet())

            assertThrows<Exception> { BackupService(dataDir).createBackup(target) }

            assertFalse(target.exists(), "target must not exist on failure")
            assertTrue(target.parentFile.listFiles()?.none { it.name.endsWith(".zip.tmp") } ?: true, "no temp zip left behind")
        } finally {
            Files.setPosixFilePermissions(bad.toPath(), setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE))
        }
    }
}
