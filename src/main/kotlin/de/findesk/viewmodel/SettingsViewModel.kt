package de.findesk.viewmodel

import de.findesk.document.DocumentService
import de.findesk.model.CategoryItem
import de.findesk.model.RuleItem
import de.findesk.repository.*
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
    val dataDirectory: String = "",
    val documentsDirectory: String = "",
    val isOcrAvailable: Boolean = false,
    val hasPendingRestore: Boolean = false,
    /** Set at startup when a restore was applied: where the replaced data was moved. */
    val restoredFrom: String? = null,
    /** Backup chosen for restore, waiting for confirmation. */
    val restoreCandidate: Pair<java.io.File, de.findesk.data.backup.BackupService.BackupInfo>? = null,
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
    private val dataDirectory: String = de.findesk.util.AppPaths.dataDir.absolutePath,
    private val isOcrAvailable: Boolean = false,
    private val backupService: de.findesk.data.backup.BackupService = de.findesk.data.backup.BackupService(java.io.File(dataDirectory)),
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
                dataDirectory = dataDirectory,
                documentsDirectory = DocumentService.defaultDocumentsDirectory().absolutePath,
                isOcrAvailable = isOcrAvailable,
                hasPendingRestore = backupService.hasPendingRestore,
                restoredFrom = de.findesk.data.database.DatabaseFactory.restoredFrom?.absolutePath,
                isLoading = false,
            )
        }
    }

    fun setDarkTheme(enabled: Boolean): Job = launchSafely(onError) {
        settingsRepository.setValue(KEY_DARK_THEME, enabled.toString())
        _state.update { it.copy(darkTheme = enabled) }
    }

    fun validateCategory(name: String, color: String, excludeId: Int? = null): String? = when {
        name.isBlank() -> "Name is required"
        !HEX_COLOR.matches(color) -> "Color must look like #3BC7B0"
        _state.value.categories.any { it.name.equals(name.trim(), ignoreCase = true) && it.id != excludeId } -> "Category already exists"
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
        pattern.isBlank() -> "Pattern is required"

        else -> try {
            pattern.toRegex()
            null
        } catch (e: PatternSyntaxException) {
            "Invalid regular expression: ${e.description}"
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
        refresh()
    }

    fun createBackup(target: java.io.File): Job {
        _state.update { it.copy(isBusy = true, error = null) }
        return launchSafely({ e -> _state.update { it.copy(isBusy = false, error = e.userMessage()) } }) {
            val info = backupService.createBackup(target)
            auditLogRepository.log("BACKUP_CREATED", "user", "Backup ${target.name}: ${info.transactions} transactions, ${info.documents} documents")
            refresh()
            _state.update { it.copy(isBusy = false, message = "Backup saved to ${target.name} (${info.transactions} transactions, ${info.documents} documents)") }
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
        _state.update { it.copy(isBusy = true, restoreCandidate = null) }
        return launchSafely({ e -> _state.update { it.copy(isBusy = false, error = e.userMessage()) } }) {
            backupService.stageRestore(file)
            auditLogRepository.log("RESTORE_STAGED", "user", "Restore from ${file.name} staged")
            refresh()
            _state.update { it.copy(isBusy = false, message = "Restore prepared. Restart FinDesk to finish; your current data will be kept in a pre-restore folder.") }
        }
    }

    fun cancelPendingRestore(): Job = launchSafely(onError) {
        backupService.cancelPendingRestore()
        refresh()
        _state.update { it.copy(message = "Pending restore cancelled") }
    }

    fun clearMessages() {
        _state.update { it.copy(error = null, message = null) }
    }
}
