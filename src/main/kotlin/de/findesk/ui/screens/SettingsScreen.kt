package de.findesk.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.findesk.document.TesseractOcrEngine
import de.findesk.i18n.AppLanguage
import de.findesk.i18n.StringKey
import de.findesk.i18n.Strings
import de.findesk.i18n.strings
import de.findesk.model.CategoryItem
import de.findesk.model.RuleDirection
import de.findesk.model.RuleField
import de.findesk.model.RuleItem
import de.findesk.ui.components.*
import de.findesk.ui.navigation.NavigationArgs
import de.findesk.ui.navigation.SettingsSection
import de.findesk.ui.theme.CategoryColors
import de.findesk.ui.theme.Success
import de.findesk.ui.theme.Warning
import de.findesk.util.Formats
import de.findesk.util.ProfileStore
import de.findesk.viewmodel.SettingsViewModel
import java.math.BigDecimal

/** Whether two or more categories currently share one colour (hex compared case-insensitively). */
private fun List<CategoryItem>.hasSharedColours(): Boolean = groupBy { it.color.uppercase() }.any { it.value.size > 1 }

@Composable
fun SettingsScreen(
    darkTheme: Boolean,
    onDarkThemeChange: (Boolean) -> Unit,
    language: AppLanguage,
    onLanguageChange: (AppLanguage) -> Unit,
    /** Opens another profile now, through its unlock screen; null keeps the "open at next start" behaviour. */
    onSwitchProfile: ((String?) -> Unit)? = null,
    profileSwitchEnabled: Boolean = true,
    /** Section to open on arrival; null starts on Appearance. */
    args: NavigationArgs = NavigationArgs(),
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

    var section by remember { mutableStateOf(args.settingsSection ?: SettingsSection.APPEARANCE) }
    val sectionContent: @Composable () -> Unit = {
        when (section) {
            SettingsSection.APPEARANCE -> SettingsSectionHeader(s[StringKey.Appearance]) {
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
                        Text(
                            s[StringKey.PrimaryCurrencyHint],
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.widthIn(max = 640.dp),
                        )
                    }
                    DropdownSelector(
                        label = state.primaryCurrency,
                        options = state.currencyOptions,
                        optionLabel = { it },
                        onSelect = { vm.setPrimaryCurrency(it) },
                    )
                }
            }

            SettingsSection.PROFILES -> SettingsSectionHeader(
                s[StringKey.Profiles],
                actions = { TextButton(onClick = { newProfile = true }) { Text(s[StringKey.NewProfile]) } },
            ) {
                Text(
                    s[StringKey.ProfilesHint],
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.widthIn(max = 640.dp),
                )
                Spacer(Modifier.height(8.dp))
                state.profiles.forEach { p ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(p.name, modifier = Modifier.weight(1f), fontWeight = if (p.slug == state.openProfile) FontWeight.SemiBold else FontWeight.Normal)
                        if (p.slug == state.openProfile) {
                            Surface(
                                color = MaterialTheme.colorScheme.secondaryContainer,
                                shape = RoundedCornerShape(12.dp),
                            ) {
                                Text(
                                    s[StringKey.ProfileOpen],
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                )
                            }
                            Spacer(Modifier.width(8.dp))
                        }
                        if (p.slug == state.nextProfile && p.slug != state.openProfile) {
                            Text(s[StringKey.ProfileNextStart], style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.width(8.dp))
                        }
                        if (onSwitchProfile != null) {
                            // The same live switch as the top bar's profile menu
                            TextButton(enabled = p.slug != state.openProfile && profileSwitchEnabled, onClick = { onSwitchProfile(p.slug) }) { Text(s[StringKey.OpenProfile]) }
                        } else {
                            TextButton(enabled = p.slug != state.nextProfile, onClick = { vm.switchProfile(p.slug) }) { Text(s[StringKey.SwitchProfile]) }
                        }
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

            SettingsSection.CATEGORIES -> SettingsSectionHeader(
                s[StringKey.Categories],
                actions = {
                    TextButton(onClick = { newCategory = true }) { Text(s[StringKey.AddCategory]) }
                    // Only while two or more categories share a colour: otherwise there is nothing to fix
                    if (state.categories.hasSharedColours()) {
                        TextButton(onClick = { vm.fixDuplicateColours() }) { Text(s[StringKey.FixDuplicateColours]) }
                    }
                    TextButton(onClick = { vm.addDefaultCategories() }) { Text(s[StringKey.AddDefaultCategories]) }
                },
            ) {
                if (state.categories.isEmpty()) {
                    Text(s[StringKey.NoCategoriesYet], color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                state.categories.forEach { c ->
                    RowContextMenu(
                        menuItem(s[StringKey.MenuEdit]) { editCategory = c },
                        menuItem(s[StringKey.Delete]) { confirmDeleteCategory = c },
                    ) {
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
                            if (c.isTransfer) {
                                CategoryBadge(s[StringKey.CategoryTransferLabel], "#9E9E9E")
                                Spacer(Modifier.width(8.dp))
                            }
                            if (c.isTaxRelevant) {
                                CategoryBadge(s[StringKey.TaxRelevant], "#F59E0B")
                                Spacer(Modifier.width(8.dp))
                            }
                            c.budgetLimit?.let {
                                Text(s.format(StringKey.BudgetAmount, Formats.money(it, state.primaryCurrency)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(Modifier.width(8.dp))
                            }
                            // Bookings for this category; 0 through the default, so unused categories show "0 bookings"
                            Text(s.format(StringKey.CategoryUsage, state.categoryUsage[c.id] ?: 0), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.width(8.dp))
                            IconButton(onClick = { confirmDeleteCategory = c }) { Icon(Icons.Default.Delete, contentDescription = s[StringKey.Delete]) }
                        }
                    }
                    HorizontalDivider()
                }
            }

            SettingsSection.RULES -> SettingsSectionHeader(
                s[StringKey.AutoCategorizationRules],
                actions = {
                    DisabledReason(s[StringKey.HintNeedsCategory].takeIf { state.categories.isEmpty() }) {
                        TextButton(onClick = { newRule = true }, enabled = state.categories.isNotEmpty()) { Text(s[StringKey.AddRule]) }
                    }
                    TextButton(onClick = {
                        FileDialogs.openFiles(s[StringKey.ImportRules], setOf("tsv", "txt")).firstOrNull()?.let { vm.chooseRuleImport(it) }
                    }) { Text(s[StringKey.ImportRules]) }
                    TextButton(onClick = {
                        FileDialogs.saveFile(s[StringKey.ExportRules], "findesk-rules-${java.time.LocalDate.now()}.tsv")?.let { vm.exportRules(it) }
                    }) { Text(s[StringKey.ExportRules]) }
                },
            ) {
                Text(
                    s[StringKey.RulesHint],
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.widthIn(max = 640.dp),
                )
                Spacer(Modifier.height(8.dp))
                state.rules.forEach { r ->
                    RowContextMenu(
                        menuItem(s[StringKey.MenuEdit]) { editRule = r },
                        menuItem(s[StringKey.Delete]) { vm.deleteRule(r.id) },
                    ) {
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
                            Column(Modifier.weight(1f)) {
                                Text("→ ${r.categoryName}" + ruleTaxMarker(r, s))
                                val restrictions = listOf(
                                    RuleField.PARTNER to s[StringKey.RuleFieldPartner],
                                    RuleField.PURPOSE to s[StringKey.RuleFieldPurpose],
                                    RuleDirection.EXPENSE to s[StringKey.RuleDirectionExpense],
                                    RuleDirection.INCOME to s[StringKey.RuleDirectionIncome],
                                ).filter { (which, _) -> r.matchField == which || r.direction == which }
                                if (restrictions.isNotEmpty()) {
                                    Spacer(Modifier.height(2.dp))
                                    Text(
                                        restrictions.joinToString(" · ") { it.second },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            Text(s.format(StringKey.Prio, r.priority) + if (!r.autoApply) " · ${s[StringKey.Manual]}" else "", style = MaterialTheme.typography.bodySmall)
                            IconButton(onClick = { vm.deleteRule(r.id) }) { Icon(Icons.Default.Delete, contentDescription = s[StringKey.Delete]) }
                        }
                    }
                    HorizontalDivider()
                }
            }

            SettingsSection.DATA -> SettingsSectionHeader(s[StringKey.DataAndOcr]) {
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

            SettingsSection.BACKUP -> SettingsSectionHeader(s[StringKey.BackupAndRestore]) {
                Text(
                    s[StringKey.BackupHint],
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.widthIn(max = 640.dp),
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
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.widthIn(max = 640.dp),
                    )
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(enabled = !state.isBusy, onClick = {
                        FileDialogs.saveFile(s[StringKey.SaveBackup], "findesk-backup-${java.time.LocalDate.now()}.zip")?.let { vm.createBackup(it) }
                    }) { Text(s[StringKey.CreateBackup]) }
                    OutlinedButton(
                        enabled = !state.isBusy && !state.hasPendingRestore,
                        onClick = {
                            FileDialogs.openFiles(s[StringKey.RestoreBackup], setOf("zip")).firstOrNull()?.let { vm.chooseRestore(it) }
                        },
                        colors = OutlinedButtonStyle.colors(),
                        border = OutlinedButtonStyle.border(enabled = !state.isBusy && !state.hasPendingRestore),
                    ) { Text(s[StringKey.RestoreFromBackup]) }
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

            SettingsSection.TRACE -> SettingsSectionHeader(s[StringKey.ProtocolTraceTitle]) {
                Text(
                    s[StringKey.ProtocolTraceHint],
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.widthIn(max = 640.dp),
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

            SettingsSection.AUDIT -> SettingsSectionHeader(s[StringKey.AuditLogLatest]) {
                if (state.auditLog.isEmpty()) {
                    Text(s[StringKey.AuditLogEmpty], color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
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

    // Each section starts at its top: a shared scroll position would open a short section part-way down
    val scroll = key(section) { rememberScrollState() }
    BoxWithConstraints(Modifier.fillMaxSize().padding(24.dp)) {
        if (maxWidth >= 840.dp) {
            Row(Modifier.fillMaxSize()) {
                SettingsSectionList(section, onSelect = { section = it })
                VerticalDivider(Modifier.fillMaxHeight())
                Column(Modifier.fillMaxSize().padding(start = 24.dp)) {
                    // The banner stays above the section, as it did above the page
                    MessageBanner(state.message, state.error, vm::clearMessages)
                    Column(Modifier.fillMaxSize().verticalScroll(scroll)) {
                        Box(Modifier.fillMaxWidth()) {
                            Column(Modifier.widthIn(max = 760.dp)) { sectionContent() }
                        }
                    }
                }
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                MessageBanner(state.message, state.error, vm::clearMessages)
                DropdownSelector(
                    label = s[section.title],
                    options = SettingsSection.entries,
                    optionLabel = { s[it.title] },
                    onSelect = { section = it },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(16.dp))
                Column(Modifier.fillMaxSize().verticalScroll(scroll)) {
                    Box(Modifier.fillMaxWidth()) {
                        Column(Modifier.widthIn(max = 760.dp)) { sectionContent() }
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
            onConfirm = { name, color, budget, tax, transfer ->
                vm.saveCategory(editCategory?.id, name, color, budget, tax, transfer)
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
            onConfirm = { pattern, categoryId, priority, autoApply, matchField, direction, taxRelevant ->
                vm.saveRule(editRule?.id, pattern, categoryId, priority, autoApply, matchField, direction, taxRelevant)
                newRule = false
                editRule = null
            },
        )
    }
    state.ruleImport?.let { preview ->
        RuleImportDialog(
            preview = preview,
            onConfirm = vm::confirmRuleImport,
            onDismiss = vm::cancelRuleImport,
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

/** The left pane: 240 dp, no card, one selectable tab per section. */
@Composable
private fun SettingsSectionList(selected: SettingsSection, onSelect: (SettingsSection) -> Unit) {
    val s = strings()
    Column(Modifier.width(240.dp).fillMaxHeight()) {
        SettingsSection.entries.forEach { entry ->
            SettingsSectionRow(
                section = entry,
                selected = entry == selected,
                label = s[entry.title],
                onClick = { onSelect(entry) },
            )
        }
    }
}

@Composable
private fun SettingsSectionRow(section: SettingsSection, selected: Boolean, label: String, onClick: () -> Unit) {
    val background = if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent
    val labelColor = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface
    val iconColor = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
    val barColor = MaterialTheme.colorScheme.primary
    // The sidebar's and account panel's 3 dp start bar, on the same rounded row
    val selectionBar = if (selected) {
        Modifier.drawBehind { drawRect(color = barColor, size = Size(3.dp.toPx(), size.height)) }
    } else {
        Modifier
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(background)
            .then(selectionBar)
            .selectable(selected = selected, onClick = onClick, role = Role.Tab)
            // Long titles ("Sicherung & Wiederherstellung") wrap to a second line instead of being cut off
            .heightIn(min = 40.dp)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(section.icon, contentDescription = null, tint = iconColor, modifier = Modifier.size(20.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, color = labelColor, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/** material-icons-core only: Palette, Category, Storage, Backup, BugReport and History are in the extended set. */
private val SettingsSection.icon: ImageVector
    get() = when (this) {
        SettingsSection.APPEARANCE -> Icons.Default.Settings
        SettingsSection.PROFILES -> Icons.Default.Person
        SettingsSection.CATEGORIES -> Icons.Default.List
        SettingsSection.RULES -> Icons.Default.Refresh
        SettingsSection.DATA -> Icons.Default.Info
        SettingsSection.BACKUP -> Icons.Default.Share
        SettingsSection.TRACE -> Icons.Default.Build
        SettingsSection.AUDIT -> Icons.Default.DateRange
    }

/** A flush section header: its title and actions, a hairline divider, then the content. */
@Composable
private fun SettingsSectionHeader(
    title: String,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        // As tall as an action button, so a title sits at the same height with or without actions
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f).semantics { heading() },
            )
            actions()
        }
        Spacer(Modifier.height(8.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Spacer(Modifier.height(12.dp))
        content()
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CategoryDialog(
    existing: CategoryItem?,
    validate: (String, String) -> String?,
    onDismiss: () -> Unit,
    onConfirm: (String, String, BigDecimal?, Boolean, Boolean) -> Unit,
) {
    var name by remember { mutableStateOf(existing?.name.orEmpty()) }
    var color by remember { mutableStateOf(existing?.color ?: colorHex(0)) }
    var budget by remember { mutableStateOf(existing?.budgetLimit?.toPlainString()?.replace('.', ',').orEmpty()) }
    var tax by remember { mutableStateOf(existing?.isTaxRelevant ?: false) }
    var isTransfer by remember { mutableStateOf(existing?.isTransfer ?: false) }
    val parsedBudget = budget.takeIf { it.isNotBlank() }?.let { Formats.parseAmount(it) }
    val s = strings()
    val error = validate(name, color) ?: if (budget.isNotBlank() && parsedBudget == null) s[StringKey.BudgetNotANumber] else null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) s[StringKey.AddCategory] else s[StringKey.EditCategory]) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text(s[StringKey.Name]) }, singleLine = true)
                // The swatches wrap over several rows instead of one row
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    CategoryColors.indices.forEach { i ->
                        val hex = colorHex(i)
                        Box(
                            Modifier.size(if (hex.equals(color, true)) 26.dp else 20.dp)
                                .background(CategoryColors[i], CircleShape)
                                .semantics { contentDescription = s.format(StringKey.ColourSwatch, i + 1, CategoryColors.size) }
                                .clickable { color = hex },
                        )
                    }
                }
                OutlinedTextField(budget, { budget = it }, label = { Text(s[StringKey.MonthlyBudgetOptional]) }, singleLine = true)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = tax, onCheckedChange = { tax = it })
                    Text(s[StringKey.TaxRelevantHint])
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = isTransfer, onCheckedChange = { isTransfer = it })
                    Text(s[StringKey.CategoryIsTransfer])
                }
                Text(s[StringKey.CategoryIsTransferHint], style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (error != null && name.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { Button(enabled = error == null, onClick = { onConfirm(name, color, parsedBudget, tax, isTransfer) }) { Text(s[StringKey.Save]) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(s[StringKey.Cancel]) } },
    )
}

@Composable
internal fun RuleDialog(
    existing: RuleItem?,
    categories: List<CategoryItem>,
    validate: (String) -> String?,
    onDismiss: () -> Unit,
    onConfirm: (String, Int, Int, Boolean, RuleField, RuleDirection, Boolean?) -> Unit,
) {
    var pattern by remember { mutableStateOf(existing?.pattern.orEmpty()) }
    var category by remember { mutableStateOf(categories.firstOrNull { it.id == existing?.categoryId } ?: categories.first()) }
    var priority by remember { mutableStateOf((existing?.priority ?: 0).toString()) }
    var autoApply by remember { mutableStateOf(existing?.autoApply ?: true) }
    var matchField by remember { mutableStateOf(existing?.matchField ?: RuleField.ANY) }
    var direction by remember { mutableStateOf(existing?.direction ?: RuleDirection.ANY) }
    var taxRelevant by remember { mutableStateOf(existing?.taxRelevant) }
    val error = validate(pattern) ?: if (priority.toIntOrNull() == null) strings()[StringKey.PriorityMustBeNumber] else null
    val s = strings()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) s[StringKey.AddRule] else s[StringKey.EditRule]) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(pattern, { pattern = it }, label = { Text(s[StringKey.PatternExample]) }, singleLine = true)
                DropdownSelector(s.format(StringKey.RuleCategoryLabel, category.name), categories, { it.name }, { category = it })
                val fieldLabel: (RuleField) -> String = {
                    s[
                        when (it) {
                            RuleField.ANY -> StringKey.RuleFieldAny
                            RuleField.PARTNER -> StringKey.RuleFieldPartner
                            RuleField.PURPOSE -> StringKey.RuleFieldPurpose
                        },
                    ]
                }
                val directionLabel: (RuleDirection) -> String = {
                    s[
                        when (it) {
                            RuleDirection.ANY -> StringKey.RuleDirectionAny
                            RuleDirection.EXPENSE -> StringKey.RuleDirectionExpense
                            RuleDirection.INCOME -> StringKey.RuleDirectionIncome
                        },
                    ]
                }
                // The button shows the current choice, like the category selector above
                DropdownSelector("${s[StringKey.RuleSearchIn]}: ${fieldLabel(matchField)}", RuleField.entries, fieldLabel, { matchField = it })
                DropdownSelector("${s[StringKey.RuleAppliesTo]}: ${directionLabel(direction)}", RuleDirection.entries, directionLabel, { direction = it })
                DropdownSelector("${s[StringKey.TaxMenu]}: ${ruleTaxLabel(taxRelevant, s)}", listOf<Boolean?>(null, true, false), { ruleTaxLabel(it, s) }, { taxRelevant = it })
                OutlinedTextField(priority, { priority = it }, label = { Text(s[StringKey.Priority]) }, singleLine = true)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = autoApply, onCheckedChange = { autoApply = it })
                    Text(s[StringKey.ApplyAutomatically])
                }
                if (error != null && pattern.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            Button(enabled = error == null, onClick = { onConfirm(pattern, category.id, priority.toInt(), autoApply, matchField, direction, taxRelevant) }) {
                Text(s[StringKey.Save])
            }
        },
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

/**
 * Reads a rule import back to the user before it is applied: the counts of new rules and new categories, the
 * duplicates and conflicts that are skipped, the invalid rows and, when bookings would be categorized now,
 * a checkbox for it. Long content scrolls inside the dialog.
 */
@Composable
private fun RuleImportDialog(
    preview: de.findesk.importer.RuleImportPreview,
    onConfirm: (categorize: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val s = strings()
    var categorize by remember { mutableStateOf(true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(s[StringKey.RuleImportTitle]) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(s.format(StringKey.RuleImportNewRules, preview.newRules.size))
                if (preview.categoriesToCreate.isNotEmpty()) {
                    Text(s.format(StringKey.RuleImportCategories, preview.categoriesToCreate.size))
                    val more = preview.categoriesToCreate.size - 20
                    Text(
                        preview.categoriesToCreate.take(20).joinToString(", ") +
                            (if (more > 0) ", ${s.format(StringKey.AndMore, more)}" else ""),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (preview.duplicates > 0) Text(s.format(StringKey.RuleImportDuplicates, preview.duplicates))
                if (preview.conflicts.isNotEmpty()) {
                    Text(s.format(StringKey.RuleImportConflicts, preview.conflicts.size))
                    LineList(preview.conflicts.map { s.format(StringKey.RuleImportConflictLine, it.line, it.pattern, it.fileCategory, it.existingCategory) })
                }
                if (preview.errors.isNotEmpty()) {
                    Text(s.format(StringKey.RuleImportErrors, preview.errors.size))
                    LineList(preview.errors.map { s.format(StringKey.RuleImportErrorLine, it.line, it.reason) })
                }
                if (preview.matchCount > 0) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = categorize, onCheckedChange = { categorize = it })
                        Text(s.format(StringKey.RuleImportCategorize, preview.matchCount))
                    }
                }
            }
        },
        confirmButton = {
            Button(
                enabled = preview.newRules.isNotEmpty() || preview.categoriesToCreate.isNotEmpty(),
                onClick = { onConfirm(categorize) },
            ) { Text(s[StringKey.Import]) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(s[StringKey.Cancel]) } },
    )
}

/** The first 20 lines of a list, then "… and N more". */
@Composable
private fun LineList(lines: List<String>) {
    val s = strings()
    lines.take(20).forEach { line ->
        Text(line, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (lines.size > 20) {
        Text(s.format(StringKey.AndMore, lines.size - 20), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** The rule dialog's Tax choices: null reads "As the category", unlike the booking override's "Automatic". */
internal fun ruleTaxLabel(value: Boolean?, s: Strings): String = when (value) {
    null -> s[StringKey.RuleTaxAsCategory]
    true -> s[StringKey.TaxRelevantChoice]
    false -> s[StringKey.TaxNotRelevantChoice]
}

/** The rules list's short marker after the category for a rule with its own tax flag ("· Tax" / "· No tax"). */
internal fun ruleTaxMarker(rule: RuleItem, s: Strings): String = when (rule.taxRelevant) {
    true -> " · ${s[StringKey.RuleTaxMarkerYes]}"
    false -> " · ${s[StringKey.RuleTaxMarkerNo]}"
    null -> ""
}
