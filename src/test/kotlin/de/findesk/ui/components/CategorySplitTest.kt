package de.findesk.ui.components

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import de.findesk.i18n.AppLanguage
import de.findesk.i18n.LocalStrings
import de.findesk.i18n.Translations
import de.findesk.model.CategoryShare
import de.findesk.ui.theme.FinDeskTheme
import de.findesk.util.Formats
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.math.BigDecimal

@OptIn(ExperimentalTestApi::class)
class CategorySplitTest {
    private fun slices(count: Int) = (count downTo 1).map { CategoryShare(it, "Category $it", BigDecimal(it.toString()), "#FF5722") }

    @Test
    fun `ten slices with maxRows 8 show 8 rows plus 2 more categories`() = runComposeUiTest {
        val selected = mutableListOf<CategoryShare>()
        setContent {
            CompositionLocalProvider(LocalStrings provides Translations.of(AppLanguage.EN)) {
                FinDeskTheme {
                    CategorySplit(slices(10), "EUR", BigDecimal.ZERO, onSelect = { selected.add(it) }, modifier = Modifier.testTag("split"))
                }
            }
        }
        onNodeWithText("2 more categories").assertExists()
        onNodeWithText("9 more categories").assertDoesNotExist()
        // 8 name rows, the biggest first
        onNodeWithContentDescription("Category 10, 18%, €10.00").assertExists()
        onNodeWithContentDescription("Category 3, 5%, €3.00").assertExists()
        onNodeWithContentDescription("Category 2, 4%, €2.00").assertDoesNotExist()
        onNodeWithContentDescription("Category 1, 2%, €1.00").assertDoesNotExist()

        onNodeWithContentDescription("Category 8, 15%, €8.00").performClick()
        val clicked = selected.single()
        assertEquals(8, clicked.categoryId)
        assertEquals(0, clicked.amount.compareTo(BigDecimal("8.00")))
        assertEquals("Category 8", clicked.name)
    }

    @Test
    fun `the transfers line appears only when there is something to name`() = runComposeUiTest {
        setContent {
            CompositionLocalProvider(LocalStrings provides Translations.of(AppLanguage.EN)) {
                FinDeskTheme {
                    CategorySplit(slices(1), "EUR", BigDecimal.ZERO, onSelect = null)
                }
            }
        }
        onNodeWithText("Not counted: transfers €0.00").assertDoesNotExist()

        setContent {
            CompositionLocalProvider(LocalStrings provides Translations.of(AppLanguage.EN)) {
                FinDeskTheme {
                    CategorySplit(slices(1), "EUR", BigDecimal("500.00"), onSelect = null)
                }
            }
        }
        onNodeWithText("Not counted: transfers €500.00").assertExists()
    }

    @Test
    fun `runs without a click target and without a language surprise`() = runComposeUiTest {
        setContent {
            CompositionLocalProvider(LocalStrings provides Translations.of(AppLanguage.DE)) {
                FinDeskTheme {
                    CategorySplit(slices(1), "EUR", BigDecimal("10.00"), onSelect = null)
                }
            }
        }
        onNodeWithText("Nicht gezählt: Umbuchungen", substring = true).assertExists()
    }
}
