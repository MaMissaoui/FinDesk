package de.findesk.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import de.findesk.TestData
import de.findesk.TestDatabase
import de.findesk.i18n.AppLanguage
import de.findesk.i18n.LocalStrings
import de.findesk.i18n.StringKey
import de.findesk.i18n.Translations
import de.findesk.ui.navigation.NavigationArgs
import de.findesk.ui.screens.TransactionsScreen
import de.findesk.ui.theme.FinDeskTheme
import de.findesk.viewmodel.Period
import de.findesk.viewmodel.TransactionsViewModel
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.LocalDate

/**
 * The figures-first hierarchy: the accounts and transactions labels keep their plural forms, and the restyled
 * selection bar and category cell stay interactive.
 */
@OptIn(ExperimentalTestApi::class)
class FiguresHierarchyTest {
    private val en = Translations.of(AppLanguage.EN)
    private val de = Translations.of(AppLanguage.DE)

    @Test
    fun `account figure labels pluralise`() {
        assertEquals("Net of 2 accounts", en.format(StringKey.AccountsNetLabel, 2))
        assertEquals("Of which loans", en[StringKey.AccountsLoansLabel])
        assertEquals("Total of 1 account", en.format(StringKey.AccountsTotalLabel, 1))
        assertEquals("Total of 3 accounts", en.format(StringKey.AccountsTotalLabel, 3))
        assertEquals("Gesamt über 1 Konto", de.format(StringKey.AccountsTotalLabel, 1))
    }

    @Test
    fun `summary labels exist in English`() {
        assertEquals("Transactions", en[StringKey.SummaryCount])
        assertEquals("In", en[StringKey.SummaryIn])
        assertEquals("Out", en[StringKey.SummaryOut])
        assertEquals("Balance", en[StringKey.SummaryBalance])
    }

    @Test
    fun `the selection bar delete still opens the multi-delete confirmation`() = runComposeUiTest {
        TestDatabase.init()
        val data = TestData()
        val account = data.account()
        data.txn(account, LocalDate.now(), "-10.00", partner = "Bakery", purpose = "Rolls")
        data.txn(account, LocalDate.now().minusDays(1), "-20.00", partner = "Kiosk", purpose = "Water")

        lateinit var vm: TransactionsViewModel
        setContent {
            CompositionLocalProvider(LocalStrings provides en) {
                FinDeskTheme {
                    TransactionsScreen(
                        args = NavigationArgs(period = Period.ALL),
                        createViewModel = { TransactionsViewModel().also { vm = it } },
                    )
                }
            }
        }

        waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("Bakery").fetchSemanticsNodes().isNotEmpty() }
        vm.selectAllShown()
        waitForIdle()

        onNodeWithText(en[StringKey.DeleteSelected]).performClick()
        waitForIdle()

        onNodeWithText(en.format(StringKey.DeleteTransactionsTitle, 2)).assertExists()
    }

    @Test
    fun `an uncategorized row opens its category menu from the quiet text`() = runComposeUiTest {
        TestDatabase.init()
        val data = TestData()
        val account = data.account()
        data.txn(account, LocalDate.now(), "-10.00", partner = "Bakery", purpose = "Rolls")
        data.category("Groceries")

        setContent {
            CompositionLocalProvider(LocalStrings provides en) {
                FinDeskTheme { TransactionsScreen(args = NavigationArgs(period = Period.ALL)) }
            }
        }

        waitUntil(timeoutMillis = 10_000) { onAllNodesWithText(en[StringKey.Uncategorized]).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("Groceries").assertDoesNotExist()

        onNodeWithText(en[StringKey.Uncategorized]).performClick()
        waitForIdle()

        onNodeWithText("Groceries").assertExists()
    }
}
