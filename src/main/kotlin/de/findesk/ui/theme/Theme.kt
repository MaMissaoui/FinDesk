package de.findesk.ui.theme

import androidx.compose.foundation.DefaultContextMenuRepresentation
import androidx.compose.foundation.LocalContextMenuRepresentation
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** FinDesk's one shape scale: surfaces and dialogs 8 dp, controls and fields 8 dp, small marks 4 dp. */
internal val FinDeskShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(8.dp),
    large = RoundedCornerShape(8.dp),
    extraLarge = RoundedCornerShape(8.dp),
)

internal val LightColorScheme = lightColorScheme(
    primary = AccentLight,
    onPrimary = OnAccentLight,
    primaryContainer = AccentContainerLight,
    onPrimaryContainer = OnAccentContainerLight,
    secondary = AccentLight,
    onSecondary = OnAccentLight,
    // Selection is a neutral fill, not a blue tint: the accent bar does the pointing.
    secondaryContainer = SelectedFillLight,
    onSecondaryContainer = OnSelectedFillLight,
    tertiary = Warning,
    onTertiary = Color.White,
    background = DeskLight,
    onBackground = InkLight,
    surface = PaperLight,
    onSurface = InkLight,
    surfaceVariant = DeskLight,
    onSurfaceVariant = SlateLight,
    // Material 3 draws cards and dialogs on these roles; all work surfaces are paper in this palette.
    surfaceContainerLowest = PaperLight,
    surfaceContainerLow = PaperLight,
    surfaceContainer = PaperLight,
    surfaceContainerHigh = PaperLight,
    surfaceContainerHighest = PaperLight,
    error = Error,
    onError = Color.White,
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
    outline = OutlineLight,
    outlineVariant = RuleLight,
)

internal val DarkColorScheme = darkColorScheme(
    primary = AccentDark,
    onPrimary = OnAccentDark,
    primaryContainer = AccentContainerDark,
    onPrimaryContainer = OnAccentContainerDark,
    secondary = AccentDark,
    onSecondary = OnAccentDark,
    secondaryContainer = SelectedFillDark,
    onSecondaryContainer = OnSelectedFillDark,
    tertiary = WarningDark,
    onTertiary = InkLight,
    background = DeskDark,
    onBackground = InkDark,
    surface = PaperDark,
    onSurface = InkDark,
    surfaceVariant = SelectedFillDark,
    onSurfaceVariant = SlateDark,
    surfaceContainerLowest = PaperDark,
    surfaceContainerLow = PaperDark,
    surfaceContainer = PaperDark,
    surfaceContainerHigh = PaperDark,
    surfaceContainerHighest = PaperDark,
    error = ErrorDark,
    onError = Color(0xFF410E0B),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    outline = OutlineDark,
    outlineVariant = RuleDark,
)

@Composable
fun FinDeskTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = FinDeskTypography,
        shapes = FinDeskShapes,
    ) {
        // Right-click menus (rows, and the text fields' Cut/Copy/Paste) are drawn by Compose Desktop, light-only by default
        val menu = DefaultContextMenuRepresentation(
            backgroundColor = colorScheme.surfaceContainerHigh,
            textColor = colorScheme.onSurface,
            itemHoverColor = colorScheme.onSurface.copy(alpha = 0.08f),
        )
        CompositionLocalProvider(LocalContextMenuRepresentation provides menu, content = content)
    }
}
