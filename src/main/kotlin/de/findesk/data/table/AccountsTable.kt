package de.findesk.data.table

import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.sql.javatime.datetime

object AccountsTable : IntIdTable("accounts") {
    val bankAccessId = reference("bank_access_id", BankAccessTable)
    val iban = varchar("iban", 34)
    val accountNumber = varchar("account_number", 34).nullable()
    val balance = decimal("balance", 15, 2).default(0.toBigDecimal())
    val currency = varchar("currency", 3).default("EUR")
    val accountType = varchar("account_type", 20).default("checking")
    val lastUpdate = datetime("last_update").nullable()
}
