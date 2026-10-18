package de.findesk.data.entity

import de.findesk.data.table.TransactionsTable
import org.jetbrains.exposed.dao.IntEntity
import org.jetbrains.exposed.dao.IntEntityClass
import org.jetbrains.exposed.dao.id.EntityID
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime

class Transaction(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<Transaction>(TransactionsTable)

    var account by Account referencedOn TransactionsTable.accountId
    var date by TransactionsTable.date
    var amount by TransactionsTable.amount
    var balanceAfter by TransactionsTable.balanceAfter
    var currency by TransactionsTable.currency
    var partnerName by TransactionsTable.partnerName
    var purpose by TransactionsTable.purpose
    var category by Category optionalReferencedOn TransactionsTable.categoryId
    var document by Document optionalReferencedOn TransactionsTable.documentId
    var hash by TransactionsTable.hash
    var createdAt by TransactionsTable.createdAt
    var isNew by TransactionsTable.isNew
}
