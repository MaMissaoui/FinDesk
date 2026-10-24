package de.findesk.model

import de.findesk.i18n.StringKey

/**
 * A sensible starting set of categories for a new database, in display order.
 *
 * The names come from the string keys, so the current UI language decides what is stored.
 * The colours are the same 24 hex strings as the UI's `ui/theme/CategoryColors`, which is built from [colors]; they
 * are kept here because the model package must not depend on Compose.
 */
object DefaultCategories {
    /** The palette, one comment with the colour's name per line. */
    val colors: List<String> = listOf(
        "#FF5722", // Deep Orange
        "#4CAF50", // Green
        "#2196F3", // Blue
        "#FF9800", // Orange
        "#9C27B0", // Purple
        "#E91E63", // Pink
        "#00BCD4", // Cyan
        "#795548", // Brown
        "#607D8B", // Blue Grey
        "#CDDC39", // Lime
        "#3F51B5", // Indigo
        "#009688", // Teal
        "#FFC107", // Amber
        "#673AB7", // Deep Purple
        "#8BC34A", // Light Green
        "#03A9F4", // Light Blue
        "#AFB42B", // Dark Lime
        "#880E4F", // Dark Pink
        "#1A237E", // Dark Indigo
        "#004D40", // Dark Teal
        "#BF360C", // Burnt Orange
        "#33691E", // Dark Green
        "#F06292", // Light Pink
        "#A1887F", // Light Brown
    )

    /** Name key, colour and transfer flag, `(StringKey, color hex, isTransfer)`, entry `i` coloured `colors[i]`. */
    val entries: List<Triple<StringKey, String, Boolean>> = listOf(
        StringKey.DefaultCategoryGroceries,
        StringKey.DefaultCategoryDrugstore,
        StringKey.DefaultCategoryHousing,
        StringKey.DefaultCategoryEnergy,
        StringKey.DefaultCategoryPhoneInternetMedia,
        StringKey.DefaultCategoryInsurance,
        StringKey.DefaultCategoryLoans,
        StringKey.DefaultCategorySavings,
        StringKey.DefaultCategoryTransport,
        StringKey.DefaultCategoryHealth,
        StringKey.DefaultCategoryClothing,
        StringKey.DefaultCategoryOnlineShopping,
        StringKey.DefaultCategoryLeisure,
        StringKey.DefaultCategoryRestaurants,
        StringKey.DefaultCategoryTaxes,
        StringKey.DefaultCategoryCash,
        // NOT a transfer: a card statement payment is the only record of card spending while the card's own
        // bookings come from no import, so counting it would erase the card's spending altogether
        StringKey.DefaultCategoryCreditCard,
        StringKey.DefaultCategoryOwnTransfers,
        StringKey.DefaultCategorySalary,
        StringKey.DefaultCategoryChildBenefit,
        StringKey.DefaultCategoryInterestFees,
        StringKey.DefaultCategoryFamily,
        StringKey.DefaultCategoryChildrenSchool,
        StringKey.DefaultCategoryOtherIncome,
    ).mapIndexed { index, key -> Triple(key, colors[index], key == StringKey.DefaultCategoryOwnTransfers) }
}
