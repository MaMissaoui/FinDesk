package de.findesk.data.table

import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.sql.javatime.datetime

object AuditLogTable : IntIdTable("audit_log") {
    val timestamp = datetime("timestamp").index()
    val action = varchar("action", 100).index()
    val user = varchar("user", 100)
    val details = text("details").nullable()
}
