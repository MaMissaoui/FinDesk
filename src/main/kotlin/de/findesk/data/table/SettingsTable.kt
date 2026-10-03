package de.findesk.data.table

import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.sql.javatime.datetime

object SettingsTable : IntIdTable("settings") {
    val key = varchar("key", 100).uniqueIndex()
    val value = text("value")
    val updatedAt = datetime("updated_at")
}