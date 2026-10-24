package de.findesk.data

import de.findesk.data.database.DatabaseFactory
import de.findesk.model.RuleDirection
import de.findesk.model.RuleField
import de.findesk.repository.RuleRepositoryImpl
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** Existing databases have no `match_field`/`direction` columns; the migration must add both and read them as ANY. */
class RulesColumnsMigrationTest {
    @TempDir
    lateinit var tmp: File

    @Test
    fun `existing databases get the new rule scope columns`() {
        val dir = File(tmp, "old-rules").apply { mkdirs() }
        val db = Database.connect("jdbc:sqlite:${File(dir, "findesk.db").absolutePath}", driver = "org.sqlite.JDBC")
        transaction(db) {
            exec("CREATE TABLE categories (id INTEGER PRIMARY KEY AUTOINCREMENT, name VARCHAR(100) NOT NULL, color VARCHAR(7) DEFAULT '#3BC7B0' NOT NULL, budget_limit NUMERIC NULL, is_tax_relevant BOOLEAN DEFAULT 0 NOT NULL)")
            exec("CREATE TABLE rules (id INTEGER PRIMARY KEY AUTOINCREMENT, pattern TEXT NOT NULL, category_id INTEGER NOT NULL REFERENCES categories(id), priority INTEGER DEFAULT 0 NOT NULL, auto_apply BOOLEAN DEFAULT 1 NOT NULL)")
            exec("INSERT INTO categories (name) VALUES ('Groceries')")
            exec("INSERT INTO rules (pattern, category_id) VALUES ('Erika Mustermann', 1)")
        }
        TransactionManager.closeAndUnregister(db)

        // The normal init path runs the schema update, which must add the columns with their defaults
        DatabaseFactory.init(dir.absolutePath)

        val item = RuleRepositoryImpl().findAllItems().single()
        assertEquals("Erika Mustermann", item.pattern)
        assertEquals(RuleField.ANY, item.matchField)
        assertEquals(RuleDirection.ANY, item.direction)
        // The old row still categorizes as before
        assertEquals(1, RuleRepositoryImpl().matchCategoryId(purpose = null, partnerName = "Erika Mustermann"))
    }
}
