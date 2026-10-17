package de.findesk.repository

import de.findesk.data.entity.Category
import de.findesk.data.entity.Rule
import de.findesk.data.table.RulesTable
import de.findesk.model.RuleItem
import de.findesk.model.toItem
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.transactions.transaction

interface RuleRepository {
    fun findAll(): List<Rule>
    fun findById(id: Int): Rule?
    fun findByCategory(categoryId: Int): List<Rule>
    fun findAutoApplyRules(): List<Rule>
    fun create(pattern: String, categoryId: Int, priority: Int = 0, autoApply: Boolean = true): Rule
    fun update(id: Int, pattern: String? = null, categoryId: Int? = null, priority: Int? = null, autoApply: Boolean? = null): Rule?
    fun delete(id: Int): Boolean

    /** The category id of the first auto-apply rule matching purpose or partner name; see [RuleMatcher]. */
    fun matchCategoryId(purpose: String?, partnerName: String?): Int?
    fun findAllItems(): List<RuleItem>

    /** The auto-apply rules, loaded and compiled once: use it to categorize many bookings in a row. */
    fun matcher(): RuleMatcher
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

    override fun create(
        pattern: String,
        categoryId: Int,
        priority: Int,
        autoApply: Boolean,
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
        autoApply: Boolean?,
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

    override fun matchCategoryId(purpose: String?, partnerName: String?): Int? = matcher().categoryFor(purpose, partnerName)

    override fun findAllItems(): List<RuleItem> = transaction {
        Rule.all().orderBy(RulesTable.priority to SortOrder.DESC).map { it.toItem() }
    }

    override fun matcher(): RuleMatcher = transaction {
        RuleMatcher(findAutoApplyRules().map { it.pattern to it.category.id.value })
    }
}

/**
 * Auto-apply rules compiled once, in priority order: the first rule whose pattern is found in purpose and partner
 * name wins; an invalid regex matches as a plain substring, like [ruleMatches].
 */
class RuleMatcher(rules: List<Pair<String, Int>>) {
    private val compiled: List<Pair<(String) -> Boolean, Int>> = rules.map { (pattern, categoryId) ->
        val regex = try {
            pattern.toRegex(RegexOption.IGNORE_CASE)
        } catch (e: java.util.regex.PatternSyntaxException) {
            null
        }
        val test: (String) -> Boolean = if (regex != null) regex::containsMatchIn else { text -> text.contains(pattern, ignoreCase = true) }
        test to categoryId
    }

    /** The category id of the first matching rule, or null. */
    fun categoryFor(purpose: String?, partnerName: String?): Int? {
        val text = listOfNotNull(purpose, partnerName).joinToString(" ")
        if (text.isBlank()) return null
        return compiled.firstOrNull { (matches, _) -> matches(text) }?.second
    }
}

/** Case-insensitive regex match; an invalid regex falls back to a plain substring match instead of throwing. */
internal fun ruleMatches(pattern: String, text: String): Boolean = try {
    pattern.toRegex(RegexOption.IGNORE_CASE).containsMatchIn(text)
} catch (e: java.util.regex.PatternSyntaxException) {
    text.contains(pattern, ignoreCase = true)
}
