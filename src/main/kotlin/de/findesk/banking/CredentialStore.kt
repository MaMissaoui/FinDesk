package de.findesk.banking

import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.WString
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

                os.contains("windows") -> WindowsCredentialStore()

                // elsewhere there is nothing to use
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
        // No answer (timeout, not startable) is a failed read, not "no PIN": the caller then asks and deletes nothing
        val result = run("lookup", "service", CredentialStores.SERVICE, "key", key) ?: throw IllegalStateException("secret-tool did not answer")
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

/**
 * Windows Credential Manager through `Advapi32.dll` via JNA — the Windows equivalent of [MacKeychainStore].
 *
 * Entries are generic credentials persisted to the local machine only ([CRED_PERSIST_LOCAL_MACHINE]); the PIN is
 * never put on a command line (`cmdkey`) or written to a file. The JVM round trip is verified on the GitHub
 * Windows runner (`.github/workflows/windows-credentials.yml`); the packaged MSI/EXE on Windows 11 is still
 * unverified. `internal`, not `private`, so a test can build it directly, bypassing `findesk.credentialStore=none`.
 */
internal class WindowsCredentialStore : CredentialStore {
    override val isAvailable: Boolean = true

    private val advapi32: Advapi32Cred

    init {
        // Load in init, as MacKeychainStore does, so a load failure falls back to NoCredentialStore in create()
        advapi32 = Native.load("Advapi32", Advapi32Cred::class.java, unicodeOptions())
    }

    override fun read(key: String): String? {
        val credentialRef = PointerByReference()
        if (!advapi32.CredReadW(WString(target(key)), CRED_TYPE_GENERIC, 0, credentialRef)) {
            // Read the last error only after the false return, on this thread, before any other native call
            val error = Native.getLastError()
            if (error == ERROR_NOT_FOUND) return null
            // A failed read is not "no PIN": report it, as the Linux store does, so nothing is deleted
            throw IllegalStateException("Credential Manager error $error")
        }
        val pointer = credentialRef.value ?: throw IllegalStateException("Credential Manager returned no credential")
        try {
            val credential = CREDENTIALW(pointer)
            val size = credential.CredentialBlobSize
            val blob = credential.CredentialBlob ?: return ""
            return blob.getByteArray(0, size).toString(Charsets.UTF_8)
        } finally {
            advapi32.CredFree(pointer)
        }
    }

    override fun write(key: String, secret: String) {
        val bytes = secret.toByteArray(Charsets.UTF_8)
        val memory = Memory(maxOf(1, bytes.size).toLong())
        memory.write(0, bytes, 0, bytes.size)
        try {
            val credential = CREDENTIALW().apply {
                Type = CRED_TYPE_GENERIC
                TargetName = WString(target(key))
                CredentialBlobSize = bytes.size
                CredentialBlob = memory
                Persist = CRED_PERSIST_LOCAL_MACHINE
                // Not the key: it holds the profile path, and UserName is capped at 513 characters. The target names it.
                UserName = WString(CredentialStores.SERVICE)
            }
            // CredWriteW overwrites an existing entry itself, so there is no find-first step
            if (!advapi32.CredWriteW(credential, 0)) {
                throw IllegalStateException("Credential Manager error ${Native.getLastError()}")
            }
        } finally {
            // The plaintext PIN does not outlive the call, even when the write failed
            memory.clear()
        }
    }

    override fun delete(key: String) {
        if (!advapi32.CredDeleteW(WString(target(key)), CRED_TYPE_GENERIC, 0)) {
            val error = Native.getLastError()
            if (error != ERROR_NOT_FOUND) throw IllegalStateException("Credential Manager error $error")
        }
    }

    private fun target(key: String): String = "${CredentialStores.SERVICE}:$key"

    private companion object {
        const val CRED_TYPE_GENERIC = 1

        /** Local machine only; CRED_PERSIST_ENTERPRISE (3) would roam the PIN to other machines. */
        const val CRED_PERSIST_LOCAL_MACHINE = 2
        const val ERROR_NOT_FOUND = 1168

        /** JNA's win32 Unicode options when they are on the classpath (they ship in jna itself), plain options otherwise. */
        @Suppress("UNCHECKED_CAST")
        fun unicodeOptions(): Map<String, *> = try {
            Class.forName("com.sun.jna.win32.W32APIOptions").getField("UNICODE_OPTIONS").get(null) as? Map<String, *>
                ?: emptyMap<String, Any>()
        } catch (_: ReflectiveOperationException) {
            emptyMap<String, Any>()
        }
    }
}

/** The wide-char Credential Manager entry points; symbol names must match the C exports exactly. */
@Suppress("ktlint:standard:function-naming")
private interface Advapi32Cred : Library {
    fun CredWriteW(credential: CREDENTIALW, flags: Int): Boolean

    fun CredReadW(target: WString, type: Int, flags: Int, credential: PointerByReference): Boolean

    fun CredDeleteW(target: WString, type: Int, flags: Int): Boolean

    fun CredFree(buffer: Pointer)
}

/**
 * `CREDENTIALW` from `wincred.h`. 64-bit layout: 80 bytes. FILETIME's `LastWritten` is inlined as two DWORDs
 * because JNA cannot marshal a nested FILETIME, and the 8-byte alignment of the pointers after it puts 4 bytes of
 * padding after `CredentialBlobSize`, landing `CredentialBlob` at offset 40 and `Persist` at 48.
 */
@Structure.FieldOrder(
    "Flags",
    "Type",
    "TargetName",
    "Comment",
    "LastWrittenLow",
    "LastWrittenHigh",
    "CredentialBlobSize",
    "CredentialBlob",
    "Persist",
    "AttributeCount",
    "Attributes",
    "TargetAlias",
    "UserName",
)
@Suppress("ktlint:standard:property-naming") // the names are the wincred.h field names JNA maps by reflection
internal class CREDENTIALW : Structure {
    @JvmField
    var Flags: Int = 0

    @JvmField
    var Type: Int = 0

    @JvmField
    var TargetName: WString? = null

    @JvmField
    var Comment: WString? = null

    @JvmField
    var LastWrittenLow: Int = 0

    @JvmField
    var LastWrittenHigh: Int = 0

    @JvmField
    var CredentialBlobSize: Int = 0

    @JvmField
    var CredentialBlob: Pointer? = null

    @JvmField
    var Persist: Int = 0

    @JvmField
    var AttributeCount: Int = 0

    @JvmField
    var Attributes: Pointer? = null

    @JvmField
    var TargetAlias: WString? = null

    @JvmField
    var UserName: WString? = null

    constructor() : super()

    /** Reads the credential [Advapi32Cred.CredReadW] returned. */
    constructor(pointer: Pointer) : super(pointer) {
        read()
    }

    /** [Structure.fieldOffset] is protected; the layout test reads the offsets through this. */
    internal fun offsetOf(field: String): Int = fieldOffset(field)
}
