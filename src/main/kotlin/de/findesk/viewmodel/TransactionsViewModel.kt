package de.findesk.viewmodel

import de.findesk.data.entity.Transaction
import de.findesk.data.entity.Category
import de.findesk.repository.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.math.BigDecimal
import java.time.LocalDate

data class TransactionsState(
    val transactions: List<Transaction> = emptyList(),
    val categories: List<Category> = emptyList(),
    val selectedCategory: String = "All",
    val searchQuery: String = "",
    val startDate: LocalDate = LocalDate.now().withDayOfMonth(1),
    val endDate: LocalDate = LocalDate.now(),
    val totalIncome: BigDecimal = BigDecimal.ZERO,
    val totalExpenses: BigDecimal = BigDecimal.ZERO,
    val isLoading: Boolean = false,
    val error: String? = null
)

class TransactionsViewModel(
    private val transactionRepository: TransactionRepository = TransactionRepositoryImpl(),
    private val categoryRepository: CategoryRepository = CategoryRepositoryImpl(),
    private val ruleRepository: RuleRepository = RuleRepositoryImpl(),
    private val auditLogRepository: AuditLogRepository = AuditLogRepositoryImpl()
) {
    private val _state = MutableStateFlow(TransactionsState())
    val state: StateFlow<TransactionsState> = _state.asStateFlow()

    fun loadTransactions() {
        _state.value = _state.value.copy(isLoading = true, error = null)
        try {
            val transactions = transactionRepository.findByDateRange(
                _state.value.startDate,
                _state.value.endDate
            )
            val categories = categoryRepository.findAll()

            val totalIncome = transactions.filter { it.amount > BigDecimal.ZERO }.sumOf { it.amount }
            val totalExpenses = transactions.filter { it.amount < BigDecimal.ZERO }.sumOf { it.amount.abs() }

            _state.value = _state.value.copy(
                transactions = transactions,
                categories = categories,
                totalIncome = totalIncome,
                totalExpenses = totalExpenses,
                isLoading = false
            )
        } catch (e: Exception) {
            _state.value = _state.value.copy(
                isLoading = false,
                error = e.message ?: "Unknown error"
            )
        }
    }

    fun updateSearchQuery(query: String) {
        _state.value = _state.value.copy(searchQuery = query)
        if (query.isNotEmpty()) {
            searchTransactions(query)
        } else {
            loadTransactions()
        }
    }

    fun updateCategoryFilter(category: String) {
        _state.value = _state.value.copy(selectedCategory = category)
        loadTransactions()
    }

    fun updateDateRange(startDate: LocalDate, endDate: LocalDate) {
        _state.value = _state.value.copy(startDate = startDate, endDate = endDate)
        loadTransactions()
    }

    private fun searchTransactions(query: String) {
        try {
            val results = transactionRepository.searchByText(query)
            _state.value = _state.value.copy(transactions = results)
        } catch (e: Exception) {
            _state.value = _state.value.copy(error = e.message)
        }
    }

    fun updateTransactionCategory(transactionId: Int, categoryId: Int?) {
        try {
            transactionRepository.updateCategory(transactionId, categoryId)
            auditLogRepository.log("UPDATE_CATEGORY", "system", "Updated transaction $transactionId category")
            loadTransactions()
        } catch (e: Exception) {
            _state.value = _state.value.copy(error = e.message)
        }
    }

    fun autoCategorizeTransactions() {
        try {
            val uncategorized = transactionRepository.findAll().filter { it.category == null }
            uncategorized.forEach { transaction ->
                val matchedCategory = ruleRepository.matchTransaction(
                    transaction.purpose,
                    transaction.partnerName
                )
                if (matchedCategory != null) {
                    transactionRepository.updateCategory(transaction.id.value, matchedCategory.id.value)
                }
            }
            auditLogRepository.log("AUTO_CATEGORIZE", "system", "Auto-categorized ${uncategorized.size} transactions")
            loadTransactions()
        } catch (e: Exception) {
            _state.value = _state.value.copy(error = e.message)
        }
    }
}