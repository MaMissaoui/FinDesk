package de.findesk.ui.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import de.findesk.i18n.strings
import de.findesk.ui.navigation.Screen
import de.findesk.ui.theme.PrimaryBlue
import de.findesk.ui.theme.SidebarNavy
import de.findesk.util.AppInfo

@Composable
fun Sidebar(
    currentScreen: Screen,
    onScreenSelected: (Screen) -> Unit,
    modifier: Modifier = Modifier,
    /** Open profile, shown when there is more than one. */
    profileName: String? = null,
    /** While set, only this screen can be selected (see NavigationState.lockedOn). */
    lockedOn: Screen? = null,
    /** Room left free at the top, e.g. for the macOS window buttons over a unified title bar. */
    topInset: Dp = 0.dp,
    /** Icons only, 72 dp wide; toggled from the top bar and remembered per profile. */
    collapsed: Boolean = false,
) {
    val width by animateDpAsState(if (collapsed) COLLAPSED_WIDTH else EXPANDED_WIDTH, animationSpec = tween(150))
    Column(
        modifier = modifier
            .width(width)
            .fillMaxHeight()
            .background(sidebarColor())
            .padding(start = if (collapsed) 12.dp else 16.dp, end = if (collapsed) 12.dp else 16.dp, bottom = 16.dp, top = 16.dp + topInset),
    ) {
        // App Logo/Title
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.padding(bottom = 32.dp),
        ) {
            Image(
                painter = painterResource("icon-64.png"),
                contentDescription = strings()[de.findesk.i18n.StringKey.AppLogo],
                modifier = Modifier.size(40.dp),
            )
            if (!collapsed) {
                Column {
                    Text(
                        text = "FinDesk",
                        style = MaterialTheme.typography.headlineMedium,
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                    )
                    // Opaque white: any transparency drops below 4.5:1 on PrimaryBlue (ContrastTest)
                    if (profileName != null) Text(profileName, style = MaterialTheme.typography.bodySmall, color = Color.White, maxLines = 1)
                }
            }
        }

        // Navigation Items
        Screen.entries.forEach { screen ->
            SidebarItem(
                screen = screen,
                isSelected = screen == currentScreen,
                enabled = lockedOn == null || screen == lockedOn,
                collapsed = collapsed,
                onClick = { onScreenSelected(screen) },
            )
            Spacer(modifier = Modifier.height(4.dp))
        }

        Spacer(modifier = Modifier.weight(1f))

        // Version info at bottom; it doesn't fit the icon-only width
        if (!collapsed) {
            Text(
                text = "v${AppInfo.VERSION}",
                style = MaterialTheme.typography.bodySmall,
                color = Color.White,
                modifier = Modifier.padding(top = 16.dp),
            )
        }
    }
}

private val EXPANDED_WIDTH = 240.dp
private val COLLAPSED_WIDTH = 72.dp

/** The brand blue in the light theme, deep navy in the dark theme so the sidebar doesn't glare next to dark surfaces. */
@Composable
internal fun sidebarColor(): Color = if (MaterialTheme.colorScheme.surface.luminance() > 0.5f) PrimaryBlue else SidebarNavy

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SidebarItem(
    screen: Screen,
    isSelected: Boolean,
    enabled: Boolean,
    collapsed: Boolean,
    onClick: () -> Unit,
) {
    if (!collapsed) {
        SidebarItemRow(screen, isSelected, enabled, collapsed = false, onClick)
        return
    }
    // Icon only: the title moves into a hover tooltip and, for screen readers, the item's content description
    TooltipArea(
        tooltip = {
            Surface(shape = RoundedCornerShape(4.dp), color = MaterialTheme.colorScheme.inverseSurface, shadowElevation = 2.dp) {
                Text(
                    screen.title(strings()),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.inverseOnSurface,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        },
        delayMillis = 300,
    ) {
        SidebarItemRow(screen, isSelected, enabled, collapsed = true, onClick)
    }
}

@Composable
private fun SidebarItemRow(
    screen: Screen,
    isSelected: Boolean,
    enabled: Boolean,
    collapsed: Boolean,
    onClick: () -> Unit,
) {
    val title = screen.title(strings())
    // Light theme darkens the blue; dark theme lightens the navy, since black on navy is invisible. Both keep the white label at 4.5:1 (see ContrastTest)
    val selectedHighlight = if (MaterialTheme.colorScheme.surface.luminance() > 0.5f) {
        Color.Black.copy(alpha = 0.2f)
    } else {
        Color.White.copy(alpha = 0.12f)
    }
    val backgroundColor = if (isSelected) selectedHighlight else Color.Transparent
    // Disabled items may fall below 4.5:1, which WCAG allows for inactive controls
    val contentColor = if (enabled) Color.White else Color.White.copy(alpha = 0.5f)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(backgroundColor)
            .selectable(
                selected = isSelected,
                enabled = enabled,
                onClick = onClick,
                role = Role.Tab,
            )
            .then(if (collapsed) Modifier.semantics { contentDescription = title } else Modifier)
            .padding(horizontal = if (collapsed) 12.dp else 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = if (collapsed) Arrangement.Center else Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector = screen.icon,
            contentDescription = null,
            tint = contentColor,
            modifier = Modifier.size(24.dp),
        )
        if (!collapsed) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = contentColor,
                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
            )
        }
    }
}
