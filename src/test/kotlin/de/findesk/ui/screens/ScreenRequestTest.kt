package de.findesk.ui.screens

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.runComposeUiTest
import de.findesk.TestData
import de.findesk.TestDatabase
import de.findesk.i18n.AppLanguage
import de.findesk.i18n.LocalStrings
import de.findesk.i18n.Translations
import de.findesk.ui.navigation.NavigationArgs
import de.findesk.ui.navigation.ScreenRequest
import de.findesk.ui.theme.FinDeskTheme
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalTestApi::class)
class ScreenRequestTest {
    private fun runNewTransactionRequest(withAccount: Boolean, request: ScreenRequest? = ScreenRequest.NEW_TRANSACTION) = runComposeUiTest {
        TestDatabase.init()
        if (withAccount) TestData().account()
        setContent {
            CompositionLocalProvider(LocalStrings provides Translations.of(AppLanguage.EN)) {
                FinDeskTheme { TransactionsScreen(args = NavigationArgs(request = request, requestId = 1)) }
            }
        }
        waitForIdle()
        if (withAccount && request != null) {
            // The accounts load on IO; the dialog follows once they are there
            waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("Add transaction").fetchSemanticsNodes().isNotEmpty() }
        } else {
            assertTrue(onAllNodesWithText("Add transaction").fetchSemanticsNodes().isEmpty(), "no dialog without an account")
        }
    }

    @Test
    fun `File - New Transaction opens the add dialog once the accounts are loaded`() = runNewTransactionRequest(withAccount = true)

    @Test
    fun `without an account the request opens nothing`() = runNewTransactionRequest(withAccount = false)

    @Test
    fun `without a request the screen opens no dialog`() = runNewTransactionRequest(withAccount = true, request = null)
}
