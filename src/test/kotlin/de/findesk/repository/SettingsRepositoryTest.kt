package de.findesk.repository

import de.findesk.data.database.DatabaseFactory
import org.jetbrains.exposed.sql.Database
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SettingsRepositoryTest {
    private lateinit var repository: SettingsRepository

    @BeforeAll
    fun setup() {
        Database.connect("jdbc:sqlite::memory:", driver = "org.sqlite.JDBC")
        DatabaseFactory.init()
        repository = SettingsRepositoryImpl()
    }

    @Test
    fun `test set and get value`() {
        repository.setValue("theme", "dark")
        assertEquals("dark", repository.getValue("theme"))
    }

    @Test
    fun `test update existing value`() {
        repository.setValue("language", "de")
        repository.setValue("language", "en")
        assertEquals("en", repository.getValue("language"))
    }

    @Test
    fun `test get non-existent key`() {
        assertNull(repository.getValue("nonexistent"))
    }

    @Test
    fun `test find by key`() {
        repository.setValue("testkey", "testvalue")
        val setting = repository.findByKey("testkey")

        assertNotNull(setting)
        assertEquals("testvalue", setting!!.value)
    }

    @Test
    fun `test delete setting`() {
        repository.setValue("todelete", "value")
        assertTrue(repository.delete("todelete"))
        assertNull(repository.getValue("todelete"))
    }

    @Test
    fun `test find all`() {
        repository.setValue("key1", "value1")
        repository.setValue("key2", "value2")

        val all = repository.findAll()
        assertTrue(all.size >= 2)
    }
}
