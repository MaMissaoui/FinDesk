package de.findesk.data.entity

import de.findesk.data.table.SettingsTable
import org.jetbrains.exposed.dao.IntEntity
import org.jetbrains.exposed.dao.IntEntityClass
import org.jetbrains.exposed.dao.id.EntityID
import java.time.LocalDateTime

class Setting(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<Setting>(SettingsTable)

    var key by SettingsTable.key
    var value by SettingsTable.value
    var updatedAt by SettingsTable.updatedAt
}