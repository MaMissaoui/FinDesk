package de.findesk.importer

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate

class ImportLimitationsTest {
    private val today = LocalDate.of(2026, 11, 1)

    @Test
    fun `two-digit years resolve to 19xx when 20xx would be more than a year ahead`() {
        assertEquals(1999, TwoDigitYear.resolve(99, today))
        assertEquals(1928, TwoDigitYear.resolve(28, today))
        assertEquals(2027, TwoDigitYear.resolve(27, today))
        assertEquals(2026, TwoDigitYear.resolve(26, today))
        assertEquals(2000, TwoDigitYear.resolve(0, today))

        assertEquals(2099, TwoDigitYear.resolve(99, LocalDate.of(2099, 6, 1)))
        assertEquals(2000, TwoDigitYear.resolve(0, LocalDate.of(2099, 6, 1)))
    }

    @Test
    fun `mt940 value date with a year before 1928 resolves to 19xx`() {
        val mt940 = """
            :20:STARTUMS
            :25:DE89370400440532013000
            :28C:1
            :60F:C991231EUR0,00
            :61:991231D45,67N066NONREF
            :86:?20Old booking
            :62F:C991231EUR0,00
        """.trimIndent()

        val s = Mt940Parser.parse(mt940.toByteArray())

        assertEquals(1, s.bookings.size)
        assertEquals(LocalDate.of(1999, 12, 31), s.bookings.single().date)
        assertEquals(LocalDate.of(1999, 12, 31), s.bookings.single().valueDate)
        assertEquals(BigDecimal("-45.67"), s.bookings.single().amount)
    }

    @Test
    fun `mt940 value date with a recent two-digit year stays 20xx`() {
        val mt940 = """
            :20:STARTUMS
            :25:DE89370400440532013000
            :28C:1
            :60F:C260115EUR0,00
            :61:260115D45,67N066NONREF
            :86:?20Recent booking
            :62F:C260115EUR0,00
        """.trimIndent()

        val s = Mt940Parser.parse(mt940.toByteArray())

        assertEquals(LocalDate.of(2026, 1, 15), s.bookings.single().date)
        assertEquals(LocalDate.of(2026, 1, 15), s.bookings.single().valueDate)
    }

    @Test
    fun `qif two-digit years follow the same rule`() {
        val qif = """
            !Type:Bank
            D12/31/99
            T-10.00
            POld
            ^
            D01/15/26
            T-20.00
            PRecent
            ^
        """.trimIndent()

        val s = QifParser.parse(qif.toByteArray())

        assertEquals(listOf(LocalDate.of(1999, 12, 31), LocalDate.of(2026, 1, 15)), s.bookings.map { it.date })
    }

    @Test
    fun `csv dates are parsed strictly`() {
        assertNull(CsvStatementParser.parseDate("31.02.2026"))
        assertEquals(LocalDate.of(2028, 2, 29), CsvStatementParser.parseDate("29.02.2028"))
        assertNull(CsvStatementParser.parseDate("29.02.2027"))
        assertEquals(LocalDate.of(1999, 12, 31), CsvStatementParser.parseDate("31.12.99"))
        assertEquals(LocalDate.of(2026, 1, 15), CsvStatementParser.parseDate("15.01.26"))
        assertNull(CsvStatementParser.parseDate("2026-02-30"))
        assertEquals(LocalDate.of(2026, 1, 15), CsvStatementParser.parseDate("15.01.2026"))
    }

    @Test
    fun `csv dates with a time and a one-digit day or month are parsed`() {
        assertEquals(LocalDate.of(2026, 10, 1), CsvStatementParser.parseDate("1.10.2026 12:00"))
        assertEquals(LocalDate.of(2026, 10, 1), CsvStatementParser.parseDate("01.10.2026 12:00"))
        assertEquals(LocalDate.of(2026, 10, 1), CsvStatementParser.parseDate("2026-10-01T12:00:00"))
        assertEquals(LocalDate.of(2026, 10, 1), CsvStatementParser.parseDate("1.10.2026"))
        assertNull(CsvStatementParser.parseDate("31.02.2026 12:00"))
    }

    @Test
    fun `csv two-digit years follow TwoDigitYear at parse time`() {
        assertEquals(LocalDate.of(1999, 12, 31), CsvStatementParser.parseDate("31.12.99", today))
        assertEquals(LocalDate.of(2026, 1, 15), CsvStatementParser.parseDate("15.01.26", today))
        assertEquals(LocalDate.of(1928, 2, 29), CsvStatementParser.parseDate("29.02.28", today))
        assertNull(CsvStatementParser.parseDate("29.02.27", today))
        assertNull(CsvStatementParser.parseDate("31.02.26", today))
    }

    @Test
    fun `csv row with an impossible date is skipped on its own line`() {
        val csv = "Buchungstag;Betrag;Verwendungszweck\n" +
            "03.10.2026;-45,67;ok\n" +
            "31.02.2026;-10,00;impossible\n" +
            "04.10.2026;-5,00;also ok\n"

        val s = CsvStatementParser.parse(csv.toByteArray())

        assertEquals(2, s.bookings.size)
        assertEquals(listOf(BigDecimal("-45.67"), BigDecimal("-5.00")), s.bookings.map { it.amount })
        assertEquals(1, s.skipped.size)
        assertTrue(s.skipped[0].startsWith("Line 3"), s.skipped[0])
        assertTrue(s.skipped[0].contains("no valid date"), s.skipped[0])
    }

    @Test
    fun `tab file whose fields contain commas is not read as comma separated`() {
        val csv = "Buchungstag\tBetrag, EUR\tVerwendungszweck\n" +
            "03.10.2026\t-45,67\tMiete, Oktober\n" +
            "04.10.2026\t-10,00\tBrot, Butter\n"

        val s = CsvStatementParser.parse(csv.toByteArray())

        assertEquals('\t', s.csv!!.delimiter)
        assertEquals(2, s.bookings.size)
        assertEquals(listOf(BigDecimal("-45.67"), BigDecimal("-10.00")), s.bookings.map { it.amount })
        assertEquals(listOf("Miete, Oktober", "Brot, Butter"), s.bookings.map { it.purpose })
    }

    /** One tab and one comma on every line: a true tie, which list order used to give to the comma. */
    @Test
    fun `a tab and comma tie goes to the delimiter with a recognisable header`() {
        val csv = "Buchungstag\tBetrag, EUR\n" +
            "03.10.2026\t-45,67\n" +
            "04.10.2026\t-10,00\n"

        val s = CsvStatementParser.parse(csv.toByteArray())

        assertEquals('\t', s.csv!!.delimiter)
        assertEquals(listOf(BigDecimal("-45.67"), BigDecimal("-10.00")), s.bookings.map { it.amount })
    }

    /** Quoted semicolons vary per row; counted raw they made the steady comma in the amount look like the delimiter. */
    @Test
    fun `quoted semicolons of varying number do not hand the file to the comma`() {
        val csv = "Datum;Betrag;Text\n" +
            "03.10.2026;-45,67;\"a;b\"\n" +
            "04.10.2026;-10,00;\"a;b;c\"\n" +
            "05.10.2026;-1,50;plain\n"

        val s = CsvStatementParser.parse(csv.toByteArray())

        assertEquals(';', s.csv!!.delimiter)
        assertEquals(listOf("a;b", "a;b;c", "plain"), s.bookings.map { it.purpose })
    }

    @Test
    fun `semicolon file with quoted commas and semicolons keeps its delimiter`() {
        val csv = "\"Datum\";\"Betrag\";\"Text\"\n" +
            "\"03.10.2026\";\"-45,67\";\"a;b, c\"\n"

        val s = CsvStatementParser.parse(csv.toByteArray())

        assertEquals(';', s.csv!!.delimiter)
        assertEquals(1, s.bookings.size)
        assertEquals("a;b, c", s.bookings.single().purpose)
        assertEquals(BigDecimal("-45.67"), s.bookings.single().amount)
    }

    @Test
    fun `comma file with a quoted multi-line purpose imports the right fields`() {
        val csv = "Date,Description,Payee,Debit,Credit,Balance\r\n" +
            "2026-09-30,\"Card payment\nLondon\",Pret A Manger,4.50,,100.00\r\n" +
            "2026-09-29,Salary,ACME Ltd,,\"1,500.00\",104.50\r\n"

        val s = CsvStatementParser.parse(csv.toByteArray())

        assertEquals(',', s.csv!!.delimiter)
        assertEquals(2, s.bookings.size)
        assertEquals(BigDecimal("-4.50"), s.bookings[0].amount)
        assertEquals("Pret A Manger", s.bookings[0].partnerName)
        assertEquals("Card payment London", s.bookings[0].purpose)
        assertEquals(BigDecimal("1500.00"), s.bookings[1].amount)
        assertEquals("ACME Ltd", s.bookings[1].partnerName)
    }
}
