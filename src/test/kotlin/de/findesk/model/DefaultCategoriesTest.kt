package de.findesk.model

import androidx.compose.ui.graphics.Color
import de.findesk.ui.theme.CategoryColors
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The palette and the default categories must be the same 24 distinct colours, in the same order. */
class DefaultCategoriesTest {
    private fun Color.hex(): String = "#%02X%02X%02X".format((red * 255).toInt(), (green * 255).toInt(), (blue * 255).toInt())

    @Test
    fun `the palette has 24 distinct colours and CategoryColors is built from it`() {
        assertEquals(24, DefaultCategories.colors.size)
        assertEquals(24, CategoryColors.size)
        assertEquals(DefaultCategories.colors, CategoryColors.map { it.hex() })
        assertEquals(24, DefaultCategories.colors.map { it.uppercase() }.toSet().size, "the colours must be distinct")
    }

    @Test
    fun `the 24 default categories have their own entry and colour`() {
        assertEquals(24, DefaultCategories.entries.size)
        assertEquals(24, DefaultCategories.entries.map { it.second }.toSet().size, "no cycling: every category needs its own colour")
        assertEquals(DefaultCategories.colors, DefaultCategories.entries.map { it.second })
    }

    @Test
    fun `the palette stays in the documented order`() {
        assertTrue(DefaultCategories.colors.take(10).joinToString("") == "#FF5722#4CAF50#2196F3#FF9800#9C27B0#E91E63#00BCD4#795548#607D8B#CDDC39")
        assertTrue(DefaultCategories.colors.drop(10).joinToString("") == "#3F51B5#009688#FFC107#673AB7#8BC34A#03A9F4#AFB42B#880E4F#1A237E#004D40#BF360C#33691E#F06292#A1887F")
    }
}
