package de.findesk.banking

import de.findesk.data.entity.Account
import de.findesk.data.entity.BankAccess
import de.findesk.data.entity.Transaction
import de.findesk.repository.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.math.BigDecimal
import java.time.LocalDate

/**
 * High-level banking service coordinating FinTS operations with local database
 */
class BankingService(
    private val finTSClient: FinTSClient = FinTSClient(),
    private val bankAccessRepository: BankAccessRepository = BankAccessRepositoryImpl(),
    private val accountRepository: AccountRepository = AccountRepositoryImpl(),
    private val transactionRepository: TransactionRepository = TransactionRepositoryImpl(),
    private val categoryRepository: CategoryRepository = CategoryRepositoryImpl(),
    private val ruleRepository: RuleRepository = RuleRepositoryImpl(),
    private val auditLogRepository: AuditLogRepository = AuditLogRepositoryImpl()
) {
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    /**
     * Add a new bank connection
     */
    suspend fun addBankConnection(config: FinTSConfig): BankAccess? {
        _isLoading.value = true
        _error.value = null

        return try {
            val result = finTSClient.connectAndSync(config)
            when (result) {
                is BankingResult.Success -> {
                    val bankAccess = bankAccessRepository.findByBlz(config.blz).firstOrNull()
                    auditLogRepository.log("ADD_BANK", "system", "Added bank: ${config.bankName}")
                    bankAccess
                }
                is BankingResult.Error -> {
                    _error.value = result.message
                    null
                }
                is BankingResult.TanRequired -> {
                    _error.value = "TAN required - not yet implemented"
                    null
                }
            }
        } catch (e: Exception) {
            _error.value = e.message
            null
        } finally {
            _isLoading.value = false
        }
    }

    /**
     * Sync a bank connection
     */
    suspend fun syncBankConnection(bankAccessId: Int): Boolean {
        _isLoading.value = true
        _error.value = null

        return try {
            val result = finTSClient.syncAll(bankAccessId)
            when (result) {
                is BankingResult.Success -> {
                    auditLogRepository.log("SYNC_BANK", "system", "Synced bank connection: $bankAccessId")
                    true
                }
                is BankingResult.Error -> {
                    _error.value = result.message
                    false
                }
                is BankingResult.TanRequired -> {
                    _error.value = "TAN required - not yet implemented"
                    false
                }
            }
        } catch (e: Exception) {
            _error.value = e.message
            false
        } finally {
            _isLoading.value = false
        }
    }

    /**
     * Import transactions from FinTS into local database
     */
    suspend fun importTransactions(
        accountId: Int,
        finTSTransactions: List<FinTSTransaction>
    ): ImportResult {
        var imported = 0
        var duplicates = 0
        var categorized = 0

        finTSTransactions.forEach { finTSTransaction ->
            val hash = finTSClient.createTransactionHash(
                date = finTSTransaction.date,
                amount = finTSTransaction.amount,
                partnerName = finTSTransaction.partnerName,
                purpose = finTSTransaction.purpose
            )

            // Check for duplicates
            if (transactionRepository.findByHash(hash) != null) {
                duplicates++
                return@forEach
            }

            // Try to auto-categorize
            val category = ruleRepository.matchTransaction(
                purpose = finTSTransaction.purpose,
                partnerName = finTSTransaction.partnerName
            )

            // Create transaction
            transactionRepository.create(
                accountId = accountId,
                date = finTSTransaction.date,
                amount = finTSTransaction.amount,
                currency = finTSTransaction.currency,
                partnerName = finTSTransaction.partnerName,
                purpose = finTSTransaction.purpose,
                categoryId = category?.id?.value,
                hash = hash
            )

            imported++
            if (category != null) categorized++
        }

        return ImportResult(
            imported = imported,
            duplicates = duplicates,
            categorized = categorized
        )
    }

    /**
     * Initiate a SEPA transfer
     */
    suspend fun initiateTransfer(
        fromAccountId: Int,
        toIban: String,
        toBic: String,
        toName: String,
        amount: BigDecimal,
        purpose: String
    ): Boolean {
        _isLoading.value = true
        _error.value = null

        return try {
            val result = finTSClient.initiateTransfer(
                fromAccountId = fromAccountId,
                toIban = toIban,
                toBic = toBic,
                toName = toName,
                amount = amount,
                purpose = purpose
            )

            when (result) {
                is BankingResult.Success -> {
                    auditLogRepository.log("TRANSFER", "system", "Transfer to $toIban: $amount")
                    true
                }
                is BankingResult.Error -> {
                    _error.value = result.message
                    false
                }
                is BankingResult.TanRequired -> {
                    // TAN required - UI should handle this
                    _error.value = "TAN required for this transfer"
                    false
                }
            }
        } catch (e: Exception) {
            _error.value = e.message
            false
        } finally {
            _isLoading.value = false
        }
    }

    data class ImportResult(
        val imported: Int,
        val duplicates: Int,
        val categorized: Int
    )
}