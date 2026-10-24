package de.findesk.model

import de.findesk.i18n.StringKey

/**
 * A sensible starting set of categories for a new database, in display order.
 *
 * The names come from the string keys, so the current UI language decides what is stored.
 * The colours cycle through the same palette as the UI's `ui/theme/CategoryColors`; the hex strings are copied here
 * because the model package must not depend on Compose.
 */
object DefaultCategories {
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
    )

    /** Name key, colour and transfer flag, `(StringKey, color hex, isTransfer)`, colours cycling through [colors]. */
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
    ).mapIndexed { index, key -> Triple(key, colors[index % colors.size], key == StringKey.DefaultCategoryOwnTransfers) }
}
