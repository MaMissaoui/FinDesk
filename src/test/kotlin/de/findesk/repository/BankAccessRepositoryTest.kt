package de.findesk.repository

import de.findesk.TestDatabase
import de.findesk.data.entity.BankAccess
import de.findesk.data.table.BankAccessTable
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BankAccessRepositoryTest {
    private lateinit var repository: BankAccessRepository

    @BeforeAll
    fun setup() {
        TestDatabase.init()
        repository = BankAccessRepositoryImpl()
    }

    @Test
    fun `test create bank access`() {
        val bankAccess = repository.create(
            bankName = "Deutsche Bank",
            blz = "37040044",
            bic = "DEUTDEDBXXX",
            userId = "testuser123",
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

    @Test
    fun `test update capabilities`() {
        val created = repository.create("CapBank", "33333333", "CAPBDEFF", "u3")
        assertNull(repository.findItem(created.id.value)!!.capabilities)

        val updated = repository.updateCapabilities(created.id.value, "SaldoReq,UebSEPA")

        assertEquals("SaldoReq,UebSEPA", updated!!.capabilities)
        assertEquals("SaldoReq,UebSEPA", repository.findItem(created.id.value)!!.capabilities)

        // A blank value clears the column, back to "capabilities unknown"
        repository.updateCapabilities(created.id.value, "  ")
        assertNull(repository.findItem(created.id.value)!!.capabilities)
    }

    @Test
    fun `test update connection stores blz and bic`() {
        val created = repository.create("HVB", "79020076", "HYVEDEMM455", "u4")
        val updated = repository.updateConnection(created.id.value, blz = "70020270", bic = "HYVEDEMMXXX")

        assertEquals("70020270", updated!!.blz)
        assertEquals("HYVEDEMMXXX", updated.bic)

        // A blank BIC stores "" (an unknown BIC); a null leaves the stored one alone
        assertEquals("", repository.updateConnection(created.id.value, bic = "  ")!!.bic)
        assertEquals("", repository.updateConnection(created.id.value, blz = "12030000")!!.bic)
        assertEquals("12030000", repository.findItem(created.id.value)!!.blz)
    }

    @Test
    fun `test clear login state drops capabilities and tan method`() {
        val created = repository.create("StaleLoginBank", "44444444", "STALDEFF", "u5")
        repository.updateCapabilities(created.id.value, "SaldoReq,UebSEPA")
        repository.updateConnection(created.id.value, tanMethod = "942")
        assertEquals("SaldoReq,UebSEPA", repository.findItem(created.id.value)!!.capabilities)
        assertEquals("942", repository.findItem(created.id.value)!!.tanMethod)

        val cleared = repository.clearLoginState(created.id.value)

        assertNull(cleared!!.capabilities)
        assertNull(cleared.tanMethod)
        assertNull(repository.findItem(created.id.value)!!.capabilities)
        assertNull(repository.findItem(created.id.value)!!.tanMethod)
    }
}
