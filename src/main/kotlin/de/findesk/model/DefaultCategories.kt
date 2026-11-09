package de.findesk.model

import de.findesk.i18n.StringKey

/**
 * A sensible starting set of categories for a new database, in display order.
 *
 * The names come from the string keys, so the current UI language decides what is stored.
 *
 * The palette is ordered and validated: neighbouring colours differ in hue, and every colour sits in the lightness
 * band that keeps it legible on both paper and the dark surface. Green and red are deliberately absent: in FinDesk
 * green means money in and red means an alert, so neither can stand for a spending category.
 *
 * The colours are the same 24 hex strings as the UI's `ui/theme/CategoryColors`, which is built from [colors]; they
 * are kept here because the model package must not depend on Compose.
 */
object DefaultCategories {
    /** The palette, one comment with the colour's hue and tone per line. */
    val colors: List<String> = listOf(
        "#E76530", // orange 4
        "#4693F3", // blue 4
        "#C38400", // yellow 4
        "#D56A93", // magenta 4
        "#867DFF", // violet 4
        "#14AC77", // aqua 4
        "#CF4E12", // orange 2
        "#2F7DDB", // blue 2
        "#A77105", // yellow 2
        "#BD547E", // magenta 2
        "#7364EC", // violet 2
        "#029465", // aqua 2
        "#DB5923", // orange 3
        "#3B88E7", // blue 3
        "#B57A03", // yellow 3
        "#C95F88", // magenta 3
        "#7C6FF8", // violet 3
        "#00A06E", // aqua 3
        "#BE4403", // orange 1
        "#2170CD", // blue 1
        "#986603", // yellow 1
        "#AF4872", // magenta 1
        "#6857DE", // violet 1
        "#07865C", // aqua 1
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
