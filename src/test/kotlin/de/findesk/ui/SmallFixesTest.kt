package de.findesk.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import de.findesk.i18n.AppLanguage
import de.findesk.i18n.LocalStrings
import de.findesk.i18n.StringKey
import de.findesk.i18n.Translations
import de.findesk.ui.screens.AccountRowMenu
import de.findesk.ui.screens.ReviewHeader
import de.findesk.ui.screens.UnlockScreen
import de.findesk.ui.theme.FinDeskTheme
import de.findesk.viewmodel.UnlockViewModel
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

@OptIn(ExperimentalTestApi::class)
class SmallFixesTest {
    private val strings = Translations.of(AppLanguage.EN)

    @TempDir
    lateinit var root: File

    @Test
    fun `the account row menu offers delete and calls the delete callback`() = runComposeUiTest {
        var deleted = 0
        setContent {
            CompositionLocalProvider(LocalStrings provides strings) {
                FinDeskTheme {
                    AccountRowMenu(
                        onTransactions = {},
                        onDetails = {},
                        onRename = {},
                        onChangeType = {},
                        onEditBalance = {},
                        onDelete = { deleted++ },
                    )
                }
            }
        }

        onNodeWithContentDescription(strings[StringKey.More]).performClick()
        onNodeWithText(strings[StringKey.DeleteAccount]).assertExists()
        onNodeWithText(strings[StringKey.DeleteAccount]).performClick()

        assertEquals(1, deleted)
    }

    @Test
    fun `the review header shows the amount and recipient without a warning when none is given`() = runComposeUiTest {
        setContent {
            CompositionLocalProvider(LocalStrings provides strings) {
                FinDeskTheme { ReviewHeader(amount = "€850.00", recipient = "Vermieter GmbH", warning = null) }
            }
        }

        onNodeWithText("€850.00").assertExists()
        onNodeWithText("to Vermieter GmbH").assertExists()
        onNodeWithText(WARNING).assertDoesNotExist()
    }

    @Test
    fun `the review header shows the warning when one is given`() = runComposeUiTest {
        setContent {
            CompositionLocalProvider(LocalStrings provides strings) {
                FinDeskTheme { ReviewHeader(amount = "€850.00", recipient = "Vermieter GmbH", warning = WARNING) }
            }
        }

        onNodeWithText("€850.00").assertExists()
        onNodeWithText("to Vermieter GmbH").assertExists()
        onNodeWithText(WARNING).assertExists()
    }

    @Test
    fun `the unlock screen names the open profile for screen readers`() = runComposeUiTest {
        val vm = UnlockViewModel(root, rootDir = root)
        try {
            setContent {
                CompositionLocalProvider(LocalStrings provides strings) {
                    FinDeskTheme { UnlockScreen(vm, profileName = "Business") }
                }
            }

            onNodeWithContentDescription(strings.format(StringKey.UnlockProfile, "Business")).assertExists()
        } finally {
            vm.close()
        }
    }

    private companion object {
        const val WARNING = "An earlier order has no confirmed outcome. Check your online banking first."
    }
}
