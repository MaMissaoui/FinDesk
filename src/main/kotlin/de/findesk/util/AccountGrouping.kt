package de.findesk.util

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import de.findesk.model.AccountGroupItem
import de.findesk.model.AccountItem
import java.math.BigDecimal

/**
 * What an [AccountGroupView] stands for: a built-in account-type group, or one of the user's custom groups.
 * Both expose a stable [id] string used for the persisted selection and the collapsed state.
 */
sealed interface GroupKey {
    val id: String

    data class Type(val type: String) : GroupKey {
        override val id: String get() = "type:$type"
    }

    data class Custom(val groupId: Int) : GroupKey {
        override val id: String get() = "custom:$groupId"
    }
}

/** One entry in the account panel: its key, an optional custom name and the accounts with their per-currency totals. */
data class AccountGroupView(
    val key: GroupKey,
    /** The custom group's name, or null for a type group (the UI localizes those with `Strings.accountType`). */
    val name: String?,
    val accounts: List<AccountItem>,
    val totals: Map<String, BigDecimal>,
    /** Sum of the accounts' unread bookings, for the panel's counter badge. */
    val newCount: Int = 0,
)

/** The account type of a loan, whose balance is negative by nature (see [isWarningBalance]). */
const val LOAN_TYPE = "loan"

/** The account types FinDesk offers, in the order their groups are shown. */
val ACCOUNT_TYPES = listOf("checking", "savings", "credit_card", LOAN_TYPE, "depot")

/** Type groups are ordered by these first; any other type follows, alphabetically. */
private val FIXED_TYPE_ORDER = ACCOUNT_TYPES

/**
 * Puts every account into its custom group when [AccountItem.groupId] names an existing group, and into its
 * account-type group otherwise (an unknown group id falls back to the type). Type groups with no accounts are left
 * out, custom groups are always shown so an empty one can receive accounts. Accounts inside a group keep the input
 * order, which is the repository order.
 */
fun group(accounts: List<AccountItem>, groups: List<AccountGroupItem>, primary: String): List<AccountGroupView> {
    val known = groups.associateBy { it.id }
    val customAccounts = LinkedHashMap<Int, MutableList<AccountItem>>()
    groups.forEach { customAccounts[it.id] = mutableListOf() }
    val typeAccounts = LinkedHashMap<String, MutableList<AccountItem>>()
    accounts.forEach { account ->
        val groupId = account.groupId
        if (groupId != null && known.containsKey(groupId)) {
            customAccounts.getValue(groupId).add(account)
        } else {
            typeAccounts.getOrPut(account.accountType) { mutableListOf() }.add(account)
        }
    }

    val typeKeys = FIXED_TYPE_ORDER + typeAccounts.keys.filter { it !in FIXED_TYPE_ORDER }.sorted()
    val views = mutableListOf<AccountGroupView>()
    typeKeys.forEach { type ->
        val list = typeAccounts[type].orEmpty()
        if (list.isNotEmpty()) {
            views += AccountGroupView(GroupKey.Type(type), name = null, accounts = list, totals = totals(list, primary), newCount = list.sumOf { it.newCount })
        }
    }
    groups.forEach { custom ->
        val list = customAccounts.getValue(custom.id)
        views += AccountGroupView(GroupKey.Custom(custom.id), name = custom.name, accounts = list, totals = totals(list, primary), newCount = list.sumOf { it.newCount })
    }
    return views
}

/** Balances per currency, primary first; different currencies are never added up. */
fun grandTotal(accounts: List<AccountItem>, primary: String): Map<String, BigDecimal> = totals(accounts, primary)

/**
 * Whether a total of [accounts]' balances in [currency] warns red: the total is below zero **and** the same total
 * without the loan accounts is also below zero. A loan is negative by nature, so red there is noise and a total
 * negative only because of loans (e.g. "All accounts" over a checking account in the black plus loans) stays in the
 * plain text colour; red is kept for overdrawn checking accounts and credit cards that are due. One account reduces
 * to the one-account rule: red unless it is a loan's.
 */
fun isWarningBalance(accounts: List<AccountItem>, currency: String): Boolean {
    val inCurrency = accounts.filter { it.currency == currency }
    if (inCurrency.sumOf { it.balance }.signum() >= 0) return false
    val withoutLoans = inCurrency.sumOf { if (it.accountType == LOAN_TYPE) BigDecimal.ZERO else it.balance }
    return withoutLoans.signum() < 0
}

/** How an account balance or a total of balances is coloured (see `balanceToneColor` in the theme). */
enum class BalanceTone { POSITIVE, NEGATIVE, LOAN, ZERO }

/** Tone of [accounts]' total in [currency]; the red/grey split follows [isWarningBalance]. */
fun balanceTone(accounts: List<AccountItem>, currency: String): BalanceTone {
    val total = accounts.filter { it.currency == currency }.sumOf { it.balance }
    return when {
        total.signum() > 0 -> BalanceTone.POSITIVE
        total.signum() == 0 -> BalanceTone.ZERO
        isWarningBalance(accounts, currency) -> BalanceTone.NEGATIVE
        else -> BalanceTone.LOAN
    }
}

/** Tone of one balance that is not a list of accounts (a single account's running balance, a dashboard figure). */
fun balanceTone(value: BigDecimal, isLoan: Boolean): BalanceTone = when {
    value.signum() > 0 -> BalanceTone.POSITIVE
    value.signum() == 0 -> BalanceTone.ZERO
    isLoan -> BalanceTone.LOAN
    else -> BalanceTone.NEGATIVE
}

private fun totals(accounts: List<AccountItem>, primary: String): Map<String, BigDecimal> = Currencies.totals(accounts, primary, { it.currency }, { it.balance })

/** The custom group [key] stands for, or null for a type group (clearing the group sends an account back to its type). */
internal fun groupIdFor(key: GroupKey): Int? = (key as? GroupKey.Custom)?.groupId

/**
 * The header under [pointer] that [account] may be dropped on, or null when the drop would do nothing. [headers]
 * holds every group header's bounds in one shared coordinate space and [pointer] is in that same space.
 *
 * A custom group accepts every account. A type group accepts an account only when it is the account's own type and
 * the account currently sits in a custom group ([currentKey] is one), which moves it back to its type group. Dropping
 * on the account's current group, on a type group of another type or on no header at all is not a target.
 */
internal fun dropTarget(pointer: Offset, headers: Map<GroupKey, Rect>, account: AccountItem, currentKey: GroupKey): GroupKey? {
    val hit = headers.entries.firstOrNull { it.value.contains(pointer) }?.key ?: return null
    if (hit == currentKey) return null
    return when (hit) {
        is GroupKey.Custom -> hit
        is GroupKey.Type -> hit.takeIf { currentKey is GroupKey.Custom && it.type == account.accountType }
    }
}
