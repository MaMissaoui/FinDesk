package de.findesk.model

import de.findesk.data.entity.Account
import de.findesk.data.entity.AccountGroup
import de.findesk.data.entity.BankAccess
import de.findesk.data.entity.Category
import de.findesk.data.entity.Document
import de.findesk.data.entity.PaymentOrder
import de.findesk.data.entity.PendingBooking
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
    syncFrom = syncFrom,
    includeInSyncAll = includeInSyncAll,
    offline = offline,
)

fun Account.toItem(newCount: Int = 0) = AccountItem(
    id = id.value,
    bankAccessId = bankAccess.id.value,
    bankName = bankAccess.bankName,
    iban = iban,
    accountNumber = accountNumber,
    balance = balance,
    currency = currency,
    accountType = accountType,
    lastUpdate = lastUpdate,
    name = name,
    groupId = groupId,
    newCount = newCount,
)

fun AccountGroup.toItem() = AccountGroupItem(
    id = id.value,
    name = name,
    sortOrder = sortOrder,
)

/**
 * Maps a pending (vorgemerkte) bank line to its snapshot. Must run inside a transaction, since it reads
 * `account`; the pending block's header shows [PendingBookingItem.fetchedAt].
 */
fun PendingBooking.toItem() = PendingBookingItem(
    accountId = account.id.value,
    date = date,
    valueDate = valueDate,
    amount = amount,
    currency = currency,
    partnerName = partnerName,
    purpose = purpose,
    fetchedAt = fetchedAt,
)

fun Category.toItem() = CategoryItem(
    id = id.value,
    name = name,
    color = color,
    budgetLimit = budgetLimit,
    isTaxRelevant = isTaxRelevant,
    isTransfer = isTransfer,
)

/**
 * Maps a booking to its snapshot. [ruleTax]/[rulePattern] pass the rule layer [findItems] resolved (first
 * tax-flagged rule matching the booking's text, amount and current category); alone, the mapper sees only the
 * stored override and the category layer.
 */
fun Transaction.toItem(documentCount: Int = 0, ruleTax: Boolean? = null, rulePattern: String? = null): TransactionItem {
    val category = category
    val override = taxOverride
    // The highest layer that has a value wins: override, then rule, then category; no category and no override is NONE
    val (effective, source) = when {
        override != null -> override to TaxSource.MANUAL
        ruleTax != null -> ruleTax to TaxSource.RULE
        category != null -> category.isTaxRelevant to TaxSource.CATEGORY
        else -> false to TaxSource.NONE
    }
    return TransactionItem(
        id = id.value,
        accountId = account.id.value,
        accountIban = account.iban,
        date = date,
        amount = amount,
        balanceAfter = balanceAfter,
        currency = currency,
        partnerName = partnerName,
        purpose = purpose,
        categoryId = category?.id?.value,
        categoryName = category?.name,
        categoryColor = category?.color,
        isTaxRelevant = effective,
        documentCount = documentCount,
        isNew = isNew,
        isTransfer = category?.isTransfer ?: false,
        taxSource = source,
        taxRulePattern = if (source == TaxSource.RULE) rulePattern else null,
        taxOverride = override,
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
    matchField = RuleField.fromKey(matchField),
    direction = RuleDirection.fromKey(direction),
    taxRelevant = taxRelevant,
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
