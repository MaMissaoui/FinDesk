package de.findesk.repository

import de.findesk.data.entity.AuditLogEntry
import de.findesk.data.table.AuditLogTable
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.LocalDateTime

interface AuditLogRepository {
    fun findAll(): List<AuditLogEntry>
    fun findByAction(action: String): List<AuditLogEntry>
    fun findByUser(user: String): List<AuditLogEntry>
    fun findRecent(limit: Int = 100): List<AuditLogEntry>
    fun log(action: String, user: String, details: String? = null): AuditLogEntry
}

class AuditLogRepositoryImpl : AuditLogRepository {
    override fun findAll(): List<AuditLogEntry> = transaction {
        AuditLogEntry.all()
            .orderBy(AuditLogTable.timestamp to SortOrder.DESC)
            .toList()
    }

    override fun findByAction(action: String): List<AuditLogEntry> = transaction {
        AuditLogEntry.find { AuditLogTable.action eq action }
            .orderBy(AuditLogTable.timestamp to SortOrder.DESC)
            .toList()
    }

    override fun findByUser(user: String): List<AuditLogEntry> = transaction {
        AuditLogEntry.find { AuditLogTable.user eq user }
            .orderBy(AuditLogTable.timestamp to SortOrder.DESC)
            .toList()
    }

    override fun findRecent(limit: Int): List<AuditLogEntry> = transaction {
        AuditLogEntry.all()
            .orderBy(AuditLogTable.timestamp to SortOrder.DESC)
            .limit(limit)
            .toList()
    }

    override fun log(action: String, user: String, details: String?): AuditLogEntry = transaction {
        AuditLogEntry.new {
            this.timestamp = LocalDateTime.now()
            this.action = action
            this.user = user
            this.details = details
        }
    }
}