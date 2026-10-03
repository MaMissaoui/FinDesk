package de.findesk.viewmodel

import de.findesk.data.entity.Transaction
import de.findesk.repository.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime

data class DashboardState(
    val totalBalance: BigDecimal = BigDecimal.ZERO,
    val monthlyIncome: BigDecimal = BigDecimal.ZERO,
    val monthlyExpenses: BigDecimal = BigDecimal.ZERO,
    val pendingTasks: Int = 0,
    val recentTransactions: List<Transaction> = emptyList(),
    val expensesByCategory: Map<String, BigDecimal> = emptyMap(),
    val isLoading: Boolean = false,
    val error: String? = null
)

class DashboardViewModel(
    private val accountRepository: AccountRepository = AccountRepositoryImpl(),
    private val transactionRepository: TransactionRepository = TransactionRepositoryImpl(),
    private val auditLogRepository: AuditLogRepository = AuditLogRepositoryImpl()
) {
    private val _state = MutableStateFlow(DashboardState())
    val state: StateFlow<DashboardState> = _state.asStateFlow()

    fun loadDashboard() {
        _state.value = _state.value.copy(isLoading = true, error = null)
        try {
            val now = LocalDate.now()
            val totalBalance = accountRepository.getTotalBalance()
            val monthlyIncome = transactionRepository.getMonthlyTotal(now.year, now.monthValue)
            val monthlyExpenses = transactionRepository.getMonthlyTotal(now.year, now.monthValue)
            val recentTransactions = transactionRepository.findRecent(10)
            val expensesByCategory = transactionRepository.getMonthlyByCategory(now.year, now.monthValue)

            _state.value = DashboardState(
                totalBalance = totalBalance,
                monthlyIncome = monthlyIncome,
                monthlyExpenses = monthlyExpenses,
                pendingTasks = 0,
                recentTransactions = recentTransactions,
                expensesByCategory = expensesByCategory,
                isLoading = false
            )
        } catch (e: Exception) {
            _state.value = _state.value.copy(
                isLoading = false,
                error = e.message ?: "Unknown error"
            )
        }
    }
}