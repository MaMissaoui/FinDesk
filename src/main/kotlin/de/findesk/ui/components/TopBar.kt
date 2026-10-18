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
