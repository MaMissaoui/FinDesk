package de.findesk.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import de.findesk.i18n.strings
import de.findesk.ui.navigation.Screen
import de.findesk.ui.theme.PrimaryBlue
import de.findesk.ui.theme.PrimaryBlueDark
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
) {
    Column(
        modifier = modifier
            .width(240.dp)
            .fillMaxHeight()
            .background(sidebarColor())
            .padding(16.dp),
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

        // Navigation Items
        Screen.entries.forEach { screen ->
            SidebarItem(
                screen = screen,
                isSelected = screen == currentScreen,
                enabled = lockedOn == null || screen == lockedOn,
                onClick = { onScreenSelected(screen) },
            )
            Spacer(modifier = Modifier.height(4.dp))
        }

        Spacer(modifier = Modifier.weight(1f))

        // Version info at bottom
        Text(
            text = "v${AppInfo.VERSION}",
            style = MaterialTheme.typography.bodySmall,
            color = Color.White,
            modifier = Modifier.padding(top = 16.dp),
        )
    }
}

/** The brand blue, darkened in the dark theme so the sidebar doesn't glare next to dark surfaces. */
@Composable
internal fun sidebarColor(): Color = if (MaterialTheme.colorScheme.surface.luminance() > 0.5f) PrimaryBlue else PrimaryBlueDark

@Composable
private fun SidebarItem(
    screen: Screen,
    isSelected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    // Darken rather than lighten: any white highlight drops the white label below 4.5:1 (see ContrastTest)
    val backgroundColor = if (isSelected) Color.Black.copy(alpha = 0.2f) else Color.Transparent
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
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector = screen.icon,
            contentDescription = null,
            tint = contentColor,
            modifier = Modifier.size(24.dp),
        )
        Text(
            text = screen.title(strings()),
            style = MaterialTheme.typography.bodyLarge,
            color = contentColor,
            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}
