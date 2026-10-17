package de.findesk.model

import de.findesk.data.entity.Account
import de.findesk.data.entity.BankAccess
import de.findesk.data.entity.Category
import de.findesk.data.entity.Document
import de.findesk.data.entity.PaymentOrder
import de.findesk.data.entity.Rule
import de.findesk.data.entity.Transaction

// All mappers read references, so they must be called inside a transaction { } block.

fun BankAccess.toItem() = BankAccessItem(
    id = id.value,
    bankName = bankName,
    blz = blz,
    bic = bic,
    userId = userId,
    hbciVersion = hbciVersion,
    tanMethod = tanMethod,
    serverUrl = serverUrl,
    lastSync = lastSync,
    capabilities = capabilities,
)

fun Account.toItem() = AccountItem(
    id = id.value,
    bankAccessId = bankAccess.id.value,
    bankName = bankAccess.bankName,
    iban = iban,
    accountNumber = accountNumber,
    balance = balance,
    currency = currency,
    accountType = accountType,
    lastUpdate = lastUpdate,
)

fun Category.toItem() = CategoryItem(
    id = id.value,
    name = name,
    color = color,
    budgetLimit = budgetLimit,
    isTaxRelevant = isTaxRelevant,
)

fun Transaction.toItem(documentCount: Int = 0): TransactionItem {
    val category = category
    return TransactionItem(
        id = id.value,
        accountId = account.id.value,
        accountIban = account.iban,
        date = date,
        amount = amount,
        currency = currency,
        partnerName = partnerName,
        purpose = purpose,
        categoryId = category?.id?.value,
        categoryName = category?.name,
        categoryColor = category?.color,
        isTaxRelevant = category?.isTaxRelevant ?: false,
        documentCount = documentCount,
    )
}

fun Document.toItem(hasOcr: Boolean, ocrConfidence: java.math.BigDecimal?): DocumentItem {
    val txn = linkedTransaction
    return DocumentItem(
        id = id.value,
        fileName = fileName,
        filePath = de.findesk.util.AppPaths.resolveDocument(filePath).absolutePath,
        mimeType = mimeType,
        uploadDate = uploadDate,
        linkedTransactionId = txn?.id?.value,
        linkedTransactionLabel = txn?.let { (it.partnerName ?: it.purpose ?: "Transaction") + " (${it.date})" },
        hasOcr = hasOcr,
        ocrConfidence = ocrConfidence,
    )
}

fun Rule.toItem() = RuleItem(
    id = id.value,
    pattern = pattern,
    categoryId = category.id.value,
    categoryName = category.name,
    priority = priority,
    autoApply = autoApply,
)

fun PaymentOrder.toItem() = PaymentOrderItem(
    id = id.value,
    createdAt = createdAt,
    action = action,
    accountIban = accountIban,
    recipientName = recipientName,
    recipientIban = recipientIban,
    amount = amount,
    currency = currency,
    purpose = purpose,
    requestedDate = requestedDate,
    schedule = schedule,
    bankOrderId = bankOrderId,
    status = status,
    message = message,
)
