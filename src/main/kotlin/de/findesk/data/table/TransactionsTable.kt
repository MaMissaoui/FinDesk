package de.findesk.data.table

import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.sql.javatime.date
import org.jetbrains.exposed.sql.javatime.datetime

object TransactionsTable : IntIdTable("transactions") {
    val accountId = reference("account_id", AccountsTable).index()
    val date = date("date").index()
    val amount = decimal("amount", 15, 2)
    val currency = varchar("currency", 3).default("EUR")
    val partnerName = varchar("partner_name", 255).nullable()
    val purpose = text("purpose").nullable()
    val categoryId = reference("category_id", CategoriesTable).nullable().index()
    val documentId = reference("document_id", DocumentsTable).nullable()
    val hash = varchar("hash", 64).index()
    val createdAt = datetime("created_at")
}
