package de.findesk.ui

import de.findesk.i18n.AppLanguage
import de.findesk.i18n.StringKey
import de.findesk.i18n.Translations
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** The review batch B strings that have to read exactly right in English. */
class ReviewBatchBTest {
    private val en = Translations.of(AppLanguage.EN)
    private val de = Translations.of(AppLanguage.DE)

    @Test
    fun `document filter counts append the number`() {
        assertEquals("Unlinked (3)", en.format(StringKey.TabWithCount, "Unlinked", 3))
    }

    @Test
    fun `the receipts card says what is missing or that everything is there`() {
        assertEquals("2 missing", en.format(StringKey.ReceiptsMissing, 2))
        assertEquals("All there", en[StringKey.ReceiptsComplete])
    }

    @Test
    fun `a suggestion names the keyword it matched`() {
        assertEquals("Matches “Apotheke”", en.format(StringKey.MatchesKeyword, "Apotheke"))
    }

    @Test
    fun `the collapsed keyword card counts words in singular and plural`() {
        assertEquals("1 keyword", en.format(StringKey.TaxKeywordsCount, 1))
        assertEquals("23 keywords", en.format(StringKey.TaxKeywordsCount, 23))
        assertEquals("23 Stichwörter", de.format(StringKey.TaxKeywordsCount, 23))
    }
}
