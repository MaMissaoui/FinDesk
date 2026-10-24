package de.findesk.viewmodel

import de.findesk.TestDatabase
import de.findesk.i18n.AppLanguage
import de.findesk.i18n.AppStrings
import de.findesk.i18n.StringKey
import de.findesk.model.DefaultCategories
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** The defaults carry the transfer flag; only "Own transfers" is marked, the credit card category is not. */
class DefaultCategoriesTransferFlagTest {
    @TempDir
    lateinit var tmp: File

    @BeforeEach
    fun setup() {
        TestDatabase.init()
        AppStrings.set(AppLanguage.EN)
    }

    @Test
    fun `only own transfers is a transfer`() {
        val transfers = DefaultCategories.entries.filter { (_, _, isTransfer) -> isTransfer }
        assertEquals(1, transfers.size)
        assertEquals(StringKey.DefaultCategoryOwnTransfers, transfers.single().first)
        // The credit card payment is the only record of card spending while card bookings are not imported
        assertFalse(DefaultCategories.entries.any { (key, _, isTransfer) -> key == StringKey.DefaultCategoryCreditCard && isTransfer })
    }

    @Test
    fun `addDefaultCategories stores the flag`() = runBlocking {
        val vm = SettingsViewModel(dataDirectory = tmp.absolutePath).also { runBlocking { it.load().join() } }
        vm.addDefaultCategories().join()

        val saved = vm.state.value.categories.associateBy { it.name }
        assertFalse(saved.getValue("Groceries").isTransfer)
        assertFalse(saved.getValue("Credit card").isTransfer)
        assertTrue(saved.getValue("Own transfers").isTransfer)
        vm.close()
    }
}
