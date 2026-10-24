package de.findesk.repository

import de.findesk.data.database.DatabaseFactory
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files

/** The transfer flag lives with the category and rides the normal create/update round trip. */
class CategoryTransferRepositoryTest {
    @TempDir
    lateinit var tmp: File

    private lateinit var repo: CategoryRepositoryImpl

    @BeforeEach
    fun setup() {
        DatabaseFactory.close()
        de.findesk.TestDatabase.init()
        repo = CategoryRepositoryImpl()
    }

    @Test
    fun `create and update round-trip the flag`() {
        val marked = repo.create("Own transfers", "#4CAF50", isTransfer = true)
        assertEquals(marked.id.value, repo.findAllItems().single { it.id == marked.id.value }.id)
        assertTrue(repo.findAllItems().single { it.id == marked.id.value }.isTransfer)

        repo.update(marked.id.value, isTransfer = false)
        assertFalse(repo.findAllItems().single { it.id == marked.id.value }.isTransfer)

        repo.update(marked.id.value, isTransfer = true)
        assertTrue(repo.findAllItems().single { it.id == marked.id.value }.isTransfer)

        assertNull(repo.update(-1, isTransfer = true))
    }

    @Test
    fun `a category created without the flag reads false`() {
        val plain = repo.create("Groceries", "#FF5722")
        assertFalse(repo.findAllItems().single { it.id == plain.id.value }.isTransfer)
        assertFalse(repo.findById(plain.id.value)!!.isTransfer)
    }

    @Test
    fun `an old table without the column migrates and reads false`() {
        File(tmp, "findesk.db").delete()
        val db = File(tmp, "findesk.db")
        java.sql.DriverManager.getConnection("jdbc:sqlite:${db.absolutePath}").use { connection ->
            connection.createStatement().use { statement ->
                // Exactly the pre-is_transfer schema, without any newer column
                statement.execute("CREATE TABLE categories (id INTEGER PRIMARY KEY AUTOINCREMENT, name VARCHAR(100) NOT NULL, color VARCHAR(7) NOT NULL, budget_limit DECIMAL(15,2) NULL, is_tax_relevant BOOLEAN NOT NULL DEFAULT 0)")
                statement.execute("INSERT INTO categories (name, color, is_tax_relevant) VALUES ('BEISPIEL MARKT', '#FF5722', 0)")
            }
        }
        DatabaseFactory.close()
        DatabaseFactory.init(tmp.absolutePath)

        val items = CategoryRepositoryImpl().findAllItems()
        assertEquals(1, items.size)
        assertEquals("BEISPIEL MARKT", items.single().name)
        assertFalse(items.single().isTransfer, "existing categories are never transfers until the user says so")
    }
}
