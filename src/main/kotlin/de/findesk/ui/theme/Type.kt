package de.findesk.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font
import androidx.compose.ui.unit.sp

/**
 * IBM Plex Sans (SIL Open Font License 1.1, see `fonts/OFL.txt`): one face on every platform, so the app looks the
 * same on macOS, Windows and Linux. Only these four weights are bundled; other weights fall back to the nearest.
 */
val FinDeskFontFamily = FontFamily(
    Font(resource = "fonts/IBMPlexSans-Regular.ttf", weight = FontWeight.Normal),
    Font(resource = "fonts/IBMPlexSans-Medium.ttf", weight = FontWeight.Medium),
    Font(resource = "fonts/IBMPlexSans-SemiBold.ttf", weight = FontWeight.SemiBold),
    Font(resource = "fonts/IBMPlexSans-Bold.ttf", weight = FontWeight.Bold),
)

// Tabular figures ("tnum"): every digit takes the same width, so amounts, dates and IBANs line up in columns.
val FinDeskTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = FinDeskFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 57.sp,
        lineHeight = 64.sp,
        letterSpacing = (-0.25).sp,
        fontFeatureSettings = "tnum",
    ),
    displayMedium = TextStyle(
        fontFamily = FinDeskFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 45.sp,
        lineHeight = 52.sp,
        fontFeatureSettings = "tnum",
    ),
    displaySmall = TextStyle(
        fontFamily = FinDeskFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 36.sp,
        lineHeight = 44.sp,
        fontFeatureSettings = "tnum",
    ),
    headlineLarge = TextStyle(
        fontFamily = FinDeskFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 32.sp,
        lineHeight = 40.sp,
        letterSpacing = 0.sp,
        fontFeatureSettings = "tnum",
    ),
    headlineMedium = TextStyle(
        fontFamily = FinDeskFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 28.sp,
        lineHeight = 36.sp,
        letterSpacing = 0.sp,
        fontFeatureSettings = "tnum",
    ),
    // Material 3's AlertDialog title reads headlineSmall, so this is every dialog title: page-title size, not a figure.
    headlineSmall = TextStyle(
        fontFamily = FinDeskFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 28.sp,
        letterSpacing = 0.sp,
        fontFeatureSettings = "tnum",
    ),
    titleLarge = TextStyle(
        fontFamily = FinDeskFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 28.sp,
        letterSpacing = 0.sp,
        fontFeatureSettings = "tnum",
    ),
    titleMedium = TextStyle(
        fontFamily = FinDeskFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.sp,
        fontFeatureSettings = "tnum",
    ),
    titleSmall = TextStyle(
        fontFamily = FinDeskFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.sp,
        fontFeatureSettings = "tnum",
    ),
    bodyLarge = TextStyle(
        fontFamily = FinDeskFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.sp,
        fontFeatureSettings = "tnum",
    ),
    bodyMedium = TextStyle(
        fontFamily = FinDeskFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.sp,
        fontFeatureSettings = "tnum",
    ),
    bodySmall = TextStyle(
        fontFamily = FinDeskFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.1.sp,
        fontFeatureSettings = "tnum",
    ),
    labelLarge = TextStyle(
        fontFamily = FinDeskFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp,
        fontFeatureSettings = "tnum",
    ),
    labelMedium = TextStyle(
        fontFamily = FinDeskFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.1.sp,
        fontFeatureSettings = "tnum",
    ),
    labelSmall = TextStyle(
        fontFamily = FinDeskFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.2.sp,
        fontFeatureSettings = "tnum",
    ),
)

/**
 * Chart axes and badge counters stay at 12 sp while body and label text grow to 13 sp; every other small label
 * uses [Typography.bodySmall] or [Typography.labelSmall].
 */
val Typography.caption: TextStyle
    get() = labelSmall.copy(fontSize = 12.sp, lineHeight = 16.sp)

/** KPI figures, the statement result, period titles, review amounts and account header figures. */
val Typography.figure: TextStyle
    get() = titleLarge.copy(fontSize = 24.sp, lineHeight = 32.sp, fontWeight = FontWeight.SemiBold)

/** The Settings section list: slightly larger than a row title, still in the navigation role. */
val Typography.navigation: TextStyle
    get() = labelLarge.copy(fontSize = 15.sp, lineHeight = 20.sp)

/** An amount in a list row: body size at the one row-amount weight the rest of the app uses. */
val Typography.rowAmount: TextStyle
    get() = bodyMedium.copy(fontWeight = FontWeight.SemiBold)
