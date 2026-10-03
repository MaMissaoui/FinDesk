package de.findesk.data.table

import org.jetbrains.exposed.dao.id.IntIdTable

object RulesTable : IntIdTable("rules") {
    val pattern = text("pattern")
    val categoryId = reference("category_id", CategoriesTable)
    val priority = integer("priority").default(0)
    val autoApply = bool("auto_apply").default(true)
}