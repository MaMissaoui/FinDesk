package de.findesk.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.findesk.i18n.AppLanguage
import de.findesk.i18n.StringKey
import de.findesk.i18n.strings

@Composable
fun TopBar(
    title: String,
    isDarkTheme: Boolean,
    onThemeToggle: () -> Unit,
    modifier: Modifier = Modifier,
    sidebarCollapsed: Boolean = false,
    onToggleSidebar: () -> Unit = {},
    language: AppLanguage = AppLanguage.EN,
    onLanguageChange: (AppLanguage) -> Unit = {},
    /** False while work runs that a language change would interrupt (see `Busy`) or a screen is locked. */
    languageEnabled: Boolean = true,
    /** The profile switcher; null hides it (e.g. in previews). Disabled together with the language menu. */
    profileMenu: ProfileMenuModel? = null,
) {
    val s = strings()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(64.dp)
            .background(MaterialTheme.colorScheme.surface)
            .padding(start = 12.dp, end = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Left side: sidebar toggle and title
        IconButton(onClick = onToggleSidebar) {
            Icon(
                imageVector = if (sidebarCollapsed) Icons.Default.Menu else AppIcons.MenuOpen,
                contentDescription = s[if (sidebarCollapsed) StringKey.ExpandSidebar else StringKey.CollapseSidebar],
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f).semantics { heading() },
        )

        // Right side: Actions
        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            profileMenu?.let { ProfileMenu(it, languageEnabled) }
            LanguageMenu(language, languageEnabled, onLanguageChange)

            // Theme Toggle
            IconButton(onClick = onThemeToggle) {
                Icon(
                    // Shows the theme a click switches to
                    imageVector = if (isDarkTheme) AppIcons.LightMode else AppIcons.DarkMode,
                    contentDescription = s[StringKey.ToggleTheme],
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** The current language's flag with a chevron; the menu lists every language with its flag and native name. */
@Composable
private fun LanguageMenu(language: AppLanguage, enabled: Boolean, onLanguageChange: (AppLanguage) -> Unit) {
    val s = strings()
    var open by remember { mutableStateOf(false) }
    DisabledReason(if (enabled) null else s[StringKey.LanguageBusy]) {
        Box {
            TextButton(
                onClick = { open = true },
                enabled = enabled,
                modifier = Modifier.semantics { contentDescription = s[StringKey.ChangeLanguage] },
            ) {
                LanguageFlag(language)
                Spacer(Modifier.width(6.dp))
                Icon(Icons.Default.KeyboardArrowDown, contentDescription = null, modifier = Modifier.size(18.dp))
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                AppLanguage.entries.forEach { option ->
                    DropdownMenuItem(
                        leadingIcon = { LanguageFlag(option) },
                        text = { Text(option.nativeName, fontWeight = if (option == language) FontWeight.SemiBold else FontWeight.Normal) },
                        trailingIcon = if (option == language) {
                            { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp)) }
                        } else {
                            null
                        },
                        onClick = {
                            open = false
                            if (option != language) onLanguageChange(option)
                        },
                    )
                }
            }
        }
    }
}

/**
 * What the top bar's profile switcher needs: the open profile, the profiles as (slug, name) pairs, read each time the
 * menu opens so a profile created or renamed in Settings shows, and the actions.
 */
data class ProfileMenuModel(
    val current: String?,
    val load: () -> List<Pair<String?, String>>,
    val onSwitch: (String?) -> Unit,
    val onManage: () -> Unit,
)

/**
 * The open profile's name with a chevron, like a company switcher. Picking another profile closes this one and shows
 * that profile's unlock screen (see `ProfileSwitch`); "Manage profiles…" opens Settings.
 */
@Composable
private fun ProfileMenu(model: ProfileMenuModel, enabled: Boolean) {
    val s = strings()
    var open by remember { mutableStateOf(false) }
    var profiles by remember(model.current) { mutableStateOf(model.load()) }
    val name = profiles.firstOrNull { it.first == model.current }?.second.orEmpty()
    DisabledReason(if (enabled) null else s[StringKey.LanguageBusy]) {
        Box {
            OutlinedButton(
                onClick = {
                    profiles = model.load()
                    open = true
                },
                enabled = enabled,
                modifier = Modifier.widthIn(min = 160.dp, max = 260.dp).semantics { contentDescription = s[StringKey.SwitchProfileMenu] },
                colors = OutlinedButtonStyle.colors(),
                border = OutlinedButtonStyle.border(enabled = enabled),
            ) {
                Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                Spacer(Modifier.width(8.dp))
                Icon(Icons.Default.KeyboardArrowDown, contentDescription = null, modifier = Modifier.size(18.dp))
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                profiles.forEach { (slug, profileName) ->
                    val isCurrent = slug == model.current
                    DropdownMenuItem(
                        text = { Text(profileName, fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal) },
                        trailingIcon = if (isCurrent) {
                            { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp)) }
                        } else {
                            null
                        },
                        onClick = {
                            open = false
                            if (!isCurrent) model.onSwitch(slug)
                        },
                    )
                }
                HorizontalDivider()
                DropdownMenuItem(
                    text = { Text(s[StringKey.ManageProfiles]) },
                    onClick = {
                        open = false
                        model.onManage()
                    },
                )
            }
        }
    }
}
