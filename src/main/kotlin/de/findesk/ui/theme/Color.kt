package de.findesk.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import java.math.BigDecimal

// Primary Colors
val PrimaryBlue = Color(0xFF2D6CDF)
val PrimaryBlueLight = Color(0xFF5B8DEF)

/** Light-theme selection background: a quiet blue tint of [PrimaryBlue] for selected rows, tabs and chips. */
val SelectionLight = Color(0xFFE3EAF9)

/** Text and icons on [SelectionLight], dark enough for 4.5:1 contrast. */
val OnSelectionLight = Color(0xFF1B3A78)

/** Dark-theme selection background: the same blue idea, muted so it does not glare next to dark surfaces. */
val SelectionDark = Color(0xFF26324D)

/** Text and icons on [SelectionDark], light enough for 4.5:1 contrast. */
val OnSelectionDark = Color(0xFFDCE5FB)

/** Light-theme sidebar: neutral, so the logo is the only saturated blue in the panel. */
val SidebarLight = Color(0xFFECEFF4)

/** Dark-theme sidebar: a neutral charcoal a step away from the content surface. */
val SidebarDark = Color(0xFF232429)

// Neutral Colors
val DarkGray = Color(0xFF2F2F2F)
val MediumGray = Color(0xFF6B7280)
val LightGray = Color(0xFFE5E7EB)
val BackgroundLight = Color(0xFFF5F7FA)
val SurfaceWhite = Color(0xFFFFFFFF)

// Semantic Colors
val Success = Color(0xFF098765)
val SuccessDark = Color(0xFF34D399)
val Warning = Color(0xFFF59E0B)
val Error = Color(0xFFDC2626)
val ErrorDark = Color(0xFFF87171)
val Info = Color(0xFF3B82F6)

/** Returns the accessible text colour for positive/negative amounts based on the current theme. */
@Composable
fun amountColor(isExpense: Boolean): Color = if (MaterialTheme.colorScheme.surface.luminance() > 0.5f) {
    if (isExpense) Error else Success
} else {
    if (isExpense) ErrorDark else SuccessDark
}

/**
 * The colour for a balance: red below zero and the theme's text colour at zero or above. Balances are stocks,
 * so only a negative one is coloured; money flows keep using [amountColor] for income and expenses.
 */
@Composable
fun balanceColor(value: BigDecimal): Color = when (value.signum()) {
    -1 -> amountColor(true)
    else -> MaterialTheme.colorScheme.onSurface
}

// Category Colors
val CategoryColors = listOf(
    Color(0xFFFF5722), // Deep Orange
    Color(0xFF4CAF50), // Green
    Color(0xFF2196F3), // Blue
    Color(0xFFFF9800), // Orange
    Color(0xFF9C27B0), // Purple
    Color(0xFFE91E63), // Pink
    Color(0xFF00BCD4), // Cyan
    Color(0xFF795548), // Brown
    Color(0xFF607D8B), // Blue Grey
    Color(0xFFCDDC39), // Lime
)

/** WCAG contrast ratio between two opaque colours, 1 to 21. */
fun contrastRatio(foreground: Color, background: Color): Float {
    val lighter = maxOf(foreground.luminance(), background.luminance())
    val darker = minOf(foreground.luminance(), background.luminance())
    return (lighter + 0.05f) / (darker + 0.05f)
}

/**
 * [color] moved towards [toward] (the theme's text colour) just far enough to read at 4.5:1 on [background].
 * Category colours are user-picked hex values, so no fixed palette can guarantee readable text in both themes.
 */
fun readableOn(color: Color, background: Color, toward: Color): Color = generateSequence(0f) { it + 0.05f }
    .takeWhile { it < 1f }
    .map { lerp(color, toward, it) }
    .firstOrNull { contrastRatio(it, background) >= 4.5f }
    ?: toward
