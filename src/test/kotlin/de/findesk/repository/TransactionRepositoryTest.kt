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
import org.junit.jupiter.api.Assertions.assertNull
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

    @Test
    fun `deleteAll deletes exactly the given ids, unlinks their documents and ignores unknown ids`() {
        val bank = bankAccessRepo.create("Fourth Bank", "11111111", "FOURDEFF", "user4")
        val account = accountRepo.create(bank.id.value, "DE44500105175407324931").id.value
        val keepId = transactionRepo.create(account, LocalDate.now(), BigDecimal("-1.00"), hash = "batch-keep").id.value
        val goneId = transactionRepo.create(account, LocalDate.now(), BigDecimal("-2.00"), hash = "batch-gone").id.value
        val otherGoneId = transactionRepo.create(account, LocalDate.now(), BigDecimal("-3.00"), hash = "batch-gone-2").id.value
        val docRepo = DocumentRepositoryImpl()
        // One receipt on a deleted booking and one on the kept one: the delete unlinks, it never deletes receipts
        val receiptGone = docRepo.create("gone-receipt.pdf", "gone-receipt.pdf", "application/pdf", linkedTransactionId = goneId)
        val receiptKeep = docRepo.create("kept-receipt.pdf", "kept-receipt.pdf", "application/pdf", linkedTransactionId = keepId)

        val deleted = transactionRepo.deleteAll(listOf(goneId, otherGoneId, 999_999))

        assertEquals(2, deleted, "the unknown id is ignored, the other two are deleted")
        assertNull(transactionRepo.findById(goneId))
        assertNull(transactionRepo.findById(otherGoneId))
        assertNotNull(transactionRepo.findById(keepId))
        assertNull(docRepo.findItem(receiptGone.id.value)?.linkedTransactionId, "the deleted booking's receipt is unlinked, not gone")
        assertEquals(keepId, docRepo.findItem(receiptKeep.id.value)?.linkedTransactionId, "the kept booking's receipt stays linked")

        assertEquals(0, transactionRepo.deleteAll(emptyList()), "an empty list does nothing")
        assertEquals(0, transactionRepo.deleteAll(emptySet()), "an empty set does nothing")

        // This class shares one database across its tests, so leave the extra account out of it again
        accountRepo.deleteWithTransactions(account)
    }

    @Test
    fun `oldestDateByAccount returns each account's oldest date and omits accounts without bookings`() {
        val bank = bankAccessRepo.create("Oldest Bank", "33333333", "OLDESTDEFF", "user6")
        val first = accountRepo.create(bank.id.value, "DE11111111111111111111").id.value
        val second = accountRepo.create(bank.id.value, "DE22222222222222222222").id.value
        val empty = accountRepo.create(bank.id.value, "DE33333333333333333333").id.value
        transactionRepo.create(first, LocalDate.of(2026, 5, 10), BigDecimal("-1.00"), hash = "oldest-first-1")
        transactionRepo.create(first, LocalDate.of(2026, 3, 2), BigDecimal("-2.00"), hash = "oldest-first-2")
        transactionRepo.create(second, LocalDate.of(2026, 7, 4), BigDecimal("-3.00"), hash = "oldest-second")

        val oldest = transactionRepo.oldestDateByAccount()

        assertEquals(LocalDate.of(2026, 3, 2), oldest[first], "the earliest of the account's bookings")
        assertEquals(LocalDate.of(2026, 7, 4), oldest[second])
        assertFalse(oldest.containsKey(empty), "an account without bookings is absent")

        // This class shares one database across its tests, so leave the extra accounts out of it again
        listOf(first, second, empty).forEach { accountRepo.deleteWithTransactions(it) }
    }

    @Test
    fun `deleteAll chunks more than the parameter limit in one call and keeps the other bookings`() {
        val bank = bankAccessRepo.create("Fifth Bank", "22222222", "FIVEDEFF", "user5")
        val account = accountRepo.create(bank.id.value, "DE02120300000000202052").id.value
        // More ids than one statement may carry, over three chunks of at most 500
        val ids = (0 until 1250).map { n ->
            transactionRepo.create(account, LocalDate.of(2026, 1, 1).plusDays((n % 300).toLong()), BigDecimal("-1.00"), hash = "chunk-$n").id.value
        }
        val survivor = transactionRepo.create(account, LocalDate.now(), BigDecimal("-5.00"), hash = "chunk-survivor").id.value

        val deleted = transactionRepo.deleteAll(ids + 4_242_424)

        assertEquals(1250, deleted)
        assertEquals(listOf(survivor), transactionRepo.findItems(TransactionFilter(accountId = account)).map { it.id })

        // This class shares one database across its tests, so leave the extra account out of it again
        accountRepo.deleteWithTransactions(account)
    }
}
