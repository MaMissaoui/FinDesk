package de.findesk.repository

import de.findesk.data.entity.Rule
import de.findesk.data.entity.Category
import de.findesk.data.table.RulesTable
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.transactions.transaction

interface RuleRepository {
    fun findAll(): List<Rule>
    fun findById(id: Int): Rule?
    fun findByCategory(categoryId: Int): List<Rule>
    fun findAutoApplyRules(): List<Rule>
    fun findByPriority(): List<Rule>
    fun create(pattern: String, categoryId: Int, priority: Int = 0, autoApply: Boolean = true): Rule
    fun update(id: Int, pattern: String? = null, categoryId: Int? = null, priority: Int? = null, autoApply: Boolean? = null): Rule?
    fun delete(id: Int): Boolean
    fun matchTransaction(purpose: String?, partnerName: String?): Category?
}

class RuleRepositoryImpl : RuleRepository {
    override fun findAll(): List<Rule> = transaction {
        Rule.all().toList()
    }

    override fun findById(id: Int): Rule? = transaction {
        Rule.findById(id)
    }

    override fun findByCategory(categoryId: Int): List<Rule> = transaction {
        Rule.find { RulesTable.categoryId eq categoryId }.toList()
    }

    override fun findAutoApplyRules(): List<Rule> = transaction {
        Rule.find { RulesTable.autoApply eq true }
            .orderBy(RulesTable.priority to SortOrder.DESC)
            .toList()
    }

    override fun findByPriority(): List<Rule> = transaction {
        Rule.all()
            .orderBy(RulesTable.priority to SortOrder.DESC)
            .toList()
    }

    override fun create(
        pattern: String,
        categoryId: Int,
        priority: Int,
        autoApply: Boolean
    ): Rule = transaction {
        val category = Category.findById(categoryId)
            ?: throw IllegalArgumentException("Category with id $categoryId not found")
        Rule.new {
            this.pattern = pattern
            this.category = category
            this.priority = priority
            this.autoApply = autoApply
        }
    }

    override fun update(
        id: Int,
        pattern: String?,
        categoryId: Int?,
        priority: Int?,
        autoApply: Boolean?
    ): Rule? = transaction {
        Rule.findById(id)?.apply {
            pattern?.let { this.pattern = it }
            categoryId?.let { this.category = Category.findById(it) ?: this.category }
            priority?.let { this.priority = it }
            autoApply?.let { this.autoApply = it }
        }
    }

    override fun delete(id: Int): Boolean = transaction {
        Rule.findById(id)?.delete() != null
    }

    override fun matchTransaction(purpose: String?, partnerName: String?): Category? = transaction {
        val rules = findAutoApplyRules()
        val textToMatch = "$purpose $partnerName".lowercase()
        rules.firstOrNull { rule ->
            rule.pattern.toRegex(RegexOption.IGNORE_CASE).containsMatchIn(textToMatch)
        }?.category
    }
}