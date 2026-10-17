package de.findesk.ui.screens

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import de.findesk.banking.BankDirectory
import de.findesk.i18n.AppLanguage
import de.findesk.i18n.LocalStrings
import de.findesk.i18n.StringKey
import de.findesk.i18n.Translations
import de.findesk.ui.theme.FinDeskTheme
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Drives the add-bank dialog: a typed BLZ should be suggested and fill the form on selection. */
@OptIn(ExperimentalTestApi::class)
class AddBankDialogTest {
    private val strings = Translations.of(AppLanguage.EN)

    @Test
    fun `suggests a bank by BLZ and fills the form on selection`() = runComposeUiTest {
        var confirmed: List<String>? = null
        setContent {
            CompositionLocalProvider(LocalStrings provides strings) {
                FinDeskTheme {
                    AddBankDialog(
                        search = BankDirectory::search,
                        lookup = BankDirectory::lookup,
                        validate = { name, blz -> if (name.isBlank() || blz.length != 8) "invalid" else null },
                        onDismiss = {},
                        onConfirm = { name, blz, bic, user -> confirmed = listOf(name, blz, bic, user) },
                    )
                }
            }
        }

        // The first editable field is the bank search box.
        onAllNodes(hasSetTextAction())[0].performTextInput("37050198")

        // The matching branch is offered; picking it fills name/BLZ/BIC.
        onNode(hasClickAction() and hasText("Sparkasse KölnBonn")).performClick()

        // The FinTS endpoint the directory knows for that bank is proposed before saving.
        onNodeWithText("https://banking-rl7.s-fints-pt-rl.de/fints30", substring = true).assertExists()

        onNodeWithText(strings[StringKey.Add]).performClick()
        assertEquals(listOf("Sparkasse KölnBonn", "37050198", "COLSDE33XXX", ""), confirmed)
    }
}
