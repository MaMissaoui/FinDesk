package de.findesk.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import de.findesk.ui.components.MonogramColors
import de.findesk.ui.theme.CategoryColors
import de.findesk.ui.theme.ControlFillDark
import de.findesk.ui.theme.ControlFillHoverDark
import de.findesk.ui.theme.ControlFillHoverLight
import de.findesk.ui.theme.ControlFillLight
import de.findesk.ui.theme.DarkColorScheme
import de.findesk.ui.theme.Error
import de.findesk.ui.theme.ErrorDark
import de.findesk.ui.theme.ErrorOnSelectionDark
import de.findesk.ui.theme.ErrorOnSelectionLight
import de.findesk.ui.theme.LightColorScheme
import de.findesk.ui.theme.SelectedAccentDark
import de.findesk.ui.theme.SelectedAccentLight
import de.findesk.ui.theme.SelectionDark
import de.findesk.ui.theme.SelectionLight
import de.findesk.ui.theme.SidebarDark
import de.findesk.ui.theme.SidebarLight
import de.findesk.ui.theme.Success
import de.findesk.ui.theme.SuccessDark
import de.findesk.ui.theme.SuccessOnSelectionDark
import de.findesk.ui.theme.SuccessOnSelectionLight
import de.findesk.ui.theme.readableOn
import org.junit.jupiter.api.Assertions.assertEquals
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

    /** The quiet filled controls and the selected settings row carry ink and slate text at 4.5:1. */
    @Test
    fun `quiet control fills keep ink and slate readable`() {
        listOf(
            "light control fill" to (LightColorScheme to ControlFillLight),
            "light control fill hover" to (LightColorScheme to ControlFillHoverLight),
            "dark control fill" to (DarkColorScheme to ControlFillDark),
            "dark control fill hover" to (DarkColorScheme to ControlFillHoverDark),
            "light selected settings row" to (LightColorScheme to SelectedAccentLight),
            "dark selected settings row" to (DarkColorScheme to SelectedAccentDark),
        ).forEach { (label, pair) ->
            val (scheme, background) = pair
            assertContrast(scheme.onSurface, background, "ink on $label")
            assertContrast(scheme.onSurfaceVariant, background, "slate on $label")
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
        listOf("light" to LightColorScheme, "dark" to DarkColorScheme).forEach { (name, scheme) ->
            val sidebar = if (name == "light") SidebarLight else SidebarDark
            assertContrast(scheme.onSurface, sidebar, "onSurface on sidebar ($name)")
            assertContrast(scheme.onSurfaceVariant, sidebar, "onSurfaceVariant on sidebar ($name)")

            // The selected item uses the same selection container as a selected filter chip
            assertContrast(scheme.onSecondaryContainer, scheme.secondaryContainer, "selected sidebar item label ($name)")

            // The 3 dp indicator bar is a graphical object, so 3:1 (WCAG 1.4.11) suffices
            assertContrastAtLeast(scheme.primary, sidebar, 3f, "selection bar on sidebar ($name)")
            assertContrastAtLeast(scheme.primary, scheme.secondaryContainer, 3f, "selection bar on selected row ($name)")

            // A secondary line could be drawn on a selected row without the helper's content colour
            assertContrast(scheme.onSurfaceVariant, scheme.secondaryContainer, "onSurfaceVariant on selected row ($name)")

            // A negative balance stays red on a selected row (see panelAmountColor), in the selection-safe red
            val selection = if (name == "light") SelectionLight else SelectionDark
            val selectionError = if (name == "light") ErrorOnSelectionLight else ErrorOnSelectionDark
            assertContrast(selectionError, selection, "negative balance on selected row ($name)")

            // A positive balance stays green on a selected row, in the selection-safe green
            val selectionSuccess = if (name == "light") SuccessOnSelectionLight else SuccessOnSelectionDark
            assertContrast(selectionSuccess, selection, "positive balance on selected row ($name)")
        }
    }

    /** The monogram badge draws white SemiBold letters on one of eight fixed colours, in both themes. */
    @Test
    fun `white monogram letters stay readable on every monogram colour`() {
        assertEquals(8, MonogramColors.size)
        MonogramColors.forEachIndexed { index, color ->
            assertContrast(Color.White, color, "white monogram letters on colour $index")
        }
    }

    /** The new roles must read on paper and on the neutral selection fill, in both themes. */
    @Test
    fun `palette roles keep their contrast on paper and selection`() {
        listOf("light" to LightColorScheme, "dark" to DarkColorScheme).forEach { (name, scheme) ->
            val light = name == "light"
            val inflow = if (light) Success else SuccessDark
            val inflowOnSelection = if (light) SuccessOnSelectionLight else SuccessOnSelectionDark
            listOf("paper" to scheme.surface, "selection" to scheme.secondaryContainer).forEach { (where, bg) ->
                assertContrast(if (where == "paper") inflow else inflowOnSelection, bg, "inflow on $where ($name)")
                assertContrast(scheme.error, bg, "alert on $where ($name)")
                assertContrast(scheme.tertiary, bg, "caution on $where ($name)")
                assertContrast(scheme.primary, bg, "accent on $where ($name)")
                assertContrast(scheme.onSurfaceVariant, bg, "slate on $where ($name)")
            }
            assertContrastAtLeast(scheme.outline, scheme.surface, 3f, "outline on paper ($name)")
        }
    }

    private fun assertContrast(foreground: Color, background: Color, label: String) {
        assertContrastAtLeast(foreground, background, 4.5f, label)
    }

    private fun assertContrastAtLeast(foreground: Color, background: Color, minimum: Float, label: String) {
        val ratio = contrastRatio(foreground, background)
        assertTrue(ratio >= minimum, "$label contrast was ${"%.2f".format(ratio)}:1")
    }

    private fun contrastRatio(foreground: Color, background: Color): Float {
        val lighter = maxOf(foreground.luminance(), background.luminance())
        val darker = minOf(foreground.luminance(), background.luminance())
        return (lighter + 0.05f) / (darker + 0.05f)
    }
}
