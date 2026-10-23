package de.findesk.banking

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class BankingRedactorTest {
    @Test
    fun `masks known secrets and iban-like tokens`() {
        val line = "user=1234567890 iban=DE89370400440532013000 amount=12,34 rewe"

        val redacted = BankingRedactor.redact(line, listOf("1234567890"))

        assertFalse(redacted.contains("1234567890"))
        assertFalse(redacted.contains("DE89370400440532013000"))
        // Non-secret content survives
        assertTrue(redacted.contains("amount=12,34 rewe"))
    }

    @Test
    fun `does not mask short values`() {
        assertEquals("PIN 123 bleibt", BankingRedactor.redact("PIN 123 bleibt", listOf("123")))
    }

    @Test
    fun `masks the longest overlapping secret without leaving fragments`() {
        assertEquals(BankingRedactor.MASK, BankingRedactor.redact("abcdef", listOf("abc", "abcdef")))
    }

    @Test
    fun `blank secrets are ignored`() {
        assertEquals("text", BankingRedactor.redact("text", listOf("", "  ")))
    }

    @Test
    fun `masks secrets in their fints-escaped form`() {
        val line = "HNSHA:5:2+998+1234567+++ab?+cd?:e?'f???@g'"

        val redacted = BankingRedactor.redact(line, listOf("ab+cd:e'f?@g"))

        assertEquals("HNSHA:5:2+998+1234567+++X'", redacted)
    }

    @Test
    fun `escapes every fints syntax character once`() {
        assertEquals("a?+b?:c?'d??e?@f", BankingRedactor.fintsEscaped("a+b:c'd?e@f"))
        assertEquals("plain", BankingRedactor.fintsEscaped("plain"))
    }

    @Test
    fun `maskAccountNumbers masks IBANs keeping the last 4 characters`() {
        assertEquals("IBAN …3000 ungültig", BankingRedactor.maskAccountNumbers("IBAN DE89370400440532013000 ungültig"))
        assertEquals("IBAN …3000 ungültig", BankingRedactor.maskAccountNumbers("IBAN DE89 3704 0044 0532 0130 00 ungültig"))
        assertEquals("…3000", BankingRedactor.maskAccountNumbers("de89370400440532013000"))
    }

    @Test
    fun `maskAccountNumbers masks long digit runs keeping the last 4 digits`() {
        assertEquals("Konto …7890 gesperrt", BankingRedactor.maskAccountNumbers("Konto 1234567890 gesperrt"))
    }

    @Test
    fun `maskAccountNumbers leaves return codes dates amounts and short numbers alone`() {
        assertEquals("9050 Auftrag abgelehnt am 23.10.2026 über 12,34 EUR", BankingRedactor.maskAccountNumbers("9050 Auftrag abgelehnt am 23.10.2026 über 12,34 EUR"))
        assertEquals("Betrag 10000 EUR", BankingRedactor.maskAccountNumbers("Betrag 10000 EUR"))
        assertEquals("Jahr 2026", BankingRedactor.maskAccountNumbers("Jahr 2026"))
    }
}
