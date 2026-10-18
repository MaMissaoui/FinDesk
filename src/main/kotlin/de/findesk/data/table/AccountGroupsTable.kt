package de.findesk.data.table

import org.jetbrains.exposed.dao.id.IntIdTable

/** User-defined account groups shown in the account panel; ungrouped accounts fall back to their type. */
object AccountGroupsTable : IntIdTable("account_groups") {
    val name = varchar("name", 100)
    val sortOrder = integer("sort_order").default(0)
}
