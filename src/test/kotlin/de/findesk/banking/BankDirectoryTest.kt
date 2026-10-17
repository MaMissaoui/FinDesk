package de.findesk.banking

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/** Exercises the offline bank directory that powers the add-bank dialog's suggestions. */
class BankDirectoryTest {

    @Test
    fun `looks up a bank by BLZ`() {
        val entry = BankDirectory.lookup("37050198")!!
        assertEquals("Sparkasse KölnBonn", entry.name)
        assertEquals("COLSDE33XXX", entry.bic)
        assertTrue(entry.hasOnlineBanking)
        assertNull(BankDirectory.lookup("00000000"))
    }

    @Test
    fun `finds a bank by BIC, ignoring case`() {
        val entry = BankDirectory.byBic("colsde33xxx")!!
        assertEquals("37050198", entry.blz)
        assertEquals("Sparkasse KölnBonn", entry.name)
        assertNull(BankDirectory.byBic("NOPE"))
    }

    @Test
    fun `extracts the BLZ from a German IBAN`() {
        assertEquals("37040044", BankDirectory.blzFromIban("DE89 3704 0044 0532 0130 00"))
        assertNull(BankDirectory.blzFromIban("FR1420041010050500013M02606"))
        assertNull(BankDirectory.blzFromIban("DE89"))
    }

    @Test
    fun `resolves a bank from a German IBAN`() {
        val entry = BankDirectory.byIban("DE89 3704 0044 0532 0130 00")!!
        assertEquals("37040044", entry.blz)
        assertEquals("Commerzbank", entry.name)
        assertEquals("COBADEFF370", entry.bic)
    }

    @Test
    fun `rejects an IBAN with a bad checksum`() {
        assertNull(BankDirectory.byIban("DE00370400440532013000"))
    }

    @Test
    fun `searches by bank name and prefers banks with online banking`() {
        val results = BankDirectory.search("Commerzbank")
        assertTrue(results.isNotEmpty())
        assertTrue(results.all { it.name.contains("Commerzbank", ignoreCase = true) })
        assertTrue(results.first().hasOnlineBanking)
    }

    @Test
    fun `searches by city`() {
        val results = BankDirectory.search("Köln")
        assertTrue(results.isNotEmpty())
        assertTrue(results.all { it.city.contains("Köln", ignoreCase = true) || it.name.contains("Köln", ignoreCase = true) })
    }

    @Test
    fun `searches by BLZ prefix`() {
        val results = BankDirectory.search("3705")
        assertTrue(results.isNotEmpty())
        assertTrue(results.all { it.blz.startsWith("3705") })
    }

    @Test
    fun `searches by BIC prefix`() {
        assertEquals("37040044", BankDirectory.search("COBADEFF370").first().blz)
    }

    @Test
    fun `resolves a pasted IBAN to a single suggestion`() {
        val results = BankDirectory.search("DE89 3704 0044 0532 0130 00")
        assertEquals(1, results.size)
        assertEquals("37040044", results.single().blz)
    }

    @Test
    fun `ignores empty and too-short queries`() {
        assertTrue(BankDirectory.search("").isEmpty())
        assertTrue(BankDirectory.search("  ").isEmpty())
        assertTrue(BankDirectory.search("12").isEmpty())
    }

    @Test
    fun `caps the number of suggestions`() {
        assertEquals(5, BankDirectory.search("Commerzbank", limit = 5).size)
        assertTrue(BankDirectory.search("Commerzbank").size <= 15)
    }
}
