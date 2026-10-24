package de.findesk.importer

import de.findesk.i18n.AppStrings
import de.findesk.model.DefaultCategories
import de.findesk.model.RuleDirection
import de.findesk.model.RuleField
import de.findesk.model.RuleItem
import de.findesk.model.TransactionFilter
import de.findesk.repository.AuditLogRepository
import de.findesk.repository.AuditLogRepositoryImpl
import de.findesk.repository.CategoryRepository
import de.findesk.repository.CategoryRepositoryImpl
import de.findesk.repository.RuleMatcher
import de.findesk.repository.RuleRepository
import de.findesk.repository.RuleRepositoryImpl
import de.findesk.repository.RuleSpec
import de.findesk.repository.TransactionRepository
import de.findesk.repository.TransactionRepositoryImpl
import org.jetbrains.exposed.sql.transactions.transaction
import java.io.File

/** Same pattern, text field and money flow as an imported row, but the file's category would sit beside another. */
data class RuleImportConflict(
    /** 1-based line of the file the new rule was read from. */
    val line: Int,
    val pattern: String,
    val fileCategory: String,
    val existingCategory: String,
)

/** What an import of a parsed rule file would change; the preview dialog shows it before anything is written. */
data class RuleImportPreview(
    /** Categories that do not exist yet, in file order, deduplicated case-insensitively (first spelling wins). */
    val categoriesToCreate: List<String>,
    /** Rows that create a new rule, in file order. */
    val newRules: List<RuleFileRow>,
    /** Rows that would collide with an existing rule or an earlier row of the same file; they are skipped. */
    val conflicts: List<RuleImportConflict>,
    /** Rows that repeat an identical rule (same pattern, field, direction and category); they are skipped. */
    val duplicates: Int,
    /** How many currently uncategorized bookings the combined auto-apply rule set would categorize. */
    val matchCount: Int,
    /** Row errors passed through from the parse. */
    val errors: List<RuleFileError>,
)

/** The counts an applied import changed. */
data class RuleImportResult(val categories: Int, val rules: Int, val categorized: Int)

/**
 * Moves a tested rule set between profiles and installations: reads a parsed rule file, previews what would change and,
 * on confirmation, creates the missing categories and the new rules and optionally categorizes uncategorized bookings
 * with the imported rules. Existing rules are never changed, existing categories never renamed.
 */
class RuleImportService(
    private val categoryRepository: CategoryRepository = CategoryRepositoryImpl(),
    private val ruleRepository: RuleRepository = RuleRepositoryImpl(),
    private val transactionRepository: TransactionRepository = TransactionRepositoryImpl(),
    private val auditLogRepository: AuditLogRepository = AuditLogRepositoryImpl(),
) {
    /** Read-only: classifies every usable row against the existing data, leaving categories and rules untouched. */
    fun preview(parsed: ParsedRuleFile): RuleImportPreview = transaction {
        val existingCategories = categoryRepository.findAllItems()
        val existingKeys = existingCategories.map { it.name.trim().lowercase() }.toSet()
        val seenInFile = mutableSetOf<String>()
        val categoriesToCreate = buildList {
            parsed.rows.forEach { row ->
                val key = row.category.trim().lowercase()
                if (key !in existingKeys && seenInFile.add(key)) add(row.category.trim())
            }
        }

        // Existing rules indexed by pattern, field and direction; the first kept is like the file's earliest row
        val existingByKey = LinkedHashMap<Triple<String, RuleField, RuleDirection>, RuleItem>()
        ruleRepository.findAllItems().forEach { item ->
            val key = Triple(item.pattern, item.matchField, item.direction)
            if (key !in existingByKey) existingByKey[key] = item
        }

        // An earlier row of the same file counts like an existing rule: a later identical row is a duplicate,
        // a row with the same key but another category a conflict. That earlier row may itself have been skipped.
        val earlierByKey = LinkedHashMap<Triple<String, RuleField, RuleDirection>, Pair<String, String>>()
        val newRules = mutableListOf<RuleFileRow>()
        val conflicts = mutableListOf<RuleImportConflict>()
        var duplicates = 0
        parsed.rows.forEach { row ->
            val key = Triple(row.pattern, row.field, row.direction)
            val categoryKey = row.category.trim().lowercase()
            val earlier = earlierByKey[key]
            val existing = existingByKey[key]
            when {
                earlier != null && earlier.first == categoryKey -> duplicates++

                earlier != null -> conflicts += RuleImportConflict(row.line, row.pattern, row.category, earlier.second)

                existing != null && existing.categoryName.trim().lowercase() == categoryKey -> duplicates++

                existing != null -> conflicts += RuleImportConflict(row.line, row.pattern, row.category, existing.categoryName)

                else -> {
                    newRules += row
                    earlierByKey[key] = categoryKey to row.category
                }
            }
        }

        RuleImportPreview(
            categoriesToCreate = categoriesToCreate,
            newRules = newRules,
            conflicts = conflicts,
            duplicates = duplicates,
            matchCount = matchCount(newRules.filter { it.autoApply }, categoriesToCreate),
            errors = parsed.errors,
        )
    }

    /** Applies the confirmed [preview] in one transaction: categories, rules and, on request, the categorization. */
    fun apply(preview: RuleImportPreview, categorize: Boolean): RuleImportResult = transaction {
        val existing = categoryRepository.findAllItems()
        // The existing spelling and id win: everything is resolved case-insensitively by trimmed name
        val resolved = LinkedHashMap<String, Int>()
        existing.forEach { category -> resolved.putIfAbsent(category.name.trim().lowercase(), category.id) }
        val created = LinkedHashMap<String, Int>()

        fun categoryId(name: String): Int {
            val key = name.trim().lowercase()
            resolved[key]?.let { return it }
            created[key]?.let { return it }
            // Not listed by the preview (should not happen): create it too, so the import still succeeds entirely
            created[key] = categoryRepository.create(name, categoryColour(name, existing.size + created.size)).id.value
            return created.getValue(key)
        }

        // The preview's list is in file order; every name must resolve before any step writes
        preview.categoriesToCreate.forEach { name -> categoryId(name) }

        preview.newRules.forEach { row ->
            ruleRepository.create(row.pattern, categoryId(row.category), row.priority, row.autoApply, row.field, row.direction)
        }

        var categorized = 0
        if (categorize && preview.newRules.isNotEmpty()) {
            val matcher = ruleRepository.matcher()
            transactionRepository.findItems(TransactionFilter(uncategorizedOnly = true)).forEach { booking ->
                val id = matcher.categoryFor(booking.purpose, booking.partnerName, booking.amount)
                if (id != null && transactionRepository.updateCategory(booking.id, id) != null) categorized++
            }
        }

        val result = RuleImportResult(categories = preview.categoriesToCreate.size, rules = preview.newRules.size, categorized = categorized)
        auditLogRepository.log(
            "IMPORT_RULES",
            "user",
            "Imported ${result.rules} rules, created ${result.categories} categories, categorized ${result.categorized} transactions",
        )
        result
    }

    /** The `DefaultCategories` colour of the current UI language when [name] names a default category, else a palette colour. */
    private fun categoryColour(name: String, ordinal: Int): String = DefaultCategories.entries
        .firstOrNull { (key, colour) -> AppStrings.current[key].trim().equals(name.trim(), ignoreCase = true) }
        ?.second
        ?: DefaultCategories.colors[ordinal % DefaultCategories.colors.size]

    /**
     * How many currently uncategorized bookings the combined auto-apply rule set (existing rules, merged with
     * [autoApplyRows]) would categorize. New categories get temporary negative ids for this simulation only.
     */
    private fun matchCount(autoApplyRows: List<RuleFileRow>, categoriesToCreate: List<String>): Int {
        val bookings = transactionRepository.findItems(TransactionFilter(uncategorizedOnly = true))
        if (bookings.isEmpty()) return 0

        val existingKeys = categoryRepository.findAllItems().associateBy({ it.name.trim().lowercase() }, { it.id })
        val tempIds = categoriesToCreate.mapIndexed { index, name -> name.trim().lowercase() to -1 - index }.toMap()

        fun categoryIdOf(name: String): Int = existingKeys[name.trim().lowercase()] ?: tempIds.getValue(name.trim().lowercase())

        // Existing rules first, then the file's rows, all merged by priority; the file keeps its own order on ties
        val merged = ruleRepository.findAllItems().filter { it.autoApply }
            .mapIndexed { index, item -> RankedSpec(item.priority, 0, index, RuleSpec(item.pattern, item.categoryId, item.matchField, item.direction)) } +
            autoApplyRows.mapIndexed { index, row ->
                RankedSpec(row.priority, 1, index, RuleSpec(row.pattern, categoryIdOf(row.category), row.field, row.direction))
            }

        val ordered = merged
            .sortedWith(compareByDescending<RankedSpec> { it.priority }.thenBy { it.source }.thenBy { it.order })
            .map { it.spec }

        return RuleMatcher(ordered).let { matcher -> bookings.count { matcher.categoryFor(it.purpose, it.partnerName, it.amount) != null } }
    }

    /** One auto-apply rule in the merge order of the matchCount simulation. */
    private data class RankedSpec(val priority: Int, val source: Int, val order: Int, val spec: RuleSpec)

    /** Writes the current rules (auto-apply and manual) to [file] in the reviewable rule-file format. */
    fun export(file: File): Int {
        val rules = ruleRepository.findAllItems()
        val (text, skipped) = RuleFile.write(rules)
        val written = rules.size - skipped
        file.writeText(text, Charsets.UTF_8)
        auditLogRepository.log(
            "EXPORT_RULES",
            "user",
            "Exported $written rules" + (if (skipped > 0) ", skipped $skipped" else ""),
        )
        return written
    }
}
