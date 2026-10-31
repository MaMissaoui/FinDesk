package de.findesk.banking

import de.findesk.data.entity.BankAccess
import de.findesk.i18n.AppStrings
import de.findesk.i18n.StringKey
import de.findesk.model.BankAccessItem
import de.findesk.model.PaymentAction
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
import java.time.LocalDateTime

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
    private val pendingRepository: PendingBookingRepository = PendingBookingRepositoryImpl(),
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
        /** Things the user should know but that must not make the sync look failed (e.g. a missing balance). */
        val notes: List<String> = emptyList(),
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
            notes.forEach { append("\n").append(it) }
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
        // An offline bank has no FinTS login: only its name may change, and never its passport or PIN
        if (previous?.offline == true) {
            return@withContext bankAccessRepository.updateConnection(bankAccessId, bankName = name)
        }
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
            // An offline bank never reaches FinTS: no dialog, no credentials, no sync
            if (bankAccess.offline) {
                return BankingResult.Error("OFFLINE", AppStrings[StringKey.ErrorBankOffline]).also { _error.value = it.message }
            }
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
                val notes = mutableListOf<String>()
                val stored = snapshots.filter { it.account.iban.isNotBlank() }
                // The known account's window is deliberately coerced to defaultStart (banks ask for a TAN beyond
                // 90 days). When that cuts into the time since the last sync, those bookings would never be fetched
                // and lastSync still advances, so tell the user once, as a note rather than a warning.
                val gapStart = bankAccess.lastSync?.toLocalDate()?.minusDays(SYNC_OVERLAP_DAYS)
                if (gapStart != null && gapStart.isBefore(defaultStart) &&
                    (historyFrom == null || historyFrom.isAfter(gapStart)) &&
                    stored.any { snap -> known.any { it.iban.equals(Iban.normalize(snap.account.iban), true) } }
                ) {
                    val gapEnd = minOf(historyFrom ?: defaultStart, defaultStart).minusDays(1)
                    notes += AppStrings.format(StringKey.SyncGapNotFetched, Formats.date(gapStart), Formats.date(gapEnd))
                }
                // Accounts without an IBAN are still dropped (they can't be matched or stored yet), but silently
                // losing them is not acceptable: name each one by at most its last four characters.
                snapshots.filter { it.account.iban.isBlank() }.forEach { snap ->
                    notes += AppStrings.format(StringKey.SyncAccountWithoutIban, noteSuffix(snap.account))
                }
                stored.forEachIndexed { i, snap ->
                    finTSClient.reportProgress(AppStrings.format(StringKey.ProgressSavingAccount, snap.account.iban.takeLast(4)), 0.5f + 0.5f * i / stored.size, i, stored.size)
                    val iban = Iban.normalize(snap.account.iban)
                    val existing = known.firstOrNull { it.iban.equals(iban, true) }
                    val accountCurrency = existing?.currency ?: snap.account.currency
                    val balance = snap.balance
                    val balanceMatchesCurrency = balance == null || balance.currency.equals(accountCurrency, ignoreCase = true)
                    val accountId = existing?.id ?: accountRepository.create(
                        bankAccessId = bankAccessId,
                        iban = iban,
                        accountNumber = snap.account.accountNumber.ifEmpty { null },
                        balance = if (balance != null && !balance.currency.equals(snap.account.currency, ignoreCase = true)) BigDecimal.ZERO else balance?.balance ?: BigDecimal.ZERO,
                        currency = snap.account.currency,
                        accountType = snap.account.accountType,
                    ).id.value.also { added++ }
                    // A sync only corrects the type the bank clearly reports: a depot the bank now calls a loan
                    if (existing != null && existing.accountType == "depot" && snap.account.accountType == "loan") {
                        accountRepository.update(existing.id, accountType = "loan")
                        auditLogRepository.log("CORRECT_ACCOUNT_TYPE", "user", "Account …${iban.takeLast(4)}: depot -> loan")
                    }
                    if (balance != null) {
                        if (balanceMatchesCurrency) {
                            accountRepository.updateBalance(accountId, balance.balance)
                        } else {
                            notes += AppStrings.format(StringKey.SyncBalanceOtherCurrency, iban.takeLast(4), balance.currency, accountCurrency)
                        }
                    }
                    snap.statementError?.let { warnings += "${iban.takeLast(4)}: $it" }
                    // No balance and nothing entered yet: tell the user, but never gate the sync on it
                    if (balance == null && (existing == null || existing.balance.compareTo(BigDecimal.ZERO) == 0)) {
                        notes += if (snap.balanceCode != null) {
                            AppStrings.format(StringKey.BalanceNotProvided, iban.takeLast(4), snap.balanceCode)
                        } else {
                            AppStrings.format(StringKey.BalanceNotProvidedNoCode, iban.takeLast(4))
                        }
                    }

                    val result = importTransactions(accountId, snap.transactions)
                    imported += result.imported
                    duplicates += result.duplicates
                    categorized += result.categorized
                    // Pending (vorgemerkte) lines, kept apart from the bookings: fetched means replace all of
                    // the account's rows (an empty list clears them), null = not fetched keeps what's stored.
                    // A failure here must never fail the sync, so it silently counts as a no-op.
                    snap.pending?.let { lines ->
                        runCatching {
                            pendingRepository.replaceForAccount(
                                accountId,
                                lines.map { t ->
                                    de.findesk.model.PendingBookingItem(
                                        accountId = accountId,
                                        date = t.date,
                                        valueDate = t.valueDate,
                                        amount = t.amount,
                                        currency = t.currency,
                                        partnerName = t.partnerName,
                                        purpose = t.purpose,
                                    )
                                },
                                LocalDateTime.now(),
                            )
                        }
                    }
                }
                // If statements failed for any account, keep the old sync date so the next sync re-fetches that window
                if (warnings.isEmpty()) {
                    bankAccessRepository.updateLastSync(bankAccessId)
                    // The chosen history start is one-shot: a fully successful sync clears it, a failure keeps it
                    if (historyFrom != null) bankAccessRepository.setSyncFrom(bankAccessId, null)
                }
                val report = SyncReport(stored.size, added, imported, duplicates, categorized, warnings, notes, historyFrom = historyFrom)
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

    /** At most the last four characters identifying an account in a note; never a full number. */
    private fun noteSuffix(account: FinTSAccount): String = account.accountNumber.ifBlank { account.productName?.takeIf { it.isNotBlank() } ?: account.accountType }
        .takeLast(4)
        .ifBlank { "?" }

    /**
     * Import bank bookings into the local database, skipping ones already imported (stored or remembered as
     * deleted) and applying rules.
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
        // The first import into an account is its history, not news: connecting a bank must not make 90 days bold
        val isFirstImport = !transactionRepository.hasBookings(accountId)
        val rows = plan.toImport.map { candidate ->
            val t = candidate.item
            NewTransaction(
                accountId = accountId,
                date = t.date,
                amount = t.amount,
                currency = t.currency,
                partnerName = t.partnerName?.take(255),
                purpose = t.purpose,
                categoryId = rules.categoryFor(purpose = t.purpose, partnerName = t.partnerName, amount = t.amount),
                hash = candidate.hash,
                balanceAfter = t.balanceAfter,
                isNew = !isFirstImport,
            )
        }
        val imported = transactionRepository.createAll(rows)

        // A remembered deleted booking comes back through no new field: it is not new, so it counts as duplicate
        ImportResult(imported = imported, duplicates = plan.exactDuplicates + plan.probableDuplicates + plan.previouslyDeleted, categorized = rows.count { it.categoryId != null })
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
     * Checks what an edit of [existing] may change; returns an error message or null. The recipient (name, IBAN,
     * BIC) is fixed: the bank identifies the order by its data, so changing it means deleting the order and creating
     * a new one. An order with an interval FinDesk has no [StandingOrderInterval] for (e.g. every 2 months) can't be
     * edited at all: the form could only hand in a schedule that changes how often it pays. A changed schedule
     * differs from [validateSchedule] for a running order: its unchanged first date may lie in the past, but a
     * changed (or new last) date must be in the future.
     */
    fun validateEditedOrder(existing: StandingOrder, order: TransferOrder, schedule: StandingOrderSchedule): String? = when {
        Iban.normalize(order.recipientIban) != Iban.normalize(existing.order.recipientIban) ||
            order.recipientName.trim() != existing.order.recipientName.trim() ||
            order.recipientBic?.trim().orEmpty() != existing.order.recipientBic?.trim().orEmpty() ->
            AppStrings[StringKey.ErrorEditRecipientFixed]

        // The edit form keeps only intervals FinDesk offers; anything else it hands in would change the cadence
        existing.schedule.interval == null -> AppStrings[StringKey.ErrorEditIntervalUnsupported]

        order.amount.signum() <= 0 -> AppStrings[StringKey.ErrorAmountGreaterThanZero]

        order.amount.scale() > 2 && order.amount.stripTrailingZeros().scale() > 2 -> AppStrings[StringKey.ErrorAmountMaxDecimals]

        order.purpose.length > MAX_PURPOSE_LENGTH -> AppStrings.format(StringKey.ErrorPurposeTooLong, MAX_PURPOSE_LENGTH)

        schedule.interval == null -> AppStrings[StringKey.ErrorIntervalUnsupported]

        // A running order keeps its start; only a moved start has to be a new one
        schedule.firstDate != existing.schedule.firstDate && !schedule.firstDate.isAfter(today()) ->
            AppStrings[StringKey.ErrorFirstExecutionFuture]

        schedule.lastDate != null && !schedule.lastDate.isAfter(today()) -> AppStrings[StringKey.ErrorLastDateFuture]

        schedule.lastDate != null && schedule.lastDate.isBefore(schedule.firstDate) -> AppStrings[StringKey.ErrorLastBeforeFirst]

        schedule.timeUnit == "M" && schedule.executionDay !in 1..31 && schedule.executionDay != 99 -> AppStrings[StringKey.ErrorExecutionDayMonth]

        schedule.timeUnit == "W" && schedule.executionDay !in 1..7 -> AppStrings[StringKey.ErrorExecutionDayWeek]

        else -> null
    }

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

    /**
     * Moves real money within seconds; same guards as [initiateTransfer]. The bank keeps the raw order status in
     * the recorded message. There is deliberately no fallback: if the instant order fails, is rejected, is
     * cancelled or has an unknown outcome, no normal transfer is sent instead — that could pay twice.
     */
    suspend fun initiateInstantTransfer(
        fromAccountId: Int,
        order: TransferOrder,
        interaction: BankingInteraction,
        confirmed: Boolean,
    ): BankingResult<FinTSClient.TransferResult> = sendOrder(
        PaymentAction.INSTANT_TRANSFER,
        fromAccountId,
        order,
        confirmed,
        check = null,
        describe = " (instant)",
        accountCheck = { account -> unsupported(account.bankAccessId, BankCapabilities.JOB_INSTANT_TRANSFER) },
    ) { account, o ->
        finTSClient.initiateInstantTransfer(account.bankAccessId, interaction, account.iban, o)
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

    /**
     * Changes amount, purpose and schedule of a standing order; the recipient can't be edited. Same guards as other
     * orders: [validateEditedOrder] takes the place of [validateTransfer] (and the schedule check of
     * [validateSchedule]), because the bank-reported order data may exceed what sending accepts and because a
     * running order's unchanged first date may lie in the past.
     */
    suspend fun editStandingOrder(
        fromAccountId: Int,
        existing: StandingOrder,
        order: TransferOrder,
        schedule: StandingOrderSchedule,
        interaction: BankingInteraction,
        confirmed: Boolean,
    ): BankingResult<String?> {
        val scheduleText = "${schedule.interval?.name?.lowercase() ?: "${schedule.step}${schedule.timeUnit}"} from ${schedule.firstDate} on day ${schedule.executionDay}" +
            (schedule.lastDate?.let { " until $it" } ?: "")
        val check = if (existing.orderId == null || !existing.canChange) AppStrings[StringKey.ErrorCannotEditStandingOrder] else validateEditedOrder(existing, order, schedule)
        return sendOrder(
            action = PaymentAction.STANDING_ORDER_EDIT,
            fromAccountId = fromAccountId,
            order = order,
            confirmed = confirmed,
            check = check,
            describe = " (id ${existing.orderId}) $scheduleText",
            // The recipient is fixed and the editable fields were checked above
            validateOrder = false,
            schedule = scheduleText,
            accountCheck = { account ->
                if (!Iban.normalize(existing.fromIban).equals(account.iban, ignoreCase = true)) {
                    AppStrings[StringKey.ErrorStandingOrderOtherAccount]
                } else {
                    unsupported(account.bankAccessId, BankCapabilities.JOB_STANDING_EDIT)
                }
            },
        ) { account, o ->
            finTSClient.editStandingOrder(account.bankAccessId, interaction, existing, o, schedule)
        }
    }

    /** Read-only: the account's standing orders as the bank reports them. */
    suspend fun loadStandingOrders(fromAccountId: Int, interaction: BankingInteraction): BankingResult<List<StandingOrder>> {
        val account = withContext(Dispatchers.IO) { accountRepository.findAllItems().firstOrNull { it.id == fromAccountId } }
            ?: return BankingResult.Error("NOT_FOUND", AppStrings[StringKey.ErrorAccountNotFound])
        // No standing orders for an account of an offline bank, nor for one whose identifier is not an IBAN
        if (bankAccessRepository.findItem(account.bankAccessId)?.offline == true || !Iban.isValid(account.iban)) {
            return BankingResult.Error("OFFLINE", AppStrings[StringKey.ErrorOrderOfflineAccount])
        }
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

    /** Read-only: the account's scheduled transfers as the bank reports them. */
    suspend fun loadScheduledTransfers(fromAccountId: Int, interaction: BankingInteraction): BankingResult<List<ScheduledTransfer>> {
        val account = withContext(Dispatchers.IO) { accountRepository.findAllItems().firstOrNull { it.id == fromAccountId } }
            ?: return BankingResult.Error("NOT_FOUND", AppStrings[StringKey.ErrorAccountNotFound])
        // No scheduled transfers for an account of an offline bank, nor for one whose identifier is not an IBAN
        if (bankAccessRepository.findItem(account.bankAccessId)?.offline == true || !Iban.isValid(account.iban)) {
            return BankingResult.Error("OFFLINE", AppStrings[StringKey.ErrorOrderOfflineAccount])
        }
        unsupported(account.bankAccessId, BankCapabilities.JOB_SCHEDULED_LIST)?.let { return BankingResult.Error("UNSUPPORTED", it) }
        return finTSClient.listScheduledTransfers(account.bankAccessId, interaction, account.iban)
    }

    /**
     * Cancels a scheduled transfer, so the bank does not execute it on [ScheduledTransfer.date]. Same guards as
     * other orders; the bank decides with the recorded order data and the execution date.
     */
    suspend fun deleteScheduledTransfer(
        fromAccountId: Int,
        existing: ScheduledTransfer,
        interaction: BankingInteraction,
        confirmed: Boolean,
    ): BankingResult<Unit> {
        val check = if (existing.orderId == null || !existing.canDelete) AppStrings[StringKey.ErrorCannotDeleteScheduledTransfer] else null
        return sendOrder(
            action = PaymentAction.SCHEDULED_TRANSFER_DELETE,
            fromAccountId = fromAccountId,
            order = existing.order,
            confirmed = confirmed,
            check = check,
            describe = " on ${existing.date} (id ${existing.orderId})",
            // The bank may report names/purposes longer than we accept when sending; don't re-validate those
            validateOrder = false,
            accountCheck = { account ->
                if (!Iban.normalize(existing.fromIban).equals(account.iban, ignoreCase = true)) {
                    AppStrings[StringKey.ErrorScheduledTransferOtherAccount]
                } else {
                    unsupported(account.bankAccessId, BankCapabilities.JOB_SCHEDULED_DELETE)
                }
            },
        ) { account, _ ->
            finTSClient.deleteScheduledTransfer(account.bankAccessId, interaction, existing)
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
            withContext(Dispatchers.IO) { auditOrder(action, normalized, accountIban, describe, BankingRedactor.maskAccountNumbers("failed: $message")) }
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
            // An offline bank (or an account whose identifier is not an IBAN, e.g. a card) can never move money
            if (bankAccessRepository.findItem(account.bankAccessId)?.offline == true || !Iban.isValid(account.iban)) {
                return deny("OFFLINE", AppStrings[StringKey.ErrorOrderOfflineAccount], account.iban)
            }
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

    /**
     * Audit-log line for an order attempt; [accountIban] is null while the account isn't known yet.
     * Bank text can carry full account numbers, so the audit keeps FinTS return codes only.
     */
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

        is BankingResult.Error -> {
            val codes = BankingRedactor.returnCodes(BankingRedactor.maskAccountNumbers(result.message))
            if (result.code == OUTCOME_UNKNOWN) {
                if (codes.isEmpty()) "outcome unknown" else "outcome unknown (bank codes ${codes.joinToString(", ")})"
            } else {
                if (codes.isEmpty()) "failed (${result.code})" else "failed (${result.code}, bank codes ${codes.joinToString(", ")})"
            }
        }
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

    /**
     * The stored message of an order: a failure's (masked) text, or an instant transfer's raw bank order status
     * (1-9), which the bank keeps uninterpreted and FinDesk stores uninterpreted alongside the accepted status.
     */
    private fun resultMessage(result: BankingResult<*>): String? = when (result) {
        is BankingResult.Error -> result.message?.let { BankingRedactor.maskAccountNumbers(it) }

        is BankingResult.Success -> (result.data as? FinTSClient.TransferResult)?.bankStatusCode
            ?.takeIf { it.isNotBlank() }

        else -> null
    }

    data class ImportResult(
        val imported: Int,
        val duplicates: Int,
        val categorized: Int,
    )
}
