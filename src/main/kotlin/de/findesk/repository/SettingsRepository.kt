package de.findesk.repository

import de.findesk.data.entity.Setting
import de.findesk.data.table.SettingsTable
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.LocalDateTime

interface SettingsRepository {
    fun findAll(): List<Setting>
    fun findByKey(key: String): Setting?
    fun getValue(key: String): String?
    fun setValue(key: String, value: String): Setting
    fun delete(key: String): Boolean
}

class SettingsRepositoryImpl : SettingsRepository {
    override fun findAll(): List<Setting> = transaction {
        Setting.all().toList()
    }

    override fun findByKey(key: String): Setting? = transaction {
        Setting.find { SettingsTable.key eq key }.firstOrNull()
    }

    override fun getValue(key: String): String? = transaction {
        findByKey(key)?.value
    }

    override fun setValue(key: String, value: String): Setting = transaction {
        val existing = findByKey(key)
        if (existing != null) {
            existing.apply {
                this.value = value
                this.updatedAt = LocalDateTime.now()
            }
        } else {
            Setting.new {
                this.key = key
                this.value = value
                this.updatedAt = LocalDateTime.now()
            }
        }
    }

    override fun delete(key: String): Boolean = transaction {
        findByKey(key)?.delete() != null
    }
}