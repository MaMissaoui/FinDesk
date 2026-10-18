package de.findesk.data

import de.findesk.data.database.DatabaseFactory
import de.findesk.data.table.*
import de.findesk.repository.AccountGroupRepositoryImpl
import de.findesk.repository.AccountRepositoryImpl
import de.findesk.repository.TransactionRepositoryImpl
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.TransactionManager
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
            SchemaUtils.create(*DatabaseFactory.tables)

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
            SchemaUtils.create(*DatabaseFactory.tables)

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

    @Test
    fun `a database without accounts name gets the column and existing rows read null`() {
        val dir = File(tempDir, "old-account-name").apply { mkdirs() }
        val db = Database.connect("jdbc:sqlite:${File(dir, "findesk.db").absolutePath}", driver = "org.sqlite.JDBC")
        transaction(db) {
            exec(
                "CREATE TABLE bank_access (id INTEGER PRIMARY KEY AUTOINCREMENT, bank_name VARCHAR(255) NOT NULL, blz VARCHAR(20) NOT NULL, " +
                    "bic VARCHAR(20) NOT NULL, user_id VARCHAR(100) NOT NULL, hbci_version VARCHAR(10) DEFAULT '300' NOT NULL, " +
                    "tan_method VARCHAR(50) NULL, last_sync TEXT NULL)",
            )
            exec(
                "CREATE TABLE accounts (id INTEGER PRIMARY KEY AUTOINCREMENT, bank_access_id INTEGER NOT NULL, iban VARCHAR(34) NOT NULL, " +
                    "account_number VARCHAR(34) NULL, balance DECIMAL(15,2) DEFAULT 0 NOT NULL, currency VARCHAR(3) DEFAULT 'EUR' NOT NULL, " +
                    "account_type VARCHAR(20) DEFAULT 'checking' NOT NULL, last_update TEXT NULL)",
            )
            exec("INSERT INTO bank_access (bank_name, blz, bic, user_id) VALUES ('Old Bank', '37050198', '', 'u')")
            exec("INSERT INTO accounts (bank_access_id, iban) VALUES (1, 'DE89370400440532013000')")
        }
        TransactionManager.closeAndUnregister(db)

        DatabaseFactory.init(dir.absolutePath, null)

        val account = AccountRepositoryImpl().findAllItems().single()
        assertEquals("DE89370400440532013000", account.iban)
        assertNull(account.name, "an existing row gets name = null")
    }

    @Test
    fun `a database without account groups gets the table and group_id and existing rows read null`() {
        val dir = File(tempDir, "old-account-groups").apply { mkdirs() }
        val db = Database.connect("jdbc:sqlite:${File(dir, "findesk.db").absolutePath}", driver = "org.sqlite.JDBC")
        transaction(db) {
            exec(
                "CREATE TABLE bank_access (id INTEGER PRIMARY KEY AUTOINCREMENT, bank_name VARCHAR(255) NOT NULL, blz VARCHAR(20) NOT NULL, " +
                    "bic VARCHAR(20) NOT NULL, user_id VARCHAR(100) NOT NULL, hbci_version VARCHAR(10) DEFAULT '300' NOT NULL, " +
                    "tan_method VARCHAR(50) NULL, last_sync TEXT NULL)",
            )
            exec(
                "CREATE TABLE accounts (id INTEGER PRIMARY KEY AUTOINCREMENT, bank_access_id INTEGER NOT NULL, iban VARCHAR(34) NOT NULL, " +
                    "account_number VARCHAR(34) NULL, balance DECIMAL(15,2) DEFAULT 0 NOT NULL, currency VARCHAR(3) DEFAULT 'EUR' NOT NULL, " +
                    "account_type VARCHAR(20) DEFAULT 'checking' NOT NULL, name VARCHAR(255) NULL, last_update TEXT NULL)",
            )
            exec("INSERT INTO bank_access (bank_name, blz, bic, user_id) VALUES ('Old Bank', '37050198', '', 'u')")
            exec("INSERT INTO accounts (bank_access_id, iban) VALUES (1, 'DE89370400440532013000')")
        }
        TransactionManager.closeAndUnregister(db)

        DatabaseFactory.init(dir.absolutePath, null)

        val account = AccountRepositoryImpl().findAllItems().single()
        assertNull(account.groupId, "an existing row gets groupId = null")
        transaction {
            assertTrue(AccountsTable.columns.map { it.name }.contains("group_id"), "the new column is added")
            val groupColumns = AccountGroupsTable.columns.map { it.name }
            assertTrue(groupColumns.contains("name"))
            assertTrue(groupColumns.contains("sort_order"))
        }
        assertTrue(AccountGroupRepositoryImpl().findAllItems().isEmpty(), "the new table exists and is empty")
    }

    @Test
    fun `a database without is_new gets the column and existing rows read false`() {
        val dir = File(tempDir, "old-is-new").apply { mkdirs() }
        val db = Database.connect("jdbc:sqlite:${File(dir, "findesk.db").absolutePath}", driver = "org.sqlite.JDBC")
        transaction(db) {
            exec("CREATE TABLE transactions (id INTEGER PRIMARY KEY AUTOINCREMENT, account_id INTEGER NOT NULL, date TEXT NOT NULL, amount DECIMAL(15,2) NOT NULL, currency VARCHAR(3) DEFAULT 'EUR' NOT NULL, partner_name VARCHAR(255) NULL, purpose TEXT NULL, category_id INTEGER NULL, document_id INTEGER NULL, hash VARCHAR(64) NOT NULL, created_at TEXT NOT NULL)")
            exec("INSERT INTO transactions (account_id, date, amount, hash, created_at) VALUES (1, '2026-10-01', -10.00, 'h1', '2026-10-01 10:00:00')")
        }
        TransactionManager.closeAndUnregister(db)

        DatabaseFactory.init(dir.absolutePath)

        transaction {
            assertTrue(TransactionsTable.columns.map { it.name }.contains("is_new"), "the new column is added")
            // The same statement updateSchema runs for a missing column: SQLite needs a default for a NOT NULL column
            val ddl = TransactionsTable.isNew.createStatement().joinToString(" ")
            assertTrue(ddl.contains("DEFAULT 0"), "the column is added with DEFAULT 0")
        }
        val rows = transaction { de.findesk.data.entity.Transaction.all().map { it.id.value to it.isNew } }
        assertEquals(listOf(1 to false), rows, "the old row is kept and reads false, never new")
        assertTrue(TransactionRepositoryImpl().countNew().isEmpty(), "an upgraded database has no unread bookings")
    }
}
