package de.findesk.data.entity

import de.findesk.data.table.RulesTable
import org.jetbrains.exposed.dao.IntEntity
import org.jetbrains.exposed.dao.IntEntityClass
import org.jetbrains.exposed.dao.id.EntityID

class Rule(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<Rule>(RulesTable)

    var pattern by RulesTable.pattern
    var category by Category referencedOn RulesTable.categoryId
    var priority by RulesTable.priority
    var autoApply by RulesTable.autoApply
}
