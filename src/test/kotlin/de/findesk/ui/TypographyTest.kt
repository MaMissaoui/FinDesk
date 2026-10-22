package de.findesk.ui

import androidx.compose.material3.Text
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import de.findesk.ui.theme.FinDeskFontFamily
import de.findesk.ui.theme.FinDeskTheme
import de.findesk.ui.theme.FinDeskTypography
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The single typeface and the tighter type scale, plus the bundled font files it relies on. */
class TypographyTest {
    private val styles: List<Pair<String, TextStyle>> = listOf(
        "displayLarge" to FinDeskTypography.displayLarge,
        "displayMedium" to FinDeskTypography.displayMedium,
        "displaySmall" to FinDeskTypography.displaySmall,
        "headlineLarge" to FinDeskTypography.headlineLarge,
        "headlineMedium" to FinDeskTypography.headlineMedium,
        "headlineSmall" to FinDeskTypography.headlineSmall,
        "titleLarge" to FinDeskTypography.titleLarge,
        "titleMedium" to FinDeskTypography.titleMedium,
        "titleSmall" to FinDeskTypography.titleSmall,
        "bodyLarge" to FinDeskTypography.bodyLarge,
        "bodyMedium" to FinDeskTypography.bodyMedium,
        "bodySmall" to FinDeskTypography.bodySmall,
        "labelLarge" to FinDeskTypography.labelLarge,
        "labelMedium" to FinDeskTypography.labelMedium,
        "labelSmall" to FinDeskTypography.labelSmall,
    )

    @Test
    fun `every style uses IBM Plex Sans with tabular figures`() {
        assertEquals(15, styles.size)
        styles.forEach { (name, style) ->
            assertEquals(FinDeskFontFamily, style.fontFamily, "$name font family")
            assertEquals("tnum", style.fontFeatureSettings, "$name font features")
        }
    }

    @Test
    fun `the type scale is the one in the spec`() {
        assertScale("headlineSmall", FinDeskTypography.headlineSmall, 24, 32, FontWeight.SemiBold)
        assertScale("titleLarge", FinDeskTypography.titleLarge, 20, 28, FontWeight.SemiBold)
        assertScale("titleMedium", FinDeskTypography.titleMedium, 16, 24, FontWeight.SemiBold)
        assertScale("bodyMedium", FinDeskTypography.bodyMedium, 14, 20, FontWeight.Normal)
        assertScale("bodySmall", FinDeskTypography.bodySmall, 12, 16, FontWeight.Normal)
        assertScale("labelSmall", FinDeskTypography.labelSmall, 12, 16, FontWeight.Medium)
    }

    private fun assertScale(name: String, style: TextStyle, size: Int, lineHeight: Int, weight: FontWeight) {
        assertEquals(size.sp, style.fontSize, "$name size")
        assertEquals(lineHeight.sp, style.lineHeight, "$name line height")
        assertEquals(weight, style.fontWeight, "$name weight")
    }

    @Test
    fun `the bundled font files are on the classpath`() {
        val loader = javaClass.classLoader
        listOf(
            "fonts/IBMPlexSans-Regular.ttf",
            "fonts/IBMPlexSans-Medium.ttf",
            "fonts/IBMPlexSans-SemiBold.ttf",
            "fonts/IBMPlexSans-Bold.ttf",
        ).forEach { path ->
            val stream = loader.getResourceAsStream(path)
            assertNotNull(stream, "$path on the classpath")
            stream!!.use { input ->
                val magic = ByteArray(4).also { read -> assertEquals(4, input.read(read), "$path header") }
                assertTrue(
                    magic.contentEquals(byteArrayOf(0x00, 0x01, 0x00, 0x00)),
                    "$path starts with the TrueType magic",
                )
            }
        }
        assertNotNull(loader.getResource("fonts/OFL.txt"), "fonts/OFL.txt on the classpath")
    }

    @Test
    @OptIn(ExperimentalTestApi::class)
    fun `amounts render in the bundled face without an exception`() = runComposeUiTest {
        setContent {
            FinDeskTheme {
                Text("€2,450.37")
            }
        }
        onNodeWithText("€2,450.37").assertExists()
    }
}
