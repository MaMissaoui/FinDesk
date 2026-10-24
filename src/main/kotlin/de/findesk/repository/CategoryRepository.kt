package de.findesk.repository

import de.findesk.data.entity.Category
import de.findesk.data.table.CategoriesTable
import de.findesk.data.table.RulesTable
import de.findesk.data.table.TransactionsTable
import de.findesk.model.CategoryItem
import de.findesk.model.toItem
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.count
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.math.BigDecimal

interface CategoryRepository {
    fun findAll(): List<Category>
    fun findById(id: Int): Category?
    fun findByName(name: String): Category?
    fun findTaxRelevant(): List<Category>
    fun create(name: String, color: String = "#3BC7B0", budgetLimit: BigDecimal? = null, isTaxRelevant: Boolean = false, isTransfer: Boolean = false): Category
    fun update(id: Int, name: String? = null, color: String? = null, budgetLimit: BigDecimal? = null, isTaxRelevant: Boolean? = null, isTransfer: Boolean? = null): Category?
    fun delete(id: Int): Boolean
    fun findAllItems(): List<CategoryItem>

    /**
     * How many bookings use each category: the `transactions` table grouped by `category_id`, in one query.
     * Uncategorized bookings (a null category) are not included; a category every booking is gone from sums to 0 by
     * default, not by a query of its own.
     */
    fun usageCounts(): Map<Int, Int>

    /** Sets or clears (null) the monthly budget. */
    fun setBudget(id: Int, budgetLimit: BigDecimal?): Boolean
}

class CategoryRepositoryImpl : CategoryRepository {
    override fun findAll(): List<Category> = transaction {
        Category.all().toList()
    }

    override fun findById(id: Int): Category? = transaction {
        Category.findById(id)
    }

    override fun findByName(name: String): Category? = transaction {
        Category.find { CategoriesTable.name eq name }.firstOrNull()
    }

    override fun findTaxRelevant(): List<Category> = transaction {
        Category.find { CategoriesTable.isTaxRelevant eq true }.toList()
    }

    override fun create(
        name: String,
        color: String,
        budgetLimit: BigDecimal?,
        isTaxRelevant: Boolean,
        isTransfer: Boolean,
    ): Category = transaction {
        Category.new {
            this.name = name
            this.color = color
            this.budgetLimit = budgetLimit
            this.isTaxRelevant = isTaxRelevant
            this.isTransfer = isTransfer
        }
    }

    override fun update(
        id: Int,
        name: String?,
        color: String?,
        budgetLimit: BigDecimal?,
        isTaxRelevant: Boolean?,
        isTransfer: Boolean?,
    ): Category? = transaction {
        Category.findById(id)?.apply {
            name?.let { this.name = it }
            color?.let { this.color = it }
            budgetLimit?.let { this.budgetLimit = it }
            isTaxRelevant?.let { this.isTaxRelevant = it }
            isTransfer?.let { this.isTransfer = it }
        }
    }

    override fun delete(id: Int): Boolean = transaction {
        // Unlink transactions and rules first; nothing else enforces the reference.
        TransactionsTable.update({ TransactionsTable.categoryId eq id }) { it[categoryId] = null }
        RulesTable.deleteWhere { RulesTable.categoryId eq id }
        Category.findById(id)?.delete() != null
    }

    override fun findAllItems(): List<CategoryItem> = transaction {
        Category.all().orderBy(CategoriesTable.name to org.jetbrains.exposed.sql.SortOrder.ASC).map { it.toItem() }
    }

    override fun usageCounts(): Map<Int, Int> = transaction {
        val count = TransactionsTable.id.count()
        TransactionsTable
            .select(TransactionsTable.categoryId, count)
            .groupBy(TransactionsTable.categoryId)
            .mapNotNull { row -> row[TransactionsTable.categoryId]?.let { it.value to row[count].toInt() } }
            .toMap()
    }

    override fun setBudget(id: Int, budgetLimit: BigDecimal?): Boolean = transaction {
        Category.findById(id)?.apply { this.budgetLimit = budgetLimit } != null
    }
}
