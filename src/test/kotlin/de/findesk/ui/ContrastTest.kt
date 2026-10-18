package de.findesk.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import de.findesk.ui.theme.CategoryColors
import de.findesk.ui.theme.DarkColorScheme
import de.findesk.ui.theme.Error
import de.findesk.ui.theme.ErrorDark
import de.findesk.ui.theme.LightColorScheme
import de.findesk.ui.theme.PrimaryBlue
import de.findesk.ui.theme.PrimaryBlueDark
import de.findesk.ui.theme.Success
import de.findesk.ui.theme.SuccessDark
import de.findesk.ui.theme.readableOn
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * WCAG 2.1 contrast checks for the text/background pairs FinDesk actually uses.
 * Normal text must achieve at least 4.5:1.
 */
class ContrastTest {

    @Test
    fun `light theme text contrasts are sufficient`() {
        assertContrast(LightColorScheme.onBackground, LightColorScheme.background, "onBackground on background (light)")
        assertContrast(LightColorScheme.onSurface, LightColorScheme.surface, "onSurface on surface (light)")
        assertContrast(LightColorScheme.onSurfaceVariant, LightColorScheme.surface, "onSurfaceVariant on surface (light)")
        assertContrast(LightColorScheme.error, LightColorScheme.surface, "error text on surface (light)")
        assertContrast(Success, LightColorScheme.surface, "Success amount on surface (light)")
        assertContrast(Error, LightColorScheme.surface, "Error amount on surface (light)")
        assertContrast(LightColorScheme.onErrorContainer, LightColorScheme.errorContainer, "onErrorContainer on errorContainer (light)")
    }

    @Test
    fun `dark theme text contrasts are sufficient`() {
        assertContrast(DarkColorScheme.onBackground, DarkColorScheme.background, "onBackground on background (dark)")
        assertContrast(DarkColorScheme.onSurface, DarkColorScheme.surface, "onSurface on surface (dark)")
        assertContrast(DarkColorScheme.onSurfaceVariant, DarkColorScheme.surface, "onSurfaceVariant on surface (dark)")
        assertContrast(DarkColorScheme.error, DarkColorScheme.surface, "error text on surface (dark)")
        assertContrast(SuccessDark, DarkColorScheme.surface, "Success amount on surface (dark)")
        assertContrast(ErrorDark, DarkColorScheme.surface, "Error amount on surface (dark)")
        assertContrast(DarkColorScheme.onErrorContainer, DarkColorScheme.errorContainer, "onErrorContainer on errorContainer (dark)")
    }

    /** Cards draw on surfaceContainerHighest and dialogs on surfaceContainerHigh, not on surface. */
    @Test
    fun `text on cards and dialogs keeps 4_5 to 1`() {
        listOf("light" to LightColorScheme, "dark" to DarkColorScheme).forEach { (name, scheme) ->
            val success = if (name == "light") Success else SuccessDark
            val error = if (name == "light") Error else ErrorDark
            listOf("card" to scheme.surfaceContainerHighest, "dialog" to scheme.surfaceContainerHigh).forEach { (where, bg) ->
                assertContrast(scheme.onSurface, bg, "onSurface on $where ($name)")
                assertContrast(scheme.onSurfaceVariant, bg, "onSurfaceVariant on $where ($name)")
                assertContrast(scheme.primary, bg, "primary text button on $where ($name)")
                assertContrast(success, bg, "income amount on $where ($name)")
                assertContrast(error, bg, "expense amount on $where ($name)")
            }
            assertContrast(scheme.onSurfaceVariant, scheme.surfaceVariant, "onSurfaceVariant on surfaceVariant, e.g. table header ($name)")
        }
    }

    /** Colour pairs Material components draw with on their own: filled buttons, selected filter chips. */
    @Test
    fun `filled buttons and selected chips keep 4_5 to 1`() {
        listOf("light" to LightColorScheme, "dark" to DarkColorScheme).forEach { (name, scheme) ->
            assertContrast(scheme.onPrimary, scheme.primary, "filled button label ($name)")
            assertContrast(scheme.onSecondaryContainer, scheme.secondaryContainer, "selected filter chip label ($name)")
            assertContrast(scheme.onSurfaceVariant, scheme.surface, "unselected tab label ($name)")
        }
    }

    /** Category badges draw the label in the category colour on a 12% tint of it; users pick any colour. */
    @Test
    fun `category badges stay readable for every colour`() {
        val extremes = listOf(Color(0xFFFFF59D), Color(0xFF3E2723), Color.White, Color.Black)
        listOf(LightColorScheme, DarkColorScheme).forEach { scheme ->
            (CategoryColors + extremes).forEach { color ->
                val tint = color.copy(alpha = 0.12f).compositeOver(scheme.surfaceContainerHighest)
                assertContrast(readableOn(color, tint, scheme.onSurface), tint, "badge text for $color")
            }
        }
    }

    @Test
    fun `sidebar text contrasts are sufficient`() {
        val white = Color.White
        assertContrast(white, PrimaryBlue, "white sidebar text on PrimaryBlue")

        // The selected tab draws a translucent dark highlight behind the label; Compose blends in sRGB.
        assertContrast(white, Color.Black.copy(alpha = 0.2f).compositeOver(PrimaryBlue), "white label on selected sidebar tab")
        assertContrast(white, PrimaryBlueDark, "white sidebar text on PrimaryBlueDark (dark theme)")
    }

    private fun assertContrast(foreground: Color, background: Color, label: String) {
        val ratio = contrastRatio(foreground, background)
        assertTrue(ratio >= 4.5f, "$label contrast was ${"%.2f".format(ratio)}:1")
    }

    private fun contrastRatio(foreground: Color, background: Color): Float {
        val lighter = maxOf(foreground.luminance(), background.luminance())
        val darker = minOf(foreground.luminance(), background.luminance())
        return (lighter + 0.05f) / (darker + 0.05f)
    }
}
