package de.findesk.repository

import de.findesk.data.table.*
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import java.io.File
import java.math.BigDecimal
import java.time.LocalDate

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TransactionRepositoryTest {
    private lateinit var transactionRepo: TransactionRepository
    private lateinit var accountRepo: AccountRepository
    private lateinit var bankAccessRepo: BankAccessRepository
    private lateinit var categoryRepo: CategoryRepository
    private var testAccountId: Int = 0
    private var testCategoryId: Int = 0
    private lateinit var dbFile: File

    @BeforeAll
    fun setup() {
        dbFile = File.createTempFile("findesk-test-transactions", ".db")
        dbFile.deleteOnExit()
        Database.connect("jdbc:sqlite:${dbFile.absolutePath}", driver = "org.sqlite.JDBC")

        transaction {
            SchemaUtils.create(
                BankAccessTable,
                AccountsTable,
                CategoriesTable,
                TransactionsTable,
                RulesTable,
                DocumentsTable,
                OcrTextsTable,
                SettingsTable,
                PluginsTable,
                AuditLogTable,
            )
        }

        transactionRepo = TransactionRepositoryImpl()
        accountRepo = AccountRepositoryImpl()
        bankAccessRepo = BankAccessRepositoryImpl()
        categoryRepo = CategoryRepositoryImpl()

        val bankAccess = bankAccessRepo.create("Test Bank", "12345678", "TESTDEFF", "testuser")
        val account = accountRepo.create(bankAccess.id.value, "DE89370400440532013000")
        testAccountId = account.id.value

        val category = categoryRepo.create("Groceries", "#FF5722")
        testCategoryId = category.id.value
    }

    @AfterAll
    fun cleanup() {
        dbFile.delete()
    }

    @Test
    fun `test create transaction`() {
        val txn = transactionRepo.create(
            accountId = testAccountId,
            date = LocalDate.now(),
            amount = BigDecimal("-49.99"),
            partnerName = "REWE",
            purpose = "Grocery shopping",
            categoryId = testCategoryId,
            hash = "hash123abc",
        )

        assertNotNull(txn)
        assertEquals("REWE", txn.partnerName)
        assertEquals(BigDecimal("-49.99"), txn.amount)
    }

    @Test
    fun `test find by hash`() {
        transactionRepo.create(
            testAccountId,
            LocalDate.now(),
            BigDecimal("-100.00"),
            hash = "unique_hash_789",
        )
        val found = transactionRepo.findByHash("unique_hash_789")

        assertNotNull(found)
    }
}
