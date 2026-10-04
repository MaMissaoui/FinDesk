package de.findesk.i18n

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Every translation key must be present in every language so a missing entry never falls back to
 * the English default at runtime.
 */
class TranslationsCompletenessTest {
    @Test
    fun `every key has a translation in every language`() {
        val expected = StringKey.entries.toSet()
        AppLanguage.entries.forEach { lang ->
            val actual = Translations.of(lang).values.keys
            assertEquals(expected, actual, "Missing keys in ${lang.code}: ${expected - actual}")
        }
    }
}
