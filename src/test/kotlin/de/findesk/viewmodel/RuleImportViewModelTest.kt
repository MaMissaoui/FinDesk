package de.findesk.viewmodel

import de.findesk.TestData
import de.findesk.TestDatabase
import de.findesk.i18n.AppStrings
import de.findesk.i18n.StringKey
import de.findesk.repository.CategoryRepositoryImpl
import de.findesk.repository.RuleRepositoryImpl
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.LocalDate

/** The settings flow around a rule file: choose (preview), confirm (apply), export. */
class RuleImportViewModelTest {
    private val header = "category\tpattern\tsearch_in\tapplies_to\tpriority\tauto_apply"

    @TempDir
    lateinit var tmp: File

    private lateinit var data: TestData

    private fun vm(tmp: File) = SettingsViewModel(dataDirectory = tmp.absolutePath).also { runBlocking { it.load().join() } }

    private fun ruleFile(name: String, vararg rows: String) = File(tmp, name).apply {
        writeText(
            buildString {
                append("# FinDesk rules v1\n")
                append(header)
                append('\n')
                rows.forEach { append(it).append('\n') }
            },
        )
    }

    @BeforeEach
    fun setup() {
        TestDatabase.init()
        data = TestData()
    }

    @Test
    fun `a rejected rule file sets an error and no preview`() = runBlocking {
        data.category("Groceries")
        val bad = File(tmp, "rules.tsv").apply { writeText("Definitely not a rule file\njust some text\n") }
        val vm = vm(tmp)

        vm.chooseRuleImport(bad).join()

        assertEquals(AppStrings[StringKey.ErrorRuleFileHeader], vm.state.value.error)
        assertNull(vm.state.value.ruleImport)
        assertTrue(vm.state.value.rules.isEmpty())
        vm.close()
    }

    @Test
    fun `confirm applies the preview and reports the counts`() = runBlocking {
        data.category("Groceries")
        val file = ruleFile("rules.tsv", "Groceries\tmusterladen\tpartner\texpense\t60\ttrue")
        val vm = vm(tmp)

        vm.chooseRuleImport(file).join()
        val preview = vm.state.value.ruleImport
        assertEquals(0, preview?.matchCount, "there are no bookings, so nothing would be categorized")
        assertTrue(preview?.categoriesToCreate.isNullOrEmpty())

        vm.confirmRuleImport(categorize = true).join()
        val state = vm.state.value
        assertNull(state.ruleImport, "the preview is consumed")
        assertNull(state.error)
        assertEquals(AppStrings.format(StringKey.MessageRulesImported, 1, 0, 0), state.message)
        assertEquals(60, state.rules.single().priority)
        assertEquals("musterladen", RuleRepositoryImpl().findAllItems().single().pattern)
        assertEquals(1, state.categories.size, "groceries existed before")
        vm.close()
    }

    @Test
    fun `export writes the rule file and reports the counts`() = runBlocking {
        val groceries = data.category("Groceries")
        data.ruleRepo.create("musterladen", groceries, priority = 60)
        val output = File(tmp, "out.tsv")
        val vm = vm(tmp)

        vm.exportRules(output).join()

        assertNull(vm.state.value.error)
        assertEquals(AppStrings.format(StringKey.MessageRulesExported, 1), vm.state.value.message)
        assertTrue(output.readText().startsWith("# FinDesk rules v1\ncategory\tpattern\tsearch_in\tapplies_to\tpriority\tauto_apply\nGroceries\tmusterladen\tany\tany\t60\ttrue\n"))
        vm.close()
    }

    @Test
    fun `cancelling drops the preview and the confirmation applies nothing then`() = runBlocking {
        val vm = vm(tmp)
        vm.chooseRuleImport(ruleFile("rules.tsv", "Cash\traw cash\tany\tany\t1\ttrue")).join()
        assertEquals(1, vm.state.value.ruleImport?.newRules?.size ?: 0, "the preview is staged, the database untouched")
        assertTrue(RuleRepositoryImpl().findAllItems().isEmpty(), "choosing alone creates nothing")
        vm.cancelRuleImport()
        vm.confirmRuleImport(categorize = false).join()
        assertTrue(vm.state.value.rules.isEmpty(), "a cancelled import applies nothing")
        vm.close()
    }
}
