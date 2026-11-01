package de.findesk.banking

import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Configuration for FinTS/HBCI banking connection
 */
data class FinTSConfig(
    val bankName: String,
    val blz: String,
    val bic: String,
    val userId: String,
    val customerId: String = userId,
    val hbciVersion: String = "300",
    val serverUrl: String? = null,
    val tanMethod: String? = null,
)

/**
 * Represents a bank account from FinTS
 */
data class FinTSAccount(
    val iban: String,
    val bic: String,
    val accountNumber: String,
    val subAccountNumber: String? = null,
    val bankName: String,
    val blz: String,
    val accountType: String,
    val currency: String = "EUR",
    val productName: String? = null,
    /** Whether the bank's account data stated [currency], rather than a fallback having supplied it. */
    val currencyStated: Boolean = true,
)

/**
 * Represents a balance from FinTS
 */
data class FinTSBalance(
    val account: FinTSAccount,
    val balance: BigDecimal,
    val availableBalance: BigDecimal? = null,
    val currency: String = "EUR",
    val date: LocalDateTime = LocalDateTime.now(),
)

/**
 * Represents a transaction from FinTS (MT940/CAMT)
 */
data class FinTSTransaction(
    val account: FinTSAccount,
    val date: LocalDate,
    val valueDate: LocalDate,
    val amount: BigDecimal,
    val currency: String = "EUR",
    val partnerName: String?,
    val partnerIban: String?,
    val partnerBic: String?,
    val purpose: String?,
    val transactionCode: String?,
    val reference: String?,
    val primaNota: String? = null,
    /** Running balance after this booking, if the bank reports it. */
    val balanceAfter: BigDecimal? = null,
    /** Bank-side booking id (CAMT), if any. */
    val bankId: String? = null,
)

/**
 * Result of a banking operation
 */
sealed class BankingResult<out T> {
    data class Success<T>(val data: T) : BankingResult<T>()
    data class Error(val code: String, val message: String) : BankingResult<Nothing>()
}

/**
 * Status of a banking connection
 */
enum class ConnectionStatus {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    SYNCING,
    ERROR,
}
