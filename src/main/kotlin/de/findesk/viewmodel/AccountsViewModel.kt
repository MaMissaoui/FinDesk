package de.findesk.viewmodel

import de.findesk.data.entity.Account
import de.findesk.data.entity.BankAccess
import de.findesk.repository.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.math.BigDecimal

data class AccountsState(
    val accounts: List<Account> = emptyList(),
    val bankAccesses: List<BankAccess> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val showAddDialog: Boolean = false
)

class AccountsViewModel(
    private val accountRepository: AccountRepository = AccountRepositoryImpl(),
    private val bankAccessRepository: BankAccessRepository = BankAccessRepositoryImpl(),
    private val auditLogRepository: AuditLogRepository = AuditLogRepositoryImpl()
) {
    private val _state = MutableStateFlow(AccountsState())
    val state: StateFlow<AccountsState> = _state.asStateFlow()

    fun loadAccounts() {
        _state.value = _state.value.copy(isLoading = true, error = null)
        try {
            val accounts = accountRepository.findAll()
            val bankAccesses = bankAccessRepository.findAll()
            _state.value = _state.value.copy(
                accounts = accounts,
                bankAccesses = bankAccesses,
                isLoading = false
            )
        } catch (e: Exception) {
            _state.value = _state.value.copy(
                isLoading = false,
                error = e.message ?: "Unknown error"
            )
        }
    }

    fun addBankAccess(bankName: String, blz: String, bic: String, userId: String) {
        try {
            bankAccessRepository.create(bankName, blz, bic, userId)
            auditLogRepository.log("ADD_BANK_ACCESS", "system", "Added bank: $bankName")
            loadAccounts()
            _state.value = _state.value.copy(showAddDialog = false)
        } catch (e: Exception) {
            _state.value = _state.value.copy(error = e.message)
        }
    }

    fun syncAccount(accountId: Int) {
        try {
            // TODO: Implement actual bank sync via HBCI
            auditLogRepository.log("SYNC_ACCOUNT", "system", "Synced account: $accountId")
            loadAccounts()
        } catch (e: Exception) {
            _state.value = _state.value.copy(error = e.message)
        }
    }

    fun showAddDialog() {
        _state.value = _state.value.copy(showAddDialog = true)
    }

    fun hideAddDialog() {
        _state.value = _state.value.copy(showAddDialog = false)
    }
}