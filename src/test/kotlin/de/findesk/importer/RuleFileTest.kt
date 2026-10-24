package de.findesk.importer

import de.findesk.i18n.AppStrings
import de.findesk.i18n.StringKey
import de.findesk.model.RuleDirection
import de.findesk.model.RuleField
import de.findesk.model.RuleItem
import de.findesk.repository.validateRulePattern
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** The plain-text rule file: header check, line skipping, row errors and the round trip through write. */
class RuleFileTest {
    private val header = "category\tpattern\tsearch_in\tapplies_to\tpriority\tauto_apply"
    private val magic = "# FinDesk rules v1"

    private fun file(vararg lines: String): ByteArray = lines.joinToString("\n").toByteArray(Charsets.UTF_8)

    private fun item(
        id: Int = 1,
        pattern: String,
        category: String,
        priority: Int = 50,
        autoApply: Boolean = true,
        field: RuleField = RuleField.ANY,
        direction: RuleDirection = RuleDirection.ANY,
    ) = RuleItem(id, pattern, 7, category, priority, autoApply, field, direction)

    private fun row(
        category: String,
        pattern: String,
        searchIn: String = "any",
        appliesTo: String = "any",
        priority: String = "50",
        autoApply: String = "true",
    ) = listOf(category, pattern, searchIn, appliesTo, priority, autoApply).joinToString("\t")

    @Test
    fun `valid file round trip through write`() {
        val rules = listOf(
            item(pattern = "beispiel.?markt|musterladen", category = "Groceries", priority = 60),
            item(id = 2, pattern = "erika mustermann", category = "Own transfers", priority = 20, field = RuleField.PARTNER),
            item(id = 3, pattern = "beispiel gmbh", category = "Salary", priority = 70, direction = RuleDirection.INCOME),
            item(id = 4, pattern = "raw cash", category = "Cash", priority = 0, autoApply = false, field = RuleField.PURPOSE),
        )

        val text = RuleFile.write(rules).first

        val parsed = RuleFile.parse(text.toByteArray(Charsets.UTF_8))
        assertEquals(emptyList<RuleFileError>(), parsed.errors)
        assertEquals(4, parsed.rows.size)
        rules.forEachIndexed { index, rule ->
            val written = parsed.rows[index]
            assertEquals(index + 3, written.line, "data rows start at file line 3, below magic and header")
            assertEquals(rule.pattern, written.pattern)
            assertEquals(rule.categoryName, written.category)
            assertEquals(rule.matchField, written.field)
            assertEquals(rule.direction, written.direction)
            assertEquals(rule.priority, written.priority)
            assertEquals(rule.autoApply, written.autoApply)
        }
    }

    @Test
    fun `the written file starts with the magic and the header and holds no bom`() {
        val text = RuleFile.write(listOf(item(pattern = "beispiel markt", category = "Groceries"))).first
        val lines = text.split('\n')

        assertEquals(magic, lines[0])
        assertEquals(header, lines[1])
        assertEquals(row("Groceries", "beispiel markt", priority = "50"), lines[2])
        assertTrue(!text.startsWith(0xFEFF.toChar().toString()), "no byte order mark is written")
    }

    @Test
    fun `bom and crlf line endings are accepted`() {
        val text = (0xFEFF.toChar().toString() + magic) + "\r\n" + arrayOf(header, row("Erika género", "erika"), row("Groceries", "beispiel markt")).joinToString("\r\n") + "\r\n"

        val parsed = RuleFile.parse(text.toByteArray(Charsets.UTF_8))

        assertEquals(listOf("Erika género", "Groceries"), parsed.rows.map { it.category })
        assertEquals(listOf(3, 4), parsed.rows.map { it.line })
    }

    @Test
    fun `comments and blank lines are skipped but their line numbers still count`() {
        val parsed = RuleFile.parse(
            file(magic, "", "# a note for the reader", "   ", header, "", "# not a rule", row("Cash", "raw cash"), row("Groceries", "beispiel markt")),
        )

        assertEquals(listOf("Cash", "Groceries"), parsed.rows.map { it.category })
        assertEquals(listOf(8, 9), parsed.rows.map { it.line })
    }

    @Test
    fun `a missing header is rejected`() {
        val exception = assertThrows<IllegalArgumentException> {
            RuleFile.parse(file(magic, row("Cash", "raw cash")))
        }
        assertEquals(AppStrings[StringKey.ErrorRuleFileHeader], exception.message)
    }

    @Test
    fun `a wrong header is rejected`() {
        val exception = assertThrows<IllegalArgumentException> {
            RuleFile.parse(file(magic, "category\tpattern\tsearch_in\tapplies_to\tpriority\tautoApply", row("Cash", "raw cash")))
        }
        assertEquals(AppStrings[StringKey.ErrorRuleFileHeader], exception.message)

        // Even a header with the columns in another order is not this file format
        val swapped = assertThrows<IllegalArgumentException> {
            RuleFile.parse(file(magic, "pattern\tcategory\tsearch_in\tapplies_to\tpriority\tauto_apply", row("Cash", "raw cash")))
        }
        assertEquals(AppStrings[StringKey.ErrorRuleFileHeader], swapped.message)
    }

    @Test
    fun `a file without any content is rejected as a rule file`() {
        assertEquals(AppStrings[StringKey.ErrorRuleFileHeader], assertThrows<IllegalArgumentException> { RuleFile.parse(ByteArray(0)) }.message)
        val noHeader = assertThrows<IllegalArgumentException> { RuleFile.parse(file("just some line")) }
        assertEquals(AppStrings[StringKey.ErrorRuleFileHeader], noHeader.message)
    }

    @Test
    fun `a file with more than 5000 data rows is rejected`() {
        val tooMany = (1..5001).map { index -> row("Cat $index", "muster $index") }.toTypedArray()
        val exception = assertThrows<IllegalArgumentException> { RuleFile.parse(file(magic, header, *tooMany)) }
        assertEquals(AppStrings[StringKey.ErrorRuleFileTooLarge], exception.message)

        val exactlyEnough = (1..5000).map { index -> row("Cat $index", "muster $index") }.toTypedArray()
        assertEquals(5000, RuleFile.parse(file(magic, header, *exactlyEnough)).rows.size)
    }

    @Test
    fun `a file larger than 1 mb is rejected`() {
        val exception = assertThrows<IllegalArgumentException> { RuleFile.parse(ByteArray(1024 * 1024 + 1)) }
        assertEquals(AppStrings[StringKey.ErrorRuleFileTooLarge], exception.message)
    }

    @Test
    fun `bytes that are not valid utf-8 are rejected`() {
        val tail = (magic + "\n" + header + "\n").toByteArray(Charsets.UTF_8) + byteArrayOf(0xFF.toByte())
        val exception = assertThrows<IllegalArgumentException> { RuleFile.parse(tail) }
        assertEquals(AppStrings[StringKey.ErrorRuleFileEncoding], exception.message)
    }

    @Test
    fun `every row error kind is reported with its file line while the valid rows remain`() {
        val parsed = RuleFile.parse(
            file(
                magic,
                header,
                row("Cat One", "rewe"),
                "Cat Two\tpattern\tpartner\tincome\t50", // missing auto_apply -> 5 columns
                row("", "pattern"), // blank category
                row("Cat Three", "[unclosed"), // invalid pattern
                row("Cat Four", "a.".repeat(501)), // 1002 characters
                row("Cat Five", "pattern", searchIn = "companions"),
                row("Cat Six", "pattern", appliesTo = "outgoing"),
                row("Cat Seven", "pattern", priority = "bold"),
                row("Cat Eight", "pattern", autoApply = "maybe"),
                row("Cat Nine", "edeka"),
            ),
        )

        assertEquals(listOf("Cat One", "Cat Nine"), parsed.rows.map { it.category })
        assertEquals(listOf(3, 12), parsed.rows.map { it.line })
        assertEquals(
            listOf(
                4 to AppStrings.format(StringKey.RuleFileErrorColumns, 5),
                5 to AppStrings[StringKey.RuleFileErrorCategory],
                6 to AppStrings.format(StringKey.RuleFileErrorPattern, validateRulePattern("[unclosed")!!),
                7 to AppStrings[StringKey.RuleFileErrorPatternLength],
                8 to AppStrings.format(StringKey.RuleFileErrorSearchIn, "companions"),
                9 to AppStrings.format(StringKey.RuleFileErrorAppliesTo, "outgoing"),
                10 to AppStrings.format(StringKey.RuleFileErrorPriority, "bold"),
                11 to AppStrings.format(StringKey.RuleFileErrorAutoApply, "maybe"),
            ),
            parsed.errors.map { it.line to it.reason },
        )
    }

    @Test
    fun `an unknown search_in key is an error, never a silent fallback to any`() {
        val parsed = RuleFile.parse(
            file(magic, header, row("Groceries", "beispiel markt", searchIn = "partner"), row("Cash", "raw", searchIn = "purpose only")),
        )

        assertEquals(1, parsed.rows.size, "the partner row is fine, the unknown key is rejected")
        assertTrue(parsed.rows.none { it.field == RuleField.PURPOSE }, "unknown keys are not mapped onto a field")
        assertEquals(listOf(4), parsed.errors.map { it.line })
        assertEquals(AppStrings.format(StringKey.RuleFileErrorSearchIn, "purpose only"), parsed.errors.single().reason)
    }

    @Test
    fun `a rule with a tab, cr or lf in its pattern or category is skipped on write and counted`() {
        val rules = listOf(
            item(pattern = "musterladen", category = "Groceries"),
            item(id = 2, pattern = "bad\tregex", category = "Cash"),
            item(id = 3, pattern = "normal", category = "Bad\rName"),
            item(id = 4, pattern = "newline\npattern", category = "Other"),
        )

        val text = RuleFile.write(rules).first
        assertEquals(3, RuleFile.write(rules).second, "three rules cannot be written")

        val parsed = RuleFile.parse(text.toByteArray(Charsets.UTF_8))
        assertEquals(listOf("musterladen"), parsed.rows.map { it.pattern })
        assertEquals(1, parsed.rows.size)
    }
}
