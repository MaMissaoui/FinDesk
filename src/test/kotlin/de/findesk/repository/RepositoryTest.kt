package de.findesk.repository

import de.findesk.data.database.DatabaseFactory
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
class RepositoryTest {
    private lateinit var bankAccessRepo: BankAccessRepository
    private lateinit var accountRepo: AccountRepository
    private lateinit var categoryRepo: CategoryRepository
    private lateinit var transactionRepo: TransactionRepository
    private lateinit var ruleRepo: RuleRepository
    private lateinit var documentRepo: DocumentRepository
    private lateinit var settingsRepo: SettingsRepository
    private lateinit var auditLogRepo: AuditLogRepository
    private lateinit var dbFile: File

    @BeforeAll
    fun setup() {
        // Use a temp file database
        dbFile = File.createTempFile("findesk-test", ".db")
        dbFile.deleteOnExit()
        Database.connect("jdbc:sqlite:${dbFile.absolutePath}", driver = "org.sqlite.JDBC")

        transaction {
            SchemaUtils.create(*DatabaseFactory.tables)
        }

        bankAccessRepo = BankAccessRepositoryImpl()
        accountRepo = AccountRepositoryImpl()
        categoryRepo = CategoryRepositoryImpl()
        transactionRepo = TransactionRepositoryImpl()
        ruleRepo = RuleRepositoryImpl()
        documentRepo = DocumentRepositoryImpl()
        settingsRepo = SettingsRepositoryImpl()
        auditLogRepo = AuditLogRepositoryImpl()
    }

    @AfterAll
    fun cleanup() {
        dbFile.delete()
    }

    @Test
    fun `test bank access CRUD`() {
        val bankAccess = bankAccessRepo.create(
            bankName = "Test Bank",
            blz = "12345678",
            bic = "TESTDEFF",
            userId = "testuser",
        )
        assertNotNull(bankAccess)
        assertEquals("Test Bank", bankAccess.bankName)

        val found = bankAccessRepo.findById(bankAccess.id.value)
        assertNotNull(found)
        assertEquals("12345678", found!!.blz)

        val updated = bankAccessRepo.update(bankAccess.id.value, bankName = "Updated Bank")
        assertNotNull(updated)
        assertEquals("Updated Bank", updated!!.bankName)

        assertTrue(bankAccessRepo.delete(bankAccess.id.value))
        assertNull(bankAccessRepo.findById(bankAccess.id.value))
    }

    @Test
    fun `test account CRUD`() {
        val bankAccess = bankAccessRepo.create("Test Bank", "12345678", "TESTDEFF", "user1")

        val account = accountRepo.create(
            bankAccessId = bankAccess.id.value,
            iban = "DE89370400440532013000",
            balance = BigDecimal("1000.00"),
            accountType = "checking",
        )
        assertNotNull(account)
        assertEquals("DE89370400440532013000", account.iban)

        val found = accountRepo.findById(account.id.value)
        assertNotNull(found)
        assertEquals(BigDecimal("1000.00"), found!!.balance)

        val updated = accountRepo.updateBalance(account.id.value, BigDecimal("1500.00"))
        assertNotNull(updated)
        assertEquals(BigDecimal("1500.00"), updated!!.balance)

        val total = accountRepo.getTotalBalance()
        assertTrue(total > BigDecimal.ZERO)
    }

    @Test
    fun `test category CRUD`() {
        val category = categoryRepo.create(
            name = "Groceries",
            color = "#FF5722",
            budgetLimit = BigDecimal("500.00"),
            isTaxRelevant = false,
        )
        assertNotNull(category)
        assertEquals("Groceries", category.name)

        val found = categoryRepo.findByName("Groceries")
        assertNotNull(found)

        val updated = categoryRepo.update(
            category.id.value,
            budgetLimit = BigDecimal("600.00"),
        )
        assertNotNull(updated)
        assertEquals(BigDecimal("600.00"), updated!!.budgetLimit)
    }

    @Test
    fun `test transaction CRUD`() {
        val bankAccess = bankAccessRepo.create("Test Bank", "12345678", "TESTDEFF", "user1")
        val account = accountRepo.create(bankAccess.id.value, "DE89370400440532013000")
        val category = categoryRepo.create("Shopping")

        val transaction = transactionRepo.create(
            accountId = account.id.value,
            date = LocalDate.now(),
            amount = BigDecimal("-49.99"),
            partnerName = "Amazon",
            purpose = "Purchase #12345",
            categoryId = category.id.value,
            hash = "abc123hash",
        )
        assertNotNull(transaction)
        assertEquals("Amazon", transaction.partnerName)

        val foundByHash = transactionRepo.findByHash("abc123hash")
        assertNotNull(foundByHash)

        val byCategory = transactionRepo.findByCategory(category.id.value)
        assertTrue(byCategory.isNotEmpty())

        val searchResults = transactionRepo.searchByText("Amazon")
        assertTrue(searchResults.isNotEmpty())
    }

    @Test
    fun `test rule matching`() {
        val category = categoryRepo.create("Groceries")

        ruleRepo.create(".*rewe.*|.*edeka.*|.*aldi.*", category.id.value, priority = 10)
        ruleRepo.create(".*amazon.*", categoryRepo.create("Shopping").id.value, priority = 5)

        assertEquals(category.id.value, ruleRepo.matchCategoryId("REWE SAGT DANKE", null))
    }

    @Test
    fun `test settings`() {
        settingsRepo.setValue("theme", "dark")
        assertEquals("dark", settingsRepo.getValue("theme"))

        settingsRepo.setValue("theme", "light")
        assertEquals("light", settingsRepo.getValue("theme"))
    }

    @Test
    fun `test audit log`() {
        val entry = auditLogRepo.log("LOGIN", "testuser", "Successful login")
        assertNotNull(entry)
        assertEquals("LOGIN", entry.action)

        val recent = auditLogRepo.findRecent(10)
        assertTrue(recent.isNotEmpty())
    }
}
