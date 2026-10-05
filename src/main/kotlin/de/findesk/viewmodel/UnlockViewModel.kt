package de.findesk.viewmodel

import de.findesk.data.database.DatabaseCrypto
import de.findesk.data.database.DatabaseFactory
import de.findesk.repository.AuditLogRepositoryImpl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File

enum class UnlockMode {
    /** Encrypted database: enter the passphrase. */
    UNLOCK,

    /** Encrypted database: current passphrase plus a new one. */
    CHANGE,

    /** No database yet: choose a passphrase; the database is created encrypted. */
    CREATE,

    /** Plaintext database (from before encryption, or a restored plaintext backup): choose a passphrase to encrypt it. */
    ENCRYPT,
}

enum class UnlockError { WRONG_PASSPHRASE, TOO_SHORT, SPACES, MISMATCH, NOT_ACKNOWLEDGED, FAILED }

data class UnlockState(
    val mode: UnlockMode,
    val isBusy: Boolean = false,
    val error: UnlockError? = null,
    val failure: String? = null,
    /** A backup was restored at this start. */
    val restored: Boolean = false,
    /** The database is open; the main window can be shown. */
    val opened: Boolean = false,
)

/**
 * The startup gate: nothing reads the database until this has opened it with a passphrase. Every key operation
 * (create, encrypt, change) happens here, before the app holds connections. The passphrase is handed to
 * [DatabaseFactory] and never kept in state.
 */
class UnlockViewModel(
    private val dataDir: File,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) : BaseViewModel(scope) {
    private val db = DatabaseFactory.databaseFile(dataDir)

    private val _state = MutableStateFlow(
        UnlockState(
            mode = when (DatabaseCrypto.state(db)) {
                DatabaseCrypto.State.ENCRYPTED -> UnlockMode.UNLOCK
                DatabaseCrypto.State.PLAINTEXT -> UnlockMode.ENCRYPT
                DatabaseCrypto.State.NEW -> UnlockMode.CREATE
            },
            restored = DatabaseFactory.restoredFrom != null,
        ),
    )
    val state: StateFlow<UnlockState> = _state.asStateFlow()

    /** Switches between unlocking and changing the passphrase (encrypted databases only). */
    fun showChange(change: Boolean) {
        if (_state.value.mode !in setOf(UnlockMode.UNLOCK, UnlockMode.CHANGE) || _state.value.isBusy) return
        _state.update { it.copy(mode = if (change) UnlockMode.CHANGE else UnlockMode.UNLOCK, error = null, failure = null) }
    }

    fun unlock(passphrase: String): Job = run {
        if (passphrase.isEmpty()) throw Rejected(UnlockError.WRONG_PASSPHRASE)
        DatabaseFactory.init(dataDir.absolutePath, passphrase)
    }

    /** CREATE and ENCRYPT. */
    fun setPassphrase(passphrase: String, repeat: String, acknowledged: Boolean): Job = run {
        validateNew(passphrase, repeat, acknowledged)
        val encrypting = _state.value.mode == UnlockMode.ENCRYPT
        if (encrypting) {
            DatabaseCrypto.reencrypt(db, null, passphrase)
            // From here on the file is encrypted: if opening fails, a retry is an unlock
            _state.update { it.copy(mode = UnlockMode.UNLOCK) }
        }
        DatabaseFactory.init(dataDir.absolutePath, passphrase)
        AuditLogRepositoryImpl().log("DATABASE_ENCRYPTED", "user", if (encrypting) "Existing database encrypted" else "New encrypted database")
    }

    fun changePassphrase(current: String, passphrase: String, repeat: String, acknowledged: Boolean): Job = run {
        try {
            DatabaseCrypto.verify(db, current)
        } catch (e: DatabaseCrypto.WrongPassphraseException) {
            throw Rejected(UnlockError.WRONG_PASSPHRASE)
        }
        validateNew(passphrase, repeat, acknowledged)
        DatabaseCrypto.reencrypt(db, current, passphrase)
        _state.update { it.copy(mode = UnlockMode.UNLOCK) }
        DatabaseFactory.init(dataDir.absolutePath, passphrase)
        AuditLogRepositoryImpl().log("DATABASE_PASSPHRASE_CHANGED", "user", "Database passphrase changed")
    }

    private fun validateNew(passphrase: String, repeat: String, acknowledged: Boolean) {
        val normalized = DatabaseCrypto.normalize(passphrase)
        when {
            normalized.length < DatabaseCrypto.MIN_PASSPHRASE_LENGTH -> throw Rejected(UnlockError.TOO_SHORT)
            passphrase.trim() != passphrase -> throw Rejected(UnlockError.SPACES)
            normalized != DatabaseCrypto.normalize(repeat) -> throw Rejected(UnlockError.MISMATCH)
            !acknowledged -> throw Rejected(UnlockError.NOT_ACKNOWLEDGED)
        }
    }

    private class Rejected(val error: UnlockError) : Exception(error.name)

    private fun run(block: () -> Unit): Job {
        if (_state.value.isBusy || _state.value.opened) return Job().apply { complete() }
        _state.update { it.copy(isBusy = true, error = null, failure = null) }
        return launchSafely({ e ->
            val error = when (e) {
                is Rejected -> e.error
                is DatabaseCrypto.WrongPassphraseException -> UnlockError.WRONG_PASSPHRASE
                else -> UnlockError.FAILED
            }
            _state.update { it.copy(isBusy = false, error = error, failure = if (error == UnlockError.FAILED) e.userMessage() else null) }
        }) {
            block()
            _state.update { it.copy(isBusy = false, opened = true) }
        }
    }
}
