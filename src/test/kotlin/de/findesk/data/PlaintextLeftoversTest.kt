package de.findesk.data

import de.findesk.data.backup.BackupService
import de.findesk.data.database.PlaintextLeftovers
import de.findesk.data.database.PlaintextLeftovers.Kind
import de.findesk.plaintextDatabase
import de.findesk.util.ProfileStore
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * [PlaintextLeftovers] backs the warning that encryption only covers the database FinDesk opens, so it has to
 * find every place the app leaves a copy behind — and must not cry wolf over an encrypted file or the live
 * database the startup gate is about to convert itself.
 */
class PlaintextLeftoversTest {
    @TempDir
    lateinit var root: File

    private val db = BackupService.DB_NAME
    private val backup = BackupService.PRE_RESTORE_PREFIX + "20260101-120000/$db"

    /** The tree a data directory accumulates: live database, replaced data, staged restore, profile, deleted profile. */
    private fun buildTree(write: (File) -> File) {
        write(File(root, db))
        write(File(root, backup))
        write(File(root, "${BackupService.PENDING_DIR}/$db"))
        write(File(root, "${ProfileStore.PROFILES_DIR}/business/$db"))
        write(File(root, "${ProfileStore.DELETED_DIR}/old-20260101-120000/$db"))
    }

    /** A SQLCipher database starts with a random salt, never with the SQLite header. */
    private fun encryptedDb(file: File): File {
        file.parentFile.mkdirs()
        return file.apply { writeBytes(ByteArray(80) { (it * 7 + 13).toByte() }) }
    }

    @Test
    fun `reports every copy that encryption cannot reach`() {
        buildTree(::plaintextDatabase)

        // The default profile is the open one, so its own database is left to the startup gate
        val found = PlaintextLeftovers.scan(root, root)

        assertEquals(
            listOf(
                backup to Kind.PRE_RESTORE,
                "${ProfileStore.DELETED_DIR}/old-20260101-120000/$db" to Kind.DELETED_PROFILE,
                "${ProfileStore.PROFILES_DIR}/business/$db" to Kind.OTHER_PROFILE,
                "${BackupService.PENDING_DIR}/$db" to Kind.PENDING_RESTORE,
            ),
            found.map { it.path to it.kind },
        )
        assertEquals(File(root, backup), found.first().file)
    }

    @Test
    fun `reports nothing when every copy is encrypted`() {
        buildTree(::encryptedDb)

        val found = PlaintextLeftovers.scan(root, root)

        assertTrue(found.isEmpty()) { "Encrypted copies must not be reported: ${found.map { it.path }}" }
    }

    @Test
    fun `leaves the database the startup gate encrypts alone`() {
        plaintextDatabase(File(root, db))

        assertTrue(PlaintextLeftovers.scan(root, root).isEmpty())
    }

    @Test
    fun `reports an unopened profile and skips the open one`() {
        val personal = plaintextDatabase(File(root, "${ProfileStore.PROFILES_DIR}/personal/$db"))
        plaintextDatabase(File(root, "${ProfileStore.PROFILES_DIR}/business/$db"))

        assertEquals(
            listOf("${ProfileStore.PROFILES_DIR}/business/$db" to Kind.OTHER_PROFILE),
            PlaintextLeftovers.scan(root, personal.parentFile).map { it.path to it.kind },
        )
    }

    @Test
    fun `reports nothing on a data directory without a database yet`() {
        assertTrue(PlaintextLeftovers.scan(root, root).isEmpty())
    }
}
