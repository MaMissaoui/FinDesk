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
    private val gateway: FinTSGateway = Hbci4JavaGateway(),
    private val tanManager: TanManager = TanManager(),
    private val passportDir: File = File(AppPaths.dataDir, "passports"),
) {
    private val _connectionStatus = MutableStateFlow(ConnectionStatus.DISCONNECTED)
    val connectionStatus: StateFlow<ConnectionStatus> = _connectionStatus.asStateFlow()

    private val _syncProgress = MutableStateFlow<SyncProgress?>(null)
    val syncProgress: StateFlow<SyncProgress?> = _syncProgress.asStateFlow()

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
     */
    suspend fun fetchAll(
        bankAccessId: Int,
        interaction: BankingInteraction,
        since: (iban: String) -> LocalDate?,
        to: LocalDate = LocalDate.now(),
    ): BankingResult<List<AccountSnapshot>> = run(bankAccessId, ConnectionStatus.SYNCING, AppStrings[StringKey.ProgressConnecting]) { connection ->
        gateway.fetchAll(connection, interaction, since, to)
    }

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
            is BankingResult.TanRequired -> return@withContext c
        }
        _connectionStatus.value = status
        _syncProgress.value = SyncProgress(step, 0.1f)
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
            _connectionStatus.value = ConnectionStatus.ERROR
            BankingResult.Error("FINTS_ERROR", e.message ?: AppStrings[StringKey.ErrorDialogFailed])
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

    /** Removes the bank's stored passport file and passphrase; call when the bank access is deleted. */
    fun deleteStoredCredentials(bankAccessId: Int) {
        File(passportDir, "bank-$bankAccessId.dat").delete()
        settingsRepository.delete("fints.passport.$bankAccessId")
    }

    /**
     * Create a hash for transaction duplicate detection
     */
    fun createTransactionHash(
        date: LocalDate,
        amount: BigDecimal,
        partnerName: String?,
        purpose: String?,
    ): String = transactionHash(date, amount, partnerName, purpose)

    /**
     * TAN methods commonly offered by German banks. The bank's actual list is requested during the dialog.
     */
    fun getTanMethods(): List<TanMethod> = tanManager.getSupportedMethods()

    data class SyncResult(
        val accountsSynced: Int,
        val transactionsImported: Int,
        val duplicatesSkipped: Int,
    )

    data class TransferResult(
        val transactionId: String,
        val status: String,
        val tanRequired: Boolean = false,
    )

    companion object {
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
