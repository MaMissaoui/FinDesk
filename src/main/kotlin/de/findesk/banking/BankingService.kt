package de.findesk.banking

import de.findesk.data.entity.BankAccess
import de.findesk.i18n.AppStrings
import de.findesk.i18n.StringKey
import de.findesk.model.BankAccessItem
import de.findesk.model.PaymentOrderStatus
import de.findesk.repository.*
import de.findesk.util.Formats
import de.findesk.util.Iban
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.math.BigDecimal
import java.time.LocalDate

/**
 * High-level banking service coordinating FinTS operations with the local database
 */
class BankingService(
    private val finTSClient: FinTSClient = FinTSClient(),
    private val bankAccessRepository: BankAccessRepository = BankAccessRepositoryImpl(),
    private val accountRepository: AccountRepository = AccountRepositoryImpl(),
    private val transactionRepository: TransactionRepository = TransactionRepositoryImpl(),
    private val ruleRepository: RuleRepository = RuleRepositoryImpl(),
    private val auditLogRepository: AuditLogRepository = AuditLogRepositoryImpl(),
    private val paymentOrderRepository: PaymentOrderRepository = PaymentOrderRepositoryImpl(),
    private val today: () -> LocalDate = { LocalDate.now() },
) {
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    val syncProgress: StateFlow<FinTSClient.SyncProgress?> get() = finTSClient.syncProgress

    /** Whether the OS offers a credential store, so the UI may offer to remember the PIN. */
    val canRememberPin: Boolean get() = finTSClient.canRememberPin

    /** Set when the last sync's opted-in PIN could not be saved in the system keychain. */
    val pinNotSaved: Boolean get() = finTSClient.pinNotSaved

    /** A PIN is remembered for [bankAccessId] (settings flag only; the keychain is never read here). */
    fun hasSavedPin(bankAccessId: Int): Boolean = finTSClient.hasSavedPin(bankAccessId)

    /** Deletes a remembered PIN for [bankAccessId]; used by the connection settings and when the login changes. */
    fun forgetPin(bankAccessId: Int) = finTSClient.forgetPin(bankAccessId)

    companion object {
        /** First sync goes back this far (many banks require a TAN for more than 90 days). */
        const val INITIAL_SYNC_DAYS = 90L

        /** Later syncs overlap the previous one to catch late bookings; duplicates are skipped. */
        const val SYNC_OVERLAP_DAYS = 10L

        /** Error code for an order whose dialog failed after it may have reached the bank. Never resend it blindly. */
        const val OUTCOME_UNKNOWN = "OUTCOME_UNKNOWN"

        const val MAX_PURPOSE_LENGTH = 140
        const val MAX_NAME_LENGTH = 70
    }

    data class SyncReport(
        val accountsSynced: Int,
        val accountsAdded: Int,
        val imported: Int,
        val duplicates: Int,
        val categorized: Int,
        val warnings: List<String>,
        /** Start date the user chose to fetch older history from, if the sync used one. */
        val historyFrom: LocalDate? = null,
    ) {
        fun summary(): String = buildString {
            append(AppStrings.format(StringKey.SyncSummary, accountsSynced))
            if (accountsAdded > 0) append(AppStrings.format(StringKey.SyncSummaryNew, accountsAdded))
            append(AppStrings.format(StringKey.SyncSummaryTransactions, imported))
            if (categorized > 0) append(AppStrings.format(StringKey.SyncSummaryCategorized, categorized))
            if (duplicates > 0) append(AppStrings.format(StringKey.SyncSummaryDuplicates, duplicates))
            historyFrom?.let { append(AppStrings.format(StringKey.SyncSummaryHistoryFrom, Formats.date(it))) }
        }
    }

    /**
     * Register a bank login locally (no network). The first sync asks for the PIN and fetches the accounts.
     */
    suspend fun addBankConnection(config: FinTSConfig): BankAccess? = withContext(Dispatchers.IO) {
        _error.value = null
        try {
            val bankAccess = bankAccessRepository.create(config.bankName, config.blz, config.bic, config.userId, config.hbciVersion)
            config.serverUrl?.let { bankAccessRepository.updateConnection(bankAccess.id.value, serverUrl = it) }
            auditLogRepository.log("ADD_BANK", "user", "Added bank: ${config.bankName}")
            bankAccess
        } catch (e: Exception) {
            _error.value = e.message
            null
        }
    }

    /**
     * Saves a bank login's connection settings. The name is only a label; when the BLZ, user id or server URL
     * changed, FinDesk's registration with the bank is reset (passport deleted, PIN forgotten when the login
     * changed) so the next sync registers again instead of reusing the old institute's values.
     */
    suspend fun updateConnection(bankAccessId: Int, name: String, blz: String, userId: String, serverUrl: String): BankAccessItem? = withContext(Dispatchers.IO) {
        val previous = bankAccessRepository.findItem(bankAccessId)
        val newBlz = blz.trim()
        val newUserId = userId.trim()
        val newUrl = serverUrl.trim().ifEmpty { null }
        val blzChanged = previous != null && previous.blz != newBlz
        val userChanged = previous != null && previous.userId != newUserId
        val urlChanged = previous != null && previous.serverUrl != newUrl

        val loginChanged = blzChanged || userChanged || urlChanged
        // Reset before saving: a failed save then only costs a fresh registration, while a failed reset after the
        // save would leave the old passport in use with the new settings shown, the silent state this prevents
        if (loginChanged) finTSClient.resetLogin(bankAccessId, forgetPin = blzChanged || userChanged)
        val updated = bankAccessRepository.updateConnection(
            bankAccessId,
            serverUrl = serverUrl,
            userId = userId,
            bankName = name,
            blz = blz,
            // The BIC follows the BLZ only when the directory knows the new one; otherwise the old BIC stays.
            bic = if (blzChanged) BankDirectory.lookup(newBlz)?.bic else null,
        )
        if (loginChanged) {
            bankAccessRepository.clearLoginState(bankAccessId)
            val fields = buildList {
                if (blzChanged) add("bank code")
                if (userChanged) add("user ID")
                if (urlChanged) add("server URL")
            }.joinToString(", ")
            auditLogRepository.log("RESET_FINTS_LOGIN", "user", "Bank access $bankAccessId: $fields changed, passport reset")
        }
        if (blzChanged) {
            auditLogRepository.log("CHANGE_BANK_CODE", "user", "Bank access $bankAccessId: BLZ ${previous?.blz} -> $newBlz")
        }
        updated
    }

    /**
     * Removes a bank login with its accounts, transactions and the stored FinTS passport/passphrase.
     */
    suspend fun removeBankAccess(bankAccessId: Int): Boolean = withContext(Dispatchers.IO) {
        val name = bankAccessRepository.findItem(bankAccessId)?.bankName
        val removed = bankAccessRepository.deleteWithAccounts(bankAccessId)
        if (removed) {
            finTSClient.deleteStoredCredentials(bankAccessId)
            auditLogRepository.log("DELETE_BANK_ACCESS", "user", "Deleted bank access ${bankAccessId}${name?.let { " ($it)" }.orEmpty()}")
        }
        removed
    }

    /**
     * Sync one bank login: accounts (matched by IBAN, new ones are created), balances and bookings.
     */
    suspend fun syncBankConnection(bankAccessId: Int, interaction: BankingInteraction): BankingResult<SyncReport> {
        _isLoading.value = true
        _error.value = null
        try {
            val bankAccess = withContext(Dispatchers.IO) { bankAccessRepository.findItem(bankAccessId) }
                ?: return BankingResult.Error("NOT_FOUND", AppStrings[StringKey.ErrorBankConnectionNotFound])
            val known = withContext(Dispatchers.IO) { accountRepository.findAllItems() }.filter { it.bankAccessId == bankAccessId }
            val now = today()
            val defaultStart = now.minusDays(INITIAL_SYNC_DAYS)
            // A chosen history start applies to every account, known or new, and is not coerced to defaultStart: the
            // point is to reach further back than the initial window. It never narrows the normal window, though, or a
            // start after the last sync would skip the bookings in between while lastSync still advanced.
            val historyFrom = bankAccess.syncFrom
            val since: (String) -> LocalDate = { iban ->
                val normal = if (known.any { it.iban.equals(iban, true) }) {
                    bankAccess.lastSync?.toLocalDate()?.minusDays(SYNC_OVERLAP_DAYS)?.coerceAtLeast(defaultStart) ?: defaultStart
                } else {
                    defaultStart
                }
                historyFrom?.let { minOf(it, normal) } ?: normal
            }

            val snapshots = when (val r = finTSClient.fetchAll(bankAccessId, interaction, since, now)) {
                is BankingResult.Success -> r.data
                is BankingResult.Error -> return r.also { _error.value = it.message }
            }

            return withContext(Dispatchers.IO) {
                var added = 0
                var imported = 0
                var duplicates = 0
                var categorized = 0
                val warnings = mutableListOf<String>()
                snapshots.filter { it.account.iban.isNotBlank() }.forEachIndexed { i, snap ->
                    finTSClient.reportProgress(AppStrings.format(StringKey.ProgressSavingAccount, snap.account.iban.takeLast(4)), 0.5f + 0.5f * i / snapshots.size, i, snapshots.size)
                    val iban = Iban.normalize(snap.account.iban)
                    val existing = known.firstOrNull { it.iban.equals(iban, true) }
                    val accountId = existing?.id ?: accountRepository.create(
                        bankAccessId = bankAccessId,
                        iban = iban,
                        accountNumber = snap.account.accountNumber.ifEmpty { null },
                        balance = snap.balance?.balance ?: BigDecimal.ZERO,
                        currency = snap.account.currency,
                        accountType = snap.account.accountType,
                    ).id.value.also { added++ }
                    snap.balance?.let { accountRepository.updateBalance(accountId, it.balance) }
                    snap.statementError?.let { warnings += "${iban.takeLast(4)}: $it" }

                    val result = importTransactions(accountId, snap.transactions)
                    imported += result.imported
                    duplicates += result.duplicates
                    categorized += result.categorized
                }
                // If statements failed for any account, keep the old sync date so the next sync re-fetches that window
                if (warnings.isEmpty()) {
                    bankAccessRepository.updateLastSync(bankAccessId)
                    // The chosen history start is one-shot: a fully successful sync clears it, a failure keeps it
                    if (historyFrom != null) bankAccessRepository.setSyncFrom(bankAccessId, null)
                }
                val report = SyncReport(snapshots.size, added, imported, duplicates, categorized, warnings, historyFrom = historyFrom)
                auditLogRepository.log("SYNC_BANK", "user", "${bankAccess.bankName}: ${report.summary()}")
                BankingResult.Success(report)
            }
        } catch (e: Exception) {
            _error.value = e.message
            return BankingResult.Error("SYNC_FAILED", e.message ?: AppStrings[StringKey.ErrorSyncFailed])
        } finally {
            _isLoading.value = false
        }
    }

    /**
     * Import bank bookings into the local database, skipping ones already imported and applying rules.
     */
    suspend fun importTransactions(
        accountId: Int,
        finTSTransactions: List<FinTSTransaction>,
    ): ImportResult = withContext(Dispatchers.IO) {
        val plan = de.findesk.repository.BookingDedup.plan(
            accountId,
            finTSTransactions.map { de.findesk.repository.BookingDedup.Candidate(it, it.date, it.amount, FinTSClient.importHash(it)) },
            transactionRepository,
        )
        val rules = ruleRepository.matcher()
        val rows = plan.toImport.map { candidate ->
            val t = candidate.item
            NewTransaction(
                accountId = accountId,
                date = t.date,
                amount = t.amount,
                currency = t.currency,
                partnerName = t.partnerName?.take(255),
                purpose = t.purpose,
                categoryId = rules.categoryFor(purpose = t.purpose, partnerName = t.partnerName),
                hash = candidate.hash,
                balanceAfter = t.balanceAfter,
            )
        }
        val imported = transactionRepository.createAll(rows)

        ImportResult(imported = imported, duplicates = plan.exactDuplicates + plan.probableDuplicates, categorized = rows.count { it.categoryId != null })
    }

    /** Validates a transfer before anything is sent; returns an error message or null. */
    fun validateTransfer(order: TransferOrder): String? = when {
        order.recipientName.isBlank() -> AppStrings[StringKey.ErrorRecipientNameRequired]
        order.recipientName.length > MAX_NAME_LENGTH -> AppStrings.format(StringKey.ErrorRecipientNameTooLong, MAX_NAME_LENGTH)
        !Iban.isValid(order.recipientIban) -> AppStrings[StringKey.ErrorRecipientIbanInvalid]
        order.amount.signum() <= 0 -> AppStrings[StringKey.ErrorAmountGreaterThanZero]
        order.amount.scale() > 2 && order.amount.stripTrailingZeros().scale() > 2 -> AppStrings[StringKey.ErrorAmountMaxDecimals]
        order.purpose.length > MAX_PURPOSE_LENGTH -> AppStrings.format(StringKey.ErrorPurposeTooLong, MAX_PURPOSE_LENGTH)
        else -> null
    }

    /** Checks a standing order's schedule; returns an error message or null. */
    fun validateSchedule(schedule: StandingOrderSchedule): String? = when {
        schedule.interval == null -> AppStrings[StringKey.ErrorIntervalUnsupported]
        !schedule.firstDate.isAfter(today()) -> AppStrings[StringKey.ErrorFirstExecutionFuture]
        schedule.lastDate != null && schedule.lastDate.isBefore(schedule.firstDate) -> AppStrings[StringKey.ErrorLastBeforeFirst]
        schedule.timeUnit == "M" && schedule.executionDay !in 1..31 && schedule.executionDay != 99 -> AppStrings[StringKey.ErrorExecutionDayMonth]
        schedule.timeUnit == "W" && schedule.executionDay !in 1..7 -> AppStrings[StringKey.ErrorExecutionDayWeek]
        else -> null
    }

    fun validateExecutionDate(date: LocalDate): String? = if (!date.isAfter(today())) AppStrings[StringKey.ErrorExecutionDateFuture] else null

    /**
     * Error if the bank login is known not to support [job]; null when it does or when its capabilities are
     * unknown (never synced, or the bank reported nothing), in which case the bank decides.
     */
    private fun unsupported(bankAccessId: Int, job: String): String? {
        val capabilities = BankCapabilities.parse(bankAccessRepository.findItem(bankAccessId)?.capabilities) ?: return null
        return if (capabilities.supports(job)) null else AppStrings[StringKey.ErrorOrderNotOffered]
    }

    /**
     * Initiate a SEPA transfer. Moves real money: [confirmed] must reflect an explicit user confirmation
     * of the exact order, otherwise nothing is sent.
     */
    suspend fun initiateTransfer(
        fromAccountId: Int,
        order: TransferOrder,
        interaction: BankingInteraction,
        confirmed: Boolean,
    ): BankingResult<FinTSClient.TransferResult> = sendOrder("TRANSFER", fromAccountId, order, confirmed, check = null, describe = "") { account, o ->
        finTSClient.initiateTransfer(account.bankAccessId, interaction, account.iban, o)
    }

    /** SEPA transfer executed on [date]. Same guards as [initiateTransfer]. */
    suspend fun scheduleTransfer(
        fromAccountId: Int,
        order: TransferOrder,
        date: LocalDate,
        interaction: BankingInteraction,
        confirmed: Boolean,
    ): BankingResult<FinTSClient.TransferResult> = sendOrder(
        "SCHEDULED_TRANSFER",
        fromAccountId,
        order,
        confirmed,
        validateExecutionDate(date),
        " on $date",
        requestedDate = date,
        accountCheck = { account -> unsupported(account.bankAccessId, BankCapabilities.JOB_SCHEDULED_TRANSFER) },
    ) { account, o ->
        finTSClient.scheduleTransfer(account.bankAccessId, interaction, account.iban, o, date)
    }

    /** Creates a standing order at the bank. Moves money repeatedly: same guards as [initiateTransfer]. */
    suspend fun createStandingOrder(
        fromAccountId: Int,
        order: TransferOrder,
        schedule: StandingOrderSchedule,
        interaction: BankingInteraction,
        confirmed: Boolean,
    ): BankingResult<String?> = sendOrder(
        "STANDING_ORDER_CREATE",
        fromAccountId,
        order,
        confirmed,
        validateSchedule(schedule),
        " ${schedule.interval?.name?.lowercase() ?: "${schedule.step}${schedule.timeUnit}"} from ${schedule.firstDate} on day ${schedule.executionDay}" +
            (schedule.lastDate?.let { " until $it" } ?: ""),
        schedule = "${schedule.interval?.name?.lowercase() ?: "${schedule.step}${schedule.timeUnit}"} from ${schedule.firstDate} on day ${schedule.executionDay}" +
            (schedule.lastDate?.let { " until $it" } ?: ""),
        accountCheck = { account -> unsupported(account.bankAccessId, BankCapabilities.JOB_STANDING_NEW) },
    ) { account, o ->
        finTSClient.createStandingOrder(account.bankAccessId, interaction, account.iban, o, schedule)
    }

    /** Read-only: the account's standing orders as the bank reports them. */
    suspend fun loadStandingOrders(fromAccountId: Int, interaction: BankingInteraction): BankingResult<List<StandingOrder>> {
        val account = withContext(Dispatchers.IO) { accountRepository.findAllItems().firstOrNull { it.id == fromAccountId } }
            ?: return BankingResult.Error("NOT_FOUND", AppStrings[StringKey.ErrorAccountNotFound])
        unsupported(account.bankAccessId, BankCapabilities.JOB_STANDING_LIST)?.let { return BankingResult.Error("UNSUPPORTED", it) }
        return finTSClient.listStandingOrders(account.bankAccessId, interaction, account.iban)
    }

    /** Deletes a standing order, which stops its future payments. Same guards as other orders. */
    suspend fun deleteStandingOrder(
        fromAccountId: Int,
        existing: StandingOrder,
        interaction: BankingInteraction,
        confirmed: Boolean,
    ): BankingResult<Unit> {
        val check = if (existing.orderId == null || !existing.canDelete) AppStrings[StringKey.ErrorCannotDeleteStandingOrder] else null
        return sendOrder(
            action = "STANDING_ORDER_DELETE",
            fromAccountId = fromAccountId,
            order = existing.order,
            confirmed = confirmed,
            check = check,
            describe = " (id ${existing.orderId})",
            // The bank may report names/purposes longer than we accept when sending; don't re-validate those
            validateOrder = false,
            accountCheck = { account ->
                if (!Iban.normalize(existing.fromIban).equals(account.iban, ignoreCase = true)) {
                    AppStrings[StringKey.ErrorStandingOrderOtherAccount]
                } else {
                    unsupported(account.bankAccessId, BankCapabilities.JOB_STANDING_DELETE)
                }
            },
        ) { account, _ ->
            finTSClient.deleteStandingOrder(account.bankAccessId, interaction, existing)
        }
    }

    /**
     * Shared guard for everything that changes an order at the bank: explicit confirmation of the exact order, a valid
     * order (and [check], e.g. the schedule), an EUR source account and any [accountCheck]. Every attempt, including
     * the rejected ones, is audit-logged with its outcome.
     */
    private suspend fun <T> sendOrder(
        action: String,
        fromAccountId: Int,
        order: TransferOrder,
        confirmed: Boolean,
        check: String?,
        describe: String,
        validateOrder: Boolean = true,
        accountCheck: ((de.findesk.model.AccountItem) -> String?)? = null,
        requestedDate: LocalDate? = null,
        schedule: String? = null,
        send: suspend (de.findesk.model.AccountItem, TransferOrder) -> BankingResult<T>,
    ): BankingResult<T> {
        val normalized = order.copy(recipientIban = Iban.normalize(order.recipientIban), recipientName = order.recipientName.trim())

        suspend fun deny(code: String, message: String, accountIban: String? = null): BankingResult<T> {
            withContext(Dispatchers.IO) { auditOrder(action, normalized, accountIban, describe, "failed: $message") }
            return BankingResult.Error(code, message)
        }

        if (!confirmed) return deny("NOT_CONFIRMED", AppStrings[StringKey.ErrorOrderNotConfirmed])
        if (validateOrder) validateTransfer(order)?.let { return deny("INVALID", it) }
        check?.let { return deny("INVALID", it) }

        _isLoading.value = true
        _error.value = null
        try {
            val account = withContext(Dispatchers.IO) { accountRepository.findAllItems().firstOrNull { it.id == fromAccountId } }
                ?: return deny("NOT_FOUND", AppStrings[StringKey.ErrorAccountNotFound])
            accountCheck?.let { it(account)?.let { message -> return deny("INVALID", message, account.iban) } }
            // SEPA credit transfers are EUR only; the order amount is in EUR, so another account currency would be wrong
            if (account.currency != "EUR") return deny("CURRENCY", AppStrings.format(StringKey.ErrorSepaEurOnly, account.currency), account.iban)
            // From here on the attempt is seen through even if the caller is cancelled (e.g. the Payments screen closes
            // mid-dialog): the bank may already have the order, so its answer must be recorded and audited, not dropped
            return withContext(NonCancellable + Dispatchers.IO) {
                // Recorded before sending so a crash mid-dialog still leaves a trace; the bank's answer updates it
                val recordId = paymentOrderRepository.create(
                    action = action,
                    accountIban = account.iban,
                    recipientName = normalized.recipientName,
                    recipientIban = normalized.recipientIban,
                    amount = normalized.amount,
                    currency = account.currency,
                    purpose = normalized.purpose,
                    requestedDate = requestedDate,
                    schedule = schedule,
                )
                val result = try {
                    ambiguousAsUnknown(send(account, normalized))
                } catch (e: Exception) {
                    BankingResult.Error(OUTCOME_UNKNOWN, AppStrings.format(StringKey.ErrorOrderOutcomeUnknown, e.message ?: e.javaClass.simpleName))
                }
                paymentOrderRepository.complete(recordId, statusOf(result), bankOrderId(result), resultMessage(result))
                auditOrder(action, normalized, account.iban, describe, outcome(result))
                if (result is BankingResult.Error) _error.value = result.message
                result
            }
        } finally {
            _isLoading.value = false
        }
    }

    /** Audit-log line for an order attempt; [accountIban] is null while the account isn't known yet. */
    private fun auditOrder(action: String, order: TransferOrder, accountIban: String?, describe: String, outcome: String) {
        val from = accountIban?.let { "from …${it.takeLast(4)} " }.orEmpty()
        auditLogRepository.log(
            action,
            "user",
            "${order.amount.toPlainString()} EUR ${from}to ${order.recipientName} …${order.recipientIban.takeLast(4)}$describe: $outcome",
        )
    }

    /**
     * A dialog failure other than a definite rejection may come after the bank received the order. Sending it again
     * could pay twice, so it is reported as unknown and the user is told to check online banking first.
     */
    private fun <T> ambiguousAsUnknown(result: BankingResult<T>): BankingResult<T> = if (result is BankingResult.Error && result.code == FinTSClient.FINTS_ERROR) {
        BankingResult.Error(OUTCOME_UNKNOWN, AppStrings.format(StringKey.ErrorOrderOutcomeUnknown, result.message))
    } else {
        result
    }

    private fun outcome(result: BankingResult<*>): String = when (result) {
        is BankingResult.Success -> "sent"
        is BankingResult.Error -> if (result.code == OUTCOME_UNKNOWN) "outcome unknown: ${result.message}" else "failed: ${result.message}"
    }

    /** Recorded status of an attempt, from the bank's answer. */
    private fun statusOf(result: BankingResult<*>): String = when (result) {
        is BankingResult.Success -> PaymentOrderStatus.ACCEPTED

        is BankingResult.Error -> when (result.code) {
            "CANCELLED" -> PaymentOrderStatus.CANCELLED
            OUTCOME_UNKNOWN -> PaymentOrderStatus.UNKNOWN
            else -> PaymentOrderStatus.REJECTED
        }
    }

    /** The reference the bank gave for the order, when it reports one. */
    private fun bankOrderId(result: BankingResult<*>): String? = when (result) {
        is BankingResult.Success -> when (val data = result.data) {
            is FinTSClient.TransferResult -> data.transactionId
            is String -> data
            else -> null
        }

        else -> null
    }

    private fun resultMessage(result: BankingResult<*>): String? = (result as? BankingResult.Error)?.message

    data class ImportResult(
        val imported: Int,
        val duplicates: Int,
        val categorized: Int,
    )
}
