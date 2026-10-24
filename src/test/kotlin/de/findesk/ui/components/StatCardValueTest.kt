package de.findesk.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import de.findesk.i18n.AppLanguage
import de.findesk.i18n.LocalStrings
import de.findesk.i18n.Translations
import de.findesk.ui.theme.FinDeskTheme
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * A stat card's value must read as one line, whatever the window or the amount make the card narrow: the text
 * shrinks (down to 14.sp) instead of wrapping the number in two or cutting it with an ellipsis.
 */
class StatCardValueTest {
    @Test
    @OptIn(ExperimentalTestApi::class)
    fun `a very long amount renders on one line in a 160dp wide card`() = runComposeUiTest {
        val value = "-€12,345,678.99"
        var layout: TextLayoutResult? = null
        setContent {
            CompositionLocalProvider(LocalStrings provides Translations.of(AppLanguage.EN)) {
                FinDeskTheme {
                    Box(Modifier.width(160.dp)) {
                        StatCard(
                            title = "Available",
                            value = value,
                            icon = null,
                            color = Color(0xFF2196F3),
                            subtitle = null,
                            onValueLayout = { layout = it },
                        )
                    }
                }
            }
        }
        awaitIdle()

        assertEquals(1, layout!!.lineCount, "the amount is one line of text even in a very narrow card")
    }
}
