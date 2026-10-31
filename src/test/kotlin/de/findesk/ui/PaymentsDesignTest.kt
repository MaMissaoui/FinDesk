package de.findesk.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onParent
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import de.findesk.TestDatabase
import de.findesk.i18n.AppLanguage
import de.findesk.i18n.AppStrings
import de.findesk.i18n.LocalStrings
import de.findesk.i18n.StringKey
import de.findesk.i18n.Translations
import de.findesk.repository.AccountRepositoryImpl
import de.findesk.repository.BankAccessRepositoryImpl
import de.findesk.ui.screens.PaymentsScreen
import de.findesk.ui.screens.ReviewHeader
import de.findesk.ui.theme.FinDeskTheme
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.math.BigDecimal

@OptIn(ExperimentalTestApi::class)
class PaymentsDesignTest {
    private val en = Translations.of(AppLanguage.EN)

    @Test
    fun `the action labels and new payment hints are translated`() {
        val de = Translations.of(AppLanguage.DE)
        assertEquals("Send €850.00", en.format(StringKey.SendAmount, "€850.00"))
        assertEquals("€850.00 senden", de.format(StringKey.SendAmount, "€850.00"))
        assertEquals("Review changes", en[StringKey.ReviewChanges])
        assertEquals("Create standing order", en[StringKey.CreateStandingOrderAction])
        assertEquals("Send changes to bank", en[StringKey.SaveChangesToBank])
        assertEquals("Enter the recipient's name", en[StringKey.EnterRecipientName])
        assertEquals("Enter the recipient's IBAN", en[StringKey.EnterRecipientIban])
        assertEquals("The bank didn't confirm this order. Check online banking before sending it again.", en[StringKey.PaymentUnknownHint])
        assertEquals("Details: %s", en[StringKey.PaymentDetail])
        assertEquals("Your bank doesn't allow deleting this order here", en[StringKey.DeleteNotAllowedByBank])
    }

    @Test
    fun `the review header warning spans the full header width`() = runComposeUiTest {
        setContent {
            CompositionLocalProvider(LocalStrings provides en) {
                FinDeskTheme {
                    Box(Modifier.width(400.dp).testTag("header")) {
                        ReviewHeader(amount = "€850.00", recipient = "Vermieter GmbH", warning = WARNING)
                    }
                }
            }
        }

        onNodeWithText(WARNING).onParent().assertWidthIsEqualTo(400.dp)
    }

    @Test
    fun `a one-off transfer is confirmed by its amount after acknowledging it`() = runComposeUiTest {
        AppStrings.set(AppLanguage.EN)
        TestDatabase.init()
        val bank = BankAccessRepositoryImpl().create("Sparkasse KölnBonn", "37050198", "COLSDE33XXX", "demo-user").id.value
        AccountRepositoryImpl().create(bank, "DE89370400440532013000", balance = BigDecimal("2450.37"))

        setContent {
            CompositionLocalProvider(LocalStrings provides en) { FinDeskTheme { PaymentsScreen() } }
        }
        waitUntil(timeoutMillis = 10_000) { onAllNodes(hasSetTextAction()).fetchSemanticsNodes().size >= 5 }

        onAllNodes(hasSetTextAction())[0].performTextInput("Vermieter GmbH")
        onAllNodes(hasSetTextAction())[1].performTextInput("DE02120300000000202051")
        onAllNodes(hasSetTextAction())[3].performTextInput("850.00")

        onNodeWithText("Review transfer").performClick()
        waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("Send €850.00").fetchSemanticsNodes().isNotEmpty() }

        onNodeWithText("Send €850.00").assertIsNotEnabled()
        onNode(isToggleable()).performClick()
        onNodeWithText("Send €850.00").assertIsEnabled()
    }

    private companion object {
        const val WARNING = "An earlier order has no confirmed outcome. Check your online banking first."
    }
}
