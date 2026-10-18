package de.findesk.data.table

import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.sql.javatime.datetime

object AccountsTable : IntIdTable("accounts") {
    val bankAccessId = reference("bank_access_id", BankAccessTable).index()
    val iban = varchar("iban", 34).index()
    val accountNumber = varchar("account_number", 34).nullable()
    val balance = decimal("balance", 15, 2).default(0.toBigDecimal())
    val currency = varchar("currency", 3).default("EUR")
    val accountType = varchar("account_type", 20).default("checking")
    val name = varchar("name", 255).nullable()
    val lastUpdate = datetime("last_update").nullable()

    /** Custom group the account belongs to; null keeps it in its account-type group. Plain int (no FK). */
    val groupId = integer("group_id").nullable().index()
}
