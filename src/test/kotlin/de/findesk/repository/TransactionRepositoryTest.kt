package de.findesk.repository

import de.findesk.data.database.DatabaseFactory
import de.findesk.data.table.*
import de.findesk.model.TransactionFilter
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assertions.assertNotNull
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
            SchemaUtils.create(*DatabaseFactory.tables)
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

    @Test
    fun `accountIds null returns all, a set returns exactly those, an empty set returns none`() {
        val bank = bankAccessRepo.create("Second Bank", "87654321", "SECONDEF", "user2")
        val second = accountRepo.create(bank.id.value, "DE44500105175407324931").id.value
        transactionRepo.create(second, LocalDate.now(), BigDecimal("-5.00"), hash = "second-account-hash")

        val all = transactionRepo.findItems()
        assertEquals(all.size, transactionRepo.findItems(TransactionFilter(accountIds = null)).size, "null means no restriction")

        val onlySecond = transactionRepo.findItems(TransactionFilter(accountIds = setOf(second)))
        assertTrue(onlySecond.isNotEmpty())
        assertTrue(onlySecond.all { it.accountId == second })

        val both = transactionRepo.findItems(TransactionFilter(accountIds = setOf(testAccountId, second)))
        assertEquals(all.size, both.size)
        assertTrue(both.any { it.accountId == testAccountId })
        assertTrue(both.any { it.accountId == second })

        assertTrue(transactionRepo.findItems(TransactionFilter(accountIds = emptySet())).isEmpty(), "an empty set matches nothing")
    }

    @Test
    fun `countNew is per account and markRead clears the requested scope`() {
        val bank = bankAccessRepo.create("Third Bank", "99999999", "THIRDDEFF", "user3")
        val second = accountRepo.create(bank.id.value, "DE44500105175407324931").id.value
        // One booking far outside any period on screen: opening the account must still clear it
        transactionRepo.create(testAccountId, LocalDate.of(2020, 1, 1), BigDecimal("-1.00"), hash = "new-a-old", isNew = true)
        transactionRepo.create(testAccountId, LocalDate.now(), BigDecimal("-2.00"), hash = "new-a-now", isNew = true)
        transactionRepo.create(second, LocalDate.now(), BigDecimal("-3.00"), hash = "new-b", isNew = true)
        transactionRepo.create(second, LocalDate.now(), BigDecimal("-4.00"), hash = "read-b")

        assertEquals(mapOf(testAccountId to 2, second to 1), transactionRepo.countNew())

        transactionRepo.markRead(emptySet())
        assertEquals(mapOf(testAccountId to 2, second to 1), transactionRepo.countNew(), "an empty set clears nothing")

        transactionRepo.markRead(setOf(testAccountId))
        assertEquals(mapOf(second to 1), transactionRepo.countNew(), "only the named account is cleared, however old its bookings")

        transactionRepo.markRead(null)
        assertTrue(transactionRepo.countNew().isEmpty(), "null clears every account")

        // This class shares one database across its tests, so leave the extra account out of it again
        accountRepo.deleteWithTransactions(second)
    }
}
