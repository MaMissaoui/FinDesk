package de.findesk.viewmodel

import de.findesk.document.DocumentService
import de.findesk.i18n.AppStrings
import de.findesk.i18n.StringKey
import de.findesk.model.CategoryItem
import de.findesk.model.RuleItem
import de.findesk.repository.*
import de.findesk.util.Currencies
import de.findesk.util.ProfileStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.math.BigDecimal
import java.time.LocalDateTime
import java.util.regex.PatternSyntaxException

data class AuditEntry(val timestamp: LocalDateTime, val action: String, val details: String?)

data class SettingsState(
    val categories: List<CategoryItem> = emptyList(),
    val rules: List<RuleItem> = emptyList(),
    val auditLog: List<AuditEntry> = emptyList(),
    val darkTheme: Boolean = false,
    val primaryCurrency: String = Currencies.DEFAULT,
    /** Choices for the primary currency: common ones plus any an account uses. */
    val currencyOptions: List<String> = Currencies.COMMON,
    val dataDirectory: String = "",
    val documentsDirectory: String = "",
    val isOcrAvailable: Boolean = false,
    val hasPendingRestore: Boolean = false,
    /** Set at startup when a restore was applied: where the replaced data was moved. */
    val restoredFrom: String? = null,
    /** Backup chosen for restore, waiting for confirmation. */
    val restoreCandidate: Pair<java.io.File, de.findesk.data.backup.BackupService.BackupInfo>? = null,
    /** Backup made under another passphrase, waiting for that passphrase; [restorePassphraseWrong] after a wrong one. */
    val restorePassphraseFor: java.io.File? = null,
    val restorePassphraseWrong: Boolean = false,
    val isEncrypted: Boolean = false,
    /** Opt-in capture of the last FinTS/HBCI dialog for support/troubleshooting. */
    val protocolTraceEnabled: Boolean = false,
    /** Whether a (redacted) trace is held in memory and can be saved. */
    val hasProtocolTrace: Boolean = false,
    val profiles: List<ProfileStore.Profile> = emptyList(),
    /** Profile running now (null = default) and the one that opens at the next start. */
    val openProfile: String? = null,
    val nextProfile: String? = null,
    val isBusy: Boolean = false,
    val isLoading: Boolean = false,
    val error: String? = null,
    val message: String? = null,
)

class SettingsViewModel(
    private val categoryRepository: CategoryRepository = CategoryRepositoryImpl(),
    private val ruleRepository: RuleRepository = RuleRepositoryImpl(),
    private val settingsRepository: SettingsRepository = SettingsRepositoryImpl(),
    private val auditLogRepository: AuditLogRepository = AuditLogRepositoryImpl(),
    private val transactionRepository: TransactionRepository = TransactionRepositoryImpl(),
    private val accountRepository: AccountRepository = AccountRepositoryImpl(),
    private val dataDirectory: String = de.findesk.util.AppPaths.dataDir.absolutePath,
    private val isOcrAvailable: Boolean = false,
    private val backupService: de.findesk.data.backup.BackupService = de.findesk.data.backup.BackupService(java.io.File(dataDirectory)),
    private val profileStore: ProfileStore = ProfileStore(),
    private val openProfile: String? = de.findesk.util.AppPaths.activeProfile,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) : BaseViewModel(scope) {
    private val _state = MutableStateFlow(SettingsState())
    val state: StateFlow<SettingsState> = _state.asStateFlow()

    private val onError: (Exception) -> Unit = { e -> _state.update { it.copy(isLoading = false, error = e.userMessage()) } }

    companion object {
        const val KEY_DARK_THEME = "ui.dark_theme"
        private val HEX_COLOR = Regex("#[0-9A-Fa-f]{6}")
    }

    fun load(): Job {
        _state.update { it.copy(isLoading = true) }
        return launchSafely(onError) { refresh() }
    }

    private fun refresh() {
        _state.update {
            it.copy(
                categories = categoryRepository.findAllItems(),
                rules = ruleRepository.findAllItems(),
                auditLog = auditLogRepository.findRecent(100).map { e -> AuditEntry(e.timestamp, e.action, e.details) },
                darkTheme = settingsRepository.getValue(KEY_DARK_THEME) == "true",
                primaryCurrency = Currencies.primary(settingsRepository),
                currencyOptions = (Currencies.COMMON + accountRepository.findAllItems().map { a -> a.currency }).distinct(),
                dataDirectory = dataDirectory,
                documentsDirectory = DocumentService.defaultDocumentsDirectory().absolutePath,
                isOcrAvailable = isOcrAvailable,
                hasPendingRestore = backupService.hasPendingRestore,
                restoredFrom = de.findesk.data.database.DatabaseFactory.restoredFrom?.absolutePath,
                isEncrypted = de.findesk.data.database.DatabaseFactory.isEncrypted,
                protocolTraceEnabled = settingsRepository.getValue(de.findesk.banking.ProtocolTraceStore.SETTING_KEY) == "true",
                hasProtocolTrace = de.findesk.banking.ProtocolTraceStore.last() != null,
                profiles = profileStore.list(),
                openProfile = openProfile,
                nextProfile = profileStore.active().slug,
                isLoading = false,
            )
        }
    }

    fun setDarkTheme(enabled: Boolean): Job = launchSafely(onError) {
        settingsRepository.setValue(KEY_DARK_THEME, enabled.toString())
        _state.update { it.copy(darkTheme = enabled) }
    }

    fun setPrimaryCurrency(code: String): Job = launchSafely(onError) {
        require(code.length == 3) { "Not a currency code: $code" }
        settingsRepository.setValue(Currencies.KEY_PRIMARY, code)
        auditLogRepository.log("SET_PRIMARY_CURRENCY", "user", code)
        _state.update { it.copy(primaryCurrency = code) }
    }

    /** Opt-in: capture the next FinTS/HBCI dialog (redacted) so it can be saved for support. */
    fun setProtocolTraceEnabled(enabled: Boolean): Job = launchSafely(onError) {
        settingsRepository.setValue(de.findesk.banking.ProtocolTraceStore.SETTING_KEY, enabled.toString())
        de.findesk.banking.ProtocolTraceStore.enabled = enabled
        auditLogRepository.log("SET_PROTOCOL_TRACE", "user", if (enabled) "FinTS protocol trace enabled" else "FinTS protocol trace disabled")
        _state.update { it.copy(protocolTraceEnabled = enabled) }
    }

    /** Saves the captured (redacted) protocol trace to [target] so the user can attach it to a report. */
    fun saveProtocolTrace(target: java.io.File): Job = launchSafely(onError) {
        if (de.findesk.banking.ProtocolTraceStore.last() == null) {
            _state.update { it.copy(error = AppStrings[StringKey.ErrorNoProtocolTrace]) }
            return@launchSafely
        }
        de.findesk.banking.ProtocolTraceStore.save(target, de.findesk.util.AppInfo.VERSION)
        auditLogRepository.log("PROTOCOL_TRACE_SAVED", "user", "FinTS protocol trace saved: ${target.name}")
        _state.update { it.copy(message = AppStrings.format(StringKey.MessageProtocolTraceSaved, target.name)) }
    }

    fun validateCategory(name: String, color: String, excludeId: Int? = null): String? = when {
        name.isBlank() -> AppStrings[StringKey.ErrorNameRequired]
        !HEX_COLOR.matches(color) -> AppStrings[StringKey.ErrorColorFormat]
        _state.value.categories.any { it.name.equals(name.trim(), ignoreCase = true) && it.id != excludeId } -> AppStrings[StringKey.ErrorCategoryExists]
        else -> null
    }

    fun saveCategory(
        id: Int?,
        name: String,
        color: String,
        budgetLimit: BigDecimal?,
        isTaxRelevant: Boolean,
    ): Job = launchSafely(onError) {
        validateCategory(name, color, id)?.let { msg ->
            _state.update { it.copy(error = msg) }
            return@launchSafely
        }
        if (id == null) {
            categoryRepository.create(name.trim(), color, budgetLimit, isTaxRelevant)
            auditLogRepository.log("ADD_CATEGORY", "user", "Added category ${name.trim()}")
        } else {
            categoryRepository.update(id, name.trim(), color, isTaxRelevant = isTaxRelevant)
            categoryRepository.setBudget(id, budgetLimit) // update() can't clear a budget
            auditLogRepository.log("UPDATE_CATEGORY", "user", "Updated category ${name.trim()}")
        }
        refresh()
    }

    /** Deleting a category un-assigns its transactions and removes its rules. */
    fun deleteCategory(id: Int): Job = launchSafely(onError) {
        transactionRepository.findByCategory(id).forEach { transactionRepository.updateCategory(it.id.value, null) }
        ruleRepository.findByCategory(id).forEach { ruleRepository.delete(it.id.value) }
        categoryRepository.delete(id)
        auditLogRepository.log("DELETE_CATEGORY", "user", "Deleted category $id")
        refresh()
    }

    fun validateRule(pattern: String): String? = when {
        pattern.isBlank() -> AppStrings[StringKey.ErrorPatternRequired]

        else -> try {
            pattern.toRegex()
            null
        } catch (e: PatternSyntaxException) {
            AppStrings.format(StringKey.ErrorInvalidRegex, e.description)
        }
    }

    fun saveRule(id: Int?, pattern: String, categoryId: Int, priority: Int, autoApply: Boolean): Job = launchSafely(onError) {
        validateRule(pattern)?.let { msg ->
            _state.update { it.copy(error = msg) }
            return@launchSafely
        }
        if (id == null) {
            ruleRepository.create(pattern.trim(), categoryId, priority, autoApply)
        } else {
            ruleRepository.update(id, pattern.trim(), categoryId, priority, autoApply)
        }
        auditLogRepository.log("SAVE_RULE", "user", "Saved rule '${pattern.trim()}'")
        refresh()
    }

    fun deleteRule(id: Int): Job = launchSafely(onError) {
        ruleRepository.delete(id)
        auditLogRepository.log("DELETE_RULE", "user", "Deleted rule $id")
        refresh()
    }

    fun createBackup(target: java.io.File): Job {
        _state.update { it.copy(isBusy = true, error = null) }
        return launchSafely({ e -> _state.update { it.copy(isBusy = false, error = e.userMessage()) } }) {
            val info = backupService.createBackup(target)
            auditLogRepository.log("BACKUP_CREATED", "user", "Backup ${target.name}: ${info.transactions} transactions, ${info.documents} documents")
            refresh()
            _state.update { it.copy(isBusy = false, message = AppStrings.format(StringKey.MessageBackupSaved, target.name, info.transactions, info.documents)) }
        }
    }

    /** Step 1: read the backup's manifest and ask for confirmation. Nothing changes yet. */
    fun chooseRestore(backup: java.io.File): Job = launchSafely(onError) {
        val info = backupService.inspect(backup)
        _state.update { it.copy(restoreCandidate = backup to info) }
    }

    fun cancelRestoreChoice() {
        _state.update { it.copy(restoreCandidate = null) }
    }

    /** Step 2: confirmed. Unpacks the backup; it replaces the current data on the next start. */
    fun confirmRestore(): Job {
        val (file, _) = _state.value.restoreCandidate ?: return Job().apply { complete() }
        return stageRestore(file, null)
    }

    /** Step 2b, for a backup made under another passphrase: checks it with that passphrase (not stored). */
    fun confirmRestoreWithPassphrase(passphrase: String): Job {
        val file = _state.value.restorePassphraseFor ?: return Job().apply { complete() }
        return stageRestore(file, passphrase)
    }

    fun cancelRestorePassphrase() {
        _state.update { it.copy(restorePassphraseFor = null, restorePassphraseWrong = false) }
    }

    private fun stageRestore(file: java.io.File, backupPassphrase: String?): Job {
        _state.update { it.copy(isBusy = true, restoreCandidate = null, restorePassphraseFor = null) }
        return launchSafely({ e ->
            if (e is de.findesk.data.backup.BackupService.BackupPassphraseRequiredException) {
                _state.update { it.copy(isBusy = false, restorePassphraseFor = file, restorePassphraseWrong = e.wrongPassphraseGiven) }
            } else {
                _state.update { it.copy(isBusy = false, error = e.userMessage()) }
            }
        }) {
            backupService.stageRestore(file, backupPassphrase)
            _state.update { it.copy(restorePassphraseWrong = false) }
            auditLogRepository.log("RESTORE_STAGED", "user", "Restore from ${file.name} staged")
            refresh()
            _state.update { it.copy(isBusy = false, message = AppStrings[StringKey.MessageRestorePrepared]) }
        }
    }

    fun cancelPendingRestore(): Job = launchSafely(onError) {
        backupService.cancelPendingRestore()
        refresh()
        _state.update { it.copy(message = AppStrings[StringKey.MessageRestoreCancelled]) }
    }

    fun createProfile(name: String): Job = launchSafely(onError) {
        val profile = profileStore.create(name)
        auditLogRepository.log("PROFILE_CREATED", "user", "Profile ${profile.name} (${profile.slug})")
        refresh()
    }

    fun renameProfile(slug: String?, name: String): Job = launchSafely(onError) {
        profileStore.rename(slug, name)
        auditLogRepository.log("PROFILE_RENAMED", "user", "Profile ${slug ?: "default"} renamed to ${name.trim()}")
        refresh()
    }

    /** Opens [slug] at the next start; the database is never switched while the app runs. */
    fun switchProfile(slug: String?): Job = launchSafely(onError) {
        profileStore.setActive(slug)
        auditLogRepository.log("PROFILE_SWITCH", "user", "Next start opens profile ${slug ?: "default"}")
        refresh()
    }

    fun deleteProfile(slug: String): Job = launchSafely(onError) {
        val movedTo = profileStore.delete(slug, openProfile)
        auditLogRepository.log("PROFILE_DELETED", "user", "Profile $slug moved to ${movedTo.absolutePath}")
        refresh()
        _state.update { it.copy(message = AppStrings.format(StringKey.MessageProfileMoved, movedTo.absolutePath)) }
    }

    fun clearMessages() {
        _state.update { it.copy(error = null, message = null) }
    }
}
