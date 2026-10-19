package de.findesk.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import de.findesk.i18n.AppLanguage
import de.findesk.i18n.LocalStrings
import de.findesk.i18n.StringKey
import de.findesk.i18n.Translations
import de.findesk.model.AccountItem
import de.findesk.ui.screens.BalanceDialog
import de.findesk.ui.theme.FinDeskTheme
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/** The balance dialog's loan hint. It can't be rendered by `ScreenshotTest` (a dialog opens its own window). */
@OptIn(ExperimentalTestApi::class)
class BalanceDialogTest {
    @Test
    fun `the balance dialog asks a loan for a negative amount`() = runComposeUiTest {
        val strings = Translations.of(AppLanguage.EN)
        setContent {
            CompositionLocalProvider(LocalStrings provides strings) {
                FinDeskTheme {
                    BalanceDialog(
                        account = dialogAccount("loan"),
                        onDismiss = {},
                        onConfirm = { _, _ -> },
                    )
                }
            }
        }

        onNodeWithText(strings[StringKey.LoanBalanceHint]).assertExists()
    }

    @Test
    fun `the balance dialog shows no loan hint for a checking account`() = runComposeUiTest {
        val strings = Translations.of(AppLanguage.EN)
        setContent {
            CompositionLocalProvider(LocalStrings provides strings) {
                FinDeskTheme {
                    BalanceDialog(
                        account = dialogAccount("checking"),
                        onDismiss = {},
                        onConfirm = { _, _ -> },
                    )
                }
            }
        }

        onNodeWithText(strings[StringKey.LoanBalanceHint]).assertDoesNotExist()
    }

    private fun dialogAccount(type: String) = AccountItem(
        id = 1,
        bankAccessId = 1,
        bankName = "Bank",
        iban = "DE89370400440532013000",
        accountNumber = null,
        balance = BigDecimal.ZERO,
        currency = "EUR",
        accountType = type,
        lastUpdate = null,
    )
}
