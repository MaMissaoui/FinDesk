package de.findesk.ui.screens

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.test.runComposeUiTest
import de.findesk.TestDatabase
import de.findesk.i18n.AppLanguage
import de.findesk.i18n.LocalStrings
import de.findesk.i18n.Translations
import de.findesk.model.PaymentAction
import de.findesk.model.PaymentOrderStatus
import de.findesk.repository.AccountRepositoryImpl
import de.findesk.repository.BankAccessRepositoryImpl
import de.findesk.repository.PaymentOrderRepositoryImpl
import de.findesk.ui.theme.FinDeskTheme
import org.junit.jupiter.api.Test
import java.math.BigDecimal

@OptIn(ExperimentalTestApi::class)
class TransferAgainTest {
    @Test
    fun `Transfer again fills the form from an earlier transfer without sending anything`() = runComposeUiTest {
        TestDatabase.init()
        val bank = BankAccessRepositoryImpl().create("Sparkasse KölnBonn", "37050198", "COLSDE33XXX", "demo-user").id.value
        AccountRepositoryImpl().create(bank, "DE89370400440532013000", balance = BigDecimal("2450.37"))
        val payments = PaymentOrderRepositoryImpl()
        val id = payments.create(PaymentAction.TRANSFER, "DE89370400440532013000", "Vermieter GmbH", "DE02120300000000202051", BigDecimal("850.00"), "EUR", "Miete Oktober", null, null)
        payments.complete(id, PaymentOrderStatus.ACCEPTED)

        setContent {
            CompositionLocalProvider(LocalStrings provides Translations.of(AppLanguage.EN)) { FinDeskTheme { PaymentsScreen() } }
        }
        waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("Vermieter GmbH").fetchSemanticsNodes().isNotEmpty() }

        onNodeWithText("Vermieter GmbH").performMouseInput { rightClick(center) }
        onNodeWithText("Transfer again").performClick()
        waitForIdle()

        // The form's fields now hold the earlier order; the review step is still ahead
        onNode(hasSetTextAction() and hasText("Vermieter GmbH")).assertExists()
        onNode(hasSetTextAction() and hasText("DE02120300000000202051")).assertExists()
        onNode(hasSetTextAction() and hasText("850.00")).assertExists()
        onNode(hasSetTextAction() and hasText("Miete Oktober")).assertExists()
        onAllNodesWithText("Confirm transfer").fetchSemanticsNodes().isEmpty().let { check(it) { "no review opened by itself" } }
    }
}
