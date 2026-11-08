package de.findesk.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.findesk.banking.BankLogos

/**
 * The eight fixed monogram backgrounds. They are mid-saturation and fixed in both themes, chosen so the white
 * SemiBold letters keep at least 4.5:1 on every one of them (see `ContrastTest`). Defined here, once, so the badge
 * and its contrast test cannot drift apart.
 */
val MonogramColors: List<Color> = listOf(
    Color(0xFF1D4ED8), // blue
    Color(0xFF0F766E), // teal
    Color(0xFF15803D), // green
    Color(0xFFB45309), // amber
    Color(0xFFB91C1C), // red
    Color(0xFFBE185D), // pink
    Color(0xFF7E22CE), // purple
    Color(0xFF475569), // slate
)

/**
 * A bank's logo on a white rounded tile, or — when [BankLogos.bankLogo] finds no logo for [name], [blz] and [bic] —
 * a monogram badge of the same [size]. Purely decorative: the bank name next to it stays the accessible label, so
 * the tile sets no content description and hides its letters from screen readers.
 */
@Composable
fun BankBadge(name: String?, blz: String?, bic: String?, size: Dp, modifier: Modifier = Modifier) {
    val logo = remember(name, blz, bic) { BankLogos.bankLogo(name, blz, bic) }
    val shape = RoundedCornerShape(size * 0.25f)
    // Border and background are drawn inside the box, so the tile never grows past its size
    val tile = modifier
        .size(size)
        .clearAndSetSemantics { }
    if (logo != null) {
        Box(
            modifier = tile
                .clip(shape)
                .background(Color.White)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
                // The logo fills the tile with a 10 % inset
                .padding(size * 0.10f),
        ) {
            Image(
                painter = painterResource("banks/${logo.id}.svg"),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
            )
        }
    } else {
        val monogram = remember(name) { BankLogos.monogram(name.orEmpty()) }
        Box(
            modifier = tile
                .clip(CircleShape)
                .background(MonogramColors[monogram.colorIndex]),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = monogram.letters,
                color = Color.White,
                fontSize = (size.value * 0.40f).sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
        }
    }
}
