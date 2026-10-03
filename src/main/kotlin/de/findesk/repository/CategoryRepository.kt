package de.findesk.repository

import de.findesk.data.entity.Category
import de.findesk.data.table.CategoriesTable
import org.jetbrains.exposed.sql.transactions.transaction
import java.math.BigDecimal

interface CategoryRepository {
    fun findAll(): List<Category>
    fun findById(id: Int): Category?
    fun findByName(name: String): Category?
    fun findTaxRelevant(): List<Category>
    fun create(name: String, color: String = "#3BC7B0", budgetLimit: BigDecimal? = null, isTaxRelevant: Boolean = false): Category
    fun update(id: Int, name: String? = null, color: String? = null, budgetLimit: BigDecimal? = null, isTaxRelevant: Boolean? = null): Category?
    fun delete(id: Int): Boolean
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
    ): Category = transaction {
        Category.new {
            this.name = name
            this.color = color
            this.budgetLimit = budgetLimit
            this.isTaxRelevant = isTaxRelevant
        }
    }

    override fun update(
        id: Int,
        name: String?,
        color: String?,
        budgetLimit: BigDecimal?,
        isTaxRelevant: Boolean?,
    ): Category? = transaction {
        Category.findById(id)?.apply {
            name?.let { this.name = it }
            color?.let { this.color = it }
            budgetLimit?.let { this.budgetLimit = it }
            isTaxRelevant?.let { this.isTaxRelevant = it }
        }
    }

    override fun delete(id: Int): Boolean = transaction {
        Category.findById(id)?.delete() != null
    }
}
