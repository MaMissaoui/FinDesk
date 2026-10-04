package de.findesk.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import de.findesk.i18n.StringKey
import de.findesk.i18n.strings
import de.findesk.ui.theme.CategoryColors
import de.findesk.ui.theme.Success
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

@Composable
fun SectionCard(title: String, modifier: Modifier = Modifier, actions: @Composable RowScope.() -> Unit = {}, content: @Composable ColumnScope.() -> Unit) {
    Card(modifier = modifier, shape = RoundedCornerShape(12.dp)) {
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
fun StatCard(title: String, value: String, icon: ImageVector, color: Color, modifier: Modifier = Modifier, subtitle: String? = null) {
    Card(modifier = modifier, shape = RoundedCornerShape(12.dp)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(title, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(24.dp))
            }
            Spacer(Modifier.height(8.dp))
            Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
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
    Surface(modifier = modifier, shape = RoundedCornerShape(4.dp), color = color.copy(alpha = 0.12f)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = color,
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
