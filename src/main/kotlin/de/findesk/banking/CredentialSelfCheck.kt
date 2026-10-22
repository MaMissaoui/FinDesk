package de.findesk.banking

import java.io.File
import java.util.UUID

/**
 * Checks inside the packaged app that "Remember PIN" can reach the Windows Credential Manager (#38).
 *
 * jpackage builds a trimmed runtime without `java.exe`, and JNA's native load can fail there in a way
 * the Gradle test run never sees. [CredentialStores] catches every load failure and returns
 * [NoCredentialStore], so such a break would silently hide the option instead of failing. This check
 * exercises the real selection and a directly built [WindowsCredentialStore], and prints one diagnostic
 * line for CI and support.
 *
 * It creates one entry under a `self-check-<uuid>` key and deletes only that key. It never reads, lists
 * or deletes another credential, and no secret or test value is ever printed, logged or written.
 */
internal object CredentialSelfCheck {
    /**
     * Runs the check against the real system selection and a directly built [WindowsCredentialStore].
     * Prints the result line and, when [resultFile] is not null, writes it there as one UTF-8 line.
     * Returns 0 for `OK`, 1 for any failure and 2 when this is not Windows (no store is touched then).
     */
    fun run(resultFile: String?): Int {
        if (!System.getProperty("os.name").orEmpty().lowercase().contains("windows")) {
            val line = SKIP
            emit(line, resultFile)
            return 2
        }
        val line = try {
            check(CredentialStores.system(), { WindowsCredentialStore() })
        } catch (t: Throwable) {
            "FAIL: check ${className(t)}: ${brief(t.message)}"
        }
        emit(line, resultFile)
        return if (line.startsWith("OK:")) 0 else 1
    }

    /**
     * The check itself, taking its collaborators so a test can drive it off Windows. [isWindowsStore]
     * defaults to the real type test; [run] leaves it at the default.
     */
    internal fun check(
        selected: CredentialStore,
        direct: () -> CredentialStore,
        isWindowsStore: (CredentialStore) -> Boolean = { it is WindowsCredentialStore },
    ): String {
        if (!isWindowsStore(selected) || !selected.isAvailable) {
            // A failed native load is what makes the selection fall back silently, so name it when loading shows it
            val cause = try {
                direct()
                null
            } catch (t: Throwable) {
                "; load ${className(t)}: ${brief(t.message)}"
            }
            return "FAIL: selection returned ${className(selected)}${cause.orEmpty()}"
        }

        val store = try {
            direct()
        } catch (t: Throwable) {
            return "FAIL: load ${className(t)}: ${brief(t.message)}"
        }

        val key = "self-check-" + UUID.randomUUID()
        val first = UUID.randomUUID().toString()
        val second = UUID.randomUUID().toString()
        var failure: String? = null
        try {
            failure = roundTrip(store, key, first, second)
        } finally {
            // The explicit delete below is the checked step; this only makes sure the key is gone.
            // A cleanup failure must not replace the first failure, so it is swallowed here.
            try {
                store.delete(key)
            } catch (_: Throwable) {
            }
        }
        return failure ?: "OK: Windows Credential Manager round trip in ${System.getProperty("java.home").orEmpty()}"
    }

    /** Writes A, reads A, writes B, reads B, deletes and reads nothing. Values only appear as lengths. */
    private fun roundTrip(store: CredentialStore, key: String, first: String, second: String): String? {
        try {
            try {
                store.write(key, first)
            } catch (t: Throwable) {
                return "FAIL: write ${className(t)}: ${brief(t.message, first, second)}"
            }
            val written = try {
                store.read(key)
            } catch (t: Throwable) {
                return "FAIL: read after write ${className(t)}: ${brief(t.message, first, second)}"
            }
            if (written != first) return mismatch("write", first, written)

            try {
                store.write(key, second)
            } catch (t: Throwable) {
                return "FAIL: overwrite ${className(t)}: ${brief(t.message, first, second)}"
            }
            val overwritten = try {
                store.read(key)
            } catch (t: Throwable) {
                return "FAIL: read after overwrite ${className(t)}: ${brief(t.message, first, second)}"
            }
            if (overwritten != second) return mismatch("overwrite", second, overwritten)

            try {
                store.delete(key)
            } catch (t: Throwable) {
                return "FAIL: delete ${className(t)}: ${brief(t.message, first, second)}"
            }
            val deleted = try {
                store.read(key)
            } catch (t: Throwable) {
                return "FAIL: read after delete ${className(t)}: ${brief(t.message, first, second)}"
            }
            if (deleted != null) return mismatch("delete", null, deleted)
            return null
        } catch (t: Throwable) {
            // Anything a CredentialStore implementation throws that the steps above did not catch
            return "FAIL: round trip ${className(t)}: ${brief(t.message, first, second)}"
        }
    }

    /** A mismatch names the step and lengths only, never the expected or actual secret. */
    private fun mismatch(step: String, expected: String?, actual: String?): String = "FAIL: mismatch after $step (expected ${lengthOf(expected)}, got ${lengthOf(actual)})"

    private fun lengthOf(value: String?): String = value?.let { "length ${it.length}" } ?: "none"

    /** First line of a message, any known test value redacted, cut to 200 characters; a load error carries no secret. */
    private fun brief(message: String?, vararg secrets: String): String {
        val line = message.orEmpty().lineSequence().firstOrNull().orEmpty()
        val redacted = secrets.fold(line) { text, secret -> if (secret.isEmpty()) text else text.replace(secret, "<redacted>") }
        return redacted.take(200)
    }

    private fun className(value: Any): String = value::class.simpleName ?: value.javaClass.name

    private fun emit(line: String, resultFile: String?) {
        println(line)
        if (resultFile != null) File(resultFile).writeText(line + "\n", Charsets.UTF_8)
    }

    private const val SKIP = "SKIP: not Windows"
}
