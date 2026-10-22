package de.findesk.banking

import org.junit.jupiter.api.Assumptions.assumeFalse
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** An in-memory CredentialStore; no test here touches a real OS store. */
private class InMemoryCredentialStore(
    override val isAvailable: Boolean = true,
    private val readResult: (String?) -> String? = { it },
    private val ignoredDeletes: Int = 0,
) : CredentialStore {
    val entries = mutableMapOf<String, String>()
    val seenKeys = mutableSetOf<String>()
    val writtenSecrets = mutableListOf<String>()
    private var deletes = 0

    override fun read(key: String): String? {
        seenKeys += key
        return readResult(entries[key])
    }

    override fun write(key: String, secret: String) {
        seenKeys += key
        writtenSecrets += secret
        entries[key] = secret
    }

    override fun delete(key: String) {
        seenKeys += key
        deletes++
        if (deletes > ignoredDeletes) entries.remove(key)
    }
}

/**
 * Drives [CredentialSelfCheck.check] with fakes on every OS: a real [WindowsCredentialStore] cannot be
 * built here. The values stay in the fake and are only ever asserted absent from the result line.
 */
class CredentialSelfCheckTest {
    private fun assertCleanedUp(store: InMemoryCredentialStore) {
        assertTrue(store.entries.isEmpty(), "the check removed the one key it created")
        assertTrue(store.seenKeys.all { it.startsWith("self-check-") }, "the check touched only its own key")
    }

    @Test
    fun `a working store passes`() {
        val store = InMemoryCredentialStore()

        val line = CredentialSelfCheck.check(store, { store }, isWindowsStore = { true })

        assertTrue(line.startsWith("OK:"), line)
        assertTrue(line.endsWith("in ${System.getProperty("java.home")}"), "the line names the runtime that ran the check")
        assertCleanedUp(store)
    }

    @Test
    fun `selection that is not the Windows store fails`() {
        val selected = InMemoryCredentialStore()
        val direct = InMemoryCredentialStore()

        val line = CredentialSelfCheck.check(selected, { direct }, isWindowsStore = { false })

        assertEquals("FAIL: selection returned InMemoryCredentialStore", line)
        assertTrue(direct.seenKeys.isEmpty(), "the direct store is not touched when selection fails")
        assertCleanedUp(direct)
    }

    @Test
    fun `selection that is unavailable fails`() {
        val selected = InMemoryCredentialStore(isAvailable = false)
        val direct = InMemoryCredentialStore()

        val line = CredentialSelfCheck.check(selected, { direct }, isWindowsStore = { true })

        assertEquals("FAIL: selection returned InMemoryCredentialStore", line)
        assertTrue(direct.seenKeys.isEmpty(), "the direct store is not touched when selection fails")
        assertCleanedUp(direct)
    }

    @Test
    fun `a selection fallback names the load error behind it`() {
        val line = CredentialSelfCheck.check(
            InMemoryCredentialStore(),
            { throw UnsatisfiedLinkError("no Advapi32") },
            isWindowsStore = { false },
        )

        assertEquals("FAIL: selection returned InMemoryCredentialStore; load UnsatisfiedLinkError: no Advapi32", line)
    }

    @Test
    fun `a load error is reported without a key`() {
        val line = CredentialSelfCheck.check(
            InMemoryCredentialStore(),
            { throw UnsatisfiedLinkError("no Advapi32") },
            isWindowsStore = { true },
        )

        assertEquals("FAIL: load UnsatisfiedLinkError: no Advapi32", line)
    }

    @Test
    fun `a read that differs after the write fails without printing the secret`() {
        val store = InMemoryCredentialStore(readResult = { it?.plus("-different") })

        val line = CredentialSelfCheck.check(store, { store }, isWindowsStore = { true })

        assertTrue(line.startsWith("FAIL: mismatch after write"), line)
        assertTrue(store.writtenSecrets.isNotEmpty(), "the fake received a value to check against")
        store.writtenSecrets.forEach { value -> assertFalse(line.contains(value), "the line never contains a written value") }
        assertCleanedUp(store)
    }

    @Test
    fun `a delete that leaves the value fails and cleanup removes it`() {
        val store = InMemoryCredentialStore(ignoredDeletes = 1)

        val line = CredentialSelfCheck.check(store, { store }, isWindowsStore = { true })

        assertTrue(line.startsWith("FAIL: mismatch after delete"), line)
        store.writtenSecrets.forEach { value -> assertFalse(line.contains(value), "the line never contains a written value") }
        assertCleanedUp(store)
    }

    @Test
    fun `run skips off Windows and writes the skip line`() {
        assumeFalse(System.getProperty("os.name").lowercase().contains("windows"), "this test checks the non-Windows path")
        val result = Files.createTempFile("findesk-credential-self-check", ".txt")
        try {
            assertEquals(2, CredentialSelfCheck.run(result.toString()))
            assertEquals(listOf("SKIP: not Windows"), Files.readAllLines(result))
        } finally {
            Files.deleteIfExists(result)
        }
    }
}
