package de.findesk.repository

import de.findesk.model.RuleDirection
import de.findesk.model.RuleField
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/** Rules match only the text their [RuleSpec.field] allows and only the money flow their [RuleSpec.direction] allows. */
class RuleMatcherTest {
    private val expense = BigDecimal("-10.00")
    private val income = BigDecimal("10.00")

    private fun one(pattern: String, field: RuleField = RuleField.ANY, direction: RuleDirection = RuleDirection.ANY, categoryId: Int = 1) = RuleSpec(pattern, categoryId, field, direction)

    @Test
    fun `partner rule matches the partner name but not a purpose that only mentions it`() {
        val matcher = RuleMatcher(listOf(one("Erika Mustermann", RuleField.PARTNER)))

        assertEquals(1, matcher.categoryFor(purpose = null, partnerName = "Erika Mustermann", amount = expense))
        // Synthetic partner: the salary names the person in the purpose, the partner is the employer
        assertNull(matcher.categoryFor(purpose = "Gehalt Erika Mustermann", partnerName = "BEISPIEL GMBH"))
        assertNull(matcher.categoryFor(purpose = null, partnerName = null))
    }

    @Test
    fun `purpose rule ignores the partner name`() {
        val matcher = RuleMatcher(listOf(one("Gehalt", RuleField.PURPOSE)))

        assertEquals(1, matcher.categoryFor(purpose = "Gehalt Oktober", partnerName = "BEISPIEL GMBH", amount = income))
        assertNull(matcher.categoryFor(purpose = "Kartenzahlung", partnerName = "Gehaltshaus", amount = expense))
        assertNull(matcher.categoryFor(purpose = null, partnerName = "Gehalt", amount = income))
    }

    @Test
    fun `any rule searches purpose and partner together as before`() {
        val matcher = RuleMatcher(listOf(one("REWE")))

        assertEquals(1, matcher.categoryFor("REWE SAGT DANKE", null))
        assertEquals(1, matcher.categoryFor(null, "REWE Markt GmbH"))
        assertEquals(1, matcher.categoryFor("rewe", "Edeka"))
    }

    @Test
    fun `blank text does not match`() {
        val matcher = RuleMatcher(listOf(one("REWE", RuleField.PURPOSE)))

        assertNull(matcher.categoryFor(purpose = null, partnerName = "REWE Markt GmbH"))
    }

    @Test
    fun `expense rule matches negative amounts only`() {
        val matcher = RuleMatcher(listOf(one("Beispiel", direction = RuleDirection.EXPENSE)))

        assertEquals(1, matcher.categoryFor("Beispiel", null, expense))
        assertNull(matcher.categoryFor("Beispiel", null, income))
        assertNull(matcher.categoryFor("Beispiel", null, null), "with amount == null only ANY rules can match")
    }

    @Test
    fun `income rule matches positive amounts only`() {
        val matcher = RuleMatcher(listOf(one("Beispiel", direction = RuleDirection.INCOME)))

        assertEquals(1, matcher.categoryFor("Beispiel", null, income))
        assertNull(matcher.categoryFor("Beispiel", null, expense))
        assertNull(matcher.categoryFor("Beispiel", null, null))
    }

    @Test
    fun `first rule in order wins when an earlier rule is excluded by direction`() {
        val matcher = RuleMatcher(
            listOf(
                one("beispielpay", direction = RuleDirection.INCOME, categoryId = 2),
                one("beispielpay", categoryId = 3),
            ),
        )

        assertEquals(3, matcher.categoryFor(null, "BeispielPay", expense), "the income rule is skipped, the ANY rule matches")
        assertEquals(2, matcher.categoryFor(null, "BeispielPay", income), "the earlier rule in the order wins")
    }

    @Test
    fun `of() creates unrestricted rules as before`() {
        val matcher = RuleMatcher.of(listOf("erika" to 7))

        // Matched with amount == null (direction ANY) and in a purpose-only context (field ANY searches the purpose too)
        assertEquals(7, matcher.categoryFor(purpose = "Überweisung an Erika", partnerName = null, amount = null))
    }

    @Test
    fun `a pattern with an umlaut matches case-insensitively`() {
        val matcher = RuleMatcher(listOf(one("Bäckerei", RuleField.PARTNER)))

        assertEquals(1, matcher.categoryFor(null, "BÄCKEREI SCHMIDT"))
    }
}
