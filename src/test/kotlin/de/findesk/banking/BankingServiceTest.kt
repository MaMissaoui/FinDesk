package de.findesk.banking

import de.findesk.data.database.DatabaseFactory
import de.findesk.repository.*
import org.jetbrains.exposed.sql.Database
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BankingServiceTest {
    private lateinit var service: BankingService

    @BeforeAll
    fun setup() {
        Database.connect("jdbc:sqlite::memory:", driver = "org.sqlite.JDBC")
        DatabaseFactory.init()
        service = BankingService()
    }

    @Test
    fun `test service initial state`() {
        assertFalse(service.isLoading.value)
        assertNull(service.error.value)
    }

    @Test
    fun `test import result data class`() {
        val result = BankingService.ImportResult(
            imported = 10,
            duplicates = 2,
            categorized = 5
        )

        assertEquals(10, result.imported)
        assertEquals(2, result.duplicates)
        assertEquals(5, result.categorized)
    }
}