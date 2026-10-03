package de.findesk.data

import de.findesk.data.database.DatabaseFactory
import de.findesk.data.table.*
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import java.io.File

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DatabaseFactoryTest {
    private lateinit var tempDir: File

    @BeforeAll
    fun setup() {
        tempDir = createTempDir("findesk-test")
    }

    @AfterAll
    fun cleanup() {
        tempDir.deleteRecursively()
    }

    @Test
    fun `test database initialization creates all tables`() {
        // Use in-memory database for testing
        Database.connect("jdbc:sqlite::memory:", driver = "org.sqlite.JDBC")

        transaction {
            SchemaUtils.create(
                BankAccessTable,
                AccountsTable,
                TransactionsTable,
                CategoriesTable,
                RulesTable,
                DocumentsTable,
                OcrTextsTable,
                SettingsTable,
                PluginsTable,
                AuditLogTable,
            )

            // Verify tables were created by checking columns exist
            val bankAccessColumns = BankAccessTable.columns.map { it.name }
            assertTrue(bankAccessColumns.contains("id"))
            assertTrue(bankAccessColumns.contains("bank_name"))
            assertTrue(bankAccessColumns.contains("blz"))

            val accountColumns = AccountsTable.columns.map { it.name }
            assertTrue(accountColumns.contains("id"))
            assertTrue(accountColumns.contains("iban"))
            assertTrue(accountColumns.contains("balance"))

            val transactionColumns = TransactionsTable.columns.map { it.name }
            assertTrue(transactionColumns.contains("id"))
            assertTrue(transactionColumns.contains("amount"))
            assertTrue(transactionColumns.contains("partner_name"))
        }
    }

    @Test
    fun `test database table schemas have correct columns`() {
        Database.connect("jdbc:sqlite::memory:", driver = "org.sqlite.JDBC")

        transaction {
            SchemaUtils.create(
                BankAccessTable,
                AccountsTable,
                TransactionsTable,
                CategoriesTable,
                RulesTable,
                DocumentsTable,
                OcrTextsTable,
                SettingsTable,
                PluginsTable,
                AuditLogTable,
            )

            // Verify BankAccessTable columns
            val bankAccessColumns = BankAccessTable.columns.map { it.name }
            assertTrue(bankAccessColumns.contains("id"))
            assertTrue(bankAccessColumns.contains("bank_name"))
            assertTrue(bankAccessColumns.contains("blz"))
            assertTrue(bankAccessColumns.contains("bic"))
            assertTrue(bankAccessColumns.contains("user_id"))
            assertTrue(bankAccessColumns.contains("hbci_version"))
            assertTrue(bankAccessColumns.contains("tan_method"))
            assertTrue(bankAccessColumns.contains("last_sync"))

            // Verify AccountsTable columns
            val accountColumns = AccountsTable.columns.map { it.name }
            assertTrue(accountColumns.contains("id"))
            assertTrue(accountColumns.contains("bank_access_id"))
            assertTrue(accountColumns.contains("iban"))
            assertTrue(accountColumns.contains("balance"))
            assertTrue(accountColumns.contains("currency"))
            assertTrue(accountColumns.contains("account_type"))

            // Verify TransactionsTable columns
            val transactionColumns = TransactionsTable.columns.map { it.name }
            assertTrue(transactionColumns.contains("id"))
            assertTrue(transactionColumns.contains("account_id"))
            assertTrue(transactionColumns.contains("date"))
            assertTrue(transactionColumns.contains("amount"))
            assertTrue(transactionColumns.contains("partner_name"))
            assertTrue(transactionColumns.contains("purpose"))
            assertTrue(transactionColumns.contains("category_id"))
            assertTrue(transactionColumns.contains("hash"))
        }
    }
}
