package de.findesk.repository

import de.findesk.data.database.DatabaseFactory
import de.findesk.data.table.*
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import java.io.File

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RuleRepositoryTest {
    private lateinit var ruleRepo: RuleRepository
    private lateinit var categoryRepo: CategoryRepository
    private var testCategoryId: Int = 0
    private var testCategoryId2: Int = 0
    private lateinit var dbFile: File

    @BeforeAll
    fun setup() {
        dbFile = File.createTempFile("findesk-test-rules", ".db")
        dbFile.deleteOnExit()
        Database.connect("jdbc:sqlite:${dbFile.absolutePath}", driver = "org.sqlite.JDBC")

        transaction {
            SchemaUtils.create(*DatabaseFactory.tables)
        }

        ruleRepo = RuleRepositoryImpl()
        categoryRepo = CategoryRepositoryImpl()

        val cat1 = categoryRepo.create("Groceries", "#FF5722")
        testCategoryId = cat1.id.value

        val cat2 = categoryRepo.create("Shopping", "#2196F3")
        testCategoryId2 = cat2.id.value
    }

    @AfterAll
    fun cleanup() {
        dbFile.delete()
    }

    @Test
    fun `test create rule`() {
        val rule = ruleRepo.create(
            pattern = ".*rewe.*|.*edeka.*",
            categoryId = testCategoryId,
            priority = 10,
            autoApply = true,
        )

        assertNotNull(rule)
        assertEquals(".*rewe.*|.*edeka.*", rule.pattern)
        assertEquals(10, rule.priority)
        assertTrue(rule.autoApply)
    }

    @Test
    fun `test match transaction`() {
        ruleRepo.create(".*rewe.*|.*edeka.*|.*aldi.*", testCategoryId, priority = 10)
        ruleRepo.create(".*amazon.*|.*ebay.*", testCategoryId2, priority = 5)

        val match1 = ruleRepo.matchTransaction("REWE SAGT DANKE", null)
        assertNotNull(match1)
        assertEquals("Groceries", match1!!.name)
    }
}
