package de.findesk.ui.screens

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
import de.findesk.i18n.Translations
import de.findesk.ui.navigation.NavigationArgs
import de.findesk.ui.theme.FinDeskTheme
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDate

/**
 * The booking list shows cleaned bank text; the detail panel keeps the stored raw strings.
 * The partner and purpose strings are synthetic: names, references and IBANs are not real data.
 */
@OptIn(ExperimentalTestApi::class)
class TransactionRowDisplayTest {
    private val paddedPartner = "BEISPIEL KARTE GOLD      1234 56XX XXXX 7890      Abrechnung 10/26"
    private val structuredPurpose = "EREF+REF0001 MREF+M-77 CRED+DE00ZZZ00000000001 SVWZ+Rechnung 4711"

    @Test
    fun `the row shows the cleaned title while the detail keeps the raw purpose`() = runComposeUiTest {
        TestDatabase.init()
        val data = TestData()
        val account = data.account()
        data.txn(account, LocalDate.now(), "-42.50", partner = paddedPartner, purpose = structuredPurpose)

        setContent {
            CompositionLocalProvider(LocalStrings provides Translations.of(AppLanguage.EN)) {
                FinDeskTheme { TransactionsScreen(args = NavigationArgs()) }
            }
        }

        // Once the bookings have loaded, the row shows the first padding-free part and the SVWZ text
        waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("BEISPIEL KARTE GOLD").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("Rechnung 4711").assertExists()

        // Before the detail is opened, the raw tagged purpose is nowhere on screen
        assertTrue(
            onAllNodesWithText(structuredPurpose).fetchSemanticsNodes().isEmpty(),
            "the raw purpose should only show in the detail view",
        )

        // Opening the row shows the stored, uncleaned purpose in the detail panel
        onNodeWithText("BEISPIEL KARTE GOLD").performClick()
        waitUntil(timeoutMillis = 10_000) { onAllNodesWithText(structuredPurpose).fetchSemanticsNodes().isNotEmpty() }
    }
}
