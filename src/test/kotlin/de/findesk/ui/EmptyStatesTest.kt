package de.findesk.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import de.findesk.i18n.AppLanguage
import de.findesk.i18n.LocalStrings
import de.findesk.i18n.StringKey
import de.findesk.i18n.Translations
import de.findesk.ui.screens.GettingStartedCard
import de.findesk.ui.screens.PendingTasksCard
import de.findesk.ui.theme.FinDeskTheme
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

@OptIn(ExperimentalTestApi::class)
class EmptyStatesTest {
    private val strings = Translations.of(AppLanguage.EN)

    @Test
    fun `the getting-started card lists the three steps and runs both actions`() = runComposeUiTest {
        var added = 0
        var opened = 0
        setContent {
            CompositionLocalProvider(LocalStrings provides strings) {
                FinDeskTheme {
                    GettingStartedCard(onAddBank = { added++ }, onOpenSettings = { opened++ })
                }
            }
        }

        onNodeWithText(strings[StringKey.StepAddBank]).assertExists()
        onNodeWithText(strings[StringKey.StepSync]).assertExists()
        onNodeWithText(strings[StringKey.StepCategorize]).assertExists()

        onNodeWithText(strings[StringKey.AddBank]).performClick()
        onNodeWithText(strings[StringKey.OpenSettings]).performClick()

        assertEquals(1, added)
        assertEquals(1, opened)
    }

    @Test
    fun `pending tasks say nothing to do only when there are none`() = runComposeUiTest {
        var pending by mutableStateOf(0)
        setContent {
            CompositionLocalProvider(LocalStrings provides strings) {
                FinDeskTheme {
                    PendingTasksCard(
                        pendingTasks = pending,
                        uncategorizedCount = if (pending == 0) 0 else 2,
                        unlinkedDocumentCount = if (pending == 0) 0 else 1,
                        onOpenTasks = {},
                    )
                }
            }
        }

        onNodeWithText(strings[StringKey.NothingPending]).assertExists()

        pending = 3
        waitForIdle()
        onNodeWithText(strings.format(StringKey.PendingSubtitle, 2, 1)).assertExists()
        onNodeWithText(strings[StringKey.NothingPending]).assertDoesNotExist()
    }
}
