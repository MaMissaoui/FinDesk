package de.findesk.importer

import de.findesk.i18n.AppStrings
import de.findesk.i18n.StringKey
import de.findesk.model.RuleDirection
import de.findesk.model.RuleField
import de.findesk.model.RuleItem
import de.findesk.repository.validateRulePattern
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/**
 * One usable rule row of a rule file, with the 1-based file [line] it was read from.
 * Pattern and category name are trimmed.
 */
data class RuleFileRow(
    val line: Int,
    val category: String,
    val pattern: String,
    val field: RuleField,
    val direction: RuleDirection,
    val priority: Int,
    val autoApply: Boolean,
)

/** One row that could not be read; [reason] is a localized string from `AppStrings`. */
data class RuleFileError(val line: Int, val reason: String)

/** All usable rows of a rule file plus everything that had to be skipped. */
data class ParsedRuleFile(val rows: List<RuleFileRow>, val errors: List<RuleFileError>)

/**
 * Reads and writes the plain-text rule file (`findesk-rules-….tsv`, UTF-8, six tab-separated columns).
 * Pure: no database, no file system.
 */
object RuleFile {
    /** First line of every written file; on reading it is just another comment line. */
    const val MAGIC = "# FinDesk rules v1"

    const val HEADER = "category\tpattern\tsearch_in\tapplies_to\tpriority\tauto_apply"
    const val MAX_FILE_BYTES = 1L * 1024 * 1024
    const val MAX_ROWS = 5000
    private const val MAX_CATEGORY_LENGTH = 100
    private const val MAX_PATTERN_LENGTH = 1000

    /** Signals that one data row is unusable; [reason] is a localized user message. */
    private class RowError(val reason: String) : RuntimeException()

    private fun fail(reason: String): Nothing = throw RowError(reason)

    /** Parses the bytes of a rule file. Whole-file problems throw an [IllegalArgumentException] with a localized message. */
    fun parse(bytes: ByteArray): ParsedRuleFile {
        if (bytes.size > MAX_FILE_BYTES) throw IllegalArgumentException(AppStrings[StringKey.ErrorRuleFileTooLarge])
        val text = try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (e: CharacterCodingException) {
            throw IllegalArgumentException(AppStrings[StringKey.ErrorRuleFileEncoding])
        }
        // A leading byte-order mark decodes to U+FEFF; drop it before the first line
        val body = if (text.isNotEmpty() && text.first() == 0xFEFF.toChar()) text.drop(1) else text

        val lines = body.split('\n').mapIndexed { index, line -> (index + 1) to line.removeSuffix("\r") }
        val content = lines.filter { (_, line) -> line.isNotBlank() && !line.trimStart().startsWith("#") }
        if (content.none() || content.first().second.trim() != HEADER) {
            throw IllegalArgumentException(AppStrings[StringKey.ErrorRuleFileHeader])
        }
        if (content.size - 1 > MAX_ROWS) throw IllegalArgumentException(AppStrings[StringKey.ErrorRuleFileTooLarge])

        val rows = mutableListOf<RuleFileRow>()
        val errors = mutableListOf<RuleFileError>()
        content.drop(1).forEach { (line, row) ->
            try {
                rows.add(readRow(line, row))
            } catch (e: RowError) {
                errors.add(RuleFileError(line, e.reason))
            }
        }
        return ParsedRuleFile(rows, errors)
    }

    /**
     * The usable [RuleFileRow] for one data line, or a [RowError] naming the first problem found.
     * Two invalid keys never fall back to ANY: the row is rejected with its own reason.
     */
    private fun readRow(line: Int, text: String): RuleFileRow {
        val columns = text.split('\t')
        if (columns.size != 6) fail(AppStrings.format(StringKey.RuleFileErrorColumns, columns.size))
        val category = columns[0]
        val pattern = columns[1]
        val searchIn = columns[2]
        val appliesTo = columns[3]
        val priority = columns[4]
        val autoApply = columns[5]

        val trimmedCategory = category.trim()
        if (trimmedCategory.isEmpty() || trimmedCategory.length > MAX_CATEGORY_LENGTH) {
            fail(AppStrings[StringKey.RuleFileErrorCategory])
        }
        val trimmedPattern = pattern.trim()
        if (trimmedPattern.length > MAX_PATTERN_LENGTH) fail(AppStrings[StringKey.RuleFileErrorPatternLength])
        validateRulePattern(trimmedPattern)?.let { fail(AppStrings.format(StringKey.RuleFileErrorPattern, it)) }
        val field = RuleField.entries.firstOrNull { it.key.equals(searchIn.trim(), ignoreCase = true) }
            ?: fail(AppStrings.format(StringKey.RuleFileErrorSearchIn, searchIn.trim()))
        val direction = RuleDirection.entries.firstOrNull { it.key.equals(appliesTo.trim(), ignoreCase = true) }
            ?: fail(AppStrings.format(StringKey.RuleFileErrorAppliesTo, appliesTo.trim()))
        val priorityText = priority.trim()
        val parsedPriority = priorityText.toIntOrNull() ?: fail(AppStrings.format(StringKey.RuleFileErrorPriority, priorityText))
        val autoApplyText = autoApply.trim()
        if (autoApplyText != "true" && autoApplyText != "false") {
            fail(AppStrings.format(StringKey.RuleFileErrorAutoApply, autoApplyText))
        }
        return RuleFileRow(line, trimmedCategory, trimmedPattern, field, direction, parsedPriority, autoApplyText == "true")
    }

    /** Whether a rule can be written at all: its pattern and category name must fit the one-line TSV format. */
    fun skippedRules(rules: List<RuleItem>): Int = rules.count {
        (it.pattern + it.categoryName).any { c -> c == '\t' || c == '\r' || c == '\n' }
    }

    /**
     * The rule file text for [rules]: magic line, header, then one line per rule, LF line endings, no BOM.
     * A rule whose pattern or category name contains a TAB, CR or LF cannot be represented; it is skipped and counted.
     */
    fun write(rules: List<RuleItem>): Pair<String, Int> {
        val text = StringBuilder().append(MAGIC).append('\n').append(HEADER)
        var skipped = 0
        rules.forEach { rule ->
            val writable = (rule.pattern + rule.categoryName).none { it == '\t' || it == '\r' || it == '\n' }
            if (!writable) {
                skipped++
            } else {
                text.append('\n').append(
                    listOf(
                        rule.categoryName,
                        rule.pattern,
                        rule.matchField.key,
                        rule.direction.key,
                        rule.priority.toString(),
                        rule.autoApply.toString(),
                    ).joinToString("\t"),
                )
            }
        }
        return text.append('\n').toString() to skipped
    }
}
