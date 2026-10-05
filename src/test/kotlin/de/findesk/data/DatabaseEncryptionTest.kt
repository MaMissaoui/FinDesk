package de.findesk.data

import de.findesk.TestData
import de.findesk.data.backup.BackupService
import de.findesk.data.database.DatabaseCrypto
import de.findesk.data.database.DatabaseCrypto.State
import de.findesk.data.database.DatabaseFactory
import de.findesk.repository.AuditLogRepositoryImpl
import de.findesk.viewmodel.UnlockError
import de.findesk.viewmodel.UnlockMode
import de.findesk.viewmodel.UnlockViewModel
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.text.Normalizer
import java.time.LocalDate
import java.util.zip.ZipFile

class DatabaseEncryptionTest {
    @TempDir
    lateinit var tmp: File

    private val pass = "correct horse battery"

    @AfterEach
    fun closeEncryptedDatabase() {
        // Later tests in this JVM open their own plaintext databases; don't leave a keyed pool behind
        DatabaseFactory.init(File(tmp, "after").apply { mkdirs() }.absolutePath)
    }

    private fun dir(name: String) = File(tmp, name).apply { mkdirs() }

    private fun db(dir: File) = DatabaseFactory.databaseFile(dir)

    private fun rawContains(file: File, text: String) = String(file.readBytes(), Charsets.ISO_8859_1).contains(text)

    private fun seed(partner: String) = TestData().apply { txn(account(), LocalDate.of(2026, 10, 1), "-42.00", partner = partner) }

    @Test
    fun `a database opened with a passphrase is encrypted on disk and needs it to open again`() {
        val d = dir("enc")
        assertEquals(State.NEW, DatabaseCrypto.state(db(d)))
        DatabaseFactory.init(d.absolutePath, pass)
        seed("Secret Grocer")
        assertTrue(DatabaseFactory.isEncrypted)

        assertEquals(State.ENCRYPTED, DatabaseCrypto.state(db(d)))
        assertFalse(rawContains(db(d), "Secret Grocer"))
        assertThrows<DatabaseCrypto.WrongPassphraseException> { DatabaseCrypto.verify(db(d), "wrong passphrase") }
        assertThrows<DatabaseCrypto.WrongPassphraseException> { DatabaseCrypto.open(db(d), null) }
        assertThrows<DatabaseCrypto.WrongPassphraseException> { DatabaseFactory.init(d.absolutePath, "wrong passphrase") }

        DatabaseFactory.init(d.absolutePath, pass)
        assertEquals(listOf("Secret Grocer"), TestData().transactionRepo.findItems().map { it.partnerName })
    }

    @Test
    fun `encrypting a plaintext database keeps every row and leaves no plaintext copy`() {
        val d = dir("plain")
        DatabaseFactory.init(d.absolutePath)
        seed("Plain Bakery")
        AuditLogRepositoryImpl().log("TEST", "user", "before")
        assertEquals(State.PLAINTEXT, DatabaseCrypto.state(db(d)))
        DatabaseFactory.init(dir("elsewhere").absolutePath) // close the pool on the file

        DatabaseCrypto.reencrypt(db(d), null, pass)

        assertEquals(State.ENCRYPTED, DatabaseCrypto.state(db(d)))
        assertFalse(rawContains(db(d), "Plain Bakery"))
        assertEquals(listOf("findesk.db"), d.list()!!.filter { it.startsWith("findesk.db") }, "no temp or journal files left")
        DatabaseFactory.init(d.absolutePath, pass)
        assertEquals(listOf("Plain Bakery"), TestData().transactionRepo.findItems().map { it.partnerName })
        assertEquals(1, TestData().accountRepo.findAllItems().size)
        assertTrue(AuditLogRepositoryImpl().findRecent().any { it.action == "TEST" })
    }

    @Test
    fun `changing the passphrase retires the old one, and a wrong old one changes nothing`() {
        val d = dir("change")
        DatabaseFactory.init(d.absolutePath, pass)
        seed("Kept")
        DatabaseFactory.init(dir("elsewhere").absolutePath)
        val before = db(d).readBytes()

        assertThrows<DatabaseCrypto.WrongPassphraseException> { DatabaseCrypto.reencrypt(db(d), "not it at all", "another passphrase") }
        assertArrayEquals(before, db(d).readBytes(), "original untouched")

        val quoted = "it's \"quoted\" -- ; DROP"
        DatabaseCrypto.reencrypt(db(d), pass, quoted)
        assertThrows<DatabaseCrypto.WrongPassphraseException> { DatabaseCrypto.verify(db(d), pass) }
        DatabaseFactory.init(d.absolutePath, quoted)
        assertEquals(listOf("Kept"), TestData().transactionRepo.findItems().map { it.partnerName })
    }

    @Test
    fun `composed and decomposed umlauts are the same passphrase`() {
        val d = dir("nfc")
        val composed = Normalizer.normalize("Grüße aus Köln", Normalizer.Form.NFC)
        val decomposed = Normalizer.normalize(composed, Normalizer.Form.NFD)
        assertNotEquals(composed, decomposed)
        DatabaseFactory.init(d.absolutePath, composed)
        DatabaseCrypto.verify(db(d), decomposed)
    }

    @Test
    fun `new passphrases need a minimum length and no surrounding spaces`() {
        assertThrows<IllegalArgumentException> { DatabaseCrypto.validateNew("short") }
        assertThrows<IllegalArgumentException> { DatabaseCrypto.validateNew(" leading space here") }
        assertThrows<IllegalArgumentException> { DatabaseCrypto.validateNew("trailing space here ") }
        DatabaseCrypto.validateNew("long enough phrase")
    }

    @Test
    fun `backups of an encrypted database stay encrypted and restore with the right passphrase`() {
        val d = dir("backup")
        DatabaseFactory.init(d.absolutePath, pass)
        seed("Backed Up Shop")
        val zip = File(tmp, "b.zip")
        val info = BackupService(d).createBackup(zip)
        assertEquals(1, info.transactions)

        val dbEntry = ZipFile(zip).use { z -> z.getInputStream(z.getEntry(BackupService.DB_NAME)).readBytes() }
        assertFalse(String(dbEntry, Charsets.ISO_8859_1).startsWith("SQLite format 3"))
        assertFalse(String(dbEntry, Charsets.ISO_8859_1).contains("Backed Up Shop"))

        // Same passphrase: staged without asking
        BackupService(d).stageRestore(zip)
        BackupService(d).cancelPendingRestore()

        // Another profile with another passphrase needs the backup's passphrase
        val other = dir("other")
        DatabaseFactory.init(other.absolutePath, "a different passphrase")
        val service = BackupService(other)
        assertFalse(assertThrows<BackupService.BackupPassphraseRequiredException> { service.stageRestore(zip) }.wrongPassphraseGiven)
        assertTrue(assertThrows<BackupService.BackupPassphraseRequiredException> { service.stageRestore(zip, "wrong one entirely") }.wrongPassphraseGiven)
        assertFalse(service.hasPendingRestore, "an unopenable backup is never staged")
        service.stageRestore(zip, pass)
        assertTrue(service.hasPendingRestore)

        // Next start: the restored file opens with the backup's passphrase
        DatabaseFactory.applyPendingRestore(other)
        assertNotNull(DatabaseFactory.restoredFrom)
        assertThrows<DatabaseCrypto.WrongPassphraseException> { DatabaseCrypto.verify(db(other), "a different passphrase") }
        DatabaseFactory.init(other.absolutePath, pass)
        assertEquals(listOf("Backed Up Shop"), TestData().transactionRepo.findItems().map { it.partnerName })
    }

    @Test
    fun `the startup gate creates, encrypts, unlocks and changes, with audit entries`() = runBlocking {
        // New profile: choose a passphrase
        val fresh = dir("fresh")
        UnlockViewModel(fresh).use { vm ->
            assertEquals(UnlockMode.CREATE, vm.state.value.mode)
            vm.setPassphrase("too short", "too short", true).join()
            assertEquals(UnlockError.TOO_SHORT, vm.state.value.error)
            vm.setPassphrase(pass, "$pass!", true).join()
            assertEquals(UnlockError.MISMATCH, vm.state.value.error)
            vm.setPassphrase(pass, pass, false).join()
            assertEquals(UnlockError.NOT_ACKNOWLEDGED, vm.state.value.error)
            assertEquals(State.NEW, DatabaseCrypto.state(db(fresh)), "nothing written before a valid choice")
            vm.setPassphrase(pass, pass, true).join()
            assertTrue(vm.state.value.opened)
        }
        assertEquals(State.ENCRYPTED, DatabaseCrypto.state(db(fresh)))
        assertEquals("New encrypted database", AuditLogRepositoryImpl().findRecent().single { it.action == "DATABASE_ENCRYPTED" }.details)

        // Existing plaintext data (e.g. from before encryption)
        val plain = dir("legacy")
        DatabaseFactory.init(plain.absolutePath)
        seed("Old Data")
        DatabaseFactory.init(dir("elsewhere").absolutePath)
        UnlockViewModel(plain).use { vm ->
            assertEquals(UnlockMode.ENCRYPT, vm.state.value.mode)
            vm.setPassphrase(pass, pass, true).join()
            assertTrue(vm.state.value.opened)
        }
        assertEquals(listOf("Old Data"), TestData().transactionRepo.findItems().map { it.partnerName })
        assertFalse(rawContains(db(plain), "Old Data"))

        // Unlock: a wrong passphrase is rejected, the right one opens
        DatabaseFactory.init(dir("elsewhere").absolutePath)
        UnlockViewModel(plain).use { vm ->
            assertEquals(UnlockMode.UNLOCK, vm.state.value.mode)
            vm.unlock("wrong passphrase").join()
            assertEquals(UnlockError.WRONG_PASSPHRASE, vm.state.value.error)
            assertFalse(vm.state.value.opened)
            vm.unlock(pass).join()
            assertTrue(vm.state.value.opened)
        }

        // Change: needs the current passphrase
        val newPass = "a brand new passphrase"
        DatabaseFactory.init(dir("elsewhere").absolutePath)
        UnlockViewModel(plain).use { vm ->
            vm.showChange(true)
            assertEquals(UnlockMode.CHANGE, vm.state.value.mode)
            vm.changePassphrase("wrong passphrase", newPass, newPass, true).join()
            assertEquals(UnlockError.WRONG_PASSPHRASE, vm.state.value.error)
            vm.changePassphrase(pass, newPass, newPass, true).join()
            assertTrue(vm.state.value.opened)
        }
        assertTrue(AuditLogRepositoryImpl().findRecent().any { it.action == "DATABASE_PASSPHRASE_CHANGED" })
        assertThrows<DatabaseCrypto.WrongPassphraseException> { DatabaseCrypto.verify(db(plain), pass) }
        DatabaseCrypto.verify(db(plain), newPass)
    }
}
