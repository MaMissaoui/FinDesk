package de.findesk.data.entity

import de.findesk.data.table.CategoriesTable
import de.findesk.data.table.RulesTable
import de.findesk.data.table.TransactionsTable
import org.jetbrains.exposed.dao.IntEntity
import org.jetbrains.exposed.dao.IntEntityClass
import org.jetbrains.exposed.dao.id.EntityID
import java.math.BigDecimal

class Category(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<Category>(CategoriesTable)

    var name by CategoriesTable.name
    var color by CategoriesTable.color
    var budgetLimit by CategoriesTable.budgetLimit
    var isTaxRelevant by CategoriesTable.isTaxRelevant

    val rules by Rule referrersOn RulesTable.categoryId
}
