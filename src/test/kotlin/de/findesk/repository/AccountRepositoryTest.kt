package de.findesk.repository

import de.findesk.data.database.DatabaseFactory
import de.findesk.data.entity.Account
import de.findesk.data.entity.BankAccess
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import java.math.BigDecimal

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AccountRepositoryTest {
    private lateinit var accountRepo: AccountRepository
    private lateinit var bankAccessRepo: BankAccessRepository
    private var testBankAccessId: Int = 0

    @BeforeAll
    fun setup() {
        Database.connect("jdbc:sqlite::memory:", driver = "org.sqlite.JDBC")
        DatabaseFactory.init()
        accountRepo = AccountRepositoryImpl()
        bankAccessRepo = BankAccessRepositoryImpl()

        // Create test bank access
        val bankAccess = bankAccessRepo.create("Test Bank", "12345678", "TESTDEFF", "testuser")
        testBankAccessId = bankAccess.id.value
    }

    @Test
    fun `test create account`() {
        val account = accountRepo.create(
            bankAccessId = testBankAccessId,
            iban = "DE89370400440532013000",
            balance = BigDecimal("5000.00"),
            accountType = "checking",
        )

        assertNotNull(account)
        assertEquals("DE89370400440532013000", account.iban)
        assertEquals(BigDecimal("5000.00"), account.balance)
        assertEquals("checking", account.accountType)
        assertEquals("EUR", account.currency)
    }

    @Test
    fun `test find by id`() {
        val created = accountRepo.create(testBankAccessId, "DE89370400440532013001", accountType = "savings")
        val found = accountRepo.findById(created.id.value)

        assertNotNull(found)
        assertEquals("savings", found!!.accountType)
    }

    @Test
    fun `test find by iban`() {
        accountRepo.create(testBankAccessId, "DE89370400440532013002")
        val found = accountRepo.findByIban("DE89370400440532013002")

        assertNotNull(found)
    }

    @Test
    fun `test find by type`() {
        accountRepo.create(testBankAccessId, "DE89370400440532013003", accountType = "credit_card")
        val results = accountRepo.findByType("credit_card")

        assertTrue(results.isNotEmpty())
    }

    @Test
    fun `test update balance`() {
        val account = accountRepo.create(testBankAccessId, "DE89370400440532013004", balance = BigDecimal("1000.00"))
        val updated = accountRepo.updateBalance(account.id.value, BigDecimal("1500.00"))

        assertNotNull(updated)
        assertEquals(BigDecimal("1500.00"), updated!!.balance)
        assertNotNull(updated.lastUpdate)
    }

    @Test
    fun `test get total balance`() {
        accountRepo.create(testBankAccessId, "DE89370400440532013005", balance = BigDecimal("2000.00"))
        accountRepo.create(testBankAccessId, "DE89370400440532013006", balance = BigDecimal("3000.00"))

        val total = accountRepo.getTotalBalance()
        assertTrue(total > BigDecimal.ZERO)
    }

    @Test
    fun `test delete account`() {
        val account = accountRepo.create(testBankAccessId, "DE89370400440532013007")
        assertTrue(accountRepo.delete(account.id.value))
        assertNull(accountRepo.findById(account.id.value))
    }
}
