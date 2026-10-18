package de.findesk.banking

import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.PointerByReference
import java.io.File
import java.util.concurrent.TimeUnit

/** Secrets kept by the operating system, never in findesk.db or a file. */
interface CredentialStore {
    val isAvailable: Boolean
    fun read(key: String): String?
    fun write(key: String, secret: String)
    fun delete(key: String)
}

/** Used where no protected store is offered: the option to remember a PIN is not shown. */
object NoCredentialStore : CredentialStore {
    override val isAvailable: Boolean = false

    override fun read(key: String): String? = null

    override fun write(key: String, secret: String): Unit = throw UnsupportedOperationException("No credential store available")

    override fun delete(key: String) {}
}

/**
 * The store the running system offers, created lazily once so constructing a [FinTSClient] never touches the OS.
 * A matching system property (`findesk.credentialStore=none`, set by the test task) or any failure while creating
 * one falls back to [NoCredentialStore].
 */
object CredentialStores {
    /** Service all entries are filed under. */
    const val SERVICE = "FinDesk"

    private val instance: CredentialStore by lazy { create() }

    fun system(): CredentialStore = instance

    private fun create(): CredentialStore {
        if (System.getProperty("findesk.credentialStore") == "none") return NoCredentialStore
        val os = System.getProperty("os.name").orEmpty().lowercase()
        return try {
            when {
                os.contains("mac") -> MacKeychainStore()

                os.contains("linux") -> SecretToolStore()

                // Windows Credential Manager is a follow-up; elsewhere there is nothing to use
                else -> NoCredentialStore
            }
        } catch (e: Exception) {
            NoCredentialStore
        } catch (e: LinkageError) {
            // A native library that can't be loaded (e.g. a stripped runtime) means no store, not a crash
            NoCredentialStore
        }
    }
}

/**
 * macOS Keychain through the Security framework. Secrets go through the API, never a command line: shelling out
 * to `/usr/bin/security` would put the PIN into the process list.
 */
private class MacKeychainStore : CredentialStore {
    override val isAvailable: Boolean = true

    init {
        // Load the frameworks now, inside CredentialStores.create(), so a load failure falls back to no store there
        // instead of throwing an Error from the first read in the middle of a sync
        security
        coreFoundation
    }

    override fun read(key: String): String? {
        val service = native(CredentialStores.SERVICE)
        val account = native(key)
        val passwordLength = IntByReference()
        val passwordData = PointerByReference()
        val itemRef = PointerByReference()
        val status = security.SecKeychainFindGenericPassword(null, byteLength(CredentialStores.SERVICE), service, byteLength(key), account, passwordLength, passwordData, itemRef)
        return when (status) {
            ERR_SEC_ITEM_NOT_FOUND -> null

            OK -> try {
                passwordData.value.getByteArray(0, passwordLength.value).toString(Charsets.UTF_8)
            } finally {
                security.SecKeychainItemFreeContent(null, passwordData.value)
                itemRef.value?.let { coreFoundation.CFRelease(it) }
            }

            else -> throw IllegalStateException("Keychain error $status")
        }
    }

    override fun write(key: String, secret: String) {
        val service = native(CredentialStores.SERVICE)
        val account = native(key)
        val password = native(secret)
        val itemRef = PointerByReference()
        // Updating an existing entry refreshes it; adding one that exists fails with errSecDuplicateItem
        val status = security.SecKeychainFindGenericPassword(null, byteLength(CredentialStores.SERVICE), service, byteLength(key), account, null, null, itemRef)
        when (status) {
            OK -> try {
                val updated = security.SecKeychainItemModifyAttributesAndData(itemRef.value, null, byteLength(secret), password)
                if (updated != OK) throw IllegalStateException("Keychain error $updated")
            } finally {
                itemRef.value?.let { coreFoundation.CFRelease(it) }
            }

            ERR_SEC_ITEM_NOT_FOUND -> {
                val added = security.SecKeychainAddGenericPassword(null, byteLength(CredentialStores.SERVICE), service, byteLength(key), account, byteLength(secret), password, null)
                if (added != OK) throw IllegalStateException("Keychain error $added")
            }

            else -> throw IllegalStateException("Keychain error $status")
        }
    }

    override fun delete(key: String) {
        val service = native(CredentialStores.SERVICE)
        val account = native(key)
        val itemRef = PointerByReference()
        val status = security.SecKeychainFindGenericPassword(null, byteLength(CredentialStores.SERVICE), service, byteLength(key), account, null, null, itemRef)
        when (status) {
            OK -> try {
                val deleted = security.SecKeychainItemDelete(itemRef.value)
                if (deleted != OK) throw IllegalStateException("Keychain error $deleted")
            } finally {
                itemRef.value?.let { coreFoundation.CFRelease(it) }
            }

            ERR_SEC_ITEM_NOT_FOUND -> Unit

            else -> throw IllegalStateException("Keychain error $status")
        }
    }

    /** UTF-8 bytes of [text] in native memory; the framework takes byte lengths, not characters. */
    private fun native(text: String): Pointer {
        val bytes = text.toByteArray(Charsets.UTF_8)
        val memory = Memory(maxOf(1, bytes.size).toLong())
        memory.write(0, bytes, 0, bytes.size)
        return memory
    }

    private fun byteLength(text: String): Int = text.toByteArray(Charsets.UTF_8).size

    private companion object {
        const val OK = 0
        const val ERR_SEC_ITEM_NOT_FOUND = -25300

        val security: SecurityFramework = Native.load("/System/Library/Frameworks/Security.framework/Security", SecurityFramework::class.java)
        val coreFoundation: CoreFoundation = Native.load("/System/Library/Frameworks/CoreFoundation.framework/CoreFoundation", CoreFoundation::class.java)
    }
}

/** Only the Security framework calls FinDesk needs; a null keychain means the user's default keychain. */
@Suppress("ktlint:standard:function-naming") // symbol names must match the C functions exactly
private interface SecurityFramework : Library {
    fun SecKeychainFindGenericPassword(
        keychainOrArray: Pointer?,
        serviceNameLength: Int,
        serviceName: Pointer,
        accountNameLength: Int,
        accountName: Pointer,
        passwordLength: IntByReference?,
        passwordData: PointerByReference?,
        itemRef: PointerByReference?,
    ): Int

    fun SecKeychainAddGenericPassword(
        keychain: Pointer?,
        serviceNameLength: Int,
        serviceName: Pointer,
        accountNameLength: Int,
        accountName: Pointer,
        passwordLength: Int,
        passwordData: Pointer,
        itemRef: PointerByReference?,
    ): Int

    fun SecKeychainItemModifyAttributesAndData(itemRef: Pointer, attrList: Pointer?, length: Int, data: Pointer?): Int

    fun SecKeychainItemDelete(itemRef: Pointer): Int

    fun SecKeychainItemFreeContent(attrList: Pointer?, data: Pointer?): Int
}

@Suppress("ktlint:standard:function-naming") // symbol names must match the C functions exactly
private interface CoreFoundation : Library {
    fun CFRelease(cf: Pointer)
}

/**
 * Linux Secret Service (libsecret) through the `secret-tool` CLI. **Not verified on Linux yet.**
 *
 * The secret is written to the tool's STDIN without a trailing newline, never passed as an argument, so it can't be
 * read from the process list. Every call is bounded by a timeout: a missing or unresponsive secret service must not
 * hang a sync.
 */
private class SecretToolStore : CredentialStore {
    override val isAvailable: Boolean
        get() = !System.getenv("DBUS_SESSION_BUS_ADDRESS").isNullOrBlank() && onPath(TOOL)

    override fun read(key: String): String? {
        val result = run("lookup", "service", CredentialStores.SERVICE, "key", key) ?: return null
        return result.stdout.removeSuffix("\n").ifEmpty { null }
    }

    override fun write(key: String, secret: String) {
        val result = run("store", "--label=FinDesk PIN", "service", CredentialStores.SERVICE, "key", key, stdin = secret)
            ?: throw IllegalStateException("secret-tool is not available")
        if (result.exitCode != 0) throw IllegalStateException("Keychain error ${result.exitCode}")
    }

    override fun delete(key: String) {
        run("clear", "service", CredentialStores.SERVICE, "key", key)
    }

    private data class Result(val exitCode: Int, val stdout: String)

    /** Runs `secret-tool` with a 5 s cap; null when it can't be started or doesn't finish in time. */
    private fun run(vararg args: String, stdin: String? = null): Result? {
        val process = try {
            ProcessBuilder(TOOL, *args).redirectErrorStream(false).start()
        } catch (e: Exception) {
            return null
        }
        return try {
            process.outputStream.use { out ->
                out.write(stdin.orEmpty().toByteArray(Charsets.UTF_8))
                out.flush()
            }
            // Wait before reading: a hung secret service must be killed by the timeout, not block on its output pipe
            if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                null
            } else {
                Result(process.exitValue(), process.inputStream.bufferedReader().use { it.readText() })
            }
        } finally {
            if (process.isAlive) process.destroyForcibly()
        }
    }

    private fun onPath(name: String): Boolean = System.getenv("PATH").orEmpty()
        .split(File.pathSeparator)
        .any { File(it, name).canExecute() }

    private companion object {
        const val TOOL = "secret-tool"
        const val TIMEOUT_SECONDS = 5L
    }
}
