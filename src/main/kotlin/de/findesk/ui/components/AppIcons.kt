package de.findesk.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * The few Material icons FinDesk needs beyond material-icons-core, drawn from the Material Design icons' path data
 * (Apache-2.0). Depending on material-icons-extended for these would add its whole icon set to every installer.
 */
object AppIcons {
    val AccountBalance: ImageVector = icon(
        "AccountBalance",
        "M4,10v7h3v-7H4zM10,10v7h3v-7h-3zM2,22h19v-3H2v3zM16,10v7h3v-7h-3zM11.5,1L2,6v2h19V6l-9.5,-5z",
    )

    val Send: ImageVector = icon("Send", "M2.01,21L23,12 2.01,3 2,10l15,2 -15,2z")

    /** The sidebar toggle while the sidebar is expanded: lines with an arrow pointing to fold it in. */
    val MenuOpen: ImageVector = icon(
        "MenuOpen",
        "M3,18h13v-2H3v2zM3,13h10v-2H3v2zM3,6v2h13V6H3zM21,15.59L17.42,12 21,8.41 19.59,7l-5,5 5,5 1.41,-1.41z",
    )

    val Description: ImageVector = icon(
        "Description",
        "M14,2H6c-1.1,0 -1.99,0.9 -1.99,2L4,20c0,1.1 0.89,2 1.99,2H18c1.1,0 2,-0.9 2,-2V8l-6,-6zM16,18H8v-2h8v2z" +
            "M16,14H8v-2h8v2zM13,9V3.5L18.5,9H13z",
    )

    val Image: ImageVector = icon(
        "Image",
        "M21,19V5c0,-1.1 -0.9,-2 -2,-2H5c-1.1,0 -2,0.9 -2,2v14c0,1.1 0.9,2 2,2h14c1.1,0 2,-0.9 2,-2zM8.5,13.5l2.5,3.01L14.5,12l4.5,6H5l3.5,-4.5z",
    )

    val AttachFile: ImageVector = icon(
        "AttachFile",
        "M16.5,6v11.5c0,2.21 -1.79,4 -4,4s-4,-1.79 -4,-4V5c0,-1.38 1.12,-2.5 2.5,-2.5s2.5,1.12 2.5,2.5v10.5c0,0.55 -0.45,1 -1,1" +
            "s-1,-0.45 -1,-1V6H10v9.5c0,1.38 1.12,2.5 2.5,2.5s2.5,-1.12 2.5,-2.5V5c0,-2.21 -1.79,-4 -4,-4S7,2.79 7,5v12.5" +
            "c0,3.04 2.46,5.5 5.5,5.5s5.5,-2.46 5.5,-5.5V6h-1.5z",
    )

    val BarChart: ImageVector = icon("BarChart", "M5,9.2h3V19H5zM10.6,5h2.8v14h-2.8zM16.2,13H19v6h-2.8z")

    /** Money coming in: a plus in a circle, so no arrow can read as a trend. */
    val AddCircleOutline: ImageVector = icon(
        "AddCircleOutline",
        "M13,7h-2v4H7v2h4v4h2v-4h4v-2h-4V7zM12,2C6.48,2 2,6.48 2,12s4.48,10 10,10 10,-4.48 10,-10S17.52,2 12,2z" +
            "M12,20c-4.41,0 -8,-3.59 -8,-8s3.59,-8 8,-8 8,3.59 8,8 -3.59,8 -8,8z",
    )

    /** Money going out: a minus in a circle, so no arrow can read as a trend. */
    val RemoveCircleOutline: ImageVector = icon(
        "RemoveCircleOutline",
        "M7,11v2h10v-2H7zM12,2C6.48,2 2,6.48 2,12s4.48,10 10,10 10,-4.48 10,-10S17.52,2 12,2z" +
            "M12,20c-4.41,0 -8,-3.59 -8,-8s3.59,-8 8,-8 8,3.59 8,8 -3.59,8 -8,8z",
    )

    val FileDownload: ImageVector = icon("FileDownload", "M5,20h14v-2H5V20zM19,9h-4V3H9v6H5l7,7L19,9z")

    val DarkMode: ImageVector = icon(
        "DarkMode",
        "M12,3c-4.97,0 -9,4.03 -9,9s4.03,9 9,9s9,-4.03 9,-9c0,-0.46 -0.04,-0.92 -0.1,-1.36c-0.98,1.37 -2.58,2.26 -4.4,2.26" +
            "c-2.98,0 -5.4,-2.42 -5.4,-5.4c0,-1.81 0.89,-3.42 2.26,-4.4C12.92,3.04 12.46,3 12,3L12,3z",
    )

    val Palette: ImageVector = icon(
        "Palette",
        "M12,3c-4.97,0 -9,4.03 -9,9s4.03,9 9,9c0.83,0 1.5,-0.67 1.5,-1.5 0,-0.39 -0.15,-0.74 -0.39,-1.01 -0.23,-0.26 -0.38,-0.61 -0.38,-0.99 0,-0.83 0.67,-1.5 1.5,-1.5L16,16c2.76,0 5,-2.24 5,-5 0,-4.42 -4.03,-8 -9,-8zM6.5,12c-0.83,0 -1.5,-0.67 -1.5,-1.5S5.67,9 6.5,9 8,9.67 8,10.5 7.33,12 6.5,12zM9.5,8C8.67,8 8,7.33 8,6.5S8.67,5 9.5,5s1.5,0.67 1.5,1.5S10.33,8 9.5,8zM14.5,8c-0.83,0 -1.5,-0.67 -1.5,-1.5S13.67,5 14.5,5s1.5,0.67 1.5,1.5S15.33,8 14.5,8zM17.5,12c-0.83,0 -1.5,-0.67 -1.5,-1.5S16.67,9 17.5,9s1.5,0.67 1.5,1.5 -0.67,1.5 -1.5,1.5z",
    )

    val AutoFixHigh: ImageVector = icon(
        "AutoFixHigh",
        "M7.5,5.6L10,7 8.6,4.5 10,2 7.5,3.4 5,2l1.4,2.5L5,7zM19.5,15.4L17,14l1.4,2.5L17,19l2.5,-1.4L22,19l-1.4,-2.5L22,14zM22,2l-2.5,1.4L17,2l1.4,2.5L17,7l2.5,-1.4L22,7l-1.4,-2.5zM14.37,7.29c-0.39,-0.39 -1.02,-0.39 -1.41,0L1.29,18.96c-0.39,0.39 -0.39,1.02 0,1.41l2.34,2.34c0.39,0.39 1.02,0.39 1.41,0L16.7,11.05c0.39,-0.39 0.39,-1.02 0,-1.41l-2.33,-2.35zM13.34,12.78l-2.12,-2.12 2.44,-2.44 2.12,2.12 -2.44,2.44z",
    )

    val Storage: ImageVector = icon(
        "Storage",
        "M2,20h20v-4L2,16v4zM4,17h2v2L4,19v-2zM2,4v4h20L22,4L2,4zM6,7L4,7L4,5h2v2zM2,14h20v-4L2,10v4zM4,11h2v2L4,13v-2z",
    )

    val Backup: ImageVector = icon(
        "Backup",
        "M19.35,10.04C18.67,6.59 15.64,4 12,4 9.11,4 6.6,5.64 5.35,8.04 2.34,8.36 0,10.91 0,14c0,3.31 2.69,6 6,6h13c2.76,0 5,-2.24 5,-5 0,-2.64 -2.05,-4.78 -4.65,-4.96zM14,13v4h-4v-4H7l5,-5 5,5h-3z",
    )

    val History: ImageVector = icon(
        "History",
        "M13,3c-4.97,0 -9,4.03 -9,9L1,12l3.89,3.89 0.07,0.14L9,12L6,12c0,-3.87 3.13,-7 7,-7s7,3.13 7,7 -3.13,7 -7,7c-1.93,0 -3.68,-0.79 -4.94,-2.06l-1.42,1.42C8.27,19.99 10.51,21 13,21c4.97,0 9,-4.03 9,-9s-4.03,-9 -9,-9zM12,8v5l4.28,2.54 0.72,-1.21 -3.5,-2.08L13.5,8L12,8z",
    )

    val LightMode: ImageVector = icon(
        "LightMode",
        "M12,7c-2.76,0 -5,2.24 -5,5s2.24,5 5,5s5,-2.24 5,-5S14.76,7 12,7L12,7z" +
            "M2,13l2,0c0.55,0 1,-0.45 1,-1s-0.45,-1 -1,-1l-2,0c-0.55,0 -1,0.45 -1,1S1.45,13 2,13z" +
            "M20,13l2,0c0.55,0 1,-0.45 1,-1s-0.45,-1 -1,-1l-2,0c-0.55,0 -1,0.45 -1,1S19.45,13 20,13z" +
            "M11,2v2c0,0.55 0.45,1 1,1s1,-0.45 1,-1V2c0,-0.55 -0.45,-1 -1,-1S11,1.45 11,2z" +
            "M11,20v2c0,0.55 0.45,1 1,1s1,-0.45 1,-1v-2c0,-0.55 -0.45,-1 -1,-1C11.45,19 11,19.45 11,20z" +
            "M5.99,4.58c-0.39,-0.39 -1.03,-0.39 -1.41,0c-0.39,0.39 -0.39,1.03 0,1.41l1.06,1.06c0.39,0.39 1.03,0.39 1.41,0" +
            "s0.39,-1.03 0,-1.41L5.99,4.58z" +
            "M18.36,16.95c-0.39,-0.39 -1.03,-0.39 -1.41,0c-0.39,0.39 -0.39,1.03 0,1.41l1.06,1.06c0.39,0.39 1.03,0.39 1.41,0" +
            "c0.39,-0.39 0.39,-1.03 0,-1.41L18.36,16.95z" +
            "M19.42,5.99c0.39,-0.39 0.39,-1.03 0,-1.41c-0.39,-0.39 -1.03,-0.39 -1.41,0l-1.06,1.06c-0.39,0.39 -0.39,1.03 0,1.41" +
            "s1.03,0.39 1.41,0L19.42,5.99z" +
            "M7.05,18.36c0.39,-0.39 0.39,-1.03 0,-1.41c-0.39,-0.39 -1.03,-0.39 -1.41,0l-1.06,1.06c-0.39,0.39 -0.39,1.03 0,1.41" +
            "s1.03,0.39 1.41,0L7.05,18.36z",
    )

    /** A 24x24 filled icon; Icon() tints it, so the fill colour here doesn't matter. */
    private fun icon(name: String, pathData: String): ImageVector = ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).addPath(pathData = addPathNodes(pathData), fill = SolidColor(Color.Black)).build()
}
