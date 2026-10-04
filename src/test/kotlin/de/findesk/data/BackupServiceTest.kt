package de.findesk.data

import de.findesk.TestData
import de.findesk.data.backup.BackupService
import de.findesk.data.database.DatabaseFactory
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.FileOutputStream
import java.time.LocalDate
import java.util.zip.ZipEntry
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
}
