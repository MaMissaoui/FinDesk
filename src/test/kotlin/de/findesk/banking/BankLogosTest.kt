package de.findesk.banking

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The logo/monogram rules from the shared bank-logos spec, one rule at a time, in their documented order. */
class BankLogosTest {

    @Test
    fun `brands with their own look never get another bank's logo`() {
        listOf("comdirect bank", "Consorsbank", "Targobank", "Sparda-Bank", "PSD Bank", "APOBANK", "Apotheker Bank").forEach { name ->
            assertNull(BankLogos.bankLogo(name, "37050198", "COLSDE33XXX"), "$name must not inherit a logo")
        }
        // Before every other rule, even when BIC and BLZ clearly name a logo bank
        assertNull(BankLogos.bankLogo("comdirect bank", "12030000", "BYLADEM1001"))
        assertNull(BankLogos.bankLogo("Sparda-Bank", null, "GENODEFFXXX"))
        assertNull(BankLogos.bankLogo("Targobank", null, "DEUTDEFFXXX"))
    }

    @Test
    fun `matches HypoVereinsbank by name and BIC`() {
        assertEquals(BankLogo.HYPOVEREINSBANK, BankLogos.bankLogo("UniCredit Bank - HypoVereinsbank AG", "70020270", "HYVEDEMMXXX"))
        assertEquals(BankLogo.HYPOVEREINSBANK, BankLogos.bankLogo("HVB Mastercard", null, null))
        assertEquals(BankLogo.HYPOVEREINSBANK, BankLogos.bankLogo("Some Bank", null, "HYVEDEMM455"))
        assertEquals(BankLogo.HYPOVEREINSBANK, BankLogos.bankLogo("My credit card", "", "hyvedemmmxxx"))
    }

    @Test
    fun `matches DKB by name, BLZ and BIC`() {
        assertEquals(BankLogo.DKB, BankLogos.bankLogo("DKB Privat", "12030000", "BYLADEM1001"))
        assertEquals(BankLogo.DKB, BankLogos.bankLogo("Deutsche Kreditbank", null, null))
        assertEquals(BankLogo.DKB, BankLogos.bankLogo("Some bank", "12030000", null))
        assertEquals(BankLogo.DKB, BankLogos.bankLogo("Some bank", null, "BYLADEM1001XXX"))
    }

    @Test
    fun `matches Postbank by name and BIC`() {
        assertEquals(BankLogo.POSTBANK, BankLogos.bankLogo("Postbank", "37050198", null))
        assertEquals(BankLogo.POSTBANK, BankLogos.bankLogo("Some bank", null, "PBNKDEFFXXX"))
    }

    @Test
    fun `matches Deutsche Bank by name and BIC`() {
        assertEquals(BankLogo.DEUTSCHE_BANK, BankLogos.bankLogo("Deutsche Bank", null, null))
        assertEquals(BankLogo.DEUTSCHE_BANK, BankLogos.bankLogo("Some bank", null, "DEUTDEFFXXX"))
    }

    @Test
    fun `matches Commerzbank by name and BIC`() {
        assertEquals(BankLogo.COMMERZBANK, BankLogos.bankLogo("Commerzbank", null, null))
        assertEquals(BankLogo.COMMERZBANK, BankLogos.bankLogo("Some bank", null, "COBADEFFXXX"))
    }

    @Test
    fun `matches Santander by name and BIC`() {
        assertEquals(BankLogo.SANTANDER, BankLogos.bankLogo("Santander Consumer Bank", null, null))
        assertEquals(BankLogo.SANTANDER, BankLogos.bankLogo("Some bank", null, "SCFBDEFFXXX"))
    }

    @Test
    fun `matches N26 by name and BIC`() {
        assertEquals(BankLogo.N26, BankLogos.bankLogo("N26 Bank", null, null))
        assertEquals(BankLogo.N26, BankLogos.bankLogo("Some bank", null, "NTSBDEB1XXX"))
    }

    @Test
    fun `matches Sparkasse by name or by the fourth BLZ digit`() {
        assertEquals(BankLogo.SPARKASSE, BankLogos.bankLogo("Sparkasse KölnBonn", "10000000", null))
        assertEquals(BankLogo.SPARKASSE, BankLogos.bankLogo("Some bank", "37050198", null))
        assertNull(BankLogos.bankLogo("Some bank", "37040198", null))
        // Too short to carry a fourth digit
        assertNull(BankLogos.bankLogo("Some bank", "370", null))
    }

    @Test
    fun `matches Volksbank and Raiffeisenbank by name and GENO BIC`() {
        assertEquals(BankLogo.VOLKSBANK, BankLogos.bankLogo("Volksbank Mittelhessen", "51390000", "VBMHDE5FXXX"))
        listOf("Raiffeisenbank", "VR Bank", "VR-Bank", "VRBank").forEach { name ->
            assertEquals(BankLogo.VOLKSBANK, BankLogos.bankLogo(name, null, null), name)
        }
        assertEquals(BankLogo.VOLKSBANK, BankLogos.bankLogo("Some bank", null, "GENODEFFXXX"))
    }

    @Test
    fun `leaves banks without a logo to the monogram`() {
        assertNull(BankLogos.bankLogo("Bank Norwegian", null, null))
        // A name match of an earlier rule beats a Sparkasse BLZ; no rule matches at all here
        assertEquals(BankLogo.POSTBANK, BankLogos.bankLogo("Postbank", "37050198", null))
        // BYLADEMMXXX is not BYLADEM1001 and has no other match
        assertNull(BankLogos.bankLogo("BayernLB", null, "BYLADEMMXXX"))
        assertNull(BankLogos.bankLogo(null, null, null))
        assertNull(BankLogos.bankLogo("", "", ""))
    }

    @Test
    fun `matches case-insensitively and ignores surrounding spaces`() {
        assertEquals(BankLogo.DKB, BankLogos.bankLogo("  dkb  ", " 12030000 ", " BYLADEM1001 "))
        assertEquals(BankLogo.VOLKSBANK, BankLogos.bankLogo("  volksbank mittelhessen ", null, " geno... "))
        assertEquals(BankLogo.SPARKASSE, BankLogos.bankLogo("  SPARKASSE  ", null, null))
    }

    @Test
    fun `every logo id is one of the nine bundled files`() {
        val ids = BankLogo.entries.map { it.id }
        assertEquals(9, ids.size)
        assertEquals(ids.size, ids.toSet().size)
        assertEquals(
            setOf("hypovereinsbank", "dkb", "postbank", "deutsche-bank", "commerzbank", "santander", "n26", "sparkasse", "volksbank"),
            ids.toSet(),
        )
        BankLogo.entries.forEach { logo ->
            val resource = "banks/${logo.id}.svg"
            assertTrue(BankLogos::class.java.classLoader.getResource(resource) != null, "missing $resource")
        }
    }

    @Test
    fun `uses an all-caps acronym when the name has one`() {
        assertEquals("HVB", BankLogos.monogram("HVB Mastercard").letters)
        assertEquals("DKB", BankLogos.monogram("DKB Privat").letters)
        assertEquals("ING", BankLogos.monogram("ING DiBa").letters)
        assertEquals("AG", BankLogos.monogram("Muster AG").letters)
    }

    @Test
    fun `falls back to the initials of the first two words that start with a letter`() {
        assertEquals("BN", BankLogos.monogram("Bank Norwegian").letters)
        assertEquals("CB", BankLogos.monogram("Cards Bank").letters)
        // The dash is not a word, so the first two letter words still decide
        assertEquals("NB", BankLogos.monogram("Nord - Bank").letters)
        assertEquals("BM", BankLogos.monogram("  bank   muster  ").letters)
    }

    @Test
    fun `a single word contributes its first two letters`() {
        assertEquals("NO", BankLogos.monogram("Noris").letters)
        assertEquals("BA", BankLogos.monogram("bank").letters)
        assertEquals("A", BankLogos.monogram("A").letters)
    }

    @Test
    fun `a blank name produces an empty monogram`() {
        assertEquals("", BankLogos.monogram("").letters)
        assertEquals("", BankLogos.monogram("   ").letters)
    }

    @Test
    fun `the monogram colour is stable, in range and ignores case`() {
        val first = BankLogos.monogram("Bank Norwegian")
        assertEquals(first, BankLogos.monogram("Bank Norwegian"))
        assertEquals(first.colorIndex, BankLogos.monogram("  bank norwegian ").colorIndex)
        assertTrue(first.colorIndex in 0..7, "colour index ${first.colorIndex}")
        // A tiny fixed sample, so a switch from the byte sum to a randomized hash would fail
        val expected = "Bank Norwegian".lowercase().toByteArray(Charsets.UTF_8).sumOf { it.toInt() and 0xFF } % 8
        assertEquals(expected, first.colorIndex)
    }
}
