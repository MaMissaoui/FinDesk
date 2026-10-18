package de.findesk.banking

import org.junit.jupiter.api.Assumptions.assumeTrue
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Round trip through the real Windows Credential Manager. Skipped off Windows, so the assertion in
 * `.github/workflows/windows-credentials.yml` fails the build if it is ever skipped on the Windows runner.
 */
class WindowsCredentialStoreTest {
    private fun onWindows() = assumeTrue(
        System.getProperty("os.name").lowercase().contains("windows"),
        "the Windows Credential Manager is only available on Windows",
    )

    @Test
    fun `writes reads overwrites and deletes a PIN`() {
        onWindows()
        val store = WindowsCredentialStore()
        val key = "test-" + UUID.randomUUID()
        try {
            store.write(key, "123456")
            assertEquals("123456", store.read(key))

            store.write(key, "654321")
            assertEquals("654321", store.read(key), "CredWriteW overwrites the existing entry")

            store.delete(key)
            assertNull(store.read(key), "a deleted entry reads as no PIN")

            store.delete(key) // deleting again is a no-op, not an error
        } finally {
            store.delete(key)
        }
    }

    @Test
    fun `round trips a non-ASCII secret`() {
        onWindows()
        val store = WindowsCredentialStore()
        val key = "test-" + UUID.randomUUID()
        val secret = "Pä$§wört1"
        try {
            store.write(key, secret)
            assertEquals(secret, store.read(key), "the blob is UTF-8, so non-ASCII survives")
        } finally {
            store.delete(key)
        }
    }
}
