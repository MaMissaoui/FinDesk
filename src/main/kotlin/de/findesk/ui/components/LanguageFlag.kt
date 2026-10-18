package de.findesk.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import de.findesk.i18n.AppLanguage

/**
 * The flag shown for a language in the top bar's language menu, drawn rather than an emoji: Windows has no flag
 * emoji. English uses the UK flag.
 */
@Composable
fun LanguageFlag(language: AppLanguage, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(2.dp)
    val border = MaterialTheme.colorScheme.outlineVariant
    Canvas(
        modifier
            .size(width = 20.dp, height = 14.dp)
            .clip(shape)
            .border(1.dp, border, shape)
            .semantics { contentDescription = language.nativeName },
    ) {
        when (language) {
            AppLanguage.DE -> horizontalStripes(listOf(Color.Black, Color(0xFFDD0000), Color(0xFFFFCE00)))
            AppLanguage.FR -> verticalStripes(listOf(Color(0xFF002395), Color.White, Color(0xFFED2939)))
            AppLanguage.EN -> unionJack()
        }
    }
}

private fun DrawScope.horizontalStripes(colors: List<Color>) {
    val h = size.height / colors.size
    colors.forEachIndexed { i, c -> drawRect(c, topLeft = Offset(0f, i * h), size = Size(size.width, h)) }
}

private fun DrawScope.verticalStripes(colors: List<Color>) {
    val w = size.width / colors.size
    colors.forEachIndexed { i, c -> drawRect(c, topLeft = Offset(i * w, 0f), size = Size(w, size.height)) }
}

/** Simplified but recognisable at 20×14 dp: blue field, white and red diagonals, white cross, red cross. */
private fun DrawScope.unionJack() {
    val blue = Color(0xFF012169)
    val red = Color(0xFFC8102E)
    val w = size.width
    val h = size.height
    drawRect(blue)
    clipRect {
        drawLine(Color.White, Offset(0f, 0f), Offset(w, h), strokeWidth = h * 0.2f)
        drawLine(Color.White, Offset(w, 0f), Offset(0f, h), strokeWidth = h * 0.2f)
        drawLine(red, Offset(0f, 0f), Offset(w, h), strokeWidth = h * 0.07f)
        drawLine(red, Offset(w, 0f), Offset(0f, h), strokeWidth = h * 0.07f)
    }
    drawRect(Color.White, topLeft = Offset(w * 0.4f, 0f), size = Size(w * 0.2f, h))
    drawRect(Color.White, topLeft = Offset(0f, h * 0.35f), size = Size(w, h * 0.3f))
    drawRect(red, topLeft = Offset(w * 0.44f, 0f), size = Size(w * 0.12f, h))
    drawRect(red, topLeft = Offset(0f, h * 0.41f), size = Size(w, h * 0.18f))
}
