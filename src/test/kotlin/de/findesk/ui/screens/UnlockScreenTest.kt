package de.findesk.ui.screens

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import de.findesk.i18n.AppLanguage
import de.findesk.i18n.LocalStrings
import de.findesk.i18n.StringKey
import de.findesk.i18n.Translations
import de.findesk.plaintextDatabase
import de.findesk.ui.theme.FinDeskTheme
import de.findesk.viewmodel.UnlockViewModel
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** The unlock screen is where the app can still warn about plaintext copies that encrypting an earlier start left behind. */
@OptIn(ExperimentalTestApi::class)
class UnlockScreenTest {
    @TempDir
    lateinit var root: File

    private val strings = Translations.of(AppLanguage.EN)

    @Test
    fun `lists the plaintext copies that encryption cannot reach`() = runComposeUiTest {
        plaintextDatabase(File(root, "pre-restore-20260101-120000/findesk.db"))
        val vm = UnlockViewModel(root, rootDir = root)
        try {
            setContent {
                CompositionLocalProvider(LocalStrings provides strings) {
                    FinDeskTheme { UnlockScreen(vm, null) }
                }
            }

            onNodeWithText(strings[StringKey.PlaintextLeftoversTitle]).assertExists()
            onNodeWithText("${strings[StringKey.PlaintextLeftoverPreRestore]} · pre-restore-20260101-120000/findesk.db").assertExists()
        } finally {
            vm.close()
        }
    }

    @Test
    fun `says nothing when there is nothing left behind`() = runComposeUiTest {
        val vm = UnlockViewModel(root, rootDir = root)
        try {
            setContent {
                CompositionLocalProvider(LocalStrings provides strings) {
                    FinDeskTheme { UnlockScreen(vm, null) }
                }
            }

            onNodeWithText(strings[StringKey.PlaintextLeftoversTitle]).assertDoesNotExist()
        } finally {
            vm.close()
        }
    }
}
