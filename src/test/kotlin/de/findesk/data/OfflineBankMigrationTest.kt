package de.findesk.data

import de.findesk.data.database.DatabaseFactory
import de.findesk.repository.BankAccessRepositoryImpl
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** Existing databases have no `offline` column; the migration must add it and keep the bank online. */
class OfflineBankMigrationTest {
    @TempDir
    lateinit var tmp: File

    @Test
    fun `existing databases get the new offline column`() {
        val dir = File(tmp, "old-offline").apply { mkdirs() }
        val db = Database.connect("jdbc:sqlite:${File(dir, "findesk.db").absolutePath}", driver = "org.sqlite.JDBC")
        transaction(db) {
            exec("CREATE TABLE bank_access (id INTEGER PRIMARY KEY AUTOINCREMENT, bank_name VARCHAR(255) NOT NULL, blz VARCHAR(20) NOT NULL, bic VARCHAR(20) NOT NULL, user_id VARCHAR(100) NOT NULL, hbci_version VARCHAR(10) DEFAULT '300' NOT NULL, tan_method VARCHAR(50) NULL, last_sync TEXT NULL)")
            exec("INSERT INTO bank_access (bank_name, blz, bic, user_id) VALUES ('Old Bank', '37050198', '', 'u')")
        }
        TransactionManager.closeAndUnregister(db)

        // The normal init path runs the schema update, which must add the column with its default
        DatabaseFactory.init(dir.absolutePath)

        val bank = BankAccessRepositoryImpl().findAllItems().single()
        assertEquals("Old Bank", bank.bankName)
        assertEquals(false, bank.offline, "an existing bank is online")
        assertTrue(bank.includeInSyncAll)
    }
}
