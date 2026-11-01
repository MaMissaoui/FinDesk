package de.findesk.banking

import de.findesk.util.Iban
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
    fun `masks spaced and lower-case IBANs`() {
        assertEquals("IBAN ${BankingRedactor.MASK} Miete", BankingRedactor.redact("IBAN DE89 3704 0044 0532 0130 00 Miete", emptyList()))
        assertEquals(BankingRedactor.MASK, BankingRedactor.redact("de89370400440532013000", emptyList()))
        assertEquals(BankingRedactor.MASK, BankingRedactor.redact("de89 3704 0044 0532 0130 00", emptyList()))
    }

    @Test
    fun `masks a spaced IBAN whose regex match swallowed the next word`() {
        val iban = "ES91 2100 0418 4502 0005 1332"
        assertTrue(Iban.isValid(iban))

        assertEquals("${BankingRedactor.MASK} EUR", BankingRedactor.redact("$iban EUR", emptyList()))
    }

    @Test
    fun `masks a spaced IBAN whose regex match swallowed the next bic`() {
        val iban = "AT61 1904 3002 3457 3201"
        assertTrue(Iban.isValid(iban))

        assertEquals("${BankingRedactor.MASK} BIC BKAUATWW", BankingRedactor.redact("$iban BIC BKAUATWW", emptyList()))
    }

    @Test
    fun `masks a spaced IBAN whose regex match swallowed the next number`() {
        val iban = "BE68 5390 0754 7034"
        assertTrue(Iban.isValid(iban))

        assertEquals("${BankingRedactor.MASK} 2026", BankingRedactor.redact("$iban 2026", emptyList()))
    }

    @Test
    fun `masks a spaced IBAN inside a sentence and keeps the surrounding words`() {
        val iban = "PL61 1090 1014 0000 0712 1981 2874"
        assertTrue(Iban.isValid(iban))

        assertEquals("Konto ${BankingRedactor.MASK} ok", BankingRedactor.redact("Konto $iban ok", emptyList()))
    }

    @Test
    fun `does not mask a spaced token without a valid IBAN prefix`() {
        val token = "DE00 1234 5678 9012 3456 78 X"
        assertFalse(Iban.isValid("DE00 1234 5678 9012 3456 78"))

        assertEquals(token, BankingRedactor.redact(token, emptyList()))
    }

    @Test
    fun `keeps a typical trace line unchanged`() {
        val line = "HNHBK:1:3+000000000123+300+DIA4711ABCD12345678+2'HIRMG:2:2+3060::Bitte beachten Sie die enthaltenen Warnungen.'"

        assertEquals(line, BankingRedactor.redact(line, emptyList()))
    }

    @Test
    fun `does not mask an IBAN-looking token that fails the check digits`() {
        val token = "DE00 1234 5678 9012 3456 78"

        assertFalse(Iban.isValid(token))

        assertEquals(token, BankingRedactor.redact(token, emptyList()))
    }

    @Test
    fun `maskAccountNumbers masks IBANs keeping the last 4 characters`() {
        assertEquals("IBAN …3000 ungültig", BankingRedactor.maskAccountNumbers("IBAN DE89370400440532013000 ungültig"))
        assertEquals("IBAN …3000 ungültig", BankingRedactor.maskAccountNumbers("IBAN DE89 3704 0044 0532 0130 00 ungültig"))
        assertEquals("…3000", BankingRedactor.maskAccountNumbers("de89370400440532013000"))
    }

    @Test
    fun `maskAccountNumbers masks a spaced IBAN whose regex match swallowed the next word`() {
        assertEquals("Konto …1332 EUR ok", BankingRedactor.maskAccountNumbers("Konto ES91 2100 0418 4502 0005 1332 EUR ok"))
    }

    @Test
    fun `maskAccountNumbers does not turn a kept IBAN tail into a return code`() {
        assertEquals(emptyList<String>(), BankingRedactor.returnCodes(BankingRedactor.maskAccountNumbers("Konto ES91 2100 0418 4502 0005 1332 EUR ok")))
    }

    @Test
    fun `maskAccountNumbers keeps the last 4 of valid spaced and lower-case IBANs`() {
        assertEquals("…3000", BankingRedactor.maskAccountNumbers("DE89 3704 0044 0532 0130 00"))
        assertEquals("…3000", BankingRedactor.maskAccountNumbers("de89 3704 0044 0532 0130 00"))
    }

    @Test
    fun `maskAccountNumbers fails closed for a spaced IBAN with a wrong check digit`() {
        assertFalse(Iban.isValid("DE00 3704 0044 0532 0130 00"))

        assertEquals("…3000", BankingRedactor.maskAccountNumbers("DE00 3704 0044 0532 0130 00"))
    }

    @Test
    fun `redact keeps its trace behaviour for a spaced IBAN whose match swallowed the next word`() {
        assertEquals("Konto ${BankingRedactor.MASK} EUR ok", BankingRedactor.redact("Konto ES91 2100 0418 4502 0005 1332 EUR ok", emptyList()))
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

    @Test
    fun `maskAccountNumbers masks labelled digit groups keeping the last 4 digits`() {
        assertEquals("Konto …5432", BankingRedactor.maskAccountNumbers("Konto 9876 5432"))
        assertEquals("Kto.-Nr. …7890", BankingRedactor.maskAccountNumbers("Kto.-Nr. 1234 5678 90"))
        assertEquals("Empfängerkonto …7890", BankingRedactor.maskAccountNumbers("Empfängerkonto 1234 567890"))
        assertEquals("Girokonto …5432 gesperrt", BankingRedactor.maskAccountNumbers("Girokonto 9876 5432 gesperrt"))
        assertEquals("Kontonummer …7890", BankingRedactor.maskAccountNumbers("Kontonummer 1234567890"))
        assertEquals("Account no. …5432", BankingRedactor.maskAccountNumbers("Account no. 9876 5432"))
        assertEquals("Kto …5678.", BankingRedactor.maskAccountNumbers("Kto 1234 5678."))
    }

    @Test
    fun `maskAccountNumbers leaves unlabelled digit groups alone`() {
        assertEquals("Kontostand 1 234,56 EUR", BankingRedactor.maskAccountNumbers("Kontostand 1 234,56 EUR"))
        assertEquals("Konto 123 456,78 EUR", BankingRedactor.maskAccountNumbers("Konto 123 456,78 EUR"))
        assertEquals("Konto: 12 34", BankingRedactor.maskAccountNumbers("Konto: 12 34"))
        assertEquals("im Oktober 1234 5678", BankingRedactor.maskAccountNumbers("im Oktober 1234 5678"))
        assertEquals("24.10.2026", BankingRedactor.maskAccountNumbers("24.10.2026"))
        assertEquals("80331 München", BankingRedactor.maskAccountNumbers("80331 München"))
        assertEquals("9050 3076 Auftrag abgelehnt", BankingRedactor.maskAccountNumbers("9050 3076 Auftrag abgelehnt"))
    }

    @Test
    fun `returnCodes still finds codes next to each other after masking`() {
        assertEquals(listOf("9050", "3076"), BankingRedactor.returnCodes(BankingRedactor.maskAccountNumbers("9050 3076 Auftrag abgelehnt")))
        assertEquals(listOf("9050"), BankingRedactor.returnCodes(BankingRedactor.maskAccountNumbers("Konto 9876 5432: 9050 Auftrag abgelehnt")))
    }

    @Test
    fun `maskAccountNumbers is idempotent for labelled groups and ibans`() {
        for (text in listOf("Konto 9876 5432", "IBAN DE89 3704 0044 0532 0130 00")) {
            val once = BankingRedactor.maskAccountNumbers(text)
            assertEquals(once, BankingRedactor.maskAccountNumbers(once))
        }
    }
}
