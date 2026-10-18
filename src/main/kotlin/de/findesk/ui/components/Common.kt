package de.findesk.ui.components

import androidx.compose.foundation.ContextMenuArea
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.findesk.i18n.StringKey
import de.findesk.i18n.strings
import de.findesk.ui.theme.CategoryColors
import de.findesk.ui.theme.Success
import de.findesk.ui.theme.amountColor
import de.findesk.ui.theme.readableOn
import de.findesk.viewmodel.BaseViewModel
import kotlinx.coroutines.delay
import java.awt.FileDialog
import java.awt.Frame
import java.io.File

/** Creates the view model once per composition and closes it (cancelling its work) when it leaves. */
@Composable
fun <T : BaseViewModel> rememberViewModel(factory: () -> T): T {
    val vm = remember { factory() }
    DisposableEffect(vm) { onDispose { vm.close() } }
    return vm
}

/** Inline banner for transient success messages and errors. Success messages auto-dismiss. */
@Composable
fun MessageBanner(message: String?, error: String?, onDismiss: () -> Unit) {
    if (message == null && error == null) return
    if (error == null) {
        LaunchedEffect(message) {
            delay(4000)
            onDismiss()
        }
    }
    val isError = error != null
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 12.dp)
            .semantics(mergeDescendants = true) {
                liveRegion = if (isError) LiveRegionMode.Assertive else LiveRegionMode.Polite
            },
        shape = RoundedCornerShape(8.dp),
        color = if (isError) MaterialTheme.colorScheme.errorContainer else Success.copy(alpha = 0.12f),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                if (isError) Icons.Default.Warning else Icons.Default.CheckCircle,
                contentDescription = null,
                tint = if (isError) MaterialTheme.colorScheme.error else Success,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = error ?: message.orEmpty(),
                style = MaterialTheme.typography.bodyMedium,
                color = if (isError) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Default.Close, contentDescription = strings()[StringKey.Dismiss], modifier = Modifier.size(16.dp))
            }
        }
    }
}

@Composable
fun EmptyState(icon: ImageVector, title: String, description: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Column(
        modifier = modifier.fillMaxWidth().padding(48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(48.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(
            description,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (action != null) {
            Spacer(Modifier.height(8.dp))
            action()
        }
    }
}

/**
 * FinDesk's card: the theme's card colour (surfaceContainerHighest) with a hairline outline, so it stands off the page
 * in both themes. Text contrast on it is checked in ContrastTest.
 */
@Composable
fun FinDeskCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        content = content,
    )
}

@Composable
fun SectionCard(title: String, modifier: Modifier = Modifier, actions: @Composable RowScope.() -> Unit = {}, content: @Composable ColumnScope.() -> Unit) {
    FinDeskCard(modifier = modifier) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f).semantics { heading() },
                )
                actions()
            }
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}

@Composable
fun StatCard(title: String, value: String, icon: ImageVector?, color: Color, modifier: Modifier = Modifier, subtitle: String? = null) {
    FinDeskCard(modifier = modifier) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(title, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (icon != null) Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(24.dp))
            }
            Spacer(Modifier.height(8.dp))
            Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            // Always laid out, empty or not, so a card without one is as tall as its neighbours
            Text(subtitle.orEmpty(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * One booking in a ledger list: a fixed-width date column, the title with an optional meta line, and the
 * right-aligned signed amount coloured by direction. Shared so every booking list reads the same.
 */
@Composable
fun LedgerRow(date: String, title: String, meta: String?, amount: String, isExpense: Boolean, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(date, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(100.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (meta != null) {
                Text(meta, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Text(amount, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = amountColor(isExpense))
    }
}

/** Parses "#RRGGBB"; falls back to a palette color derived from [fallbackKey]. */
fun categoryColor(hex: String?, fallbackKey: String? = null): Color {
    val parsed = hex?.removePrefix("#")?.takeIf { it.length == 6 }?.toLongOrNull(16)
    if (parsed != null) return Color(0xFF000000 or parsed)
    val index = ((fallbackKey?.hashCode() ?: 0) and Int.MAX_VALUE) % CategoryColors.size
    return CategoryColors[index]
}

@Composable
fun CategoryBadge(name: String?, colorHex: String?, modifier: Modifier = Modifier) {
    val label = name ?: strings()[StringKey.Uncategorized]
    val color = if (name == null) MaterialTheme.colorScheme.onSurfaceVariant else categoryColor(colorHex, name)
    val tint = color.copy(alpha = 0.12f).compositeOver(MaterialTheme.colorScheme.surfaceContainerHighest)
    Surface(modifier = modifier, shape = RoundedCornerShape(4.dp), color = tint) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            // Lime or orange text on its own light tint was below 2:1 in the light theme, purple and brown in the dark one
            color = readableOn(color, tint, MaterialTheme.colorScheme.onSurface),
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

@Composable
fun ConfirmDialog(title: String, text: String, confirmLabel: String? = null, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val s = strings()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            Button(
                onClick = {
                    onConfirm()
                    onDismiss()
                },
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
            ) { Text(confirmLabel ?: s[StringKey.Delete]) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(s[StringKey.Cancel]) } },
    )
}

/** A compact dropdown selector built on an outlined button. */
@Composable
fun <T> DropdownSelector(
    label: String,
    options: List<T>,
    optionLabel: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    leadingIcon: ImageVector? = null,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        OutlinedButton(onClick = { expanded = true }) {
            if (leadingIcon != null) {
                Icon(leadingIcon, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
            }
            Text(label, maxLines = 1)
            Icon(Icons.Default.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
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
}

/** Native file dialogs (AWT FileDialog renders the platform dialog on macOS and Windows). */
object FileDialogs {
    fun openFiles(title: String, extensions: Set<String>): List<File> {
        val dialog = FileDialog(null as Frame?, title, FileDialog.LOAD).apply {
            isMultipleMode = true
            setFilenameFilter { _, name -> name.substringAfterLast('.', "").lowercase() in extensions }
        }
        dialog.isVisible = true
        return dialog.files?.toList().orEmpty()
    }

    fun saveFile(title: String, defaultName: String): File? {
        val dialog = FileDialog(null as Frame?, title, FileDialog.SAVE).apply { file = defaultName }
        dialog.isVisible = true
        val name = dialog.file ?: return null
        val ext = defaultName.substringAfterLast('.', "")
        val withExt = if (ext.isNotEmpty() && !name.endsWith(".$ext", ignoreCase = true)) "$name.$ext" else name
        return File(dialog.directory, withExt)
    }
}

/**
 * Says why a control is disabled: on hover as a tooltip, and to screen readers as its state. Pass null when the
 * control is enabled (or disabled for an obvious reason, like work in progress) and [content] is shown unchanged.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DisabledReason(reason: String?, content: @Composable () -> Unit) {
    if (reason == null) {
        content()
        return
    }
    TooltipArea(
        tooltip = {
            Surface(shape = RoundedCornerShape(4.dp), color = MaterialTheme.colorScheme.inverseSurface, shadowElevation = 2.dp) {
                Text(
                    reason,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.inverseOnSurface,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        },
        delayMillis = 300,
    ) {
        Box(Modifier.semantics { stateDescription = reason }) { content() }
    }
}

/**
 * A right-click menu on [content] with shortcuts to actions that are also reachable through visible buttons, since
 * context menus are mouse-only. Null entries are left out, for actions that don't apply to this row.
 */
@Composable
fun RowContextMenu(vararg items: Pair<String, () -> Unit>?, content: @Composable () -> Unit) {
    val entries = items.filterNotNull()
    // No applicable action: no menu at all rather than an empty one
    if (entries.isEmpty()) return content()
    ContextMenuArea(items = { entries.map { (label, action) -> ContextMenuItem(label, action) } }, content = content)
}

/** One [RowContextMenu] entry; taking `() -> Unit` here lets actions that return a value (e.g. a Job) be used as is. */
fun menuItem(label: String, action: () -> Unit): Pair<String, () -> Unit> = label to action
