package de.findesk.util

import de.findesk.model.TransactionItem
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate

/** Display-only bank text cleaning: the stored raw strings never change, lists just render the readable part. */
class BankTextTest {
    @Test
    fun `a purpose with EREF MREF CRED and SVWZ shows only the SVWZ text`() {
        val raw = "EREF+REF0001 MREF+M-77 CRED+DE00ZZZ00000000001 SVWZ+Rechnung 4711"
        assertEquals("Rechnung 4711", BankText.displayPurpose(raw))
    }

    @Test
    fun `tags glued to text without spaces are still separated`() {
        assertEquals("Miete Oktober", BankText.displayPurpose("EREF+X1SVWZ+Miete Oktober"))
    }

    @Test
    fun `SVWZ text ends at the next tag`() {
        assertEquals("Taschengeld", BankText.displayPurpose("SVWZ+Taschengeld PURP+RINP"))
    }

    @Test
    fun `a purpose of only tags without SVWZ shows the untagged remainder or nothing`() {
        // Nothing before the first tag and nothing after the last: no readable text at all
        assertNull(BankText.displayPurpose("EREF+B00520201172143239 MREF+P0202011731458239"))
        // Text left of the tags is outside the tagged fields
        assertEquals("Kartenzahlung", BankText.displayPurpose("Kartenzahlung EREF+REF0001"))
    }

    @Test
    fun `lower-case tags are recognised too`() {
        assertEquals("abc", BankText.displayPurpose("svwz+abc"))
    }

    @Test
    fun `a plain purpose without tags is returned unchanged except collapsed whitespace`() {
        assertEquals("Warenkauf 12.03.2026", BankText.displayPurpose("Warenkauf  12.03.2026"))
    }

    @Test
    fun `a plus sign that opens no tag is left alone`() {
        assertEquals("A+B Bau", BankText.displayPurpose("A+B Bau"))
    }

    @Test
    fun `the first non-blank part of a padded partner is shown`() {
        assertEquals(
            "BEISPIEL KARTE GOLD",
            BankText.displayPartner("BEISPIEL KARTE GOLD      1234 56XX XXXX 7890      Abrechnung 10/26"),
        )
    }

    @Test
    fun `a double space in a partner collapses to one`() {
        assertEquals("BEISPIEL GMBH", BankText.displayPartner("BEISPIEL  GMBH"))
    }

    @Test
    fun `null and blank resolve to null`() {
        assertNull(BankText.displayPartner(null))
        assertNull(BankText.displayPartner("   "))
        assertNull(BankText.displayPurpose(null))
        assertNull(BankText.displayPurpose("\t  "))
    }

    @Test
    fun `the row description prefers the partner, then the purpose, then a placeholder`() {
        val withPartner = BankText.displayDescription(txn(partner = "Erika Mustermann", purpose = "EREF+R1 SVWZ+Rechnung 4711"))
        val withoutPartner = BankText.displayDescription(txn(partner = null, purpose = "EREF+R1 SVWZ+Rechnung 4711"))
        val withNothing = BankText.displayDescription(txn(partner = null, purpose = null))
        assertEquals("Erika Mustermann", withPartner)
        assertEquals("Rechnung 4711", withoutPartner)
        assertEquals("—", withNothing)
    }

    private fun txn(partner: String?, purpose: String?) = TransactionItem(
        id = 1,
        accountId = 1,
        accountIban = "DE89370400440532013000",
        date = LocalDate.of(2026, 10, 24),
        amount = BigDecimal("-42.50"),
        currency = "EUR",
        partnerName = partner,
        purpose = purpose,
        categoryId = null,
        categoryName = null,
        categoryColor = null,
        isTaxRelevant = false,
        documentCount = 0,
    )
}
