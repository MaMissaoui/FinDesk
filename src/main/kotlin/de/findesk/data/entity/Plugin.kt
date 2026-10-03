package de.findesk.data.entity

import de.findesk.data.table.PluginsTable
import org.jetbrains.exposed.dao.IntEntity
import org.jetbrains.exposed.dao.IntEntityClass
import org.jetbrains.exposed.dao.id.EntityID

class Plugin(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<Plugin>(PluginsTable)

    var name by PluginsTable.name
    var version by PluginsTable.version
    var enabled by PluginsTable.enabled
    var entryPoint by PluginsTable.entryPoint
}