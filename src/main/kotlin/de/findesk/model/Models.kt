package de.findesk.model

import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime

/*
 * Immutable snapshots for the UI and services.
 *
 * Exposed DAO entities can't be read outside the transaction that loaded them
 * (references like Transaction.category throw "Can't init value outside the transaction"),
 * so everything that leaves the repository layer for display or reporting is mapped
 * to one of these inside the transaction.
 */

data class BankAccessItem(
    val id: Int,
    val bankName: String,
    val blz: String,
    val bic: String,
    val userId: String,
    val hbciVersion: String,
    val tanMethod: String?,
    val serverUrl: String?,
    val lastSync: LocalDateTime?,
)

data class AccountItem(
    val id: Int,
    val bankAccessId: Int,
    val bankName: String,
    val iban: String,
    val accountNumber: String?,
    val balance: BigDecimal,
    val currency: String,
    val accountType: String,
    val lastUpdate: LocalDateTime?,
)

data class CategoryItem(
    val id: Int,
    val name: String,
    val color: String,
    val budgetLimit: BigDecimal?,
    val isTaxRelevant: Boolean,
)

data class TransactionItem(
    val id: Int,
    val accountId: Int,
    val accountIban: String,
    val date: LocalDate,
    val amount: BigDecimal,
    val currency: String,
    val partnerName: String?,
    val purpose: String?,
    val categoryId: Int?,
    val categoryName: String?,
    val categoryColor: String?,
    val isTaxRelevant: Boolean,
    val documentCount: Int,
) {
    val description: String get() = partnerName?.takeIf { it.isNotBlank() } ?: purpose?.takeIf { it.isNotBlank() } ?: "—"
    val isExpense: Boolean get() = amount.signum() < 0
}

data class DocumentItem(
    val id: Int,
    val fileName: String,
    val filePath: String,
    val mimeType: String,
    val uploadDate: LocalDateTime,
    val linkedTransactionId: Int?,
    val linkedTransactionLabel: String?,
    val hasOcr: Boolean,
    val ocrConfidence: BigDecimal?,
) {
    val isPdf: Boolean get() = mimeType == "application/pdf"
    val isImage: Boolean get() = mimeType.startsWith("image/")
}

data class RuleItem(
    val id: Int,
    val pattern: String,
    val categoryId: Int,
    val categoryName: String,
    val priority: Int,
    val autoApply: Boolean,
)

enum class Direction { ALL, INCOME, EXPENSES }

data class TransactionFilter(
    val startDate: LocalDate? = null,
    val endDate: LocalDate? = null,
    val accountId: Int? = null,
    val categoryId: Int? = null,
    val uncategorizedOnly: Boolean = false,
    val taxRelevantOnly: Boolean = false,
    val direction: Direction = Direction.ALL,
    val text: String? = null,
    /** Absolute amount bounds (match income and expenses alike). */
    val minAmount: BigDecimal? = null,
    val maxAmount: BigDecimal? = null,
)
