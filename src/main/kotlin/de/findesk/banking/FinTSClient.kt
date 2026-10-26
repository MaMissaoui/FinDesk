package de.findesk.banking

import de.findesk.i18n.AppStrings
import de.findesk.i18n.StringKey
import de.findesk.model.BankAccessItem
import de.findesk.repository.*
import de.findesk.util.AppPaths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.math.BigDecimal
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.LocalDate
import java.util.Base64

/**
 * Turns a stored bank access into a FinTS connection and runs operations through a [FinTSGateway],
 * publishing connection status and progress for the UI.
 */
class FinTSClient(
    private val bankAccessRepository: BankAccessRepository = BankAccessRepositoryImpl(),
    private val settingsRepository: SettingsRepository = SettingsRepositoryImpl(),
    private val auditLogRepository: AuditLogRepository = AuditLogRepositoryImpl(),
    private val gateway: FinTSGateway = Hbci4JavaGateway(),
    private val passportDir: File = File(AppPaths.dataDir, "passports"),
    /** Resolved lazily, so constructing a client never touches the operating system's credential store. */
    private val credentialStore: () -> CredentialStore = CredentialStores::system,
) {
    private val _connectionStatus = MutableStateFlow(ConnectionStatus.DISCONNECTED)
    val connectionStatus: StateFlow<ConnectionStatus> = _connectionStatus.asStateFlow()

    private val _syncProgress = MutableStateFlow<SyncProgress?>(null)
    val syncProgress: StateFlow<SyncProgress?> = _syncProgress.asStateFlow()

    /** Set when the last sync's opted-in PIN could not be saved; the sync's view model shows a short, non-secret note. */
    @Volatile
    var pinNotSaved: Boolean = false
        private set

    /** Whether the system offers a credential store, so the UI may offer to remember the PIN. */
    val canRememberPin: Boolean get() = credentialStore().isAvailable

    data class SyncProgress(
        val currentStep: String,
        val progress: Float, // 0.0 to 1.0
        val accountsProcessed: Int = 0,
        val totalAccounts: Int = 0,
    )

    /**
     * Builds the connection for a stored bank access. The server URL comes from the bank access or,
     * if unset, from the bundled bank directory.
     */
    fun connectionFor(bankAccess: BankAccessItem): BankingResult<FinTSConnection> {
        // Choke point: an offline bank never reaches FinTS — before the server lookup and before the
        // passport passphrase (which may create a stored passphrase). Every FinTS job goes through run().
        if (bankAccess.offline) return BankingResult.Error("OFFLINE", AppStrings[StringKey.ErrorBankOffline])
        val serverUrl = bankAccess.serverUrl?.takeIf { it.isNotBlank() } ?: BankDirectory.lookup(bankAccess.blz)?.pinTanUrl
            ?: return BankingResult.Error("NO_SERVER", AppStrings.format(StringKey.ErrorNoFintsServer, bankAccess.blz))
        runCatching { FinDeskHbciCallback.hostFor(serverUrl) }.onFailure {
            return BankingResult.Error("BAD_SERVER", it.message ?: AppStrings[StringKey.ErrorBadServerUrl])
        }
        if (bankAccess.userId.isBlank()) {
            return BankingResult.Error("NO_USER", AppStrings.format(StringKey.ErrorEnterUserId, bankAccess.bankName))
        }
        return BankingResult.Success(
            FinTSConnection(
                bankName = bankAccess.bankName,
                blz = bankAccess.blz,
                userId = bankAccess.userId,
                serverUrl = serverUrl,
                hbciVersion = bankAccess.hbciVersion,
                passportFile = File(passportDir, "bank-${bankAccess.id}.dat"),
                passportPassphrase = passportPassphrase(bankAccess.id),
                tanMethod = bankAccess.tanMethod,
            ),
        )
    }

    /**
     * Fetch accounts, balances and bookings for one bank access in a single dialog.
     *
     * Only this call may use a stored PIN: the user opted in on an earlier sync, it belongs to the current login and
     * the OS credential store still has it. Transfers and standing orders always ask again.
     */
    suspend fun fetchAll(
        bankAccessId: Int,
        interaction: BankingInteraction,
        since: (iban: String) -> LocalDate?,
        to: LocalDate = LocalDate.now(),
    ): BankingResult<List<AccountSnapshot>> {
        pinNotSaved = false
        // The flag and key are read (and the OS store touched) on IO, before the gateway call
        val stored = withContext(Dispatchers.IO) { bankAccessRepository.findItem(bankAccessId)?.let { storedPinFor(it) } }
        val remembering = RememberingInteraction(
            inner = interaction,
            stored = stored,
            offer = canRememberPin,
            // A rejected stored PIN must not survive the re-prompt, even if the user cancels it: drop it right away
            onStoredRejected = { runCatching { forgetPin(bankAccessId) } },
        )
        val result = run(bankAccessId, ConnectionStatus.SYNCING, AppStrings[StringKey.ProgressConnecting]) { connection ->
            gateway.fetchAll(connection, remembering, since, to)
        }
        withContext(Dispatchers.IO) { finishFetchAll(bankAccessId, remembering, result) }
        return result
    }

    /**
     * Settles the PIN after a sync dialog: save an opted-in PIN after success, drop a stored PIN the bank rejected.
     * CANCELLED keeps the stored PIN (nothing changed at the bank), and a store failure never fails the sync.
     */
    private fun finishFetchAll(bankAccessId: Int, remembering: RememberingInteraction, result: BankingResult<List<AccountSnapshot>>) {
        val entered = remembering.lastEntry
        remembering.lastEntry = null // the PIN must not outlive the dialog
        when (result) {
            is BankingResult.Success -> {
                if (entered?.remember == true && entered.pin.isNotEmpty()) {
                    try {
                        savePin(bankAccessId, entered)
                    } catch (e: Exception) {
                        // Keep the sync result; tell the view model the store refused, and never leak the PIN
                        pinNotSaved = true
                    }
                }
            }

            is BankingResult.Error -> if (result.code != "CANCELLED" && remembering.storedUsed) {
                runCatching { forgetPin(bankAccessId) }
            }
        }
    }

    /** The stored PIN for the current login, or null. A stale flag (another login) forgets it; a missing entry clears the flag. */
    private fun storedPinFor(bankAccess: BankAccessItem): String? {
        val flagKey = pinFlagKey(bankAccess.id)
        val flag = settingsRepository.getValue(flagKey) ?: return null
        if (flag != loginSignature(bankAccess)) {
            // The PIN belongs to another login: it would only lock this one
            forgetPin(bankAccess.id)
            return null
        }
        val stored = try {
            credentialStore().read(pinKey(bankAccess.id))
        } catch (e: Exception) {
            // Locked or denied store: ask the user, but don't touch the entry or the flag
            return null
        }
        if (stored.isNullOrEmpty()) {
            settingsRepository.delete(flagKey)
            return null
        }
        return stored
    }

    private fun savePin(bankAccessId: Int, entry: PinEntry) {
        val bankAccess = bankAccessRepository.findItem(bankAccessId) ?: return
        credentialStore().write(pinKey(bankAccessId), entry.pin)
        settingsRepository.setValue(pinFlagKey(bankAccessId), loginSignature(bankAccess))
        auditLogRepository.log("PIN_SAVED", "user", "Saved PIN for bank access $bankAccessId for syncs")
    }

    /** Deletes a stored PIN and its flag. The flag goes first, so a store failure can never keep a rejected PIN in use. */
    fun forgetPin(bankAccessId: Int) {
        val wasSaved = settingsRepository.getValue(pinFlagKey(bankAccessId)) != null
        settingsRepository.delete(pinFlagKey(bankAccessId))
        // Also without a flag: an entry whose flag write failed must not linger in the keychain
        runCatching { credentialStore().delete(pinKey(bankAccessId)) }
        // Logged only when a PIN was remembered, not on every bank removal or user-id change
        if (wasSaved) auditLogRepository.log("PIN_FORGOTTEN", "user", "Removed stored PIN of bank access $bankAccessId")
    }

    /** A saved PIN exists for [bankAccessId]; reads only the settings flag, never the credential store. */
    fun hasSavedPin(bankAccessId: Int): Boolean = settingsRepository.getValue(pinFlagKey(bankAccessId)) != null

    /** Key of the credential entry. The data dir makes it profile-specific and survives renaming a profile. */
    private fun pinKey(bankAccessId: Int): String = pinKey(passportDir.absoluteFile.parentFile, bankAccessId)

    private fun pinFlagKey(bankAccessId: Int): String = "fints.pinSaved.$bankAccessId"

    private fun loginSignature(bankAccess: BankAccessItem): String = "${bankAccess.blz}|${bankAccess.userId}"

    /**
     * Send a SEPA single transfer. The caller must have obtained explicit user confirmation first.
     */
    suspend fun initiateTransfer(
        bankAccessId: Int,
        interaction: BankingInteraction,
        fromIban: String,
        order: TransferOrder,
    ): BankingResult<TransferResult> = run(bankAccessId, ConnectionStatus.CONNECTING, AppStrings[StringKey.ProgressSendingTransfer]) { connection ->
        gateway.transfer(connection, interaction, fromIban, order)
    }

    /**
     * Send a SEPA instant transfer (Echtzeitüberweisung), which the bank executes within seconds. Opens one dialog
     * that always asks for the PIN (a remembered PIN is used for syncs only) and is confirmed explicitly, exactly
     * like [initiateTransfer], with the same error mapping.
     */
    suspend fun initiateInstantTransfer(
        bankAccessId: Int,
        interaction: BankingInteraction,
        fromIban: String,
        order: TransferOrder,
    ): BankingResult<TransferResult> = run(bankAccessId, ConnectionStatus.CONNECTING, AppStrings[StringKey.ProgressSendingInstant]) { connection ->
        gateway.instantTransfer(connection, interaction, fromIban, order)
    }

    suspend fun scheduleTransfer(
        bankAccessId: Int,
        interaction: BankingInteraction,
        fromIban: String,
        order: TransferOrder,
        date: java.time.LocalDate,
    ): BankingResult<TransferResult> = run(bankAccessId, ConnectionStatus.CONNECTING, AppStrings[StringKey.ProgressSendingScheduled]) { connection ->
        gateway.scheduledTransfer(connection, interaction, fromIban, order, date)
    }

    suspend fun listStandingOrders(
        bankAccessId: Int,
        interaction: BankingInteraction,
        fromIban: String,
    ): BankingResult<List<StandingOrder>> = run(bankAccessId, ConnectionStatus.CONNECTING, AppStrings[StringKey.ProgressLoadingStandingOrders]) { connection ->
        gateway.listStandingOrders(connection, interaction, fromIban)
    }

    suspend fun createStandingOrder(
        bankAccessId: Int,
        interaction: BankingInteraction,
        fromIban: String,
        order: TransferOrder,
        schedule: StandingOrderSchedule,
    ): BankingResult<String?> = run(bankAccessId, ConnectionStatus.CONNECTING, AppStrings[StringKey.ProgressSendingStandingOrder]) { connection ->
        gateway.createStandingOrder(connection, interaction, fromIban, order, schedule)
    }

    suspend fun deleteStandingOrder(
        bankAccessId: Int,
        interaction: BankingInteraction,
        existing: StandingOrder,
    ): BankingResult<Unit> = run(bankAccessId, ConnectionStatus.CONNECTING, AppStrings[StringKey.ProgressDeletingStandingOrder]) { connection ->
        gateway.deleteStandingOrder(connection, interaction, existing)
    }

    /** Edits a standing order (amount, purpose and schedule). Like every order, this dialog always asks for the PIN. */
    suspend fun editStandingOrder(
        bankAccessId: Int,
        interaction: BankingInteraction,
        existing: StandingOrder,
        order: TransferOrder,
        schedule: StandingOrderSchedule,
    ): BankingResult<String?> = run(bankAccessId, ConnectionStatus.CONNECTING, AppStrings[StringKey.ProgressEditingStandingOrder]) { connection ->
        gateway.editStandingOrder(connection, interaction, existing, order, schedule)
    }

    private suspend fun <T> run(
        bankAccessId: Int,
        status: ConnectionStatus,
        step: String,
        block: (FinTSConnection) -> T,
    ): BankingResult<T> = withContext(Dispatchers.IO) {
        val bankAccess = bankAccessRepository.findItem(bankAccessId)
            ?: return@withContext BankingResult.Error("NOT_FOUND", AppStrings.format(StringKey.ErrorConnectionNotFound, bankAccessId))
        val connection = when (val c = connectionFor(bankAccess)) {
            is BankingResult.Success -> c.data
            is BankingResult.Error -> return@withContext c
        }
        _connectionStatus.value = status
        _syncProgress.value = SyncProgress(step, 0.1f)
        // Refreshed from the settings here so a trace is captured even if Settings was never opened
        ProtocolTraceStore.enabled = settingsRepository.getValue(ProtocolTraceStore.SETTING_KEY) == "true"
        try {
            val result = block(connection)
            gateway.lastTanMethod?.takeIf { it != bankAccess.tanMethod }?.let {
                bankAccessRepository.updateConnection(bankAccessId, tanMethod = it)
            }
            gateway.lastCapabilities?.let {
                bankAccessRepository.updateCapabilities(bankAccessId, it.serialize())
            }
            _connectionStatus.value = ConnectionStatus.CONNECTED
            BankingResult.Success(result)
        } catch (e: BankingCancelledException) {
            _connectionStatus.value = ConnectionStatus.DISCONNECTED
            BankingResult.Error("CANCELLED", e.message ?: AppStrings[StringKey.ErrorCancelled])
        } catch (e: Exception) {
            if (e is OrderNotExecutedException || e is UnsupportedOperationException) {
                // The bank refused it, or nothing was sent: unlike other failures, an order can't have gone through
                _connectionStatus.value = ConnectionStatus.ERROR
                return@withContext BankingResult.Error(NOT_EXECUTED, e.message ?: AppStrings[StringKey.ErrorBankRejected])
            }
            _connectionStatus.value = ConnectionStatus.ERROR
            BankingResult.Error(FINTS_ERROR, e.message ?: AppStrings[StringKey.ErrorDialogFailed])
        } finally {
            _syncProgress.value = null
        }
    }

    internal fun reportProgress(step: String, progress: Float, processed: Int = 0, total: Int = 0) {
        _syncProgress.value = SyncProgress(step, progress, processed, total)
    }

    /** Random per-bank passphrase for the HBCI4Java passport file, kept in the settings table. */
    private fun passportPassphrase(bankAccessId: Int): String {
        val key = "fints.passport.$bankAccessId"
        settingsRepository.getValue(key)?.let { return it }
        val bytes = ByteArray(24).also { SecureRandom().nextBytes(it) }
        val passphrase = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        settingsRepository.setValue(key, passphrase)
        return passphrase
    }

    /** Removes the bank's stored passport file, passphrase and any remembered PIN; call when the bank access is deleted. */
    fun deleteStoredCredentials(bankAccessId: Int) {
        resetLogin(bankAccessId, forgetPin = true)
    }

    /**
     * Forgets everything tied to the old login after a login field (BLZ, user id or server URL) changed: the
     * HBCI4Java passport file, its passphrase and, when [forgetPin] is set, a remembered PIN. The next dialog
     * then asks HBCI4Java for the login again instead of reusing the old institute's values.
     */
    fun resetLogin(bankAccessId: Int, forgetPin: Boolean) {
        gateway.resetPassport(File(passportDir, "bank-$bankAccessId.dat"))
        settingsRepository.delete("fints.passport.$bankAccessId")
        if (forgetPin) forgetPin(bankAccessId)
    }

    /**
     * Wraps the caller's interaction for a sync: a stored PIN answers the first prompt, everything else is delegated,
     * and after the bank rejects it the entry is dropped before the user is asked again. A stored PIN is never
     * returned twice, because banks lock a login after a few wrong PINs.
     */
    private class RememberingInteraction(
        private val inner: BankingInteraction,
        private val stored: String?,
        private val offer: Boolean,
        private val onStoredRejected: () -> Unit,
    ) : BankingInteraction by inner {
        var storedUsed: Boolean = false
            private set

        /** Set once the rejected stored PIN was dropped, so repeated re-prompts don't audit it again. */
        private var storedDropped = false

        /** Last PIN the user entered (not the stored one), kept to save it after a successful sync. */
        var lastEntry: PinEntry? = null

        override fun pin(bankName: String, userId: String, offerRemember: Boolean): PinEntry? {
            val saved = stored
            if (saved != null && !storedUsed) {
                storedUsed = true
                return PinEntry(saved)
            }
            // A later call means the bank rejected the PIN: forget it before asking again, so cancelling at the
            // re-prompt (or a timeout) can never keep a PIN the bank refuses
            if (storedUsed && !storedDropped) {
                storedDropped = true
                onStoredRejected()
            }
            return inner.pin(bankName, userId, offer).also { lastEntry = it }
        }
    }

    data class TransferResult(
        val transactionId: String,
        val status: String,
        /** The bank's raw order status code (instant transfers), if it reports one; never interpreted. */
        val bankStatusCode: String? = null,
    )

    companion object {
        /**
         * Credential-store key of a bank access's remembered PIN. [dataDir] (the profile's data directory) makes it
         * profile-specific; profile deletion uses the same function to find the entries.
         */
        fun pinKey(dataDir: File, bankAccessId: Int): String = "${dataDir.absolutePath}#bank-$bankAccessId"

        /** Error code for a failure that certainly didn't execute an order (see [OrderNotExecutedException]). */
        const val NOT_EXECUTED = "NOT_EXECUTED"

        /** Error code for any other dialog failure; for an order it means the outcome is unknown. */
        const val FINTS_ERROR = "FINTS_ERROR"

        /** SHA-256 over the fields that identify a booking; used for duplicate detection. */
        fun transactionHash(date: LocalDate, amount: BigDecimal, partnerName: String?, purpose: String?): String {
            val input = "$date|${amount.stripTrailingZeros().toPlainString()}|${partnerName.orEmpty()}|${purpose.orEmpty()}"
            val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
            return bytes.joinToString("") { "%02x".format(it) }
        }

        /**
         * Hash for bank-imported bookings. Includes the bank's booking id or the running balance so that
         * two identical purchases on the same day aren't treated as duplicates.
         */
        fun importHash(t: FinTSTransaction): String {
            val discriminator = t.bankId ?: t.balanceAfter?.stripTrailingZeros()?.toPlainString() ?: t.primaNota.orEmpty()
            return transactionHash(t.date, t.amount, t.partnerName, "${t.purpose.orEmpty()}#${t.account.iban}#$discriminator")
        }
    }
}
