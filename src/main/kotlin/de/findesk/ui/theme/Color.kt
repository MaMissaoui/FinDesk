package de.findesk.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import de.findesk.model.DefaultCategories
import de.findesk.util.BalanceTone
import java.math.BigDecimal

// Ink and neutrals: the statement's calm, blue-leaning greys.
val InkLight = Color(0xFF1C2733)
val InkDark = Color(0xFFE6EAEE)
val SlateLight = Color(0xFF5B6875)
val SlateDark = Color(0xFF9AA5B1)
val PaperLight = Color(0xFFFFFFFF)
val PaperDark = Color(0xFF1A2129)
val DeskLight = Color(0xFFEEF1F4)
val DeskDark = Color(0xFF11161C)
val RuleLight = Color(0xFFDDE2E7)
val RuleDark = Color(0xFF2C3540)
val OutlineLight = Color(0xFF7D8996)
val OutlineDark = Color(0xFF6E7D8C)

// One deep FinDesk blue carries actions, focus, links and the selection bar.
val AccentLight = Color(0xFF1F4FA8)
val AccentDark = Color(0xFF8AB0F0)
val OnAccentLight = Color(0xFFFFFFFF)
val OnAccentDark = Color(0xFF0B1A33)
val AccentContainerLight = Color(0xFFDCE6F7)
val OnAccentContainerLight = Color(0xFF0B2A66)
val AccentContainerDark = Color(0xFF1E3A6E)
val OnAccentContainerDark = Color(0xFFDCE6F7)

// Selection is a neutral fill, not a blue tint, so the accent bar does the pointing.
val SelectedFillLight = Color(0xFFE4EBEF)
val SelectedFillDark = Color(0xFF232D37)
val OnSelectedFillLight = InkLight
val OnSelectedFillDark = InkDark

// Quiet filled controls: buttons, icon buttons and the tracks of segmented choices. No outline.
val ControlFillLight = Color(0xFFE6EAEE)
val ControlFillDark = Color(0xFF26303A)
val ControlFillHoverLight = Color(0xFFE1E6EA)
val ControlFillHoverDark = Color(0xFF2F3A45)

// The selected settings row: a light accent tint on paper, with the same ink and slate text.
val SelectedAccentLight = Color(0xFFDCE6F5)
val SelectedAccentDark = Color(0xFF1E2F4D)

/** The quiet filled-control background: neutral in both themes. */
@Composable
fun controlFill(): Color = if (isLightTheme()) ControlFillLight else ControlFillDark

/** The hover state of a quiet filled control. */
@Composable
fun controlFillHover(): Color = if (isLightTheme()) ControlFillHoverLight else ControlFillHoverDark

/** The selected settings row's accent tint. */
@Composable
fun selectedAccent(): Color = if (isLightTheme()) SelectedAccentLight else SelectedAccentDark

// Money in is the only green; expenses are ink. Red is reserved for real problems.
val MoneyIn = Color(0xFF1E7B4F)
val MoneyInDark = Color(0xFF5CC28F)
val ChartIn = MoneyIn
val ChartInDark = MoneyInDark
val ChartOut = Color(0xFF7A8795)
val ChartOutDark = Color(0xFF6E7D8C)

// Names kept for the call sites that still use them, now carrying the new palette.
val PrimaryBlue = AccentLight
val PrimaryBlueLight = AccentDark
val SelectionLight = SelectedFillLight
val SelectionDark = SelectedFillDark
val OnSelectionLight = OnSelectedFillLight
val OnSelectionDark = OnSelectedFillDark

/** Light-theme sidebar: the desk colour, one step away from the white work surfaces. */
val SidebarLight = DeskLight

/** Dark-theme sidebar: the window background, one step away from the paper surfaces. */
val SidebarDark = DeskDark

val DarkGray = InkLight
val MediumGray = SlateLight
val LightGray = RuleLight
val BackgroundLight = DeskLight
val SurfaceWhite = PaperLight

// Semantic colours.
val Success = MoneyIn
val SuccessDark = MoneyInDark
val Warning = Color(0xFF9A5B00)
val WarningDark = Color(0xFFE0A54A)
val Error = Color(0xFFB3261E)
val ErrorDark = Color(0xFFF2867E)

/** Alert text on the selected fill; the alert red passes on both selected fills. */
val ErrorOnSelectionLight = Error
val ErrorOnSelectionDark = ErrorDark

/**
 * Inflow text on the selected fill. The plain light inflow is 4.36:1 on #E4EBEF, just short of 4.5:1, so the
 * selection-safe light green is a shade darker (5.4:1). The dark inflow already passes.
 */
val SuccessOnSelectionLight = Color(0xFF1A6B44)
val SuccessOnSelectionDark = MoneyInDark

@Composable
fun isLightTheme(): Boolean = MaterialTheme.colorScheme.surface.luminance() > 0.5f

/** The theme's incoming-money colour. */
@Composable
fun inflowColor(): Color = if (isLightTheme()) MoneyIn else MoneyInDark

/** Income bars and lines in the charts. */
@Composable
fun chartInColor(): Color = if (isLightTheme()) ChartIn else ChartInDark

/** Expense bars and lines in the charts. */
@Composable
fun chartOutColor(): Color = if (isLightTheme()) ChartOut else ChartOutDark

/** A booking or flow amount: incoming in inflow, outgoing in ink. Expenses are never red. */
@Composable
fun amountColor(isExpense: Boolean): Color = if (isExpense) MaterialTheme.colorScheme.onSurface else inflowColor()

/** A net figure: non-negative in inflow, negative in alert. */
@Composable
fun netColor(value: BigDecimal): Color = if (value.signum() < 0) MaterialTheme.colorScheme.error else inflowColor()

/** The colour for a plain balance: alert below zero and ink at zero or above. */
@Composable
fun balanceColor(value: BigDecimal): Color = when (value.signum()) {
    -1 -> MaterialTheme.colorScheme.error
    else -> MaterialTheme.colorScheme.onSurface
}

/** The colour for a [BalanceTone]: ink above zero, alert for a warning, slate for a loan and at zero. */
@Composable
fun balanceToneColor(tone: BalanceTone): Color = when (tone) {
    BalanceTone.POSITIVE -> MaterialTheme.colorScheme.onSurface
    BalanceTone.NEGATIVE -> MaterialTheme.colorScheme.error
    BalanceTone.LOAN -> MaterialTheme.colorScheme.onSurfaceVariant
    BalanceTone.ZERO -> MaterialTheme.colorScheme.onSurfaceVariant
}

// Category Colors
// The hex strings (and the one-name-per-line comments) live in `model/DefaultCategories.colors`, because the model
// package must not depend on Compose; this list is built from them so the palette and the defaults cannot drift apart.
val CategoryColors: List<Color> = DefaultCategories.colors.map { hex ->
    val value = hex.removePrefix("#").toLong(16)
    Color(0xFF000000 or value)
}

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
