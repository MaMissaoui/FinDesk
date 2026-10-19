package de.findesk.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import de.findesk.i18n.AppLanguage
import de.findesk.i18n.LocalStrings
import de.findesk.i18n.Translations
import de.findesk.ui.components.CompactSearchField
import de.findesk.ui.components.OverflowItem
import de.findesk.ui.components.OverflowMenu
import de.findesk.ui.components.SingleChoiceRow
import de.findesk.ui.screens.AmountFilter
import de.findesk.ui.theme.FinDeskTheme
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.math.BigDecimal

@OptIn(ExperimentalTestApi::class)
class ToolbarComponentsTest {
    @Composable
    private fun Host(content: @Composable () -> Unit) {
        CompositionLocalProvider(LocalStrings provides Translations.of(AppLanguage.EN)) {
            FinDeskTheme { content() }
        }
    }

    @Test
    fun `compact search field is 40 dp tall, reports typing and clears`() = runComposeUiTest {
        var value by mutableStateOf("")
        setContent {
            Host {
                CompactSearchField(value, { value = it }, "Search partner or purpose…", Modifier.testTag("search"))
            }
        }

        onNodeWithTag("search").assertHeightIsEqualTo(40.dp)
        onNodeWithTag("search").performTextInput("Miete")
        assertEquals("Miete", value)

        onNodeWithContentDescription("Clear search").performClick()
        assertEquals("", value)
    }

    @Test
    fun `overflow menu shows its items and a disabled item explains itself without acting`() = runComposeUiTest {
        var clicks = 0
        setContent {
            Host {
                OverflowMenu(
                    items = listOf(
                        OverflowItem("Auto-categorize", Icons.Default.Refresh, onClick = { clicks++ }),
                        OverflowItem("Export CSV", null, disabledReason = "No transactions in this view to export", onClick = { clicks += 10 }),
                    ),
                )
            }
        }

        onNodeWithContentDescription("More").performClick()
        onNodeWithText("Auto-categorize").assertExists()
        onNodeWithText("Export CSV").assertExists()
        onNodeWithText("No transactions in this view to export").assertExists()

        onNodeWithText("Export CSV").assertIsNotEnabled().performClick()
        assertEquals(0, clicks)

        onNodeWithText("Auto-categorize").performClick()
        assertEquals(1, clicks)
    }

    @Test
    fun `single choice row selects an enabled option and ignores a disabled one`() = runComposeUiTest {
        val picks = mutableListOf<String>()
        setContent {
            Host {
                SingleChoiceRow(
                    options = listOf("All", "Income", "Expenses"),
                    selected = "All",
                    label = { it },
                    onSelect = { picks += it },
                    enabled = { it != "Expenses" },
                )
            }
        }

        onNodeWithText("Income").performClick()
        assertEquals(listOf("Income"), picks)

        onNodeWithText("Expenses").assertIsNotEnabled().performClick()
        assertEquals(listOf("Income"), picks)
    }

    private fun assertAmountLabel(min: BigDecimal?, max: BigDecimal?, expected: String) = runComposeUiTest {
        setContent { Host { AmountFilter(min, max) { _, _ -> } } }
        onNodeWithText(expected).assertExists()
    }

    @Test
    fun `amount filter says any amount without bounds`() = assertAmountLabel(null, null, "Any amount")

    @Test
    fun `amount filter shows a minimum`() = assertAmountLabel(BigDecimal("10"), null, "≥ €10.00")

    @Test
    fun `amount filter shows a maximum`() = assertAmountLabel(null, BigDecimal("50"), "≤ €50.00")

    @Test
    fun `amount filter shows both bounds with an en dash`() = assertAmountLabel(BigDecimal("10"), BigDecimal("50"), "€10.00–€50.00")
}
