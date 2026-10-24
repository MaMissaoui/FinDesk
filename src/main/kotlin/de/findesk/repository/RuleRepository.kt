package de.findesk.repository

import de.findesk.data.entity.Category
import de.findesk.data.entity.Rule
import de.findesk.data.table.RulesTable
import de.findesk.i18n.AppStrings
import de.findesk.i18n.StringKey
import de.findesk.model.RuleDirection
import de.findesk.model.RuleField
import de.findesk.model.RuleItem
import de.findesk.model.toItem
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.transactions.transaction
import java.math.BigDecimal
import java.util.regex.PatternSyntaxException

/** One auto-apply rule compiled for [RuleMatcher]. */
data class RuleSpec(
    val pattern: String,
    val categoryId: Int,
    val field: RuleField = RuleField.ANY,
    val direction: RuleDirection = RuleDirection.ANY,
)

interface RuleRepository {
    fun findAll(): List<Rule>
    fun findById(id: Int): Rule?
    fun findByCategory(categoryId: Int): List<Rule>
    fun findAutoApplyRules(): List<Rule>
    fun create(
        pattern: String,
        categoryId: Int,
        priority: Int = 0,
        autoApply: Boolean = true,
        matchField: RuleField = RuleField.ANY,
        direction: RuleDirection = RuleDirection.ANY,
    ): Rule
    fun update(
        id: Int,
        pattern: String? = null,
        categoryId: Int? = null,
        priority: Int? = null,
        autoApply: Boolean? = null,
        matchField: RuleField? = null,
        direction: RuleDirection? = null,
    ): Rule?
    fun delete(id: Int): Boolean

    /** The category id of the first auto-apply rule matching purpose or partner name; see [RuleMatcher]. */
    fun matchCategoryId(purpose: String?, partnerName: String?, amount: BigDecimal? = null): Int?
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
            .orderBy(RulesTable.priority to SortOrder.DESC, RulesTable.id to SortOrder.ASC)
            .toList()
    }

    override fun create(
        pattern: String,
        categoryId: Int,
        priority: Int,
        autoApply: Boolean,
        matchField: RuleField,
        direction: RuleDirection,
    ): Rule = transaction {
        val category = Category.findById(categoryId)
            ?: throw IllegalArgumentException("Category with id $categoryId not found")
        Rule.new {
            this.pattern = pattern
            this.category = category
            this.priority = priority
            this.autoApply = autoApply
            this.matchField = matchField.key
            this.direction = direction.key
        }
    }

    override fun update(
        id: Int,
        pattern: String?,
        categoryId: Int?,
        priority: Int?,
        autoApply: Boolean?,
        matchField: RuleField?,
        direction: RuleDirection?,
    ): Rule? = transaction {
        Rule.findById(id)?.apply {
            pattern?.let { this.pattern = it }
            categoryId?.let { this.category = Category.findById(it) ?: this.category }
            priority?.let { this.priority = it }
            autoApply?.let { this.autoApply = it }
            matchField?.let { this.matchField = it.key }
            direction?.let { this.direction = it.key }
        }
    }

    override fun delete(id: Int): Boolean = transaction {
        Rule.findById(id)?.delete() != null
    }

    override fun matchCategoryId(purpose: String?, partnerName: String?, amount: BigDecimal?): Int? = matcher().categoryFor(purpose, partnerName, amount)

    override fun findAllItems(): List<RuleItem> = transaction {
        Rule.all().orderBy(RulesTable.priority to SortOrder.DESC, RulesTable.id to SortOrder.ASC).map { it.toItem() }
    }

    override fun matcher(): RuleMatcher = transaction {
        RuleMatcher(findAutoApplyRules().map { RuleSpec(it.pattern, it.category.id.value, RuleField.fromKey(it.matchField), RuleDirection.fromKey(it.direction)) })
    }
}

/**
 * Why [pattern] is unusable as a rule pattern, as a localized user message, or null when it is fine.
 * Shared by the settings dialog, rule-file import and every saved pattern.
 */
fun validateRulePattern(pattern: String): String? = when {
    pattern.isBlank() -> AppStrings[StringKey.ErrorPatternRequired]

    else -> try {
        pattern.toRegex()
        null
    } catch (e: PatternSyntaxException) {
        AppStrings.format(StringKey.ErrorInvalidRegex, e.description)
    }
}

/**
 * Auto-apply rules compiled once, in priority order: the first rule whose pattern is found in the rule's text scope
 * (purpose and partner name, partner name only or purpose only) and whose direction matches the money flow wins.
 * Matching ignores case, and an invalid regex matches as a plain substring instead of throwing.
 */
class RuleMatcher(val specs: List<RuleSpec>) {
    companion object {
        /** Pairs of pattern to category id, all rules unrestricted (ANY/ANY). */
        fun of(rules: List<Pair<String, Int>>) = RuleMatcher(rules.map { (pattern, categoryId) -> RuleSpec(pattern, categoryId) })
    }

    private val compiled: List<Triple<RuleField, RuleDirection, (String) -> Boolean>> =
        specs.map { (pattern, _, field, direction) ->
            val regex = try {
                pattern.toRegex(RegexOption.IGNORE_CASE)
            } catch (e: java.util.regex.PatternSyntaxException) {
                null
            }
            val test: (String) -> Boolean = if (regex != null) regex::containsMatchIn else { text -> text.contains(pattern, ignoreCase = true) }
            Triple(field, direction, test)
        }

    /** The category id of the first matching rule, or null. */
    fun categoryFor(purpose: String?, partnerName: String?, amount: BigDecimal? = null): Int? {
        specs.forEachIndexed { index, spec ->
            val (_, direction, test) = compiled[index]
            val directionOk = when (direction) {
                RuleDirection.ANY -> true
                RuleDirection.EXPENSE -> amount != null && amount < BigDecimal.ZERO
                RuleDirection.INCOME -> amount != null && amount > BigDecimal.ZERO
            }
            if (!directionOk) return@forEachIndexed
            val text = when (spec.field) {
                RuleField.ANY -> listOfNotNull(purpose, partnerName).joinToString(" ")
                RuleField.PARTNER -> partnerName.orEmpty()
                RuleField.PURPOSE -> purpose.orEmpty()
            }
            if (text.isNotBlank() && test(text)) return spec.categoryId
        }
        return null
    }
}
