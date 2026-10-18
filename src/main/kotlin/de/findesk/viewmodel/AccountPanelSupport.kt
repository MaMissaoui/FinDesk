package de.findesk.viewmodel

import de.findesk.i18n.AppStrings
import de.findesk.i18n.StringKey
import de.findesk.model.AccountItem
import de.findesk.repository.AccountGroupRepository
import de.findesk.repository.AccountRepository
import de.findesk.repository.AuditLogRepository
import de.findesk.repository.SettingsRepository
import de.findesk.repository.TransactionRepository
import de.findesk.util.AccountGroupView
import de.findesk.util.GroupKey

/**
 * The account panel's stored state, shared by the Accounts and Transactions screens: one selection and one set of
 * collapsed groups per profile, so switching screens keeps both.
 */
object AccountPanelSettings {
    /** Settings keys holding the panel's selection and collapsed groups, per profile. */
    const val KEY_SELECTION = "transactions.selection"
    const val KEY_COLLAPSED = "accountGroups.collapsed"

    /** The stored selection, or All when none is stored or it names an account or group that no longer exists. */
    fun loadSelection(settings: SettingsRepository, groups: List<AccountGroupView>, accounts: List<AccountItem>): AccountSelection {
        val raw = settings.getValue(KEY_SELECTION) ?: return AccountSelection.All
        return when {
            raw.startsWith("group:") -> {
                val keyId = raw.removePrefix("group:")
                val key = when {
                    keyId.startsWith("type:") -> GroupKey.Type(keyId.removePrefix("type:"))
                    keyId.startsWith("custom:") -> keyId.removePrefix("custom:").toIntOrNull()?.let { GroupKey.Custom(it) }
                    else -> null
                }
                if (key != null && groups.any { it.key.id == key.id }) AccountSelection.Group(key) else AccountSelection.All
            }

            raw.startsWith("account:") -> {
                val id = raw.removePrefix("account:").toIntOrNull()
                if (id != null && accounts.any { it.id == id }) AccountSelection.Account(id) else AccountSelection.All
            }

            else -> AccountSelection.All
        }
    }

    fun saveSelection(settings: SettingsRepository, selection: AccountSelection) = settings.setValue(KEY_SELECTION, selectionValue(selection))

    /** Falls back to All when the selected account or group no longer exists (or a type group is now empty). */
    fun resolve(selection: AccountSelection, groups: List<AccountGroupView>, accounts: List<AccountItem>): AccountSelection = when (selection) {
        is AccountSelection.All -> selection
        is AccountSelection.Account -> if (accounts.any { it.id == selection.id }) selection else AccountSelection.All
        is AccountSelection.Group -> if (groups.any { it.key.id == selection.key.id }) selection else AccountSelection.All
    }

    fun selectionValue(selection: AccountSelection): String = when (selection) {
        AccountSelection.All -> "all"
        is AccountSelection.Group -> "group:${selection.key.id}"
        is AccountSelection.Account -> "account:${selection.id}"
    }

    fun loadCollapsed(settings: SettingsRepository): Set<String> = settings.getValue(KEY_COLLAPSED)
        ?.split(",")
        ?.map { it.trim() }
        ?.filter { it.isNotEmpty() }
        ?.toSet()
        .orEmpty()

    fun saveCollapsed(settings: SettingsRepository, collapsed: Set<String>) = settings.setValue(KEY_COLLAPSED, collapsed.sorted().joinToString(","))
}

/**
 * The account ids [selection] shows, or null for every account (All). An empty set is an empty custom group, or a
 * selection whose group no longer has accounts: it shows nothing rather than everything.
 */
fun visibleAccounts(selection: AccountSelection, groups: List<AccountGroupView>): Set<Int>? = when (selection) {
    AccountSelection.All -> null
    is AccountSelection.Account -> setOf(selection.id)
    is AccountSelection.Group -> groups.firstOrNull { it.key.id == selection.key.id }?.accounts?.map { it.id }?.toSet() ?: emptySet()
}

/**
 * The account panel's group actions with their audit entries, for both screens. Each call is synchronous; the view
 * models run it inside their own `launchSafely` and refresh afterwards.
 */
class AccountGroupActions(
    private val accountGroupRepository: AccountGroupRepository,
    private val accountRepository: AccountRepository,
    private val transactionRepository: TransactionRepository,
    private val auditLogRepository: AuditLogRepository,
    private val settingsRepository: SettingsRepository,
) {
    /** Returns an error message, or null when the group was created. */
    fun create(name: String): String? {
        if (name.isBlank()) return AppStrings[StringKey.ErrorNameRequired]
        val created = accountGroupRepository.create(name)
        auditLogRepository.log("CREATE_ACCOUNT_GROUP", "user", "Created account group '${created.name}'")
        return null
    }

    /** Returns an error message, or null when the group was renamed (or no longer exists). */
    fun rename(id: Int, name: String): String? {
        if (name.isBlank()) return AppStrings[StringKey.ErrorNameRequired]
        val renamed = accountGroupRepository.rename(id, name)
        if (renamed != null) auditLogRepository.log("RENAME_ACCOUNT_GROUP", "user", "Renamed account group $id to '${renamed.name}'")
        return null
    }

    /**
     * Deletes the group; its accounts return to their type groups. Returns true when [current] was that group: the
     * stored selection is then reset to All, and the caller shows All too.
     */
    fun delete(id: Int, current: AccountSelection): Boolean {
        accountGroupRepository.delete(id)
        auditLogRepository.log("DELETE_ACCOUNT_GROUP", "user", "Deleted account group $id")
        val wasSelected = (current as? AccountSelection.Group)?.key == GroupKey.Custom(id)
        if (wasSelected) AccountPanelSettings.saveSelection(settingsRepository, AccountSelection.All)
        return wasSelected
    }

    fun move(id: Int, up: Boolean) {
        accountGroupRepository.move(id, up)
        auditLogRepository.log("REORDER_ACCOUNT_GROUP", "user", "Moved account group $id ${if (up) "up" else "down"}")
    }

    fun moveAccount(accountId: Int, groupId: Int?) {
        accountRepository.setGroup(accountId, groupId)
        auditLogRepository.log("MOVE_ACCOUNT_TO_GROUP", "user", "Moved account $accountId to group ${groupId ?: "default"}")
    }

    /** Marks the bookings behind [selection] as read: All clears everything, a group its accounts. View state only: no audit entry. */
    fun markRead(selection: AccountSelection, groups: List<AccountGroupView>) {
        val accountIds: Set<Int>? = when (selection) {
            AccountSelection.All -> null
            is AccountSelection.Account -> setOf(selection.id)
            is AccountSelection.Group -> groups.firstOrNull { it.key == selection.key }?.accounts?.map { it.id }?.toSet() ?: emptySet()
        }
        transactionRepository.markRead(accountIds)
    }
}
