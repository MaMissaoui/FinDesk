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

    // PayPal shop views: the shop named in the purpose is tested before the original text.

    private val paypalPartner = "PayPal Europe S.a.r.l. et Cie S.C.A"

    private fun paypalPurpose(shop: String = "Beispiel Shop GmbH") = "EREF+R1 SVWZ+PP.471.PPX . $shop, Ihr Einkauf bei $shop"

    @Test
    fun `a partner rule for the shop beats the paypal rule whatever the rule order`() {
        // Listed as in the repository's priority order: the "PayPal" rule would sit first (priority 10), the "Beispiel Shop" rule last (priority 0)
        val paypalFirst = RuleMatcher(
            listOf(
                one("PayPal", RuleField.PARTNER, categoryId = 2),
                one("Beispiel Shop", RuleField.PARTNER, categoryId = 1),
            ),
        )

        assertEquals(1, paypalFirst.categoryFor(paypalPurpose(), paypalPartner, expense), "the shop view is tested before the original, so the PayPal rule never comes to it")
        assertEquals(1, paypalFirst.categoryFor(paypalPurpose(), paypalPartner, income))
    }

    @Test
    fun `with no shop rule the paypal rule applies through the original view`() {
        val matcher = RuleMatcher(listOf(one("PayPal", RuleField.PARTNER, categoryId = 2)))

        assertEquals(2, matcher.categoryFor(paypalPurpose(), paypalPartner, expense))
    }

    @Test
    fun `a purpose-only rule does not match in the shop view but can still match the original`() {
        val purposeOnly = RuleMatcher(listOf(one("Ihr Einkauf", RuleField.PURPOSE, categoryId = 3)))
        assertEquals(3, purposeOnly.categoryFor(paypalPurpose(), paypalPartner, expense), "matched in the original view; the shop view has no purpose")

        // In the shop view the purpose is null, so the PURPOSE rule cannot fire there and the shop rule wins
        val withShop = RuleMatcher(
            listOf(
                one("Ihr Einkauf", RuleField.PURPOSE, categoryId = 3),
                one("Beispiel", RuleField.PARTNER, categoryId = 1),
            ),
        )
        assertEquals(1, withShop.categoryFor(paypalPurpose(), paypalPartner, expense), "the shop rule in the shop view beats the purpose rule of the original view")
    }

    @Test
    fun `an income-only rule does not match an expense via the shop view`() {
        val matcher = RuleMatcher(listOf(one("Beispiel", RuleField.PARTNER, RuleDirection.INCOME, categoryId = 4)))

        assertNull(matcher.categoryFor(paypalPurpose(), paypalPartner, expense), "INCOME rejects the expense in the shop view too")
        assertEquals(4, matcher.categoryFor(paypalPurpose(), paypalPartner, income))
    }

    @Test
    fun `a non-paypal booking is matched exactly as before`() {
        val matcher = RuleMatcher(listOf(one("Beispiel", RuleField.PARTNER, categoryId = 5)))

        assertEquals(5, matcher.categoryFor("Kauf bei Beispiel", "Beispiel Shop GmbH", expense), "no PayPal partner, no shop view")
        assertNull(matcher.categoryFor("Ihr Einkauf bei Beispiel", null, expense), "a PayPal-like purpose without a PayPal partner stays rule-less")
    }
}
