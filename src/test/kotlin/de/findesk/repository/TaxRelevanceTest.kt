package de.findesk.repository

import de.findesk.TestData
import de.findesk.TestDatabase
import de.findesk.document.ReportService
import de.findesk.model.TaxSource
import de.findesk.model.TransactionFilter
import de.findesk.model.TransactionItem
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate

/**
 * The effective tax relevance of a booking: three layers, the highest that has a value wins — the user's manual
 * override, a rule's tax flag (evaluated at read time, for bookings of the rule's own category) and the category.
 */
class TaxRelevanceTest {
    private val today = LocalDate.of(2026, 6, 15)
    private lateinit var data: TestData
    private var account = 0
    private var taxCat = 0
    private var normalCat = 0

    @BeforeEach
    fun setup() {
        TestDatabase.init()
        data = TestData()
        account = data.account()
        taxCat = data.category("Office supplies", taxRelevant = true)
        normalCat = data.category("Groceries")
    }

    private fun txn(amount: String, categoryId: Int?, partner: String? = null, purpose: String? = null, date: LocalDate = today.minusDays(1)): Int = data.txn(account, date, amount, partner = partner, purpose = purpose, categoryId = categoryId)

    private fun item(id: Int): TransactionItem = data.transactionRepo.findItems().first { it.id == id }

    private fun items(): List<TransactionItem> = data.transactionRepo.findItems()

    // 1. Category layer

    @Test
    fun `the category layer decides when no rule or override does`() {
        val inTax = txn("-10.00", taxCat, partner = "Beispiel Shop")
        val inNormal = txn("-10.00", normalCat, partner = "Anderer Shop")
        val uncategorized = txn("-10.00", null, partner = "Dritter Shop")

        val byId = items().associateBy { it.id }
        with(byId[inTax]!!) {
            assertTrue(isTaxRelevant)
            assertEquals(TaxSource.CATEGORY, taxSource)
            assertNull(taxOverride)
            assertNull(taxRulePattern)
        }
        with(byId[inNormal]!!) {
            assertFalse(isTaxRelevant)
            assertEquals(TaxSource.CATEGORY, taxSource)
        }
        with(byId[uncategorized]!!) {
            assertFalse(isTaxRelevant, "no category means not tax relevant")
            assertEquals(TaxSource.NONE, taxSource)
        }
    }

    // 2. Rule layer

    @Test
    fun `a rule with a tax flag decides at read time for bookings of its own category`() {
        val flagged = txn("-10.00", normalCat, partner = "Beispiel Shop")
        data.ruleRepo.create("Beispiel", normalCat, taxRelevant = true)
        val unflagged = txn("-20.00", taxCat, partner = "Anderer Shop")
        data.ruleRepo.create("Anderer", taxCat, taxRelevant = false)

        val byId = items().associateBy { it.id }
        with(byId[flagged]!!) {
            assertTrue(isTaxRelevant, "a true flag makes a booking of a non-tax category relevant")
            assertEquals(TaxSource.RULE, taxSource)
            assertEquals("Beispiel", taxRulePattern)
        }
        with(byId[unflagged]!!) {
            assertFalse(isTaxRelevant, "a false flag makes a booking of a tax category not relevant")
            assertEquals(TaxSource.RULE, taxSource)
            assertEquals("Anderer", taxRulePattern)
        }
    }

    @Test
    fun `a rule of another category, a manual rule and a flag-less rule have no effect`() {
        // A matching rule whose category differs from the booking's category is ignored
        val otherCategory = txn("-10.00", normalCat, partner = "Beispiel Shop")
        data.ruleRepo.create("Beispiel", taxCat, taxRelevant = true)
        assertEquals(TaxSource.CATEGORY, item(otherCategory).taxSource)
        assertFalse(item(otherCategory).isTaxRelevant)

        // A rule without auto-apply never decides, and a flag-less rule is skipped in favour of a later one
        val manualOnly = txn("-20.00", normalCat, partner = "Erika Mustermann")
        data.ruleRepo.create("Erika", normalCat, priority = 10, autoApply = false, taxRelevant = true)
        assertEquals(TaxSource.CATEGORY, item(manualOnly).taxSource, "autoApply = false has no effect")

        val laterWins = txn("-30.00", normalCat, partner = "Beispiel Vertrieb")
        data.ruleRepo.create("Beispiel", normalCat, priority = 10)
        data.ruleRepo.create("Vertrieb", normalCat, priority = 5, taxRelevant = true)
        with(item(laterWins)) {
            assertTrue(isTaxRelevant, "a flag-less rule is skipped and a later tax rule can still decide")
            assertEquals(TaxSource.RULE, taxSource)
            assertEquals("Vertrieb", taxRulePattern, "the deciding rule is the one with the flag")
        }
    }

    @Test
    fun `two matching tax rules pick the higher priority, or the lower id on ties`() {
        val highPriority = txn("-10.00", normalCat, partner = "Beispiel Shop")
        data.ruleRepo.create("Beispiel", normalCat, priority = 10, taxRelevant = false)
        data.ruleRepo.create("Beispiel", normalCat, priority = 20, taxRelevant = true)
        with(item(highPriority)) {
            assertTrue(isTaxRelevant, "priority 20 beats priority 10")
            assertEquals(TaxSource.RULE, taxSource)
        }

        val samePriority = txn("-20.00", normalCat, partner = "Erika Mustermann")
        data.ruleRepo.create("Erika", normalCat, priority = 5, taxRelevant = false)
        data.ruleRepo.create("Erika", normalCat, priority = 5, taxRelevant = true)
        with(item(samePriority)) {
            assertFalse(isTaxRelevant, "on equal priority the lower id wins")
            assertEquals(TaxSource.RULE, taxSource)
            assertEquals("Erika", taxRulePattern, "the first created rule of the tie decided")
        }
    }

    @Test
    fun `the rule's text field and direction are honoured`() {
        // A PARTNER rule does not match a purpose that only mentions the shop
        val partnerOnly = txn("-10.00", normalCat, partner = "Buchhaltung GmbH", purpose = "Beispiel Shop Rechnung")
        data.ruleRepo.create("Beispiel Shop", normalCat, matchField = de.findesk.model.RuleField.PARTNER, taxRelevant = true)
        with(item(partnerOnly)) {
            assertFalse(isTaxRelevant, "the PARTNER rule only searches the partner name")
            assertEquals(TaxSource.CATEGORY, taxSource)
        }

        // An EXPENSE rule does not match income; its own pattern keeps the PARTNER rule above out of the picture
        val income = txn("100.00", normalCat, partner = "Beispiel Laden")
        data.ruleRepo.create("Beispiel Laden", normalCat, direction = de.findesk.model.RuleDirection.EXPENSE, taxRelevant = true)
        with(item(income)) {
            assertFalse(isTaxRelevant, "the EXPENSE rule never matches an income booking")
            assertEquals(TaxSource.CATEGORY, taxSource)
        }

        // The same rules do decide for the bookings they are scoped to
        val partnerMatch = txn("-20.00", normalCat, partner = "Beispiel Shop GmbH")
        assertTrue(item(partnerMatch).isTaxRelevant)
        assertEquals(TaxSource.RULE, item(partnerMatch).taxSource)
        val expenseMatch = txn("-30.00", normalCat, partner = "Beispiel Laden")
        assertTrue(item(expenseMatch).isTaxRelevant)
        assertEquals(TaxSource.RULE, item(expenseMatch).taxSource)
    }

    // 3. A rule flag set later applies to existing bookings

    @Test
    fun `setting a rule's tax flag changes existing bookings without any re-apply`() {
        val booking = txn("-10.00", normalCat, partner = "Beispiel Shop")
        val rule = data.ruleRepo.create("Beispiel", normalCat)
        assertFalse(item(booking).isTaxRelevant)
        assertEquals(TaxSource.CATEGORY, item(booking).taxSource)

        assertTrue(data.ruleRepo.setTaxRelevant(rule.id.value, true))
        with(item(booking)) {
            assertTrue(isTaxRelevant, "the flag applies the moment it is saved")
            assertEquals(TaxSource.RULE, taxSource)
            assertEquals("Beispiel", taxRulePattern)
        }

        assertTrue(data.ruleRepo.setTaxRelevant(rule.id.value, null))
        with(item(booking)) {
            assertFalse(isTaxRelevant, "resetting to null restores the category layer")
            assertEquals(TaxSource.CATEGORY, taxSource)
            assertNull(taxRulePattern)
        }
    }

    // 4. Override layer

    @Test
    fun `the override wins over rule and category, and null returns to automatic`() {
        val booking = txn("-10.00", normalCat, partner = "Beispiel Shop")
        data.ruleRepo.create("Beispiel", normalCat, taxRelevant = true)
        assertTrue(item(booking).isTaxRelevant, "the rule makes it relevant first")

        assertEquals(1, data.transactionRepo.setTaxOverride(listOf(booking), false))
        with(item(booking)) {
            assertFalse(isTaxRelevant, "the override beats the rule")
            assertEquals(TaxSource.MANUAL, taxSource)
            assertEquals(false, taxOverride)
            assertNull(taxRulePattern, "no rule pattern when the override decides")
        }

        assertEquals(1, data.transactionRepo.setTaxOverride(listOf(booking), true))
        assertTrue(item(booking).isTaxRelevant)
        assertEquals(TaxSource.MANUAL, item(booking).taxSource)

        assertEquals(1, data.transactionRepo.setTaxOverride(listOf(booking), null))
        with(item(booking)) {
            assertTrue(isTaxRelevant, "null returns to the automatic result")
            assertEquals(TaxSource.RULE, taxSource)
            assertNull(taxOverride)
        }
    }

    @Test
    fun `the override survives a category change`() {
        val booking = txn("-10.00", normalCat)
        assertEquals(1, data.transactionRepo.setTaxOverride(listOf(booking), true))

        assertNotNull(data.transactionRepo.updateCategory(booking, taxCat))

        with(item(booking)) {
            assertTrue(isTaxRelevant)
            assertEquals(TaxSource.MANUAL, taxSource, "nothing but the user ever writes the override")
            assertEquals(true, taxOverride)
        }
    }

    @Test
    fun `setTaxOverride chunks more than 1200 ids, returns the count and ignores empty and unknown ids`() {
        val bookings = (1..1300).map { n ->
            NewTransaction(
                accountId = account,
                date = today.minusDays(1),
                amount = BigDecimal("-$n.00"),
                currency = "EUR",
                partnerName = "Shop $n",
                purpose = null,
                categoryId = normalCat,
                hash = "tax-chunk-$n",
            )
        }
        assertEquals(1300, data.transactionRepo.createAll(bookings))
        val ids = data.transactionRepo.findItems().map { it.id }
        val unknown = listOf(999_001, 999_002, 999_003)

        assertEquals(1300, data.transactionRepo.setTaxOverride(ids + unknown, true), "unknown ids are ignored, the rest counted")
        assertEquals(1300, items().count { it.taxSource == TaxSource.MANUAL && it.taxOverride == true })

        assertEquals(0, data.transactionRepo.setTaxOverride(emptyList(), true), "an empty collection does nothing")
        assertEquals(0, data.transactionRepo.setTaxOverride(unknown, true), "unknown ids alone change nothing")
        assertEquals(1300, items().count { it.taxSource == TaxSource.MANUAL })
    }

    // 5. taxRelevantOnly

    @Test
    fun `taxRelevantOnly returns exactly the effectively relevant bookings, limit after the filter`() {
        val byCategory = txn("-10.00", taxCat, partner = "Steuer Shop")
        val byRule = txn("-20.00", normalCat, partner = "Beispiel Shop")
        data.ruleRepo.create("Beispiel", normalCat, taxRelevant = true)
        val overriddenFalse = txn("-30.00", taxCat, partner = "Widerspruch Shop")
        val plain = txn("-40.00", normalCat, partner = "Normaler Shop")
        assertEquals(1, data.transactionRepo.setTaxOverride(listOf(overriddenFalse), false))

        val relevant = data.transactionRepo.findItems(TransactionFilter(taxRelevantOnly = true)).map { it.id }
        assertEquals(setOf(byCategory, byRule), relevant.toSet(), "rule-flagged in, overridden-to-false out")
        assertTrue(plain !in relevant)

        val limited = data.transactionRepo.findItems(TransactionFilter(taxRelevantOnly = true), limit = 1)
        assertEquals(1, limited.size, "the limit applies after the filter")
        assertEquals(relevant.first(), limited.single().id, "the newest relevant booking")
    }

    // 6. Readers see the effective value

    @Test
    fun `the report counts a rule-flagged booking of a non-tax category among the tax-relevant expenses`() {
        val booking = txn("-100.00", normalCat, partner = "Beispiel Shop")
        data.ruleRepo.create("Beispiel", normalCat, taxRelevant = true)

        val report = ReportService().buildReport(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), items())

        assertTrue(report.taxRelevantTransactions.any { it.id == booking }, "the report reads the effective value")
        assertEquals(BigDecimal("100.00"), report.taxRelevantTotal)
    }

    // 7. PayPal: the rule also matches the shop the purpose names (the shop view of TaxRuleSpec)

    @Test
    fun `a tax-flagged shop rule makes paypal bookings of its category tax relevant, through the item mapping`() {
        val byRule = txn(
            "-10.00",
            normalCat,
            partner = "PayPal Europe S.a.r.l. et Cie S.C.A",
            purpose = "EREF+R1 SVWZ+PP.471.PPX . Beispiel Shop GmbH, Ihr Einkauf bei Beispiel Shop GmbH",
        )
        val otherShop = txn(
            "-20.00",
            normalCat,
            partner = "PayPal Europe S.a.r.l. et Cie S.C.A",
            purpose = "EREF+R2 SVWZ+PP.472.PPX . Anderer Shop GmbH, Ihr Einkauf bei Anderer Shop GmbH",
        )
        data.ruleRepo.create("Beispiel Shop", normalCat, matchField = de.findesk.model.RuleField.PARTNER, taxRelevant = true)

        with(item(byRule)) {
            assertTrue(isTaxRelevant, "the PARTNER rule matches the shop extracted from the purpose")
            assertEquals(TaxSource.RULE, taxSource)
            assertEquals("Beispiel Shop", taxRulePattern)
        }
        with(item(otherShop)) {
            assertFalse(isTaxRelevant, "a shop the rule does not name stays on the category layer")
            assertEquals(TaxSource.CATEGORY, taxSource)
        }
    }

    @Test
    fun `a provider rule gives way to a same-category shop tax rule`() {
        val booking = txn(
            "-10.00",
            taxCat,
            partner = "PayPal Europe S.a.r.l. et Cie S.C.A",
            purpose = "EREF+R1 SVWZ+PP.471.PPX . Beispiel Shop GmbH, Ihr Einkauf bei Beispiel Shop GmbH",
        )
        data.ruleRepo.create("PayPal", taxCat, priority = 10, matchField = de.findesk.model.RuleField.PARTNER, taxRelevant = false)
        data.ruleRepo.create("Beispiel Shop", taxCat, priority = 0, matchField = de.findesk.model.RuleField.PARTNER, taxRelevant = true)

        with(item(booking)) {
            assertTrue(isTaxRelevant, "the shop rule, not the PayPal rule, decides the tax flag")
            assertEquals(TaxSource.RULE, taxSource)
            assertEquals("Beispiel Shop", taxRulePattern)
        }
    }
}
