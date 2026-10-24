package de.findesk.repository

import de.findesk.TestData
import de.findesk.TestDatabase
import de.findesk.model.RuleDirection
import de.findesk.model.RuleField
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.math.BigDecimal
import java.nio.file.Files

/** Rules with a limited [RuleField] or [RuleDirection] keep their scope in the database. */
class RuleScopeRepositoryTest {
    @TempDir
    lateinit var tmp: File

    private lateinit var data: TestData
    private var groceries = 0
    private var savings = 0

    @BeforeEach
    fun setup() {
        TestDatabase.init()
        data = TestData()
        groceries = data.category("Groceries")
        savings = data.category("Savings")
    }

    @Test
    fun `create stores field and direction`() {
        val rule = data.ruleRepo.create("Erika Mustermann", groceries, priority = 5, matchField = RuleField.PARTNER, direction = RuleDirection.EXPENSE)

        val item = data.ruleRepo.findAllItems().single()
        assertEquals(rule.id.value, item.id)
        assertEquals(RuleField.PARTNER, item.matchField)
        assertEquals(RuleDirection.EXPENSE, item.direction)
    }

    @Test
    fun `defaults read as any`() {
        data.ruleRepo.create("REWE", groceries)

        val item = data.ruleRepo.findAllItems().single()
        assertEquals(RuleField.ANY, item.matchField)
        assertEquals(RuleDirection.ANY, item.direction)
    }

    @Test
    fun `update stores field and direction and null leaves them unchanged`() {
        val rule = data.ruleRepo.create("Erika", groceries, matchField = RuleField.PARTNER, direction = RuleDirection.EXPENSE)
        val id = rule.id.value

        data.ruleRepo.update(id, matchField = RuleField.PURPOSE, direction = RuleDirection.INCOME)
        val item = data.ruleRepo.findAllItems().single()
        assertEquals(RuleField.PURPOSE, item.matchField)
        assertEquals(RuleDirection.INCOME, item.direction)

        // Null means unchanged
        data.ruleRepo.update(id, pattern = "Beispiel Laden")
        val after = data.ruleRepo.findAllItems().single()
        assertEquals(RuleField.PURPOSE, after.matchField)
        assertEquals(RuleDirection.INCOME, after.direction)
    }

    @Test
    fun `same priority comes back in id order`() {
        data.ruleRepo.create("second", savings, priority = 5)
        data.ruleRepo.create("first", groceries, priority = 5)
        data.ruleRepo.create("lower", groceries, priority = 1)

        assertEquals(listOf("second", "first", "lower"), data.ruleRepo.findAllItems().map { it.pattern })
        // The matcher works on the auto-apply rules; the lowest id of the tied priority wins
        assertEquals(savings, data.ruleRepo.matchCategoryId(purpose = null, partnerName = "second", amount = BigDecimal("-1.00")))
    }

    @Test
    fun `a scoped rule does not match outside its direction`() {
        data.ruleRepo.create("Erika", groceries, matchField = RuleField.PARTNER, direction = RuleDirection.EXPENSE)

        assertNull(data.ruleRepo.matchCategoryId(purpose = "Gehalt Erika", partnerName = "BEISPIEL GMBH", amount = BigDecimal("2500.00")))
        assertEquals(groceries, data.ruleRepo.matchCategoryId(purpose = null, partnerName = "Erika Mustermann", amount = BigDecimal("-4.50")))
    }
}
