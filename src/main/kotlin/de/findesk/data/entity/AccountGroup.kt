package de.findesk.data.entity

import de.findesk.data.table.AccountGroupsTable
import org.jetbrains.exposed.dao.IntEntity
import org.jetbrains.exposed.dao.IntEntityClass
import org.jetbrains.exposed.dao.id.EntityID

class AccountGroup(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<AccountGroup>(AccountGroupsTable)

    var name by AccountGroupsTable.name
    var sortOrder by AccountGroupsTable.sortOrder
}
