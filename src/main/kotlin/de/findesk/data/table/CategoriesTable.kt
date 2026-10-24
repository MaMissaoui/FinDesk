package de.findesk.data.table

import org.jetbrains.exposed.dao.id.IntIdTable

object CategoriesTable : IntIdTable("categories") {
    val name = varchar("name", 100)
    val color = varchar("color", 7).default("#3BC7B0")
    val budgetLimit = decimal("budget_limit", 15, 2).nullable()
    val isTaxRelevant = bool("is_tax_relevant").default(false)
    val isTransfer = bool("is_transfer").default(false)
}
