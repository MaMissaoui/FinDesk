package de.findesk.util

import de.findesk.TestData
import de.findesk.banking.ProtocolTrace
import de.findesk.banking.ProtocolTraceStore
import de.findesk.data.database.DatabaseFactory
import de.findesk.repository.AuditLogRepositoryImpl
import de.findesk.repository.TransactionRepositoryImpl
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.LocalDate

class ProfileSwitchTest {
    @TempDir
    lateinit var root: File

    private val previousProfile = AppPaths.activeProfile

    @AfterEach
    fun restore() {
        AppPaths.activeProfile = previousProfile
        // Later tests open their own plaintext databases; don't leave a keyed pool or a closed one behind
        DatabaseFactory.init(File(root, "after").apply { mkdirs() }.absolutePath)
    }

    @Test
    fun `switching closes the open profile and the next one sees only its own data`() {
        val store = ProfileStore(root)
        val business = store.create("Business")
        val personalDir = ProfileSwitch.dataDir(null, root)
        val businessDir = ProfileSwitch.dataDir(business.slug, root)
        assertEquals(business.dir.absoluteFile, businessDir.absoluteFile)

        DatabaseFactory.init(personalDir.absolutePath, "personal passphrase")
        TestData().apply { txn(account(), LocalDate.of(2026, 10, 1), "-42.00", partner = "Personal Grocer") }
        val personalDb = DatabaseFactory.currentDatabase
        ProtocolTraceStore.publish(ProtocolTrace(java.time.LocalDateTime.now(), "DKB", "12030000", "300", emptyList()))

        ProfileSwitch.closeAndActivate(business.slug, store)

        assertFalse(DatabaseFactory.isOpen)
        assertFalse(DatabaseFactory.isEncrypted, "the previous profile's key is no longer held")
        assertNull(TransactionManager.managerFor(personalDb), "Exposed no longer holds the previous database, nor the pool behind it")
        assertThrows<Exception>("a transaction left over from the old profile fails instead of reaching the next one") {
            AuditLogRepositoryImpl().log("LATE_WRITE", "user", "after the switch")
        }
        assertNull(ProtocolTraceStore.last(), "the previous profile's bank trace is forgotten")
        assertEquals(business.slug, AppPaths.activeProfile)
        assertEquals(business.slug, store.active().slug, "the next start opens the same profile")

        DatabaseFactory.init(businessDir.absolutePath, "business passphrase")
        assertTrue(TransactionRepositoryImpl().findAll().isEmpty(), "the business profile doesn't see personal bookings")
        assertTrue(AuditLogRepositoryImpl().findByAction("LATE_WRITE").isEmpty())

        // And back: the personal data is still there under its own passphrase
        ProfileSwitch.closeAndActivate(null, store)
        DatabaseFactory.init(personalDir.absolutePath, "personal passphrase")
        assertEquals(listOf("Personal Grocer"), TransactionRepositoryImpl().findAll().map { it.partnerName })
        assertNull(AppPaths.activeProfile)
    }
}
