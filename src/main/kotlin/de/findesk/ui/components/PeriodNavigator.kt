package de.findesk.ui.components

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * A period switcher as a statement header reads it: a round previous button, the period between two, and a round
 * next button. Clicking the period opens its menu. Used by the Dashboard now and Reports later.
 */
@Composable
fun <T> PeriodNavigator(
    label: String,
    options: List<T>,
    optionLabel: (T) -> String,
    onSelect: (T) -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    previousEnabled: Boolean,
    nextEnabled: Boolean,
    previousContentDescription: String,
    nextContentDescription: String,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PeriodArrow(
            icon = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
            contentDescription = previousContentDescription,
            enabled = previousEnabled,
            onClick = onPrevious,
        )
        Box {
            PeriodLabel(label, onClick = { expanded = true })
            AppDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(optionLabel(option)) },
                        onClick = {
                            expanded = false
                            onSelect(option)
                        },
                    )
                }
            }
        }
        PeriodArrow(
            icon = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = nextContentDescription,
            enabled = nextEnabled,
            onClick = onNext,
        )
    }
}

/** A 40 dp circle with a hairline outline; disabled shows only a faint chevron and takes no ripple. */
@Composable
private fun PeriodArrow(icon: ImageVector, contentDescription: String, enabled: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(if (hovered && enabled) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
            .hoverable(interaction)
            .clickable(
                interactionSource = interaction,
                indication = LocalIndication.current,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = contentDescription,
            // Enabled chevrons carry the accent; a disabled one is slate at 40 %, with no ripple behind it
            tint = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
            modifier = Modifier.size(20.dp),
        )
    }
}

/** The period itself: 24 sp ink with a small slate chevron, opening the menu on click or keyboard activation. */
@Composable
private fun PeriodLabel(label: String, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Row(
        modifier = Modifier
            .clip(MaterialTheme.shapes.small)
            .background(if (hovered) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent)
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = LocalIndication.current, role = Role.Button, onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        Icon(
            Icons.Default.KeyboardArrowDown,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
    }
}
