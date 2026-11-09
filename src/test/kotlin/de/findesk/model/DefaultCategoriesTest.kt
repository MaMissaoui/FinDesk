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
        assertTrue(DefaultCategories.colors.take(10).joinToString("") == "#E76530#4693F3#C38400#D56A93#867DFF#14AC77#CF4E12#2F7DDB#A77105#BD547E")
        assertTrue(DefaultCategories.colors.drop(10).joinToString("") == "#7364EC#029465#DB5923#3B88E7#B57A03#C95F88#7C6FF8#00A06E#BE4403#2170CD#986603#AF4872#6857DE#07865C")
    }
}
