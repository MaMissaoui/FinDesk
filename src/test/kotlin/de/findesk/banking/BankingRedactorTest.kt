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
}
