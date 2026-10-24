package de.findesk.importer

import de.findesk.TestData
import de.findesk.TestDatabase
import de.findesk.i18n.StringKey
import de.findesk.model.DefaultCategories
import de.findesk.model.RuleDirection
import de.findesk.model.RuleField
import de.findesk.repository.AuditLogRepositoryImpl
import de.findesk.repository.CategoryRepositoryImpl
import de.findesk.repository.RuleRepositoryImpl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.LocalDate

/**
 * Import and export of a reviewed rule set: classification (new rules, duplicates, conflicts), the matchCount
 * simulation and the applied effect on categories, rules and bookings.
 */
class RuleImportServiceTest {
    private val header = "category\tpattern\tsearch_in\tapplies_to\tpriority\tauto_apply"
    private val today = LocalDate.of(2026, 10, 24)

    @TempDir
    lateinit var tmp: File

    private lateinit var data: TestData
    private val service = RuleImportService()

    private fun parse(vararg lines: String, ignoreErrors: Boolean = false) = RuleFile.parse(lines.joinToString("\n").toByteArray())
    private fun row(
        category: String,
        pattern: String,
        searchIn: String = "any",
        appliesTo: String = "any",
        priority: String = "50",
        autoApply: String = "true",
    ) = listOf(category, pattern, searchIn, appliesTo, priority, autoApply).joinToString("\t")

    @BeforeEach
    fun setup() {
        TestDatabase.init()
        data = TestData()
    }

    @Test
    fun `existing categories are matched case-insensitively and nothing is created for them`() {
        data.category("groceries")

        val preview = service.preview(parse("# FinDesk rules v1", header, row("Groceries", "beispiel markt"), row("  Groceries  ", "musterladen")))

        assertEquals(emptyList<String>(), preview.categoriesToCreate, "Groceries exists, in another spelling, so it is not created again")
        assertEquals(2, preview.newRules.size)
        service.apply(preview, categorize = false)
        assertEquals(listOf("groceries"), CategoryRepositoryImpl().findAllItems().map { it.name }, "the existing spelling wins")
    }

    @Test
    fun `a duplicate of an existing rule is skipped`() {
        val groceries = data.category("Groceries")
        data.ruleRepo.create("beispiel markt", groceries, priority = 30, matchField = RuleField.PARTNER, direction = RuleDirection.EXPENSE)

        val preview = service.preview(parse("# FinDesk rules v1", header, row("Groceries", "beispiel markt", "partner", "expense", "30", "false")))
        val result = service.apply(preview, categorize = false)

        assertEquals(1, preview.duplicates)
        assertTrue(preview.newRules.isEmpty() && preview.conflicts.isEmpty())
        assertEquals(0, result.rules, "nothing new is created")
        assertEquals(1, RuleRepositoryImpl().findAllItems().size)
    }

    @Test
    fun `a conflict is reported and the existing rule stays unchanged`() {
        val groceries = data.category("Groceries")
        data.ruleRepo.create("beispiel markt", groceries, priority = 30)

        val preview = service.preview(parse("# FinDesk rules v1", header, row("Household", "beispiel markt")))
        val result = service.apply(preview, categorize = false)

        assertEquals(listOf(RuleImportConflict(3, "beispiel markt", "Household", "Groceries")), preview.conflicts)
        assertTrue(preview.newRules.isEmpty())
        assertEquals(0, result.rules, "existing rules are never changed")
        assertEquals(1, RuleRepositoryImpl().findAllItems().size)
        assertEquals(1, RuleRepositoryImpl().findAllItems().single().categoryId)
        assertEquals("Household", preview.categoriesToCreate.single(), "the file's category is created but no rule points at it")
    }

    @Test
    fun `identical rows of the same file are duplicates, rows with another category conflict with them`() {
        val file = parse(
            "# FinDesk rules v1",
            header,
            row("Groceries", "rewe"), // line 3: creates the rule
            row("Groceries", "rewe"), // line 4: duplicate of line 3
            row("Cash", "rewe"), // line 5: same pattern, another category -> conflict with line 3
            row("Groceries", "beispiel markt"), // line 6
        )

        val preview = service.preview(file)

        assertEquals(1, preview.duplicates)
        assertEquals(listOf(RuleImportConflict(5, "rewe", "Cash", "Groceries")), preview.conflicts)
        assertEquals(listOf(3, 6), preview.newRules.map { it.line })
    }

    @Test
    fun `matchCount respects priority order, search_in, applies_to, the existing rules and manual rules`() {
        val catSupply = data.category("Salary")
        data.ruleRepo.create("beispielpay", catSupply, priority = 5, matchField = RuleField.PARTNER, direction = RuleDirection.EXPENSE)
        val account = data.account()
        // Matched by the existing rule (partner, expense)
        data.txn(account, today, "-4.50", partner = "BeispielPay", purpose = "Kartenzahlung")
        // Matched by the file's income rule (partner, income)
        data.txn(account, today, "40.00", partner = "Muster GmbH", purpose = "Rueckzahlung")
        // Not matched: the INCOME rule ignores expenses
        data.txn(account, today, "-40.00", partner = "Muster GmbH")
        // Matched by the file's purpose rule only (purpose, income); a partner rule does not match it
        data.txn(account, today, "2500.00", partner = "BeispielPay", purpose = "Gehalt Beispiel GmbH")
        // Matched only by the file's manual rule, which never takes part in the simulation
        data.txn(account, today, "-9.99", partner = "Musterladen")
        // Uncategorized income that no rule matches
        data.txn(account, today, "1.23", partner = "BeispielPay")

        val preview = service.preview(
            parse(
                "# FinDesk rules v1",
                header,
                row("Salary", "muster gmbh", "partner", "income", "90", "true"),
                row("Other income", "gehalt", "purpose", "income", "40", "true"),
                row("Groceries", "musterladen", "partner", "any", "60", "false"),
            ),
        )

        assertEquals(3, preview.matchCount, "the partner expense (expense-only rule) and the unmatched income are not counted")
        assertEquals(3, preview.newRules.size, "rules are previewed even when they do not participate in the match")
    }

    @Test
    fun `applying creates the categories and rules with all their fields and categorizes only uncategorized bookings`() {
        val groceries = data.category("Groceries")
        val stuff = data.category("Stuff")
        val account = data.account()
        data.txn(account, today, "-42.00", partner = "Musterladen")
        val preCategorized = data.txn(account, today, "-7.77", partner = "Musterladen", categoryId = stuff)
        data.txn(account, today, "1500.00", partner = "Erika Mustermann")
        data.txn(account, today, "-0.01", partner = "Nirwana")

        val preview = service.preview(
            parse(
                "# FinDesk rules v1",
                header,
                row("Groceries", "musterladen", "partner", "expense", "60", "true"),
                row("Salary", "erika mustermann", "partner", "income", "70", "true"),
            ),
        )
        val result = service.apply(preview, categorize = true)

        assertEquals(1, result.categories)
        assertEquals(2, result.rules)
        assertEquals(2, result.categorized)

        val salary = CategoryRepositoryImpl().findAllItems().single { it.name == "Salary" }
        assertEquals(
            DefaultCategories.entries.single { it.first == StringKey.DefaultCategorySalary }.second,
            salary.color,
            "a file category named like a default category in the UI language gets that default colour",
        )

        val rules = RuleRepositoryImpl().findAllItems()
        assertEquals(2, rules.size)
        val salaryRule = rules.single { it.pattern == "erika mustermann" }
        assertEquals(salary.id, salaryRule.categoryId)
        assertEquals(70, salaryRule.priority)
        assertTrue(salaryRule.autoApply)
        assertEquals(RuleField.PARTNER, salaryRule.matchField)
        assertEquals(RuleDirection.INCOME, salaryRule.direction)
        assertEquals(60, rules.single { it.pattern == "musterladen" }.priority)

        val txnById = data.transactionRepo.findItems().associateBy { it.id }
        assertEquals(4, txnById.size)
        assertEquals(groceries, txnById.values.single { it.id != preCategorized && it.partnerName == "Musterladen" }.categoryId, "the uncategorized expense gets the rule's category")
        assertEquals(stuff, txnById.getValue(preCategorized).categoryId, "a booking that already has a category keeps it")
        assertEquals(salary.id, txnById.values.single { it.partnerName == "Erika Mustermann" }.categoryId)
        assertNull(txnById.values.single { it.partnerName == "Nirwana" }.categoryId, "an unmatched booking stays uncategorized")
    }

    @Test
    fun `categorize false creates categories and rules but touches no booking`() {
        data.category("Groceries")
        val account = data.account()
        data.txn(account, today, "-42.00", partner = "Musterladen")

        val preview = service.preview(parse("# FinDesk rules v1", header, row("Groceries", "musterladen", "partner", "expense", "60", "true")))
        val result = service.apply(preview, categorize = false)

        assertEquals(0, result.categorized)
        assertNull(data.transactionRepo.findItems().single().categoryId)
        assertEquals(1, RuleRepositoryImpl().findAllItems().size)
    }

    @Test
    fun `importing the same file twice adds nothing the second time`() {
        data.category("Groceries")
        val file = "# FinDesk rules v1\n" + header + "\n" + row("Groceries", "musterladen", "partner", "expense", "60", "true") + "\n"

        val first = service.apply(service.preview(RuleFile.parse(file.toByteArray())), categorize = true)
        assertEquals(1, first.rules)

        val secondPreview = service.preview(RuleFile.parse(file.toByteArray()))
        assertTrue(secondPreview.newRules.isEmpty() && secondPreview.categoriesToCreate.isEmpty())
        assertEquals(1, secondPreview.duplicates)
        val second = service.apply(secondPreview, categorize = true)
        assertEquals(0, second.categories)
        assertEquals(0, second.rules)
        assertEquals(0, second.categorized)
        assertEquals(1, RuleRepositoryImpl().findAllItems().size)
        assertEquals(1, CategoryRepositoryImpl().findAllItems().size)
    }

    @Test
    fun `import and export leave audit entries with counts only`() {
        val groceries = data.category("Groceries")
        data.txn(data.account(), today, "-4.50", partner = "Musterladen")

        val preview = service.preview(parse("# FinDesk rules v1", header, row("Groceries", "musterladen", "partner", "expense", "60", "true")))
        service.apply(preview, categorize = true)

        val imported = AuditLogRepositoryImpl().findByAction("IMPORT_RULES").single()
        assertEquals("Imported 1 rules, created 0 categories, categorized 1 transactions", imported.details)

        // A rule whose pattern holds a tab cannot be written and is counted
        RuleRepositoryImpl().create("bad\tstock", groceries)
        val written = service.export(File(tmp, "rules.tsv"))
        assertEquals(1, written)
        assertEquals("Exported 1 rules, skipped 1", AuditLogRepositoryImpl().findByAction("EXPORT_RULES").single().details)
    }

    @Test
    fun `export into a fresh database reproduces the same rules`() {
        val groceries = data.category("Groceries")
        val salary = data.category("Salary")
        val cash = data.category("Cash")
        data.ruleRepo.create("beispiel.?markt|musterladen", groceries, priority = 60)
        data.ruleRepo.create("beispiel gmbh", cash, priority = 20, matchField = RuleField.PARTNER)
        data.ruleRepo.create("beispiel gmbh", salary, priority = 70, matchField = RuleField.PARTNER, direction = RuleDirection.INCOME)
        data.ruleRepo.create("raw cash", cash, priority = 0, autoApply = false, matchField = RuleField.PURPOSE)
        // Cannot be written: pattern with a tab
        data.ruleRepo.create("bad\tstock", cash, priority = 100)

        val rules = RuleRepositoryImpl().findAllItems().filter { it.pattern != "bad\tstock" }
        val file = File(tmp, "findesk-rules-2026-10-24.tsv")
        assertEquals(4, service.export(file))

        // Fresh database: only the importer sees the exported file
        TestDatabase.init()
        val written = RuleFile.parse(file.readBytes())
        val preview = RuleImportService().preview(written)
        assertEquals(4, preview.newRules.size)
        RuleImportService().apply(preview, categorize = false)

        val restored = RuleRepositoryImpl().findAllItems().associateBy { it.pattern to it.priority }
        rules.forEach { original ->
            val copy = restored.getValue(original.pattern to original.priority)
            assertEquals(original.categoryName, copy.categoryName)
            assertEquals(original.priority, copy.priority)
            assertEquals(original.autoApply, copy.autoApply)
            assertEquals(original.matchField, copy.matchField)
            assertEquals(original.direction, copy.direction)
        }
    }
}
