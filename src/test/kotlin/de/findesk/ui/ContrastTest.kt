package de.findesk.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import de.findesk.ui.theme.DarkColorScheme
import de.findesk.ui.theme.Error
import de.findesk.ui.theme.ErrorDark
import de.findesk.ui.theme.LightColorScheme
import de.findesk.ui.theme.PrimaryBlue
import de.findesk.ui.theme.Success
import de.findesk.ui.theme.SuccessDark
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

    @Test
    fun `sidebar text contrasts are sufficient`() {
        val white = Color.White
        assertContrast(white, PrimaryBlue, "white sidebar text on PrimaryBlue")

        // The selected tab draws a translucent dark highlight behind the label; Compose blends in sRGB.
        assertContrast(white, Color.Black.copy(alpha = 0.2f).compositeOver(PrimaryBlue), "white label on selected sidebar tab")
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
