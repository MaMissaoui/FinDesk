package de.findesk.viewmodel

import de.findesk.TestData
import de.findesk.TestDatabase
import de.findesk.i18n.AppStrings
import de.findesk.i18n.StringKey
import de.findesk.repository.AuditLogRepositoryImpl
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** The fix for categories that share one colour: the lowest id keeps it, the others get free palette colours. */
class FixDuplicateColoursTest {
    @TempDir
    lateinit var tmp: File

    private lateinit var data: TestData

    private fun vm() = SettingsViewModel(dataDirectory = tmp.absolutePath).also { runBlocking { it.load().join() } }

    private fun colorsById() = data.categoryRepo.findAllItems().associate { it.id to it.color }

    @BeforeEach
    fun setup() {
        TestDatabase.init()
        data = TestData()
    }

    private fun auditFixes() = AuditLogRepositoryImpl().findRecent(100).filter { it.action == "FIX_CATEGORY_COLOURS" }.map { it.details }

    @Test
    fun `the lowest id keeps its colour and the others get unused palette colours`() = runBlocking {
        val first = data.category("Groceries")
        val second = data.category("Travel")
        val third = data.category("Cash")
        val untouched = data.category("Health", color = "#123ABC")
        val vm = vm()

        vm.fixDuplicateColours().join()

        val colors = colorsById()
        assertEquals("#FF5722", colors[first], "the lowest id keeps the shared colour")
        assertEquals("#4CAF50", colors[second], "the first unused palette colour")
        assertEquals("#2196F3", colors[third], "the next unused palette colour")
        assertEquals("#123ABC", colors[untouched], "a unique colour is never changed")
        val items = data.categoryRepo.findAllItems()
        assertEquals(items.size, items.map { it.color.uppercase() }.toSet().size, "after the fix nothing shares a colour")
        assertEquals(AppStrings.format(StringKey.MessageColoursFixed, 2), vm.state.value.message)
        val fixes = auditFixes()
        assertEquals(listOf("Recoloured 2 categories"), fixes)
        vm.close()
    }

    @Test
    fun `names and other fields are never touched`() = runBlocking {
        val groceries = data.category("Groceries")
        val ownId = data.categoryRepo.create("Card", "#FF5722", isTransfer = true).id.value
        data.categoryRepo.setBudget(groceries, java.math.BigDecimal("120.00"))
        data.category("Travel")
        val vm = vm()

        vm.fixDuplicateColours().join()

        val items = data.categoryRepo.findAllItems().associateBy { it.id }
        assertEquals("Groceries", items[groceries]?.name)
        assertEquals(true, items[ownId]?.isTransfer)
        assertEquals(java.math.BigDecimal("120.00"), items[groceries]?.budgetLimit)
        assertEquals(false as Boolean?, items[groceries]?.isTaxRelevant)
        vm.close()
    }

    @Test
    fun `a second call changes nothing and writes no audit entry`() = runBlocking {
        data.category("Groceries")
        data.category("Travel")
        val vm = vm()
        vm.fixDuplicateColours().join()
        vm.clearMessages()
        val colorsAfterFirst = colorsById()
        val fixesAfterFirst = auditFixes()

        vm.fixDuplicateColours().join()

        assertEquals(colorsAfterFirst, colorsById(), "nothing is recoloured a second time")
        assertEquals(fixesAfterFirst, auditFixes(), "no further audit entry")
        assertEquals(AppStrings[StringKey.MessageColoursAlreadyDistinct], vm.state.value.message)
        vm.close()
    }

    @Test
    fun `lower and upper case hex count as the same colour`() = runBlocking {
        val first = data.category("Groceries", color = "#ff5722")
        val second = data.category("Travel", color = "#FF5722")
        val vm = vm()

        vm.fixDuplicateColours().join()

        val colors = colorsById()
        assertEquals("#ff5722", colors[first], "the lowest id keeps its colour untouched")
        assertEquals("#4CAF50", colors[second])
        assertEquals(AppStrings.format(StringKey.MessageColoursFixed, 1), vm.state.value.message)
        assertEquals(listOf("Recoloured 1 categories"), auditFixes())
        vm.close()
    }
}
