package de.findesk.banking

import de.findesk.data.entity.BankAccess
import de.findesk.repository.*
import de.findesk.util.Iban
import kotlinx.coroutines.Dispatchers
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
    private val today: () -> LocalDate = { LocalDate.now() },
) {
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    val syncProgress: StateFlow<FinTSClient.SyncProgress?> get() = finTSClient.syncProgress

    companion object {
        /** First sync goes back this far (many banks require a TAN for more than 90 days). */
        const val INITIAL_SYNC_DAYS = 90L

        /** Later syncs overlap the previous one to catch late bookings; duplicates are skipped. */
        const val SYNC_OVERLAP_DAYS = 10L

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
    ) {
        fun summary(): String = buildString {
            append("Synced $accountsSynced account(s)")
            if (accountsAdded > 0) append(", $accountsAdded new")
            append(": $imported new transaction(s)")
            if (categorized > 0) append(", $categorized auto-categorized")
            if (duplicates > 0) append(", $duplicates already known")
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
     * Sync one bank login: accounts (matched by IBAN, new ones are created), balances and bookings.
     */
    suspend fun syncBankConnection(bankAccessId: Int, interaction: BankingInteraction): BankingResult<SyncReport> {
        _isLoading.value = true
        _error.value = null
        try {
            val bankAccess = withContext(Dispatchers.IO) { bankAccessRepository.findItem(bankAccessId) }
                ?: return BankingResult.Error("NOT_FOUND", "Bank connection not found")
            val known = withContext(Dispatchers.IO) { accountRepository.findAllItems() }.filter { it.bankAccessId == bankAccessId }
            val now = today()
            val defaultStart = now.minusDays(INITIAL_SYNC_DAYS)
            val since: (String) -> LocalDate = { iban ->
                if (known.any { it.iban.equals(iban, true) }) {
                    bankAccess.lastSync?.toLocalDate()?.minusDays(SYNC_OVERLAP_DAYS)?.coerceAtLeast(defaultStart) ?: defaultStart
                } else {
                    defaultStart
                }
            }

            val snapshots = when (val r = finTSClient.fetchAll(bankAccessId, interaction, since, now)) {
                is BankingResult.Success -> r.data
                is BankingResult.Error -> return r.also { _error.value = it.message }
                is BankingResult.TanRequired -> return r
            }

            return withContext(Dispatchers.IO) {
                var added = 0
                var imported = 0
                var duplicates = 0
                var categorized = 0
                val warnings = mutableListOf<String>()
                snapshots.filter { it.account.iban.isNotBlank() }.forEachIndexed { i, snap ->
                    finTSClient.reportProgress("Saving ${snap.account.iban.takeLast(4)}…", 0.5f + 0.5f * i / snapshots.size, i, snapshots.size)
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
                if (warnings.isEmpty()) bankAccessRepository.updateLastSync(bankAccessId)
                val report = SyncReport(snapshots.size, added, imported, duplicates, categorized, warnings)
                auditLogRepository.log("SYNC_BANK", "user", "${bankAccess.bankName}: ${report.summary()}")
                BankingResult.Success(report)
            }
        } catch (e: Exception) {
            _error.value = e.message
            return BankingResult.Error("SYNC_FAILED", e.message ?: "Sync failed")
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
        var imported = 0
        var categorized = 0

        plan.toImport.forEach { candidate ->
            val t = candidate.item
            val hash = candidate.hash
            val category = ruleRepository.matchTransaction(purpose = t.purpose, partnerName = t.partnerName)
            transactionRepository.create(
                accountId = accountId,
                date = t.date,
                amount = t.amount,
                currency = t.currency,
                partnerName = t.partnerName?.take(255),
                purpose = t.purpose,
                categoryId = category?.id?.value,
                hash = hash,
            )
            imported++
            if (category != null) categorized++
        }

        ImportResult(imported = imported, duplicates = plan.exactDuplicates + plan.probableDuplicates, categorized = categorized)
    }

    /** Validates a transfer before anything is sent; returns an error message or null. */
    fun validateTransfer(order: TransferOrder): String? = when {
        order.recipientName.isBlank() -> "Recipient name is required"
        order.recipientName.length > MAX_NAME_LENGTH -> "Recipient name is longer than $MAX_NAME_LENGTH characters"
        !Iban.isValid(order.recipientIban) -> "Recipient IBAN is not valid"
        order.amount.signum() <= 0 -> "Amount must be greater than zero"
        order.amount.scale() > 2 && order.amount.stripTrailingZeros().scale() > 2 -> "Amount can have at most 2 decimals"
        order.purpose.length > MAX_PURPOSE_LENGTH -> "Purpose is longer than $MAX_PURPOSE_LENGTH characters"
        else -> null
    }

    /** Checks a standing order's schedule; returns an error message or null. */
    fun validateSchedule(schedule: StandingOrderSchedule): String? = when {
        schedule.interval == null -> "This interval is not supported"
        !schedule.firstDate.isAfter(today()) -> "The first execution must be after today"
        schedule.lastDate != null && schedule.lastDate.isBefore(schedule.firstDate) -> "The last execution is before the first"
        schedule.timeUnit == "M" && schedule.executionDay !in 1..31 && schedule.executionDay != 99 -> "The execution day must be between 1 and 31"
        schedule.timeUnit == "W" && schedule.executionDay !in 1..7 -> "The execution day must be between 1 (Monday) and 7 (Sunday)"
        else -> null
    }

    fun validateExecutionDate(date: LocalDate): String? = if (!date.isAfter(today())) "The execution date must be after today" else null

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
    ): BankingResult<FinTSClient.TransferResult> = sendOrder("SCHEDULED_TRANSFER", fromAccountId, order, confirmed, validateExecutionDate(date), " on $date") { account, o ->
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
    ) { account, o ->
        finTSClient.createStandingOrder(account.bankAccessId, interaction, account.iban, o, schedule)
    }

    /** Read-only: the account's standing orders as the bank reports them. */
    suspend fun loadStandingOrders(fromAccountId: Int, interaction: BankingInteraction): BankingResult<List<StandingOrder>> {
        val account = withContext(Dispatchers.IO) { accountRepository.findAllItems().firstOrNull { it.id == fromAccountId } }
            ?: return BankingResult.Error("NOT_FOUND", "Account not found")
        return finTSClient.listStandingOrders(account.bankAccessId, interaction, account.iban)
    }

    /** Deletes a standing order, which stops its future payments. Needs [confirmed] like any order. */
    suspend fun deleteStandingOrder(
        fromAccountId: Int,
        existing: StandingOrder,
        interaction: BankingInteraction,
        confirmed: Boolean,
    ): BankingResult<Unit> {
        if (!confirmed) return BankingResult.Error("NOT_CONFIRMED", "Deleting the standing order was not confirmed")
        if (existing.orderId == null || !existing.canDelete) return BankingResult.Error("INVALID", "The bank does not allow deleting this standing order here")
        val account = withContext(Dispatchers.IO) { accountRepository.findAllItems().firstOrNull { it.id == fromAccountId } }
            ?: return BankingResult.Error("NOT_FOUND", "Account not found")
        if (!Iban.normalize(existing.fromIban).equals(account.iban, ignoreCase = true)) return BankingResult.Error("INVALID", "The standing order belongs to another account")
        val result = finTSClient.deleteStandingOrder(account.bankAccessId, interaction, existing)
        withContext(Dispatchers.IO) {
            auditLogRepository.log(
                "STANDING_ORDER_DELETE",
                "user",
                "${existing.order.amount.toPlainString()} EUR from …${account.iban.takeLast(4)} to ${existing.order.recipientName} (id ${existing.orderId}): ${outcome(result)}",
            )
        }
        if (result is BankingResult.Error) _error.value = result.message
        return result
    }

    /**
     * Shared guard for everything that moves money: explicit confirmation of the exact order, a valid order (and
     * [check], e.g. the schedule), an EUR source account. Every attempt is audit-logged with its outcome.
     */
    private suspend fun <T> sendOrder(
        action: String,
        fromAccountId: Int,
        order: TransferOrder,
        confirmed: Boolean,
        check: String?,
        describe: String,
        send: suspend (de.findesk.model.AccountItem, TransferOrder) -> BankingResult<T>,
    ): BankingResult<T> {
        if (!confirmed) return BankingResult.Error("NOT_CONFIRMED", "The order was not confirmed")
        validateTransfer(order)?.let { return BankingResult.Error("INVALID", it) }
        check?.let { return BankingResult.Error("INVALID", it) }

        _isLoading.value = true
        _error.value = null
        try {
            val account = withContext(Dispatchers.IO) { accountRepository.findAllItems().firstOrNull { it.id == fromAccountId } }
                ?: return BankingResult.Error("NOT_FOUND", "Account not found")
            // SEPA credit transfers are EUR only; the order amount is in EUR, so another account currency would be wrong
            if (account.currency != "EUR") return BankingResult.Error("CURRENCY", "SEPA transfers can only be sent from EUR accounts (this account is in ${account.currency})")
            val normalized = order.copy(recipientIban = Iban.normalize(order.recipientIban), recipientName = order.recipientName.trim())
            val result = send(account, normalized)
            withContext(Dispatchers.IO) {
                auditLogRepository.log(
                    action,
                    "user",
                    "${normalized.amount.toPlainString()} EUR from …${account.iban.takeLast(4)} to ${normalized.recipientName} …${normalized.recipientIban.takeLast(4)}$describe: ${outcome(result)}",
                )
            }
            if (result is BankingResult.Error) _error.value = result.message
            return result
        } finally {
            _isLoading.value = false
        }
    }

    private fun outcome(result: BankingResult<*>): String = when (result) {
        is BankingResult.Success -> "sent"
        is BankingResult.Error -> "failed: ${result.message}"
        is BankingResult.TanRequired -> "TAN required"
    }

    data class ImportResult(
        val imported: Int,
        val duplicates: Int,
        val categorized: Int,
    )
}
