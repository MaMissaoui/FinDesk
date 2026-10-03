package de.findesk.repository

import de.findesk.data.database.DatabaseFactory
import de.findesk.data.entity.Category
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import java.math.BigDecimal

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CategoryRepositoryTest {
    private lateinit var repository: CategoryRepository

    @BeforeAll
    fun setup() {
        Database.connect("jdbc:sqlite::memory:", driver = "org.sqlite.JDBC")
        DatabaseFactory.init()
        repository = CategoryRepositoryImpl()
    }

    @Test
    fun `test create category`() {
        val category = repository.create(
            name = "Groceries",
            color = "#FF5722",
            budgetLimit = BigDecimal("500.00"),
            isTaxRelevant = false,
        )

        assertNotNull(category)
        assertEquals("Groceries", category.name)
        assertEquals("#FF5722", category.color)
        assertEquals(BigDecimal("500.00"), category.budgetLimit)
        assertFalse(category.isTaxRelevant)
    }

    @Test
    fun `test find by name`() {
        repository.create("Transport", "#2196F3")
        val found = repository.findByName("Transport")

        assertNotNull(found)
        assertEquals("Transport", found!!.name)
    }

    @Test
    fun `test find tax relevant`() {
        repository.create("Office Supplies", "#4CAF50", isTaxRelevant = true)
        repository.create("Entertainment", "#9C27B0", isTaxRelevant = false)

        val taxRelevant = repository.findTaxRelevant()
        assertTrue(taxRelevant.any { it.name == "Office Supplies" })
    }

    @Test
    fun `test update category`() {
        val category = repository.create("Shopping", "#E91E63")
        val updated = repository.update(
            category.id.value,
            budgetLimit = BigDecimal("1000.00"),
            isTaxRelevant = true,
        )

        assertNotNull(updated)
        assertEquals(BigDecimal("1000.00"), updated!!.budgetLimit)
        assertTrue(updated.isTaxRelevant)
    }

    @Test
    fun `test delete category`() {
        val category = repository.create("ToDelete", "#000000")
        assertTrue(repository.delete(category.id.value))
        assertNull(repository.findById(category.id.value))
    }

    @Test
    fun `test find all`() {
        val all = repository.findAll()
        assertTrue(all.isNotEmpty())
    }
}
