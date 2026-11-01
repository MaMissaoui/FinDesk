package de.findesk.document

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.LocalDate

class ReceiptParserTest {
    private fun date(text: String) = ReceiptParser.parse(text).date

    @Test
    fun `two-digit years use the shared two-digit-year rule`() {
        assertEquals(LocalDate.of(1999, 3, 5), date("Datum 05.03.99"))
        assertEquals(LocalDate.of(2026, 3, 5), date("Datum 05.03.26"))
    }

    @Test
    fun `slash dates keep the day-first reading unless it is invalid`() {
        assertEquals(LocalDate.of(2026, 5, 1), date("Datum 01/05/2026"))
        assertEquals(LocalDate.of(2026, 5, 13), date("Datum 05/13/2026"))
    }

    @Test
    fun `an invalid date alone does not crash and yields no date`() {
        assertNull(date("Datum 31.02.26"))
    }
}
