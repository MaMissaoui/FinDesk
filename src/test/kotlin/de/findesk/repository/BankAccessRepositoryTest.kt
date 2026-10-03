package de.findesk.repository

import de.findesk.data.database.DatabaseFactory
import de.findesk.data.entity.BankAccess
import de.findesk.data.table.BankAccessTable
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BankAccessRepositoryTest {
    private lateinit var repository: BankAccessRepository

    @BeforeAll
    fun setup() {
        Database.connect("jdbc:sqlite::memory:", driver = "org.sqlite.JDBC")
        DatabaseFactory.init()
        repository = BankAccessRepositoryImpl()
    }

    @Test
    fun `test create bank access`() {
        val bankAccess = repository.create(
            bankName = "Deutsche Bank",
            blz = "37040044",
            bic = "DEUTDEDBXXX",
            userId = "testuser123"
        )

        assertNotNull(bankAccess)
        assertEquals("Deutsche Bank", bankAccess.bankName)
        assertEquals("37040044", bankAccess.blz)
        assertEquals("DEUTDEDBXXX", bankAccess.bic)
        assertEquals("testuser123", bankAccess.userId)
        assertEquals("300", bankAccess.hbciVersion)
    }

    @Test
    fun `test find by id`() {
        val created = repository.create("Commerzbank", "37040044", "COBADEFFXXX", "user456")
        val found = repository.findById(created.id.value)

        assertNotNull(found)
        assertEquals("Commerzbank", found!!.bankName)
    }

    @Test
    fun `test find by blz`() {
        repository.create("Sparkasse", "37050198", "COLSDE33XXX", "user789")
        val results = repository.findByBlz("37050198")

        assertTrue(results.isNotEmpty())
        assertEquals("Sparkasse", results.first().bankName)
    }

    @Test
    fun `test update bank access`() {
        val created = repository.create("Volksbank", "37060190", "GENODED1BRS", "user101")
        val updated = repository.update(created.id.value, tanMethod = "942")

        assertNotNull(updated)
        assertEquals("942", updated!!.tanMethod)
    }

    @Test
    fun `test update last sync`() {
        val created = repository.create("ING", "50010517", "INGDDEFFXXX", "user202")
        val updated = repository.updateLastSync(created.id.value)

        assertNotNull(updated)
        assertNotNull(updated!!.lastSync)
    }

    @Test
    fun `test delete bank access`() {
        val created = repository.create("TestBank", "12345678", "TESTDEFF", "user303")
        assertTrue(repository.delete(created.id.value))
        assertNull(repository.findById(created.id.value))
    }

    @Test
    fun `test find all`() {
        repository.create("Bank1", "11111111", "BANK1DEFF", "u1")
        repository.create("Bank2", "22222222", "BANK2DEFF", "u2")

        val all = repository.findAll()
        assertTrue(all.size >= 2)
    }
}