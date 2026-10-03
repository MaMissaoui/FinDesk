package de.findesk.data.table

import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.sql.javatime.datetime

object AuditLogTable : IntIdTable("audit_log") {
    val timestamp = datetime("timestamp")
    val action = varchar("action", 100)
    val user = varchar("user", 100)
    val details = text("details").nullable()
}