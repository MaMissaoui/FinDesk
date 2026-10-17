package de.findesk.i18n

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PluralTest {
    private val en = Translations.of(AppLanguage.EN)
    private val de = Translations.of(AppLanguage.DE)
    private val fr = Translations.of(AppLanguage.FR)

    @Test
    fun `counts pick singular or plural per language`() {
        assertEquals("1 booking", en.format(StringKey.BookingCount, 1))
        assertEquals("0 bookings", en.format(StringKey.BookingCount, 0))
        assertEquals("2 Buchungen", de.format(StringKey.BookingCount, 2))
        assertEquals("1 Umsatz · Eingang €0.00 · Ausgang €3.50", de.format(StringKey.TransactionsSummary, 1, "€0.00", "€3.50"))
        // French treats zero as singular
        assertEquals("0 écriture", fr.format(StringKey.BookingCount, 0))
        assertEquals("2 écritures", fr.format(StringKey.BookingCount, 2))
    }

    @Test
    fun `the count can be any argument and drive several words`() {
        assertEquals("Total €5.00 across 1 account", en.format(StringKey.AccountsTotal, "€5.00", 1))
        assertEquals("Created today · 1 transaction · 2 documents", en.format(StringKey.RestoreCreated, "today", 1, 2))
        assertEquals("1 booking is in another currency than this EUR account and will be skipped.", en.format(StringKey.WrongCurrencySkipped, 1, "EUR"))
        assertEquals("All amounts in EUR. 3 bookings in other currencies are not included.", en.format(StringKey.OtherCurrenciesExcluded, "EUR", 3))
    }

    @Test
    fun `every plural marker points at a numeric argument`() {
        val spec = Regex("%(\\d+\\$)?[-#+ 0,(]*\\d*(?:\\.\\d+)?([a-zA-Z%])")
        AppLanguage.entries.forEach { lang ->
            Translations.of(lang).values.forEach { (key, value) ->
                // The conversions in argument order; positional ones (%2$d) name their slot
                val conversions = mutableMapOf<Int, Char>()
                var next = 1
                spec.findAll(value).filter { it.groupValues[2] != "%" }.forEach { m ->
                    val index = m.groupValues[1].takeIf { it.isNotEmpty() }?.dropLast(1)?.toInt() ?: next++
                    conversions[index] = m.groupValues[2].single()
                }
                Strings.PLURAL.findAll(value).forEach { m ->
                    val index = m.groupValues[1].toInt()
                    assertEquals('d', conversions[index], "${lang.code} $key: {$index:…} needs a %d as argument $index")
                }
            }
        }
    }

    @Test
    fun `no string falls back to bracketed plurals`() {
        val bracketed = Regex("\\w\\((s|en|e|n)\\)|Konto/Konten|é\\(s\\)|e\\(s\\)")
        AppLanguage.entries.forEach { lang ->
            Translations.of(lang).values.forEach { (key, value) ->
                assertTrue(!bracketed.containsMatchIn(value), "${lang.code} $key: \"$value\" — use {n:singular|plural}")
            }
        }
    }
}
