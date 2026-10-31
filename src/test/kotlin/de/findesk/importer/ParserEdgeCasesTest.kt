package de.findesk.importer

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate

class ParserEdgeCasesTest {
    @Test
    fun `a truncated mt940 detail marker does not abort the file`() {
        val mt940 = """
            :20:STARTUMS
            :25:DE89370400440532013000
            :28C:1
            :60F:C240102EUR0,00
            :61:2401020102C10,00N066REF1
            :86:?20First
            :61:2401030103C20,00N
            :61:2401040104D5,50N066REF3
            :86:?20Third
            :62F:C240104EUR24,50
        """.trimIndent().toByteArray()

        val s = Mt940Parser.parse(mt940)

        assertTrue(s.skipped.isEmpty(), s.skipped.toString())
        assertEquals(3, s.bookings.size)
        assertEquals(
            listOf(BigDecimal("10.00"), BigDecimal("20.00"), BigDecimal("-5.50")),
            s.bookings.map { it.amount },
        )
        assertEquals(listOf(LocalDate.of(2024, 1, 2), LocalDate.of(2024, 1, 3), LocalDate.of(2024, 1, 4)), s.bookings.map { it.date })
        assertEquals("REF1", s.bookings[0].bankReference)
        assertNull(s.bookings[1].bankReference, "a truncated :61: line parses with its amount and no reference")
        assertEquals("REF3", s.bookings[2].bankReference)
    }

    @Test
    fun `ofx amounts with both separators use the last one as decimal separator`() {
        val ofx = """
            OFXHEADER:100
            DATA:OFXSGML
            VERSION:102
            SECURITY:NONE
            ENCODING:USASCII
            CHARSET:1252
            COMPRESSION:NONE
            OLDFILEUID:NONE
            NEWFILEUID:NONE

            <OFX>
            <BANKMSGSRSV1>
            <STMTTRNRS>
            <STMTRS>
            <CURDEF>EUR</CURDEF>
            <BANKACCTFROM>
            <ACCTID>DE89370400440532013000</ACCTID>
            </BANKACCTFROM>
            <BANKTRANLIST>
            ${transaction("20261001000000", "-1.234,56", "REF-1")}
            ${transaction("20261002000000", "-1,234.56", "REF-2")}
            ${transaction("20261003000000", "-45,67", "REF-3")}
            ${transaction("20261004000000", "-45.67", "REF-4")}
            ${transaction("20261005000000", "1234", "REF-5")}
            ${transaction("20261006000000", "1.234.567", "REF-6")}
            ${transaction("20261007000000", "1,234,567", "REF-7")}
            </BANKTRANLIST>
            </STMTRS>
            </STMTTRNRS>
            </BANKMSGSRSV1>
            </OFX>
        """.trimIndent()

        val s = OfxParser.parse(ofx.toByteArray())

        assertEquals(
            listOf(
                BigDecimal("-1234.56"),
                BigDecimal("-1234.56"),
                BigDecimal("-45.67"),
                BigDecimal("-45.67"),
                BigDecimal("1234"),
            ),
            s.bookings.map { it.amount },
        )
        // Values with more than one grouping separator stay unparseable, exactly as before.
        assertEquals(
            listOf("Record 6: invalid amount '1.234.567'", "Record 7: invalid amount '1,234,567'"),
            s.skipped,
        )
    }

    private fun transaction(date: String, amount: String, fitId: String) = """
        <STMTTRN>
        <TRNTYPE>DEBIT</TRNTYPE>
        <DTPOSTED>$date</DTPOSTED>
        <TRNAMT>$amount</TRNAMT>
        <FITID>$fitId</FITID>
        <NAME>Test</NAME>
        </STMTTRN>
    """.trimIndent()
}
