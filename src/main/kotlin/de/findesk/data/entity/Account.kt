package de.findesk.data.entity

import de.findesk.data.table.AccountsTable
import de.findesk.data.table.BankAccessTable
import de.findesk.data.table.TransactionsTable
import org.jetbrains.exposed.dao.IntEntity
import org.jetbrains.exposed.dao.IntEntityClass
import org.jetbrains.exposed.dao.id.EntityID
import java.math.BigDecimal

class Account(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<Account>(AccountsTable)

    var bankAccess by BankAccess referencedOn AccountsTable.bankAccessId
    var iban by AccountsTable.iban
    var accountNumber by AccountsTable.accountNumber
    var balance by AccountsTable.balance
    var currency by AccountsTable.currency
    var accountType by AccountsTable.accountType
    var lastUpdate by AccountsTable.lastUpdate

    val transactions by Transaction referrersOn TransactionsTable.accountId
}