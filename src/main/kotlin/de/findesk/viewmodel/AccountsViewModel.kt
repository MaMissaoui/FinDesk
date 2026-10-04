package de.findesk.viewmodel

import de.findesk.banking.BankDirectory
import de.findesk.banking.BankingResult
import de.findesk.banking.BankingService
import de.findesk.model.AccountItem
import de.findesk.model.BankAccessItem
import de.findesk.repository.*
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

data class AccountsState(
    val accounts: List<AccountItem> = emptyList(),
    val bankAccesses: List<BankAccessItem> = emptyList(),
    val isLoading: Boolean = false,
    /** Bank access currently syncing, if any. */
    val syncingBankId: Int? = null,
    val syncStep: String? = null,
    val error: String? = null,
    val message: String? = null,
) {
    val totalBalance: BigDecimal get() = accounts.fold(BigDecimal.ZERO) { acc, a -> acc + a.balance }
}

class AccountsViewModel(
    private val accountRepository: AccountRepository = AccountRepositoryImpl(),
    private val bankAccessRepository: BankAccessRepository = BankAccessRepositoryImpl(),
    private val auditLogRepository: AuditLogRepository = AuditLogRepositoryImpl(),
    private val bankingService: BankingService = BankingService(),
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
        _state.update { it.copy(accounts = accounts, bankAccesses = bankAccesses, isLoading = false) }
    }

    /** Validates input; returns an error message or null. Exposed for the dialog's inline validation. */
    fun validateBankAccess(bankName: String, blz: String): String? = when {
        bankName.isBlank() -> "Bank name is required"
        !blz.trim().matches(Regex("\\d{8}")) -> "BLZ must be 8 digits"
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
        _state.update { it.copy(message = "Added ${bankName.trim()}", error = null) }
    }

    fun validateAccount(iban: String): String? {
        val normalized = Iban.normalize(iban)
        return when {
            normalized.isEmpty() -> "IBAN is required"
            !Iban.isValid(normalized) -> "IBAN is not valid (checksum failed)"
            _state.value.accounts.any { it.iban == normalized } -> "An account with this IBAN already exists"
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
        _state.update { it.copy(message = "Account added", error = null) }
    }

    fun updateBalance(accountId: Int, balance: BigDecimal): Job = launchSafely(onError) {
        accountRepository.updateBalance(accountId, balance)
        auditLogRepository.log("UPDATE_BALANCE", "user", "Updated balance of account $accountId")
        refresh()
    }

    fun deleteAccount(accountId: Int): Job = launchSafely(onError) {
        accountRepository.deleteWithTransactions(accountId)
        auditLogRepository.log("DELETE_ACCOUNT", "user", "Deleted account $accountId and its transactions")
        refresh()
        _state.update { it.copy(message = "Account deleted") }
    }

    fun deleteBankAccess(bankAccessId: Int): Job = launchSafely(onError) {
        bankAccessRepository.deleteWithAccounts(bankAccessId)
        auditLogRepository.log("DELETE_BANK_ACCESS", "user", "Deleted bank access $bankAccessId")
        refresh()
        _state.update { it.copy(message = "Bank removed") }
    }

    /** Bank name, BIC and FinTS URL from the bundled directory, for pre-filling the add-bank dialog. */
    fun lookupBank(blz: String): BankDirectory.Entry? = BankDirectory.lookup(blz)

    fun updateConnection(bankAccessId: Int, userId: String, serverUrl: String): Job = launchSafely(onError) {
        bankAccessRepository.updateConnection(bankAccessId, serverUrl = serverUrl, userId = userId)
        refresh()
        _state.update { it.copy(message = "Connection settings saved") }
    }

    /** Online sync via FinTS. Prompts for PIN/TAN through [bankingInteraction]. */
    fun syncBankAccess(bankAccessId: Int): Job {
        _state.update { it.copy(syncingBankId = bankAccessId, syncStep = "Connecting…", error = null, message = null) }
        val progressJob = launchSafely({}) {
            bankingService.syncProgress.collect { p -> _state.update { it.copy(syncStep = p?.currentStep ?: it.syncStep) } }
        }
        return launchSafely({ e -> _state.update { it.copy(syncingBankId = null, error = e.userMessage()) } }) {
            try {
                when (val r = bankingService.syncBankConnection(bankAccessId, bankingInteraction)) {
                    is BankingResult.Success -> {
                        refresh()
                        val warn = r.data.warnings.takeIf { it.isNotEmpty() }?.joinToString("\n")
                        _state.update { it.copy(message = r.data.summary(), error = warn) }
                    }

                    is BankingResult.Error -> _state.update { it.copy(error = if (r.code == "CANCELLED") null else r.message) }

                    is BankingResult.TanRequired -> _state.update { it.copy(error = "TAN required") }
                }
            } finally {
                progressJob.cancel()
                _state.update { it.copy(syncingBankId = null, syncStep = null) }
            }
        }
    }

    fun clearMessages() {
        _state.update { it.copy(error = null, message = null) }
    }
}
