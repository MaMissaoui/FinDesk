package de.findesk.data.entity

import de.findesk.data.table.AuditLogTable
import org.jetbrains.exposed.dao.IntEntity
import org.jetbrains.exposed.dao.IntEntityClass
import org.jetbrains.exposed.dao.id.EntityID
import java.time.LocalDateTime

class AuditLogEntry(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<AuditLogEntry>(AuditLogTable)

    var timestamp by AuditLogTable.timestamp
    var action by AuditLogTable.action
    var user by AuditLogTable.user
    var details by AuditLogTable.details
}
