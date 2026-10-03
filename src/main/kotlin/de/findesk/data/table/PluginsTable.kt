package de.findesk.data.table

import org.jetbrains.exposed.dao.id.IntIdTable

object PluginsTable : IntIdTable("plugins") {
    val name = varchar("name", 100)
    val version = varchar("version", 20)
    val enabled = bool("enabled").default(true)
    val entryPoint = varchar("entry_point", 255)
}
