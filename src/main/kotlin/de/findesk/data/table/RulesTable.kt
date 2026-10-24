package de.findesk.data.table

import org.jetbrains.exposed.dao.id.IntIdTable

object RulesTable : IntIdTable("rules") {
    val pattern = text("pattern")
    val categoryId = reference("category_id", CategoriesTable).index()
    val priority = integer("priority").default(0)
    val autoApply = bool("auto_apply").default(true)

    /** Which text a rule matches ("any", "partner", "purpose"). */
    val matchField = varchar("match_field", 10).default("any")

    /** Which money flow a rule applies to ("any", "expense", "income"). */
    val direction = varchar("direction", 10).default("any")
}
