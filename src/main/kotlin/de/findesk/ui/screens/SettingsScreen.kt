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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import de.findesk.document.TesseractOcrEngine
import de.findesk.i18n.AppLanguage
import de.findesk.i18n.StringKey
import de.findesk.i18n.strings
import de.findesk.model.CategoryItem
import de.findesk.model.RuleItem
import de.findesk.ui.components.*
import de.findesk.ui.theme.CategoryColors
import de.findesk.ui.theme.Success
import de.findesk.ui.theme.Warning
import de.findesk.util.Formats
import de.findesk.util.ProfileStore
import de.findesk.viewmodel.SettingsViewModel
import java.math.BigDecimal

@Composable
fun SettingsScreen(
    darkTheme: Boolean,
    onDarkThemeChange: (Boolean) -> Unit,
    language: AppLanguage,
    onLanguageChange: (AppLanguage) -> Unit,
) {
    val vm = rememberViewModel { SettingsViewModel(isOcrAvailable = TesseractOcrEngine.createOrNull() != null) }
    val state by vm.state.collectAsState()
    val s = strings()
    LaunchedEffect(Unit) { vm.load() }

    var editCategory by remember { mutableStateOf<CategoryItem?>(null) }
    var newCategory by remember { mutableStateOf(false) }
    var editRule by remember { mutableStateOf<RuleItem?>(null) }
    var newRule by remember { mutableStateOf(false) }
    var confirmDeleteCategory by remember { mutableStateOf<CategoryItem?>(null) }
    var newProfile by remember { mutableStateOf(false) }
    var renameProfile by remember { mutableStateOf<ProfileStore.Profile?>(null) }
    var confirmDeleteProfile by remember { mutableStateOf<ProfileStore.Profile?>(null) }

    LazyColumn(modifier = Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Text(
                s[StringKey.NavSettings],
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.semantics { heading() },
            )
            Spacer(Modifier.height(8.dp))
            MessageBanner(state.message, state.error, vm::clearMessages)
        }

        item {
            SectionCard(s[StringKey.Appearance], Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(s[StringKey.DarkTheme], modifier = Modifier.weight(1f))
                    Switch(checked = darkTheme, onCheckedChange = onDarkThemeChange)
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(s[StringKey.Language], modifier = Modifier.weight(1f))
                    DropdownSelector(
                        label = language.nativeName,
                        options = AppLanguage.entries,
                        optionLabel = { option -> option.nativeName },
                        onSelect = { onLanguageChange(it) },
                    )
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(s[StringKey.PrimaryCurrency])
                        Text(s[StringKey.PrimaryCurrencyHint], style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    DropdownSelector(
                        label = state.primaryCurrency,
                        options = state.currencyOptions,
                        optionLabel = { it },
                        onSelect = { vm.setPrimaryCurrency(it) },
                    )
                }
            }
        }

        item {
            SectionCard(
                s[StringKey.Profiles],
                Modifier.fillMaxWidth(),
                actions = { TextButton(onClick = { newProfile = true }) { Text(s[StringKey.NewProfile]) } },
            ) {
                Text(s[StringKey.ProfilesHint], style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                state.profiles.forEach { p ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(p.name, modifier = Modifier.weight(1f), fontWeight = if (p.slug == state.openProfile) FontWeight.SemiBold else FontWeight.Normal)
                        if (p.slug == state.openProfile) {
                            Text(s[StringKey.ProfileOpen], style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.width(8.dp))
                        }
                        if (p.slug == state.nextProfile && p.slug != state.openProfile) {
                            Text(s[StringKey.ProfileNextStart], style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.width(8.dp))
                        }
                        TextButton(enabled = p.slug != state.nextProfile, onClick = { vm.switchProfile(p.slug) }) { Text(s[StringKey.SwitchProfile]) }
                        IconButton(onClick = { renameProfile = p }) { Icon(Icons.Default.Edit, contentDescription = s.format(StringKey.RenameProfile, p.name)) }
                        IconButton(enabled = !p.isDefault && p.slug != state.openProfile, onClick = { confirmDeleteProfile = p }) {
                            Icon(Icons.Default.Delete, contentDescription = s.format(StringKey.DeleteProfile, p.name))
                        }
                    }
                }
                if (state.nextProfile != state.openProfile) {
                    val next = state.profiles.firstOrNull { it.slug == state.nextProfile }?.name.orEmpty()
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Warning, contentDescription = null, tint = Warning, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(s.format(StringKey.RestartToOpenProfile, next))
                    }
                }
            }
        }

        item {
            SectionCard(
                s[StringKey.Categories],
                Modifier.fillMaxWidth(),
                actions = { TextButton(onClick = { newCategory = true }) { Text(s[StringKey.AddCategory]) } },
            ) {
                if (state.categories.isEmpty()) {
                    Text(s[StringKey.NoCategoriesYet], color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                state.categories.forEach { c ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable(
                                onClick = { editCategory = c },
                                onClickLabel = s.format(StringKey.EditCategoryNamed, c.name),
                                role = Role.Button,
                            )
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.size(12.dp).background(categoryColor(c.color, c.name), CircleShape))
                        Spacer(Modifier.width(12.dp))
                        Text(c.name, modifier = Modifier.weight(1f))
                        if (c.isTaxRelevant) {
                            CategoryBadge(s[StringKey.TaxRelevant], "#F59E0B")
                            Spacer(Modifier.width(8.dp))
                        }
                        c.budgetLimit?.let {
                            Text(s.format(StringKey.BudgetAmount, Formats.money(it, state.primaryCurrency)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.width(8.dp))
                        }
                        IconButton(onClick = { confirmDeleteCategory = c }) { Icon(Icons.Default.Delete, contentDescription = s[StringKey.Delete]) }
                    }
                    HorizontalDivider()
                }
            }
        }

        item {
            SectionCard(
                s[StringKey.AutoCategorizationRules],
                Modifier.fillMaxWidth(),
                actions = {
                    DisabledReason(s[StringKey.HintNeedsCategory].takeIf { state.categories.isEmpty() }) {
                        TextButton(onClick = { newRule = true }, enabled = state.categories.isNotEmpty()) { Text(s[StringKey.AddRule]) }
                    }
                },
            ) {
                Text(
                    s[StringKey.RulesHint],
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                state.rules.forEach { r ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable(
                                onClick = { editRule = r },
                                onClickLabel = s.format(StringKey.EditRuleNamed, r.pattern),
                                role = Role.Button,
                            )
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(r.pattern, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                        Text("→ ${r.categoryName}", modifier = Modifier.weight(1f))
                        Text(s.format(StringKey.Prio, r.priority) + if (!r.autoApply) " · ${s[StringKey.Manual]}" else "", style = MaterialTheme.typography.bodySmall)
                        IconButton(onClick = { vm.deleteRule(r.id) }) { Icon(Icons.Default.Delete, contentDescription = s[StringKey.Delete]) }
                    }
                    HorizontalDivider()
                }
            }
        }

        item {
            SectionCard(s[StringKey.DataAndOcr], Modifier.fillMaxWidth()) {
                InfoRow(s[StringKey.Database], state.dataDirectory + "/findesk.db")
                InfoRow(s[StringKey.DocumentsFolder], state.documentsDirectory)
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
                            s[StringKey.TesseractAvailable]
                        } else {
                            s[StringKey.TesseractMissing]
                        },
                    )
                }
            }
        }

        item {
            SectionCard(s[StringKey.BackupAndRestore], Modifier.fillMaxWidth()) {
                Text(
                    s[StringKey.BackupHint],
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (state.isEncrypted) Icons.Default.Lock else Icons.Default.Warning,
                        contentDescription = null,
                        tint = if (state.isEncrypted) MaterialTheme.colorScheme.primary else Warning,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        if (state.isEncrypted) s[StringKey.EncryptionStatus] + " " + s[StringKey.BackupNote] else s[StringKey.EncryptionOff],
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(enabled = !state.isBusy, onClick = {
                        FileDialogs.saveFile(s[StringKey.SaveBackup], "findesk-backup-${java.time.LocalDate.now()}.zip")?.let { vm.createBackup(it) }
                    }) { Text(s[StringKey.CreateBackup]) }
                    OutlinedButton(enabled = !state.isBusy && !state.hasPendingRestore, onClick = {
                        FileDialogs.openFiles(s[StringKey.RestoreBackup], setOf("zip")).firstOrNull()?.let { vm.chooseRestore(it) }
                    }) { Text(s[StringKey.RestoreFromBackup]) }
                    if (state.isBusy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                }
                if (state.hasPendingRestore) {
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Warning, contentDescription = null, tint = Warning, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(s[StringKey.RestoreWaiting], modifier = Modifier.weight(1f))
                        TextButton(onClick = { vm.cancelPendingRestore() }) { Text(s[StringKey.CancelRestore]) }
                    }
                }
                state.restoredFrom?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(s.format(StringKey.BackupRestoredAt, it), style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        item {
            SectionCard(s[StringKey.ProtocolTraceTitle], Modifier.fillMaxWidth()) {
                Text(
                    s[StringKey.ProtocolTraceHint],
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(s[StringKey.ProtocolTraceEnable], modifier = Modifier.weight(1f))
                    Switch(checked = state.protocolTraceEnabled, onCheckedChange = { vm.setProtocolTraceEnabled(it) })
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(
                        enabled = state.hasProtocolTrace,
                        onClick = {
                            FileDialogs.saveFile(s[StringKey.ProtocolTraceSave], "findesk-fints-trace-${java.time.LocalDate.now()}.txt")?.let { vm.saveProtocolTrace(it) }
                        },
                    ) { Text(s[StringKey.ProtocolTraceSave]) }
                    Text(
                        if (state.hasProtocolTrace) s[StringKey.ProtocolTraceAvailable] else s[StringKey.ProtocolTraceNone],
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        item {
            SectionCard(s[StringKey.AuditLogLatest], Modifier.fillMaxWidth()) {
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

    if (newProfile || renameProfile != null) {
        ProfileNameDialog(
            initial = renameProfile?.name.orEmpty(),
            onDismiss = {
                newProfile = false
                renameProfile = null
            },
            onConfirm = { name ->
                renameProfile?.let { vm.renameProfile(it.slug, name) } ?: vm.createProfile(name)
                newProfile = false
                renameProfile = null
            },
        )
    }
    confirmDeleteProfile?.let { p ->
        ConfirmDialog(
            title = s.format(StringKey.DeleteProfile, p.name),
            text = s.format(StringKey.DeleteProfileText, p.name),
            onConfirm = { p.slug?.let(vm::deleteProfile) },
            onDismiss = { confirmDeleteProfile = null },
        )
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
            title = { Text(s.format(StringKey.RestoreFileTitle, file.name)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(s.format(StringKey.RestoreCreated, info.created.take(16).replace('T', ' '), info.transactions, info.documents))
                    Text(s[StringKey.RestoreExplanation])
                }
            },
            confirmButton = { Button(onClick = { vm.confirmRestore() }) { Text(s[StringKey.PrepareRestore]) } },
            dismissButton = { TextButton(onClick = vm::cancelRestoreChoice) { Text(s[StringKey.Cancel]) } },
        )
    }
    state.restorePassphraseFor?.let { file ->
        BackupPassphraseDialog(
            fileName = file.name,
            wrong = state.restorePassphraseWrong,
            onConfirm = vm::confirmRestoreWithPassphrase,
            onDismiss = vm::cancelRestorePassphrase,
        )
    }
    confirmDeleteCategory?.let { c ->
        ConfirmDialog(
            title = s.format(StringKey.DeleteCategoryTitle, c.name),
            text = s[StringKey.DeleteCategoryText],
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
    val s = strings()
    val error = validate(name, color) ?: if (budget.isNotBlank() && parsedBudget == null) s[StringKey.BudgetNotANumber] else null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) s[StringKey.AddCategory] else s[StringKey.EditCategory]) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text(s[StringKey.Name]) }, singleLine = true)
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
                OutlinedTextField(budget, { budget = it }, label = { Text(s[StringKey.MonthlyBudgetOptional]) }, singleLine = true)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = tax, onCheckedChange = { tax = it })
                    Text(s[StringKey.TaxRelevantHint])
                }
                if (error != null && name.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { Button(enabled = error == null, onClick = { onConfirm(name, color, parsedBudget, tax) }) { Text(s[StringKey.Save]) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(s[StringKey.Cancel]) } },
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
    val error = validate(pattern) ?: if (priority.toIntOrNull() == null) strings()[StringKey.PriorityMustBeNumber] else null
    val s = strings()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) s[StringKey.AddRule] else s[StringKey.EditRule]) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(pattern, { pattern = it }, label = { Text(s[StringKey.PatternExample]) }, singleLine = true)
                DropdownSelector("Category: ${category.name}", categories, { it.name }, { category = it })
                OutlinedTextField(priority, { priority = it }, label = { Text(s[StringKey.Priority]) }, singleLine = true)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = autoApply, onCheckedChange = { autoApply = it })
                    Text(s[StringKey.ApplyAutomatically])
                }
                if (error != null && pattern.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { Button(enabled = error == null, onClick = { onConfirm(pattern, category.id, priority.toInt(), autoApply) }) { Text(s[StringKey.Save]) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(s[StringKey.Cancel]) } },
    )
}

@Composable
private fun BackupPassphraseDialog(fileName: String, wrong: Boolean, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    val s = strings()
    var passphrase by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(s[StringKey.BackupPassphraseTitle]) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(fileName, style = MaterialTheme.typography.labelLarge)
                Text(s[StringKey.BackupPassphraseText])
                OutlinedTextField(
                    passphrase,
                    { passphrase = it },
                    label = { Text(s[StringKey.Passphrase]) },
                    singleLine = true,
                    isError = wrong,
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    supportingText = if (wrong) {
                        { Text(s[StringKey.WrongPassphrase]) }
                    } else {
                        null
                    },
                )
            }
        },
        confirmButton = { Button(enabled = passphrase.isNotEmpty(), onClick = { onConfirm(passphrase) }) { Text(s[StringKey.PrepareRestore]) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(s[StringKey.Cancel]) } },
    )
}

@Composable
private fun ProfileNameDialog(initial: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    val s = strings()
    var name by remember { mutableStateOf(initial) }
    val valid = name.isNotBlank() && name.trim().length <= ProfileStore.MAX_NAME_LENGTH
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial.isEmpty()) s[StringKey.NewProfile] else s.format(StringKey.RenameProfile, initial)) },
        text = {
            OutlinedTextField(
                name,
                { name = it.take(ProfileStore.MAX_NAME_LENGTH) },
                label = { Text(s[StringKey.ProfileName]) },
                singleLine = true,
                supportingText = { Text("${name.trim().length}/${ProfileStore.MAX_NAME_LENGTH}") },
            )
        },
        confirmButton = { Button(enabled = valid, onClick = { onConfirm(name) }) { Text(s[StringKey.Save]) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(s[StringKey.Cancel]) } },
    )
}
