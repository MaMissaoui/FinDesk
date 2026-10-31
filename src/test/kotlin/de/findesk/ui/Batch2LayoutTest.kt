package de.findesk.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import de.findesk.TestDatabase
import de.findesk.i18n.AppLanguage
import de.findesk.i18n.LocalStrings
import de.findesk.i18n.StringKey
import de.findesk.i18n.Translations
import de.findesk.repository.AccountRepositoryImpl
import de.findesk.repository.BankAccessRepositoryImpl
import de.findesk.ui.screens.AccountsScreen
import de.findesk.ui.theme.FinDeskTheme
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/** Batch 2's layout rules on the Accounts screen, plus the new strings they introduce. */
@OptIn(ExperimentalTestApi::class)
class Batch2LayoutTest {
    private val en = Translations.of(AppLanguage.EN)

    @Test
    fun `batch 2 keys read as specified in English`() {
        assertEquals("In and out", en[StringKey.DirectionAnyLabel])
        assertEquals("Add your bank to import or enter transactions.", en[StringKey.NoTransactionsAddBank])
        assertEquals("Scale doesn't start at zero", en[StringKey.AxisNotFromZero])
    }

    @Test
    fun `one online bank shows no Sync all and its card's own Sync`() = runComposeUiTest {
        TestDatabase.init()
        val bank = BankAccessRepositoryImpl().create("Test Bank", "37040044", "COBADEFFXXX", "user1").id.value
        AccountRepositoryImpl().create(bank, "DE89370400440532013000", balance = BigDecimal("1000.00"))

        setContent {
            CompositionLocalProvider(LocalStrings provides en) {
                FinDeskTheme { AccountsScreen() }
            }
        }

        waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("Test Bank").fetchSemanticsNodes().isNotEmpty() }
        onAllNodesWithText(en[StringKey.SyncAll]).assertCountEquals(0)
        onNodeWithText(en[StringKey.Sync]).assertExists()
    }

    @Test
    fun `no banks leaves the empty state's Add bank as the only one`() = runComposeUiTest {
        TestDatabase.init()

        setContent {
            CompositionLocalProvider(LocalStrings provides en) {
                FinDeskTheme { AccountsScreen() }
            }
        }

        // Until the load settles the header may still carry its own button, so wait for the one-button state
        waitUntil(timeoutMillis = 10_000) { onAllNodesWithText(en[StringKey.AddBank]).fetchSemanticsNodes().size == 1 }
        onAllNodesWithText(en[StringKey.AddBank]).assertCountEquals(1)
    }

    @Test
    fun `two accounts show no Filter accounts field`() = runComposeUiTest {
        TestDatabase.init()
        val bank = BankAccessRepositoryImpl().create("Test Bank", "37040044", "COBADEFFXXX", "user1").id.value
        val accounts = AccountRepositoryImpl()
        accounts.create(bank, "DE89370400440532013000", balance = BigDecimal("1000.00"))
        accounts.create(bank, "DE02120300000000202051", balance = BigDecimal("500.00"))

        setContent {
            CompositionLocalProvider(LocalStrings provides en) {
                FinDeskTheme { AccountsScreen() }
            }
        }

        waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("Test Bank").fetchSemanticsNodes().isNotEmpty() }
        onAllNodesWithText(en[StringKey.FilterAccounts]).assertCountEquals(0)
    }
}
