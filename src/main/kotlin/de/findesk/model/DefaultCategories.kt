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

    /** Name key and colour, `(StringKey, color hex)`, colours cycling through [colors]. */
    val entries: List<Pair<StringKey, String>> = listOf(
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
        StringKey.DefaultCategoryCreditCard,
        StringKey.DefaultCategoryOwnTransfers,
        StringKey.DefaultCategorySalary,
        StringKey.DefaultCategoryChildBenefit,
        StringKey.DefaultCategoryInterestFees,
        StringKey.DefaultCategoryFamily,
        StringKey.DefaultCategoryChildrenSchool,
        StringKey.DefaultCategoryOtherIncome,
    ).mapIndexed { index, key -> key to colors[index % colors.size] }
}
