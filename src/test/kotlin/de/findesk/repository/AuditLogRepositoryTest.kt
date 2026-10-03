package de.findesk.repository

import de.findesk.TestDatabase
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AuditLogRepositoryTest {
    private lateinit var repository: AuditLogRepository

    @BeforeAll
    fun setup() {
        TestDatabase.init()
        repository = AuditLogRepositoryImpl()
    }

    @Test
    fun `test log entry`() {
        val entry = repository.log("LOGIN", "testuser", "Successful login")

        assertNotNull(entry)
        assertEquals("LOGIN", entry.action)
        assertEquals("testuser", entry.user)
        assertEquals("Successful login", entry.details)
        assertNotNull(entry.timestamp)
    }

    @Test
    fun `test find by action`() {
        repository.log("SYNC", "user1", "Sync completed")
        repository.log("SYNC", "user2", "Another sync")
        repository.log("LOGIN", "user1", "Logged in")

        val syncEntries = repository.findByAction("SYNC")
        assertTrue(syncEntries.size >= 2)
        assertTrue(syncEntries.all { it.action == "SYNC" })
    }

    @Test
    fun `test find by user`() {
        repository.log("ACTION", "specificuser", "Some action")
        val userEntries = repository.findByUser("specificuser")

        assertTrue(userEntries.isNotEmpty())
        assertTrue(userEntries.all { it.user == "specificuser" })
    }

    @Test
    fun `test find recent`() {
        for (i in 1..10) {
            repository.log("TEST_ACTION", "user", "Entry $i")
        }

        val recent = repository.findRecent(5)
        assertTrue(recent.size <= 5)
    }

    @Test
    fun `test find all`() {
        val all = repository.findAll()
        assertTrue(all.isNotEmpty())
    }
}
