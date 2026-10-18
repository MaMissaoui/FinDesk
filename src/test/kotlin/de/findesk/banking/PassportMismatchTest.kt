package de.findesk.banking

import de.findesk.i18n.AppStrings
import de.findesk.i18n.StringKey
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files

/**
 * The stale-passport check is pure comparison logic, so it is tested without HBCI4Java: the smoke test covers the
 * wiring, this covers every field and the host normalisation.
 */
class PassportMismatchTest {
    private fun connection(
        blz: String = "12030000",
        userId: String = "user-1",
        serverUrl: String = "https://localhost/fints",
    ) = FinTSConnection(
        bankName = "Test Bank",
        blz = blz,
        userId = userId,
        serverUrl = serverUrl,
        passportFile = File("unused-passport.dat"),
        passportPassphrase = "test-passphrase-123",
    )

    @Test
    fun `matching login does not mismatch`() {
        assertFalse(passportMismatch(StoredLogin("12030000", "user-1", "localhost/fints"), connection()))
    }

    @Test
    fun `each different field mismatches`() {
        assertTrue(passportMismatch(StoredLogin("70020270", "user-1", "localhost/fints"), connection()), "their BLZ")
        assertTrue(passportMismatch(StoredLogin("12030000", "user-2", "localhost/fints"), connection()), "their user id")
        assertTrue(passportMismatch(StoredLogin("12030000", "user-1", "127.0.0.1/fints"), connection()), "their host")
    }

    @Test
    fun `user id is compared after trimming`() {
        assertFalse(passportMismatch(StoredLogin("12030000", "  user-1  ", "localhost/fints"), connection(userId = " user-1 ")))
    }

    @Test
    fun `host is normalised before comparing`() {
        assertFalse(passportMismatch(StoredLogin("12030000", "user-1", "LOCALHOST/Fints/"), connection()), "case and trailing slash")
        assertFalse(passportMismatch(StoredLogin("12030000", "user-1", "localhost:443/fints"), connection()), "stored port")
        assertFalse(passportMismatch(StoredLogin("12030000", "user-1", "localhost/fints:443"), connection()), "trailing port")
    }

    /**
     * `File.delete()` can fail silently. A passport inside a read-only directory reports that failure instead of
     * leaving FinDesk thinking the old login was reset. Skipped where the OS still deletes (e.g. running as root).
     */
    @Test
    fun `resetting a passport that cannot be deleted is an error`() {
        val dir = Files.createTempDirectory("findesk-passport-ro").toFile()
        val passport = File(dir, "bank-1.dat").apply { writeText("passport") }
        val probe = File(dir, "probe.dat").apply { writeText("probe") }
        try {
            dir.setWritable(false)
            assumeTrue(!probe.delete(), "this filesystem still allows deleting from a read-only directory (e.g. running as root)")
            assertTrue(passport.exists(), "the passport is still there")

            val error = assertThrows(IllegalStateException::class.java) { Hbci4JavaGateway().resetPassport(passport) }

            assertEquals(AppStrings[StringKey.ErrorPassportReset], error.message)
            assertTrue(passport.exists(), "a failed reset must not delete the passport")
        } finally {
            dir.setWritable(true)
            dir.deleteRecursively()
        }
    }
}
