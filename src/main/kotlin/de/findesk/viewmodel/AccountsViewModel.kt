package de.findesk.viewmodel

import de.findesk.banking.BankDirectory
import de.findesk.banking.BankingResult
import de.findesk.banking.BankingService
import de.findesk.i18n.AppStrings
import de.findesk.i18n.StringKey
import de.findesk.model.AccountItem
import de.findesk.model.BankAccessItem
import de.findesk.repository.*
import de.findesk.util.Currencies
import de.findesk.util.Formats
import de.findesk.util.Iban
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.math.BigDecimal
import java.time.LocalDate

data class AccountDetail(
    val account: AccountItem,
    val days: Int,
    val history: List<Pair<java.time.LocalDate, BigDecimal>>,
    val recent: List<de.findesk.model.TransactionItem>,
    val incomeInPeriod: BigDecimal,
    val expensesInPeriod: BigDecimal,
)

data class AccountsState(
    val accounts: List<AccountItem> = emptyList(),
    val bankAccesses: List<BankAccessItem> = emptyList(),
    val isLoading: Boolean = false,
    /** Bank access currently syncing, if any. */
    val syncingBankId: Int? = null,
    val detail: AccountDetail? = null,
    val syncStep: String? = null,
    val error: String? = null,
    val message: String? = null,
    val primaryCurrency: String = Currencies.DEFAULT,
    /** Bank access ids whose PIN is remembered for syncs (the settings flag only; the keychain is never read here). */
    val savedPins: Set<Int> = emptySet(),
    /** Whether the operating system offers a credential store, so the UI may offer to remember a PIN. */
    val canRememberPin: Boolean = false,
) {
    /** Balances per currency, primary first; different currencies are never added up. */
    val totals: Map<String, BigDecimal> get() = Currencies.totals(accounts, primaryCurrency, { it.currency }, { it.balance })
}

class AccountsViewModel(
    private val accountRepository: AccountRepository = AccountRepositoryImpl(),
    private val bankAccessRepository: BankAccessRepository = BankAccessRepositoryImpl(),
    private val auditLogRepository: AuditLogRepository = AuditLogRepositoryImpl(),
    private val bankingService: BankingService = BankingService(),
    private val transactionRepository: TransactionRepository = TransactionRepositoryImpl(),
    private val settingsRepository: SettingsRepository = SettingsRepositoryImpl(),
    private val today: () -> java.time.LocalDate = { java.time.LocalDate.now() },
    val bankingInteraction: UiBankingInteraction = UiBankingInteraction(),
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) : BaseViewModel(scope) {
    private val _state = MutableStateFlow(AccountsState())
    val state: StateFlow<AccountsState> = _state.asStateFlow()

    private val onError: (Exception) -> Unit = { e -> _state.update { it.copy(isLoading = false, error = e.userMessage()) } }

    fun loadAccounts(): Job {
        _state.update { it.copy(isLoading = true, error = null) }
        return launchSafely(onError) { refresh() }
    }

    private fun refresh() {
        val accounts = accountRepository.findAllItems()
        val bankAccesses = bankAccessRepository.findAllItems()
        _state.update {
            it.copy(
                accounts = accounts,
                bankAccesses = bankAccesses,
                isLoading = false,
                primaryCurrency = Currencies.primary(settingsRepository),
                canRememberPin = bankingService.canRememberPin,
                savedPins = bankAccesses.filter { bank -> bankingService.hasSavedPin(bank.id) }.map { bank -> bank.id }.toSet(),
            )
        }
    }

    /** Validates input; returns an error message or null. Exposed for the dialog's inline validation. */
    fun validateBankAccess(bankName: String, blz: String): String? = when {
        bankName.isBlank() -> AppStrings[StringKey.BankNameRequired]
        !blz.trim().matches(Regex("\\d{8}")) -> AppStrings[StringKey.BlzMustBe8Digits]
        else -> null
    }

    fun addBankAccess(bankName: String, blz: String, bic: String, userId: String): Job = launchSafely(onError) {
        validateBankAccess(bankName, blz)?.let { msg ->
            _state.update { it.copy(error = msg) }
            return@launchSafely
        }
        bankAccessRepository.create(bankName.trim(), blz.trim(), bic.trim().uppercase(), userId.trim())
        auditLogRepository.log("ADD_BANK_ACCESS", "user", "Added bank: ${bankName.trim()}")
        refresh()
        _state.update { it.copy(message = AppStrings.format(StringKey.MessageAdded, bankName.trim()), error = null) }
    }

    fun validateAccount(iban: String): String? {
        val normalized = Iban.normalize(iban)
        return when {
            normalized.isEmpty() -> AppStrings[StringKey.IbanRequired]
            !Iban.isValid(normalized) -> AppStrings[StringKey.IbanChecksumFailed]
            _state.value.accounts.any { it.iban == normalized } -> AppStrings[StringKey.AccountIbanExists]
            else -> null
        }
    }

    fun addAccount(
        bankAccessId: Int,
        iban: String,
        accountType: String,
        balance: BigDecimal,
        currency: String = "EUR",
    ): Job = launchSafely(onError) {
        validateAccount(iban)?.let { msg ->
            _state.update { it.copy(error = msg) }
            return@launchSafely
        }
        val normalized = Iban.normalize(iban)
        accountRepository.create(
            bankAccessId = bankAccessId,
            iban = normalized,
            balance = balance,
            currency = currency,
            accountType = accountType,
        )
        auditLogRepository.log("ADD_ACCOUNT", "user", "Added account ${normalized.takeLast(4)}")
        refresh()
        _state.update { it.copy(message = AppStrings[StringKey.MessageAccountAdded], error = null) }
    }

    fun updateBalance(accountId: Int, balance: BigDecimal): Job = launchSafely(onError) {
        accountRepository.updateBalance(accountId, balance)
        auditLogRepository.log("UPDATE_BALANCE", "user", "Updated balance of account $accountId")
        refresh()
    }

    /** Sets the account's display name (blank clears it). Only a change to the stored name is audit-logged. */
    fun renameAccount(accountId: Int, name: String): Job = launchSafely(onError) {
        val previous = accountRepository.findAllItems().firstOrNull { it.id == accountId }?.name
        val updated = accountRepository.rename(accountId, name)
        refresh()
        // An open detail view holds a snapshot of the account, so its title would keep the old name
        if (updated != null) _state.update { st -> st.copy(detail = st.detail?.takeIf { it.account.id == accountId }?.copy(account = updated) ?: st.detail) }
        if (updated != null && updated.name != previous) {
            auditLogRepository.log(
                "RENAME_ACCOUNT",
                "user",
                "Renamed account ${updated.iban.takeLast(4)} from ${previous ?: "default"} to ${updated.name ?: "default"}",
            )
        }
    }

    fun deleteAccount(accountId: Int): Job = launchSafely(onError) {
        accountRepository.deleteWithTransactions(accountId)
        auditLogRepository.log("DELETE_ACCOUNT", "user", "Deleted account $accountId and its transactions")
        refresh()
        _state.update { it.copy(message = AppStrings[StringKey.MessageAccountDeleted]) }
    }

    fun deleteBankAccess(bankAccessId: Int): Job = launchSafely(onError) {
        bankingService.removeBankAccess(bankAccessId)
        refresh()
        _state.update { it.copy(message = AppStrings[StringKey.MessageBankRemoved]) }
    }

    /** Removes the PIN remembered for [bankAccessId] from the system's credential store. */
    fun forgetPin(bankAccessId: Int): Job = launchSafely(onError) {
        bankingService.forgetPin(bankAccessId)
        refresh()
    }

    /** Opens the detail view: balance history over [days] days plus recent bookings. */
    fun openDetail(accountId: Int, days: Int = 90): Job = launchSafely(onError) {
        val account = accountRepository.findAllItems().firstOrNull { it.id == accountId } ?: return@launchSafely
        val to = today()
        val from = to.minusDays(days.toLong() - 1)
        // Everything from the window start on, including bookings dated after today, to rewind the balance
        val txns = transactionRepository.findItems(de.findesk.model.TransactionFilter(startDate = from, accountId = accountId))
        val inWindow = txns.filter { !it.date.isAfter(to) }
        _state.update {
            it.copy(
                detail = AccountDetail(
                    account = account,
                    days = days,
                    history = de.findesk.util.BalanceHistory.compute(account.balance, txns, from, to),
                    recent = inWindow.take(15),
                    incomeInPeriod = inWindow.filter { t -> t.amount.signum() > 0 }.sumOf { t -> t.amount },
                    expensesInPeriod = inWindow.filter { t -> t.isExpense }.sumOf { t -> t.amount.abs() },
                ),
            )
        }
    }

    fun closeDetail() {
        _state.update { it.copy(detail = null) }
    }

    /** Bank name, BIC and FinTS URL from the bundled directory, for pre-filling the add-bank dialog. */
    fun lookupBank(blz: String): BankDirectory.Entry? = BankDirectory.lookup(blz)

    /** Bank suggestions for the add-bank dialog, matched on name, BLZ, BIC or a German IBAN. */
    fun searchBanks(query: String): List<BankDirectory.Entry> = BankDirectory.search(query)

    /** Saves the connection settings. [name] is the label shown for the connection; blank is rejected. */
    fun updateConnection(bankAccessId: Int, name: String, blz: String, userId: String, serverUrl: String): Job = launchSafely(onError) {
        validateBankAccess(name, blz)?.let { msg ->
            _state.update { it.copy(error = msg) }
            return@launchSafely
        }
        if (_state.value.syncingBankId == bankAccessId) {
            _state.update { it.copy(error = AppStrings[StringKey.ErrorConnectionBusy]) }
            return@launchSafely
        }
        val previous = bankAccessRepository.findItem(bankAccessId)
        val oldName = previous?.bankName
        val updated = bankingService.updateConnection(bankAccessId, name, blz, userId, serverUrl)
        auditLogRepository.log("UPDATE_BANK_CONNECTION", "user", "Updated connection settings of bank access $bankAccessId")
        if (updated != null && updated.bankName != oldName) {
            auditLogRepository.log("RENAME_BANK_ACCESS", "user", "Renamed bank access $bankAccessId from $oldName to ${updated.bankName}")
        }
        // The service resets the login (and forgets the PIN) when the BLZ, user id or URL changed.
        refresh()
        _state.update { it.copy(message = AppStrings[StringKey.MessageConnectionSaved]) }
    }

    /** Validates the start date of a historical sync ("dd.MM.yyyy"); returns an error message or null. */
    fun validateSyncFrom(input: String): String? {
        val date = Formats.parseDate(input) ?: return AppStrings[StringKey.ErrorSyncFromDateInvalid]
        return if (date.isAfter(today())) AppStrings[StringKey.ErrorSyncFromDateFuture] else null
    }

    /** Stores [date] as the start of the next sync's history window, then starts that sync for [bankAccessId]. */
    fun fetchHistoryFrom(bankAccessId: Int, date: LocalDate): Job = launchSafely(onError) {
        bankAccessRepository.setSyncFrom(bankAccessId, date)
        auditLogRepository.log("SET_SYNC_FROM", "user", "Bank access $bankAccessId: history from $date")
        refresh()
        syncBankAccess(bankAccessId).join()
    }

    /** Drops a stored history start so the next sync uses the normal delta window again. */
    fun clearSyncFrom(bankAccessId: Int): Job = launchSafely(onError) {
        bankAccessRepository.setSyncFrom(bankAccessId, null)
        auditLogRepository.log("SET_SYNC_FROM", "user", "Bank access $bankAccessId: history start reset")
        refresh()
    }

    /** Online sync via FinTS. Prompts for PIN/TAN through [bankingInteraction]. */
    fun syncBankAccess(bankAccessId: Int): Job {
        _state.update { it.copy(syncingBankId = bankAccessId, syncStep = AppStrings[StringKey.SyncConnecting], error = null, message = null) }
        val progressJob = launchSafely({}) {
            bankingService.syncProgress.collect { p -> _state.update { it.copy(syncStep = p?.currentStep ?: it.syncStep) } }
        }
        return launchSafely({ e -> _state.update { it.copy(syncingBankId = null, error = e.userMessage()) } }) {
            try {
                when (val r = bankingService.syncBankConnection(bankAccessId, bankingInteraction)) {
                    is BankingResult.Success -> {
                        refresh()
                        val warn = r.data.warnings.takeIf { it.isNotEmpty() }?.joinToString("\n")
                        // A PIN the user asked to remember that the keychain refused: a short, non-secret note
                        val pinNote = AppStrings[StringKey.PinNotSaved].takeIf { bankingService.pinNotSaved }
                        _state.update { it.copy(message = listOfNotNull(r.data.summary(), pinNote).joinToString("\n"), error = warn) }
                    }

                    is BankingResult.Error -> _state.update { it.copy(error = if (r.code == "CANCELLED") null else r.message) }
                }
            } finally {
                progressJob.cancel()
                _state.update { it.copy(syncingBankId = null, syncStep = null) }
            }
        }
    }

    override fun close() {
        bankingInteraction.cancelPending()
        super.close()
    }

    fun clearMessages() {
        _state.update { it.copy(error = null, message = null) }
    }
}
