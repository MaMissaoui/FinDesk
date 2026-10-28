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
    /** Comma-separated BPD/UPD job names the login supports; null until the first sync. */
    val capabilities: String? = null,
    /** Chosen start date for the next sync (fetch older history); null uses the normal delta sync. */
    val syncFrom: LocalDate? = null,
    /** Whether "Sync all" syncs this connection. */
    val includeInSyncAll: Boolean = true,
    /** Whether this connection never goes online (statements only); fixed at creation. */
    val offline: Boolean = false,
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
    /** The user's own label for the account; null shows the account type instead. */
    val name: String? = null,
    /** Custom group the account belongs to; null keeps it in its account-type group. */
    val groupId: Int? = null,
    /** Bookings a bank sync brought in that the user has not read yet (see TransactionsViewModel.boldIds). */
    val newCount: Int = 0,
)

data class AccountGroupItem(
    val id: Int,
    val name: String,
    val sortOrder: Int,
)

data class CategoryItem(
    val id: Int,
    val name: String,
    val color: String,
    val budgetLimit: BigDecimal?,
    val isTaxRelevant: Boolean,
    /** Marks transfers between the user's own accounts: they count neither as income nor as spending. */
    val isTransfer: Boolean = false,
)

/** One slice of the spending split: a category's share of the total (or the user's total spending). */
data class CategoryShare(
    /** The category's id, or null for uncategorized. */
    val categoryId: Int?,
    val name: String,
    val amount: BigDecimal,
    val color: String,
)

data class TransactionItem(
    val id: Int,
    val accountId: Int,
    val accountIban: String,
    val date: LocalDate,
    val amount: BigDecimal,
    /** The bank's running balance after this booking, when the statement reported one. */
    val balanceAfter: BigDecimal? = null,
    val currency: String,
    val partnerName: String?,
    val purpose: String?,
    val categoryId: Int?,
    val categoryName: String?,
    val categoryColor: String?,
    val isTaxRelevant: Boolean,
    val documentCount: Int,
    /** A bank sync brought this booking in and its account has not been opened since. */
    val isNew: Boolean = false,
    /** True when the booking's category marks a transfer between the user's own accounts. */
    val isTransfer: Boolean = false,
    /** Which layer decided [isTaxRelevant] (manual override, rule or category); NONE = no layer said yes or no. */
    val taxSource: TaxSource = TaxSource.NONE,
    /** The deciding rule's pattern when [taxSource] is [TaxSource.RULE], else null. */
    val taxRulePattern: String? = null,
    /** The stored manual override, so the UI can show and reset it; null = automatic. */
    val taxOverride: Boolean? = null,
) {
    val description: String get() = partnerName?.takeIf { it.isNotBlank() } ?: purpose?.takeIf { it.isNotBlank() } ?: "—"
    val isExpense: Boolean get() = amount.signum() < 0

    /** Counts as income: money in on a normal category; own-account transfers never do. */
    val countsAsIncome: Boolean get() = amount.signum() > 0 && !isTransfer

    /** Counts as spending: money out on a normal category; own-account transfers never do. */
    val countsAsSpending: Boolean get() = amount.signum() < 0 && !isTransfer
}

/**
 * One pending line (vorgemerkter Umsatz): the bank named it but has not booked it yet, so it can change or
 * vanish. Never a booking — shown greyed out above the list, never counted into any total.
 */
data class PendingBookingItem(
    val accountId: Int,
    val date: LocalDate,
    val valueDate: LocalDate? = null,
    val amount: BigDecimal,
    val currency: String = "EUR",
    val partnerName: String?,
    val purpose: String?,
    /** When the sync that fetched the line ran; the pending block's header shows it. */
    val fetchedAt: LocalDateTime = LocalDateTime.now(),
)

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
    val matchField: RuleField = RuleField.ANY,
    val direction: RuleDirection = RuleDirection.ANY,
    /** Tax relevance the rule decides for matching bookings of its own category; null = "as the category". */
    val taxRelevant: Boolean? = null,
)

/** Which layer decided a booking's effective tax relevance. */
enum class TaxSource {
    /** The user's stored override (`transactions.tax_override`). */
    MANUAL,

    /** A rule's tax flag, resolved at read time (first matching rule of the booking's own category). */
    RULE,

    /** The category's `isTaxRelevant` flag. */
    CATEGORY,

    /** No layer said yes or no: no category and no override. */
    NONE,
}

/** Which text an auto-categorization rule matches. */
enum class RuleField(val key: String) {
    ANY("any"),
    PARTNER("partner"),
    PURPOSE("purpose"),
    ;

    companion object {
        fun fromKey(key: String?): RuleField = entries.firstOrNull { it.key.equals(key, ignoreCase = true) } ?: ANY
    }
}

/** Which money flow an auto-categorization rule applies to. */
enum class RuleDirection(val key: String) {
    ANY("any"),
    EXPENSE("expense"),
    INCOME("income"),
    ;

    companion object {
        fun fromKey(key: String?): RuleDirection = entries.firstOrNull { it.key.equals(key, ignoreCase = true) } ?: ANY
    }
}

enum class Direction { ALL, INCOME, EXPENSES }

/** Actions recorded in [PaymentOrderItem.action]. */
object PaymentAction {
    const val TRANSFER = "TRANSFER"
    const val INSTANT_TRANSFER = "INSTANT_TRANSFER"
    const val SCHEDULED_TRANSFER = "SCHEDULED_TRANSFER"
    const val SCHEDULED_TRANSFER_DELETE = "SCHEDULED_TRANSFER_DELETE"
    const val STANDING_ORDER_CREATE = "STANDING_ORDER_CREATE"
    const val STANDING_ORDER_DELETE = "STANDING_ORDER_DELETE"
    const val STANDING_ORDER_EDIT = "STANDING_ORDER_EDIT"
}

/** Lifecycle of a [PaymentOrderItem]; only the bank's answer changes it after it was recorded. */
object PaymentOrderStatus {
    const val PENDING = "PENDING"
    const val ACCEPTED = "ACCEPTED"
    const val REJECTED = "REJECTED"
    const val TAN_REQUIRED = "TAN_REQUIRED"
    const val CANCELLED = "CANCELLED"

    /** The dialog failed after the order may have reached the bank (or the app stopped mid-send): check online banking. */
    const val UNKNOWN = "UNKNOWN"
}

/** A locally recorded order sent to a bank, with the bank's answer once it is known. */
data class PaymentOrderItem(
    val id: Int,
    val createdAt: LocalDateTime,
    val action: String,
    val accountIban: String?,
    val recipientName: String?,
    val recipientIban: String?,
    val amount: BigDecimal?,
    val currency: String,
    val purpose: String?,
    val requestedDate: LocalDate?,
    val schedule: String?,
    val bankOrderId: String?,
    val status: String,
    val message: String?,
)

data class TransactionFilter(
    val startDate: LocalDate? = null,
    val endDate: LocalDate? = null,
    val accountId: Int? = null,
    /**
     * Restricts to these accounts; null means no restriction and an empty set matches nothing (an empty group must
     * show no bookings, not all of them). Kept next to [accountId] for callers that filter by a single account.
     */
    val accountIds: Set<Int>? = null,
    val categoryId: Int? = null,
    val uncategorizedOnly: Boolean = false,
    val taxRelevantOnly: Boolean = false,
    val direction: Direction = Direction.ALL,
    val text: String? = null,
    /** Absolute amount bounds (match income and expenses alike). */
    val minAmount: BigDecimal? = null,
    val maxAmount: BigDecimal? = null,
    /** Only bookings in this currency; totals must never add up different currencies. */
    val currency: String? = null,
)
