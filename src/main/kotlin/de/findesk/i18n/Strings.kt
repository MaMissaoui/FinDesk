package de.findesk.i18n

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import de.findesk.document.RecurringDetector
import de.findesk.model.AccountItem
import de.findesk.model.Direction
import de.findesk.model.PaymentAction
import de.findesk.model.PaymentOrderStatus
import de.findesk.viewmodel.DocumentTab
import de.findesk.viewmodel.Period
import java.time.YearMonth
import java.time.format.DateTimeFormatter

class Strings(
    val language: AppLanguage,
    @Suppress("MemberVisibilityCanBePrivate") val values: Map<StringKey, String>,
) {
    operator fun get(key: StringKey): String = values[key] ?: Translations.en[key] ?: key.name

    /**
     * Formats [key] with [args]. A value picks a word by a count with `{2:account|accounts}`: the first form when
     * argument 2 is singular under the language's rule (see [AppLanguage.isSingular]), so "1 account" and
     * "3 accounts" come from one string instead of "account(s)".
     */
    fun format(key: StringKey, vararg args: Any?): String {
        val text = PLURAL.replace(this[key]) { m ->
            val count = (args.getOrNull(m.groupValues[1].toInt() - 1) as? Number)?.toLong()
            if (count != null && language.isSingular(count)) m.groupValues[2] else m.groupValues[3]
        }
        return String.format(language.locale, text, *args)
    }

    fun accountType(type: String): String = when (type) {
        "checking" -> this[StringKey.TypeChecking]
        "savings" -> this[StringKey.TypeSavings]
        "credit_card" -> this[StringKey.TypeCreditCard]
        "loan" -> this[StringKey.TypeLoan]
        "depot" -> this[StringKey.TypeDepot]
        else -> type
    }

    /**
     * The user's own account name, or the localized type plus "…<last 4>" distinguishing it, when none is set.
     * The 4 come from the IBAN (whitespace removed), else from the account number, else the type stands alone;
     * several unnamed accounts of one type therefore never read the same.
     */
    fun accountName(account: AccountItem): String = account.name ?: run {
        val type = accountType(account.accountType)
        val iban = account.iban.filterNot { it.isWhitespace() }
        val number = account.accountNumber?.filterNot { it.isWhitespace() }
        when {
            iban.isNotBlank() -> "$type …${iban.takeLast(4)}"
            !number.isNullOrBlank() -> "$type …${number.takeLast(4)}"
            else -> type
        }
    }

    fun period(period: Period): String = when (period) {
        Period.THIS_MONTH -> this[StringKey.PeriodThisMonth]
        Period.LAST_MONTH -> this[StringKey.PeriodLastMonth]
        Period.LAST_3_MONTHS -> this[StringKey.PeriodLast3Months]
        Period.THIS_YEAR -> this[StringKey.PeriodThisYear]
        Period.LAST_YEAR -> this[StringKey.PeriodLastYear]
        Period.ALL -> this[StringKey.PeriodAllTime]
    }

    /**
     * "September 2026": the full standalone month name (its natural form, lowercase in French) plus the year,
     * in the [Strings]' language, capitalised like everywhere else in the UI.
     */
    fun month(month: YearMonth): String {
        val locale = language.locale
        val text = month.atDay(1).format(DateTimeFormatter.ofPattern("LLLL yyyy", locale))
        return text.replaceFirstChar { if (it.isLowerCase()) it.titlecase(locale) else it.toString() }
    }

    fun direction(direction: Direction): String = when (direction) {
        Direction.ALL -> this[StringKey.DirectionAll]
        Direction.INCOME -> this[StringKey.DirectionIncome]
        Direction.EXPENSES -> this[StringKey.DirectionExpenses]
    }

    fun cadence(cadence: RecurringDetector.Cadence): String = when (cadence) {
        RecurringDetector.Cadence.WEEKLY -> this[StringKey.CadenceWeekly]
        RecurringDetector.Cadence.MONTHLY -> this[StringKey.CadenceMonthly]
        RecurringDetector.Cadence.QUARTERLY -> this[StringKey.CadenceQuarterly]
        RecurringDetector.Cadence.YEARLY -> this[StringKey.CadenceYearly]
    }

    fun documentTab(tab: DocumentTab): String = when (tab) {
        DocumentTab.ALL -> this[StringKey.TabAllDocuments]
        DocumentTab.UNLINKED -> this[StringKey.TabUnlinked]
        DocumentTab.LINKED -> this[StringKey.TabLinked]
    }

    fun paymentStatus(status: String): String = when (status) {
        PaymentOrderStatus.PENDING -> this[StringKey.PaymentStatusPending]
        PaymentOrderStatus.ACCEPTED -> this[StringKey.PaymentStatusAccepted]
        PaymentOrderStatus.REJECTED -> this[StringKey.PaymentStatusRejected]
        PaymentOrderStatus.TAN_REQUIRED -> this[StringKey.PaymentStatusTanRequired]
        PaymentOrderStatus.CANCELLED -> this[StringKey.PaymentStatusCancelled]
        PaymentOrderStatus.UNKNOWN -> this[StringKey.PaymentStatusUnknown]
        else -> status
    }

    fun paymentAction(action: String): String = when (action) {
        PaymentAction.TRANSFER -> this[StringKey.PaymentActionTransfer]
        PaymentAction.INSTANT_TRANSFER -> this[StringKey.PaymentActionInstant]
        PaymentAction.SCHEDULED_TRANSFER -> this[StringKey.PaymentActionScheduled]
        PaymentAction.STANDING_ORDER_CREATE -> this[StringKey.PaymentActionStandingOrder]
        PaymentAction.STANDING_ORDER_DELETE -> this[StringKey.PaymentActionStandingOrderDelete]
        PaymentAction.STANDING_ORDER_EDIT -> this[StringKey.PaymentActionStandingOrderEdit]
        PaymentAction.SCHEDULED_TRANSFER_DELETE -> this[StringKey.PaymentActionScheduledDelete]
        else -> action
    }

    internal companion object {
        /** `{n:singular|plural}`, where n is the 1-based index of the count among the format arguments. */
        val PLURAL = Regex("\\{(\\d+):([^{}|]*)\\|([^{}|]*)\\}")
    }
}

val LocalStrings = staticCompositionLocalOf { Translations.of(AppLanguage.EN) }

@Composable
fun strings(): Strings = LocalStrings.current
