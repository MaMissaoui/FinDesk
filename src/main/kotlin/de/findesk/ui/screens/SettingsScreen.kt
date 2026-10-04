package de.findesk.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import de.findesk.document.TesseractOcrEngine
import de.findesk.model.CategoryItem
import de.findesk.model.RuleItem
import de.findesk.ui.components.*
import de.findesk.ui.theme.CategoryColors
import de.findesk.ui.theme.Success
import de.findesk.ui.theme.Warning
import de.findesk.util.Formats
import de.findesk.viewmodel.SettingsViewModel
import java.math.BigDecimal

@Composable
fun SettingsScreen(darkTheme: Boolean, onDarkThemeChange: (Boolean) -> Unit) {
    val vm = rememberViewModel { SettingsViewModel(isOcrAvailable = TesseractOcrEngine.createOrNull() != null) }
    val state by vm.state.collectAsState()
    LaunchedEffect(Unit) { vm.load() }

    var editCategory by remember { mutableStateOf<CategoryItem?>(null) }
    var newCategory by remember { mutableStateOf(false) }
    var editRule by remember { mutableStateOf<RuleItem?>(null) }
    var newRule by remember { mutableStateOf(false) }
    var confirmDeleteCategory by remember { mutableStateOf<CategoryItem?>(null) }

    LazyColumn(modifier = Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Text("Settings", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            MessageBanner(state.message, state.error, vm::clearMessages)
        }

        item {
            SectionCard("Appearance", Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Dark theme", modifier = Modifier.weight(1f))
                    Switch(checked = darkTheme, onCheckedChange = onDarkThemeChange)
                }
            }
        }

        item {
            SectionCard(
                "Categories",
                Modifier.fillMaxWidth(),
                actions = { TextButton(onClick = { newCategory = true }) { Text("Add category") } },
            ) {
                if (state.categories.isEmpty()) {
                    Text("No categories yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                state.categories.forEach { c ->
                    Row(Modifier.fillMaxWidth().clickable { editCategory = c }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(12.dp).background(categoryColor(c.color, c.name), CircleShape))
                        Spacer(Modifier.width(12.dp))
                        Text(c.name, modifier = Modifier.weight(1f))
                        if (c.isTaxRelevant) {
                            CategoryBadge("tax-relevant", "#F59E0B")
                            Spacer(Modifier.width(8.dp))
                        }
                        c.budgetLimit?.let {
                            Text("Budget ${Formats.money(it)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.width(8.dp))
                        }
                        IconButton(onClick = { confirmDeleteCategory = c }) { Icon(Icons.Default.Delete, contentDescription = "Delete") }
                    }
                    HorizontalDivider()
                }
            }
        }

        item {
            SectionCard(
                "Auto-categorization rules",
                Modifier.fillMaxWidth(),
                actions = { TextButton(onClick = { newRule = true }, enabled = state.categories.isNotEmpty()) { Text("Add rule") } },
            ) {
                Text(
                    "A rule's pattern is a case-insensitive regular expression matched against partner name and purpose. Higher priority wins.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                state.rules.forEach { r ->
                    Row(Modifier.fillMaxWidth().clickable { editRule = r }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(r.pattern, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                        Text("→ ${r.categoryName}", modifier = Modifier.weight(1f))
                        Text("prio ${r.priority}" + if (!r.autoApply) " · manual" else "", style = MaterialTheme.typography.bodySmall)
                        IconButton(onClick = { vm.deleteRule(r.id) }) { Icon(Icons.Default.Delete, contentDescription = "Delete") }
                    }
                    HorizontalDivider()
                }
            }
        }

        item {
            SectionCard("Data & OCR", Modifier.fillMaxWidth()) {
                InfoRow("Database", state.dataDirectory + "/findesk.db")
                InfoRow("Documents", state.documentsDirectory)
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 6.dp)) {
                    Icon(
                        if (state.isOcrAvailable) Icons.Default.CheckCircle else Icons.Default.Warning,
                        contentDescription = null,
                        tint = if (state.isOcrAvailable) Success else Warning,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (state.isOcrAvailable) {
                            "Tesseract OCR available (German + English)"
                        } else {
                            "Tesseract not found. Install with: brew install tesseract tesseract-lang"
                        },
                    )
                }
            }
        }

        item {
            SectionCard("Backup & restore", Modifier.fillMaxWidth()) {
                Text(
                    "A backup contains the database, all stored documents and the online-banking connection files.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(enabled = !state.isBusy, onClick = {
                        FileDialogs.saveFile("Save backup", "findesk-backup-${java.time.LocalDate.now()}.zip")?.let { vm.createBackup(it) }
                    }) { Text("Create backup") }
                    OutlinedButton(enabled = !state.isBusy && !state.hasPendingRestore, onClick = {
                        FileDialogs.openFiles("Restore backup", setOf("zip")).firstOrNull()?.let { vm.chooseRestore(it) }
                    }) { Text("Restore from backup…") }
                    if (state.isBusy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                }
                if (state.hasPendingRestore) {
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Warning, contentDescription = null, tint = Warning, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("A restore is waiting. Restart FinDesk to apply it.", modifier = Modifier.weight(1f))
                        TextButton(onClick = { vm.cancelPendingRestore() }) { Text("Cancel restore") }
                    }
                }
                state.restoredFrom?.let {
                    Spacer(Modifier.height(8.dp))
                    Text("Backup restored at this start. Your previous data is in $it", style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        item {
            SectionCard("Audit log (latest 100)", Modifier.fillMaxWidth()) {
                state.auditLog.forEach { e ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Text(Formats.dateTime(e.timestamp), style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(140.dp))
                        Text(e.action, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium, modifier = Modifier.width(180.dp))
                        Text(e.details.orEmpty(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }

    if (newCategory || editCategory != null) {
        CategoryDialog(
            existing = editCategory,
            validate = { name, color -> vm.validateCategory(name, color, editCategory?.id) },
            onDismiss = {
                newCategory = false
                editCategory = null
            },
            onConfirm = { name, color, budget, tax ->
                vm.saveCategory(editCategory?.id, name, color, budget, tax)
                newCategory = false
                editCategory = null
            },
        )
    }
    if (newRule || editRule != null) {
        RuleDialog(
            existing = editRule,
            categories = state.categories,
            validate = vm::validateRule,
            onDismiss = {
                newRule = false
                editRule = null
            },
            onConfirm = { pattern, categoryId, priority, autoApply ->
                vm.saveRule(editRule?.id, pattern, categoryId, priority, autoApply)
                newRule = false
                editRule = null
            },
        )
    }
    state.restoreCandidate?.let { (file, info) ->
        AlertDialog(
            onDismissRequest = vm::cancelRestoreChoice,
            title = { Text("Restore ${file.name}?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Created ${info.created.take(16).replace('T', ' ')} · ${info.transactions} transactions · ${info.documents} documents")
                    Text("On the next start, all current data is replaced by this backup. The current data is moved to a pre-restore folder, not deleted.")
                }
            },
            confirmButton = { Button(onClick = { vm.confirmRestore() }) { Text("Prepare restore") } },
            dismissButton = { TextButton(onClick = vm::cancelRestoreChoice) { Text("Cancel") } },
        )
    }
    confirmDeleteCategory?.let { c ->
        ConfirmDialog(
            title = "Delete category ${c.name}?",
            text = "Its transactions become uncategorized and its rules are deleted.",
            onConfirm = { vm.deleteCategory(c.id) },
            onDismiss = { confirmDeleteCategory = null },
        )
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.padding(vertical = 6.dp)) {
        Text(label, fontWeight = FontWeight.Medium, modifier = Modifier.width(120.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

private fun colorHex(index: Int): String {
    val c = CategoryColors[index % CategoryColors.size]
    return "#%02X%02X%02X".format((c.red * 255).toInt(), (c.green * 255).toInt(), (c.blue * 255).toInt())
}

@Composable
private fun CategoryDialog(
    existing: CategoryItem?,
    validate: (String, String) -> String?,
    onDismiss: () -> Unit,
    onConfirm: (String, String, BigDecimal?, Boolean) -> Unit,
) {
    var name by remember { mutableStateOf(existing?.name.orEmpty()) }
    var color by remember { mutableStateOf(existing?.color ?: colorHex(0)) }
    var budget by remember { mutableStateOf(existing?.budgetLimit?.toPlainString()?.replace('.', ',').orEmpty()) }
    var tax by remember { mutableStateOf(existing?.isTaxRelevant ?: false) }
    val parsedBudget = budget.takeIf { it.isNotBlank() }?.let { Formats.parseAmount(it) }
    val error = validate(name, color) ?: if (budget.isNotBlank() && parsedBudget == null) "Budget is not a number" else null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "Add category" else "Edit category") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    CategoryColors.indices.forEach { i ->
                        val hex = colorHex(i)
                        Box(
                            Modifier.size(if (hex.equals(color, true)) 26.dp else 20.dp)
                                .background(CategoryColors[i], CircleShape)
                                .clickable { color = hex },
                        )
                    }
                }
                OutlinedTextField(budget, { budget = it }, label = { Text("Monthly budget (€, optional)") }, singleLine = true)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = tax, onCheckedChange = { tax = it })
                    Text("Tax-relevant (Werbungskosten, Sonderausgaben, …)")
                }
                if (error != null && name.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { Button(enabled = error == null, onClick = { onConfirm(name, color, parsedBudget, tax) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun RuleDialog(
    existing: RuleItem?,
    categories: List<CategoryItem>,
    validate: (String) -> String?,
    onDismiss: () -> Unit,
    onConfirm: (String, Int, Int, Boolean) -> Unit,
) {
    var pattern by remember { mutableStateOf(existing?.pattern.orEmpty()) }
    var category by remember { mutableStateOf(categories.firstOrNull { it.id == existing?.categoryId } ?: categories.first()) }
    var priority by remember { mutableStateOf((existing?.priority ?: 0).toString()) }
    var autoApply by remember { mutableStateOf(existing?.autoApply ?: true) }
    val error = validate(pattern) ?: if (priority.toIntOrNull() == null) "Priority must be a whole number" else null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "Add rule" else "Edit rule") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(pattern, { pattern = it }, label = { Text("Pattern (e.g. rewe|edeka|aldi)") }, singleLine = true)
                DropdownSelector("Category: ${category.name}", categories, { it.name }, { category = it })
                OutlinedTextField(priority, { priority = it }, label = { Text("Priority") }, singleLine = true)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = autoApply, onCheckedChange = { autoApply = it })
                    Text("Apply automatically")
                }
                if (error != null && pattern.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { Button(enabled = error == null, onClick = { onConfirm(pattern, category.id, priority.toInt(), autoApply) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
