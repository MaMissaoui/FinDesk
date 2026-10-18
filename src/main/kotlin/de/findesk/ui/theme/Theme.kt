package de.findesk.ui.theme

import androidx.compose.foundation.DefaultContextMenuRepresentation
import androidx.compose.foundation.LocalContextMenuRepresentation
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color

internal val LightColorScheme = lightColorScheme(
    primary = PrimaryBlue,
    onPrimary = Color.White,
    primaryContainer = PrimaryBlueLight,
    onPrimaryContainer = Color.White,
    secondary = Teal,
    onSecondary = Color.White,
    secondaryContainer = TealLight,
    onSecondaryContainer = DarkGray,
    tertiary = Color(0xFF7C5800),
    onTertiary = Color.White,
    background = BackgroundLight,
    onBackground = DarkGray,
    surface = SurfaceWhite,
    onSurface = DarkGray,
    surfaceVariant = LightGray,
    // Darker than MediumGray so secondary text keeps 4.5:1 on surfaceVariant too (table headers; see ContrastTest)
    onSurfaceVariant = Color(0xFF5B6472),
    // Material 3 draws cards and dialogs on these roles; left unset they fall back to its purple-tinted baseline, on
    // which secondary text and amounts dropped below 4.5:1. White cards keep every text colour readable.
    surfaceContainerLowest = SurfaceWhite,
    surfaceContainerLow = SurfaceWhite,
    surfaceContainer = SurfaceWhite,
    surfaceContainerHigh = SurfaceWhite,
    surfaceContainerHighest = SurfaceWhite,
    error = Error,
    onError = Color.White,
    outline = MediumGray,
    outlineVariant = LightGray,
)

internal val DarkColorScheme = darkColorScheme(
    primary = PrimaryBlueLight,
    onPrimary = Color.White,
    primaryContainer = PrimaryBlue,
    onPrimaryContainer = Color.White,
    secondary = TealLight,
    onSecondary = DarkGray,
    secondaryContainer = Teal,
    onSecondaryContainer = Color.White,
    tertiary = Color(0xFFFFD99D),
    onTertiary = DarkGray,
    background = Color(0xFF1C1B1F),
    onBackground = Color(0xFFE6E1E5),
    surface = Color(0xFF1C1B1F),
    onSurface = Color(0xFFE6E1E5),
    surfaceVariant = Color(0xFF49454F),
    onSurfaceVariant = Color(0xFFCAC4D0),
    // Neutral greys instead of Material 3's purple baseline; #232529 keeps the blue text buttons at 4.5:1
    surfaceContainerLowest = Color(0xFF17181B),
    surfaceContainerLow = Color(0xFF1E2023),
    surfaceContainer = Color(0xFF202226),
    surfaceContainerHigh = Color(0xFF232529),
    surfaceContainerHighest = Color(0xFF232529),
    error = ErrorDark,
    onError = Color(0xFF690005),
    outline = Color(0xFF938F99),
    outlineVariant = Color(0xFF49454F),
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
