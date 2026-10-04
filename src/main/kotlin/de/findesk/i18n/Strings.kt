package de.findesk.i18n

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import de.findesk.document.RecurringDetector
import de.findesk.model.Direction
import de.findesk.viewmodel.DocumentTab
import de.findesk.viewmodel.Period

class Strings(
    val language: AppLanguage,
    @Suppress("MemberVisibilityCanBePrivate") val values: Map<StringKey, String>,
) {
    operator fun get(key: StringKey): String = values[key] ?: Translations.en[key] ?: key.name

    fun format(key: StringKey, vararg args: Any?): String = String.format(language.locale, this[key], *args)

    fun accountType(type: String): String = when (type) {
        "checking" -> this[StringKey.TypeChecking]
        "savings" -> this[StringKey.TypeSavings]
        "credit_card" -> this[StringKey.TypeCreditCard]
        "depot" -> this[StringKey.TypeDepot]
        else -> type
    }

    fun period(period: Period): String = when (period) {
        Period.THIS_MONTH -> this[StringKey.PeriodThisMonth]
        Period.LAST_MONTH -> this[StringKey.PeriodLastMonth]
        Period.LAST_3_MONTHS -> this[StringKey.PeriodLast3Months]
        Period.THIS_YEAR -> this[StringKey.PeriodThisYear]
        Period.LAST_YEAR -> this[StringKey.PeriodLastYear]
        Period.ALL -> this[StringKey.PeriodAllTime]
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
}

val LocalStrings = staticCompositionLocalOf { Translations.of(AppLanguage.EN) }

@Composable
fun strings(): Strings = LocalStrings.current
