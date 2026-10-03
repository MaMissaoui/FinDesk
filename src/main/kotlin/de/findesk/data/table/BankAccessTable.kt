package de.findesk.data.table

import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.sql.javatime.datetime

object BankAccessTable : IntIdTable("bank_access") {
    val bankName = varchar("bank_name", 255)
    val blz = varchar("blz", 20)
    val bic = varchar("bic", 20)
    val userId = varchar("user_id", 100)
    val hbciVersion = varchar("hbci_version", 10).default("300")
    val tanMethod = varchar("tan_method", 50).nullable()
    val lastSync = datetime("last_sync").nullable()
}