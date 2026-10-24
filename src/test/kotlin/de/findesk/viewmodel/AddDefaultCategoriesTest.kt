package de.findesk.viewmodel

import de.findesk.TestDatabase
import de.findesk.i18n.AppLanguage
import de.findesk.i18n.AppStrings
import de.findesk.model.DefaultCategories
import de.findesk.repository.AuditLogRepositoryImpl
import de.findesk.repository.CategoryRepositoryImpl
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files

/** The default categories give a new database a starting set; existing categories are never touched. */
class AddDefaultCategoriesTest {
    @TempDir
    lateinit var tmp: File

    @BeforeEach
    fun setup() {
        TestDatabase.init()
        AppStrings.set(AppLanguage.EN)
    }

    private fun vm(tmp: File) = SettingsViewModel(dataDirectory = tmp.absolutePath).also { runBlocking { it.load().join() } }

    @Test
    fun `on an empty database all 24 defaults are created`() = runBlocking {
        val vm = vm(tmp)
        vm.addDefaultCategories().join()

        val names = vm.state.value.categories.map { it.name }
        assertEquals(24, vm.state.value.categories.size, names.joinToString())
        assertTrue(names.contains("Groceries"))
        assertTrue(names.containsAll(listOf("Drugstore & household", "Other income")))
        // Colours cycle through the palette
        assertTrue(vm.state.value.categories.all { it.color.matches(Regex("#[0-9A-Fa-f]{6}")) })
        assertEquals("24 default categories added", vm.state.value.message)
        vm.close()
    }

    @Test
    fun `existing categories are kept and matched case-insensitively`() = runBlocking {
        val categoryRepo = CategoryRepositoryImpl()
        categoryRepo.create("groceries", "#123456")
        val vm = vm(tmp)
        val groceriesId = vm.state.value.categories.single().id

        vm.addDefaultCategories().join()

        assertEquals(24, vm.state.value.categories.size)
        val groceries = vm.state.value.categories.single { it.id == groceriesId }
        assertEquals("groceries", groceries.name, "existing categories are never changed")
        assertEquals("23 default categories added", vm.state.value.message)

        vm.addDefaultCategories().join()
        assertEquals(24, vm.state.value.categories.size)
        assertEquals("All default categories already exist", vm.state.value.message)
        vm.close()
    }

    @Test
    fun `the creation is audited`() = runBlocking {
        val vm = vm(tmp)
        vm.addDefaultCategories().join()

        val entry = AuditLogRepositoryImpl().findRecent(10).first { it.action == "ADD_DEFAULT_CATEGORIES" }
        assertNotNull(entry)
        assertEquals("Added 24 default categories", entry.details)
        vm.close()
    }

    @Test
    fun `the list holds 24 entries in palette order`() {
        assertEquals(24, DefaultCategories.entries.size)
        assertEquals("#FF5722", DefaultCategories.entries.first().second)
        assertEquals(24, DefaultCategories.colors.size, "24 categories, 24 distinct colours, no cycling any more")
    }
}
