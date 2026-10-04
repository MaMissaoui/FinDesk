package de.findesk.util

import de.findesk.TestData
import de.findesk.banking.FinTSClient
import de.findesk.data.database.DatabaseFactory
import de.findesk.document.DocumentService
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.LocalDate

class ProfileStoreTest {
    @TempDir
    lateinit var root: File

    @Test
    fun `default profile is the root and others get unique slugs`() {
        val store = ProfileStore(root)
        assertEquals(listOf(ProfileStore.Profile(null, "Personal", root)), store.list())

        val business = store.create("  Business GmbH ")
        val second = store.create("business gmbh")
        val umlaut = store.create("Müller & Söhne")

        assertEquals("business-gmbh", business.slug)
        assertEquals("Business GmbH", business.name)
        assertEquals("business-gmbh-2", second.slug)
        assertEquals("muller-sohne", umlaut.slug)
        assertTrue(business.dir.isDirectory)
        assertEquals(File(root, "profiles/business-gmbh"), business.dir)
        assertEquals(listOf(null, "business-gmbh", "business-gmbh-2", "muller-sohne"), store.list().map { it.slug }, "default first, then by name")
    }

    @Test
    fun `names are validated and slugs cannot escape the profiles folder`() {
        val store = ProfileStore(root)
        assertThrows<IllegalArgumentException> { store.create("   ") }
        assertThrows<IllegalArgumentException> { store.create("x".repeat(41)) }
        assertEquals("etc-passwd", store.create("../../etc/passwd").slug)
        assertThrows<IllegalArgumentException> { ProfileStore.profileDir(root, "../escape") }
        assertThrows<IllegalArgumentException> { ProfileStore.profileDir(root, "a/b") }
        assertThrows<IllegalArgumentException> { ProfileStore.profileDir(root, "default") }
        assertThrows<IllegalArgumentException> { store.setActive("../escape") }
    }

    @Test
    fun `the active profile is remembered and falls back to the default when its folder is gone`() {
        val business = ProfileStore(root).create("Business")
        ProfileStore(root).setActive(business.slug)
        assertEquals(business.slug, ProfileStore(root).active().slug, "persisted across instances")

        ProfileStore(root).rename(null, "Privat")
        ProfileStore(root).rename(business.slug, "Firma")
        assertEquals(listOf("Privat", "Firma"), ProfileStore(root).list().map { it.name })
        assertEquals(business.slug, ProfileStore(root).active().slug, "rename keeps the choice")

        business.dir.deleteRecursively()
        assertNull(ProfileStore(root).active().slug)
    }

    @Test
    fun `delete moves the folder aside and refuses the default and the open profile`() {
        val store = ProfileStore(root)
        val business = store.create("Business")
        File(business.dir, "findesk.db").writeText("data")
        store.setActive(business.slug)

        assertThrows<IllegalArgumentException> { store.delete(business.slug!!, openSlug = business.slug) }
        assertThrows<IllegalArgumentException> { store.delete("default", openSlug = null) }

        val moved = store.delete(business.slug!!, openSlug = null)

        assertFalse(business.dir.exists())
        assertEquals("data", File(moved, "findesk.db").readText(), "nothing is erased")
        assertTrue(moved.parentFile == File(root, "profiles-deleted"))
        assertEquals(listOf<String?>(null), store.list().map { it.slug })
        assertNull(store.active().slug, "a deleted next-start choice falls back to the default")
    }

    @Test
    fun `profiles do not see each other's data, documents or passports`() {
        val previousHome = System.getProperty("findesk.home")
        val previousProfile = AppPaths.activeProfile
        try {
            System.setProperty("findesk.home", root.absolutePath)
            val business = ProfileStore(root).create("Business")

            AppPaths.activeProfile = null
            assertEquals(root, AppPaths.dataDir)
            DatabaseFactory.init(AppPaths.dataDir.absolutePath)
            TestData().apply { txn(account(), LocalDate.of(2026, 10, 1), "-12.00", partner = "Personal shop") }
            assertEquals(1, TestData().transactionRepo.findItems().size)

            AppPaths.activeProfile = business.slug
            assertEquals(business.dir, AppPaths.dataDir)
            DatabaseFactory.init(AppPaths.dataDir.absolutePath)
            assertTrue(TestData().transactionRepo.findItems().isEmpty(), "business profile starts empty")
            assertTrue(TestData().accountRepo.findAllItems().isEmpty())
            assertTrue(File(business.dir, "findesk.db").exists())
            assertEquals(File(business.dir, "documents"), DocumentService.defaultDocumentsDirectory())

            val passportDir = FinTSClient::class.java.getDeclaredField("passportDir").apply { isAccessible = true }.get(FinTSClient()) as File
            assertEquals(File(business.dir, "passports"), passportDir)

            AppPaths.activeProfile = null
            DatabaseFactory.init(AppPaths.dataDir.absolutePath)
            assertEquals(listOf("Personal shop"), TestData().transactionRepo.findItems().map { it.partnerName })
        } finally {
            AppPaths.activeProfile = previousProfile
            if (previousHome == null) System.clearProperty("findesk.home") else System.setProperty("findesk.home", previousHome)
        }
    }

    @Test
    fun `backup and restore of the default profile leave other profiles alone`() {
        val business = ProfileStore(root).create("Business")
        File(business.dir, "findesk.db").writeText("business data")
        File(business.dir, "passports").apply { mkdirs() }.resolve("bank-1.dat").writeText("business passport")
        DatabaseFactory.init(root.absolutePath)
        TestData().account()

        val service = de.findesk.data.backup.BackupService(root)
        val zipFile = File(root.parentFile, "personal-${System.nanoTime()}.zip")
        service.createBackup(zipFile)
        val entries = java.util.zip.ZipFile(zipFile).use { z -> z.entries().toList().map { it.name } }
        assertTrue(entries.none { it.startsWith("profiles") }, entries.toString())

        service.stageRestore(zipFile)
        DatabaseFactory.init(root.absolutePath) // applies the restore
        assertEquals("business data", File(business.dir, "findesk.db").readText())
        assertEquals("business passport", File(business.dir, "passports/bank-1.dat").readText())
        assertEquals(listOf(null, business.slug), ProfileStore(root).list().map { it.slug })
        zipFile.delete()
    }
}
