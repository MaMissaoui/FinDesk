package de.findesk.importer

import de.findesk.TestData
import de.findesk.TestDatabase
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.math.BigDecimal
import java.nio.charset.Charset
import java.time.LocalDate

class Mt940ParserTest {
    private val cp1252 = Charset.forName("windows-1252")

    @Test
    fun `mt940 with two statements structured 86 wrapped line reversal and year rollover`() {
        val mt940 = """
            :20:STARTUMS
            :25:DE89370400440532013000
            :28C:00001/001
            :60F:C231030EUR1234,56
            :61:2310301031D45,67N066NONREF
            :86:166?00KARTENZAHLUNG?100005?20REWE SAGT DANKE?211234?32REWE Mar
            kt GmbH
            :61:2310311102C500,00N066NONREF
            :86:166?00GUTSCHRIFT?100005?20Gehalt Oktober?32Müller & Söhne GmbH
            :62F:C231031EUR1688,89
            -
            :20:STARTUMS
            :25:DE89370400440532013000
            :28C:00001/002
            :60F:C231031EUR1688,89
            :61:2312310102RD100,00N066RUECKLASTSCHRIFT
            :86:166?00RÜCKBUCHUNG?20Lastschrift retour?32Kunde AG
            :62F:C240102EUR1588,89
        """.trimIndent().toByteArray(cp1252)

        val s = Mt940Parser.parse(mt940)

        assertEquals(StatementFormat.MT940, s.format)
        assertEquals("DE89370400440532013000", s.accountIban)
        assertEquals(3, s.bookings.size)
        assertTrue(s.skipped.isEmpty(), s.skipped.toString())

        val rewe = s.bookings[0]
        assertEquals(LocalDate.of(2023, 10, 31), rewe.date)
        assertEquals(LocalDate.of(2023, 10, 30), rewe.valueDate)
        assertEquals(BigDecimal("-45.67"), rewe.amount)
        assertEquals("REWE Markt GmbH", rewe.partnerName, "wrapped line joined mid-word")
        assertEquals("REWE SAGT DANKE1234", rewe.purpose)
        assertEquals("EUR", rewe.currency)
        assertNull(rewe.balanceAfter)

        val gehalt = s.bookings[1]
        assertEquals(LocalDate.of(2023, 11, 2), gehalt.date)
        assertEquals(LocalDate.of(2023, 10, 31), gehalt.valueDate)
        assertEquals(BigDecimal("500.00"), gehalt.amount)
        assertEquals("Müller & Söhne GmbH", gehalt.partnerName, "windows-1252 umlaut decoded")
        assertEquals("Gehalt Oktober", gehalt.purpose)

        val reversal = s.bookings[2]
        assertEquals(LocalDate.of(2024, 1, 2), reversal.date, "Dec -> Jan rollover infers next year")
        assertEquals(LocalDate.of(2023, 12, 31), reversal.valueDate)
        assertEquals(BigDecimal("-100.00"), reversal.amount, "RD is money out")
        assertEquals("RUECKLASTSCHRIFT", reversal.bankReference)
    }

    @Test
    fun `mt940 structured 86 with GVC partner iban bic and wrapped subfield marker`() {
        val mt940 = """
            :20:STARTUMS
            :25:DE89370400440532013000
            :28C:1
            :60F:C240102EUR0,00
            :61:2401020103C1234,56NTRF
            :86:166?00GUTSCHRIFT?100005?20EREF+X?21SVWZ+Rechnung 4711?2
            0Weitere Zeile?30COBADEFFXXX?31DE89370400440532013000?32Max Muster
            :62F:C240102EUR1234,56
        """.trimIndent()

        val s = Mt940Parser.parse(mt940.toByteArray())
        assertEquals(1, s.bookings.size)
        val b = s.bookings[0]
        assertEquals(LocalDate.of(2024, 1, 3), b.date)
        assertEquals(LocalDate.of(2024, 1, 2), b.valueDate)
        assertEquals(BigDecimal("1234.56"), b.amount)
        assertEquals("Max Muster", b.partnerName)
        assertEquals("DE89370400440532013000", b.partnerIban)
        assertEquals("EREF+XSVWZ+Rechnung 4711Weitere Zeile", b.purpose)
    }

    @Test
    fun `mt940 purpose containing a time is not cut at a false tag`() {
        val mt940 = """
            :20:STARTUMS
            :25:DE89370400440532013000
            :28C:1
            :60F:C240102EUR0,00
            :61:240102D10,00N066NONREF
            :86:2026-10-03T14:30:15 meeting at 14:30:15
            :62F:C240102EUR10,00
        """.trimIndent()

        val s = Mt940Parser.parse(mt940.toByteArray())
        assertEquals(1, s.bookings.size)
        assertEquals("2026-10-03T14:30:15 meeting at 14:30:15", s.bookings[0].purpose)
    }

    @Test
    fun `mt940 funds code after debit credit mark`() {
        val mt940 = """
            :20:STARTUMS
            :25:DE89370400440532013000
            :28C:1
            :60F:C240102EUR0,00
            :61:240102DR10,50N066NONREF
            :86:?20Coffee
            :62F:C240102EUR10,50
        """.trimIndent()

        val s = Mt940Parser.parse(mt940.toByteArray())
        assertEquals(1, s.bookings.size)
        assertEquals(BigDecimal("-10.50"), s.bookings[0].amount)

        val mt940Reversal = """
            :20:STARTUMS
            :25:DE89370400440532013000
            :28C:1
            :60F:C240102EUR0,00
            :61:240102RCR10,50N066NONREF
            :86:?20Refund
            :62F:C240102EUR10,50
        """.trimIndent()
        val sr = Mt940Parser.parse(mt940Reversal.toByteArray())
        assertEquals(BigDecimal("10.50"), sr.bookings[0].amount, "RC with funds code is still a reversal credit")
    }

    @Test
    @Timeout(5)
    fun `mt940 with a 20-digit amount skips the record instead of importing it`() {
        val mt940 = """
            :20:STARTUMS
            :25:DE89370400440532013000
            :28C:1
            :60F:C240102EUR0,00
            :61:2401020103C99999999999999999999NTRF
            :86:?20Huge
            :62F:C240102EUR0,00
        """.trimIndent()

        val s = Mt940Parser.parse(mt940.toByteArray())

        assertTrue(s.bookings.isEmpty())
        assertEquals(1, s.skipped.size)
        assertTrue(s.skipped[0].startsWith("Record 1: invalid amount"), s.skipped.toString())
    }

    @Test
    fun `mt940 bank reference after slashes and supplementary details`() {
        val mt940 = """
            :20:STARTUMS
            :25:DE89370400440532013000
            :28C:1
            :60F:C240102EUR0,00
            :61:240102D25,00NTRFEXAMPLE//1234567890
            extra line ignored
            :86:?20Taxi
            :62F:C240102EUR25,00
        """.trimIndent()

        val s = Mt940Parser.parse(mt940.toByteArray())
        assertEquals(1, s.bookings.size)
        assertEquals("1234567890", s.bookings[0].bankReference)
    }

    @Test
    fun `mt25 currency appended to iban`() {
        val mt940 = """
            :20:STARTUMS
            :25:DE89370400440532013000EUR
            :28C:1
            :60F:C240102EUR0,00
            :61:240102D5,00N066NONREF
            :86:?20Test
            :62F:C240102EUR5,00
        """.trimIndent()

        val s = Mt940Parser.parse(mt940.toByteArray())
        assertEquals("DE89370400440532013000", s.accountIban)
    }
}

class OfxParserTest {
    private val ofxSgml = """
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
        <BANKID>37040044</BANKID>
        <ACCTID>DE89370400440532013000</ACCTID>
        <ACCTTYPE>CHECKING</ACCTTYPE>
        </BANKACCTFROM>
        <BANKTRANLIST>
        <DTSTART>20261003000000</DTSTART>
        <DTEND>20261004000000</DTEND>
        <STMTTRN>
        <TRNTYPE>DEBIT</TRNTYPE>
        <DTPOSTED>20261003000000</DTPOSTED>
        <TRNAMT>-45.67</TRNAMT>
        <FITID>REF-1</FITID>
        <NAME>REWE Markt GmbH</NAME>
        <MEMO>REWE SAGT DANKE 1234</MEMO>
        </STMTTRN>
        <STMTTRN>
        <TRNTYPE>CREDIT</TRNTYPE>
        <DTPOSTED>20261001000000</DTPOSTED>
        <TRNAMT>3200.00</TRNAMT>
        <FITID>REF-2</FITID>
        <NAME>Müller &amp; Söhne GmbH</NAME>
        <MEMO>Gehalt Oktober</MEMO>
        </STMTTRN>
        </BANKTRANLIST>
        </STMTRS>
        </STMTTRNRS>
        </BANKMSGSRSV1>
        </OFX>
    """.trimIndent()

    private val ofxXml = """
        <?xml version="1.0" encoding="UTF-8"?>
        <OFX>
          <BANKMSGSRSV1>
            <STMTTRNRS>
              <STMTRS>
                <CURDEF>USD</CURDEF>
                <BANKACCTFROM>
                  <ACCTID>DE89370400440532013000</ACCTID>
                </BANKACCTFROM>
                <BANKTRANLIST>
                  <STMTTRN>
                    <TRNTYPE>DEBIT</TRNTYPE>
                    <DTPOSTED>20261003000000</DTPOSTED>
                    <TRNAMT>-45.67</TRNAMT>
                    <FITID>REF-1</FITID>
                    <NAME>REWE Markt GmbH</NAME>
                    <MEMO>REWE SAGT DANKE 1234</MEMO>
                  </STMTTRN>
                  <STMTTRN>
                    <TRNTYPE>CREDIT</TRNTYPE>
                    <DTPOSTED>20261001000000</DTPOSTED>
                    <TRNAMT>3200.00</TRNAMT>
                    <FITID>REF-2</FITID>
                    <NAME>Müller &amp; Söhne GmbH</NAME>
                    <MEMO>Gehalt Oktober</MEMO>
                  </STMTTRN>
                </BANKTRANLIST>
              </STMTRS>
            </STMTTRNRS>
          </BANKMSGSRSV1>
        </OFX>
    """.trimIndent()

    private val cp1252 = Charset.forName("windows-1252")

    @Test
    fun `ofx sgml and xml produce the same bookings with FITID as bank reference`() {
        val sgml = OfxParser.parse(ofxSgml.toByteArray())
        val xml = OfxParser.parse(ofxXml.toByteArray())

        assertEquals(StatementFormat.OFX, sgml.format)
        assertEquals(StatementFormat.OFX, xml.format)
        assertEquals("DE89370400440532013000", sgml.accountIban)
        assertEquals("DE89370400440532013000", xml.accountIban)
        assertEquals(2, sgml.bookings.size)
        assertEquals(2, xml.bookings.size)
        assertTrue(sgml.skipped.isEmpty())
        assertTrue(xml.skipped.isEmpty())

        assertEquals(listOf("REF-1", "REF-2"), sgml.bookings.map { it.bankReference })
        assertEquals(listOf("REF-1", "REF-2"), xml.bookings.map { it.bankReference })

        val s = sgml.bookings[0]
        assertEquals(LocalDate.of(2026, 10, 3), s.date)
        assertEquals(BigDecimal("-45.67"), s.amount)
        assertEquals("REWE Markt GmbH", s.partnerName)
        assertEquals("REWE SAGT DANKE 1234", s.purpose)
        assertEquals("EUR", s.currency, "from CURDEF")

        assertEquals(sgml.bookings.map { it.amount }, xml.bookings.map { it.amount })
        assertEquals(sgml.bookings.map { it.partnerName }, xml.bookings.map { it.partnerName })
        assertEquals(sgml.bookings.map { it.purpose }, xml.bookings.map { it.purpose })
        assertEquals("USD", xml.bookings[0].currency)
    }

    @Test
    fun `ofx sgml in windows-1252 with umlauts and decoded ampersand`() {
        val sgml = """
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
            <BANKACCTFROM>
            <ACCTID>DE89370400440532013000</ACCTID>
            </BANKACCTFROM>
            <BANKTRANLIST>
            <STMTTRN>
            <TRNTYPE>DEBIT</TRNTYPE>
            <DTPOSTED>20261003000000</DTPOSTED>
            <TRNAMT>-45,67</TRNAMT>
            <FITID>REF-1</FITID>
            <NAME>Müller &amp; Söhne GmbH</NAME>
            <MEMO>äöü ß &amp;lt;b&amp;gt;</MEMO>
            </STMTTRN>
            </BANKTRANLIST>
            </STMTRS>
            </STMTTRNRS>
            </BANKMSGSRSV1>
            </OFX>
        """.trimIndent().toByteArray(cp1252)

        val s = OfxParser.parse(sgml)
        assertEquals(1, s.bookings.size)
        val b = s.bookings[0]
        assertEquals(BigDecimal("-45.67"), b.amount, "comma decimal accepted")
        assertEquals("Müller & Söhne GmbH", b.partnerName, "&amp; decoded")
        assertEquals("äöü ß &lt;b&gt;", b.purpose, "windows-1252 umlauts decoded, &amp; decoded only once")
    }

    @Test
    @Timeout(5)
    fun `ofx with an out-of-range amount skips the record instead of importing it`() {
        val sgml = """
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
            <BANKTRANLIST>
            <STMTTRN>
            <TRNTYPE>DEBIT</TRNTYPE>
            <DTPOSTED>20261003000000</DTPOSTED>
            <TRNAMT>1E999999999</TRNAMT>
            <FITID>REF-1</FITID>
            <NAME>REWE Markt GmbH</NAME>
            </STMTTRN>
            </BANKTRANLIST>
            </STMTRS>
            </STMTTRNRS>
            </BANKMSGSRSV1>
            </OFX>
        """.trimIndent()

        val s = OfxParser.parse(sgml.toByteArray())

        assertTrue(s.bookings.isEmpty())
        assertEquals(1, s.skipped.size)
        assertTrue(s.skipped[0].contains("invalid amount"), s.skipped.toString())
    }

    @Test
    fun `ofx xml with doctype and external entity is rejected`() {
        val xxe = """
            <?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE ofx [<!ENTITY x SYSTEM "file:///etc/passwd">]>
            <OFX>
              <BANKMSGSRSV1>
                <STMTTRNRS>
                  <STMTRS>
                    <BANKTRANLIST>
                      <STMTTRN>
                        <TRNTYPE>DEBIT</TRNTYPE>
                        <DTPOSTED>20261003000000</DTPOSTED>
                        <TRNAMT>-45.67</TRNAMT>
                        <FITID>X</FITID>
                        <NAME>&x;</NAME>
                      </STMTTRN>
                    </BANKTRANLIST>
                  </STMTRS>
                </STMTTRNRS>
              </BANKMSGSRSV1>
            </OFX>
        """.trimIndent()
        assertThrows(Exception::class.java) { OfxParser.parse(xxe.toByteArray()) }
    }
}

class QifParserTest {
    @Test
    fun `qif with us dates and thousands separator`() {
        val qif = """
            !Type:Bank
            D10/03/2026
            T-45.67
            PREWE Markt GmbH
            MREWE SAGT DANKE 1234
            N123
            ^
            D10/01/26
            T3,200.00
            PMüller & Söhne GmbH
            MGehalt Oktober
            ^
        """.trimIndent()

        val s = QifParser.parse(qif.toByteArray())
        assertEquals(StatementFormat.QIF, s.format)
        assertEquals(2, s.bookings.size)
        assertEquals(LocalDate.of(2026, 10, 3), s.bookings[0].date)
        assertEquals(BigDecimal("-45.67"), s.bookings[0].amount)
        assertEquals("REWE SAGT DANKE 1234", s.bookings[0].purpose)
        assertNull(s.bookings[0].bankReference, "QIF N field must not be used as bank reference")
        assertEquals(LocalDate.of(2026, 10, 1), s.bookings[1].date)
        assertEquals(BigDecimal("3200.00"), s.bookings[1].amount)
    }

    @Test
    fun `qif with german dotted dates`() {
        val qif = """
            !Type:Bank
            D03.10.2026
            T-45,67
            PREWE Markt GmbH
            ^
            D01.10.26
            T3.200,00
            PMüller & Söhne GmbH
            ^
        """.trimIndent()

        val s = QifParser.parse(qif.toByteArray())
        assertEquals(2, s.bookings.size)
        assertEquals(LocalDate.of(2026, 10, 3), s.bookings[0].date)
        assertEquals(BigDecimal("-45.67"), s.bookings[0].amount)
        assertEquals(BigDecimal("3200.00"), s.bookings[1].amount)
    }

    @Test
    fun `qif day greater than 12 disambiguates slash dates to day-month`() {
        val qif = """
            !Type:Bank
            D13/10/2026
            T-45.67
            PShop
            ^
            D14/10/26
            T-20.00
            PCafe
            ^
        """.trimIndent()

        val s = QifParser.parse(qif.toByteArray())
        assertEquals(2, s.bookings.size)
        assertEquals(LocalDate.of(2026, 10, 13), s.bookings[0].date)
        assertEquals(LocalDate.of(2026, 10, 14), s.bookings[1].date)
    }

    @Test
    fun `qif apostrophe year separator`() {
        val qif = """
            !Type:Bank
            D10/03'26
            T-12.00
            PKiosk
            ^
        """.trimIndent()

        val s = QifParser.parse(qif.toByteArray())
        assertEquals(LocalDate.of(2026, 10, 3), s.bookings.single().date)
    }

    @Test
    fun `qif unpadded and spaced dates`() {
        val qif = """
            !Type:Bank
            D1/5/26
            T-10.00
            PA
            ^
            D 1/ 5'26
            T-11.00
            PB
            ^
            D5.1.2026
            T-12.00
            PC
            ^
            D12/25/2026
            T-13.00
            PD
            ^
        """.trimIndent()

        val s = QifParser.parse(qif.toByteArray())
        assertEquals(4, s.bookings.size)
        assertEquals(LocalDate.of(2026, 1, 5), s.bookings[0].date)
        assertEquals(LocalDate.of(2026, 1, 5), s.bookings[1].date)
        assertEquals(LocalDate.of(2026, 1, 5), s.bookings[2].date)
        assertEquals(LocalDate.of(2026, 12, 25), s.bookings[3].date)
    }

    @Test
    fun `qif with bom and account header`() {
        val qif = "\uFEFF" + """
            !Account
            NMy Bank
            TBank
            ^
            !Type:Bank
            D10/03/2026
            T-45.67
            PREWE Markt GmbH
            MREWE SAGT DANKE
            ^
        """.trimIndent()

        val s = QifParser.parse(qif.toByteArray())
        assertEquals(1, s.bookings.size)
        assertEquals(LocalDate.of(2026, 10, 3), s.bookings[0].date)
    }

    @Test
    fun `qif skips non-bank types`() {
        val qif = """
            !Type:Bank
            D10/03/2026
            T-45.67
            PREWE
            ^
            !Type:Invst
            D10/03/2026
            T-100.00
            PBroker
            ^
            !Type:Bank
            D10/04/2026
            T-12.00
            PCafe
            ^
        """.trimIndent()

        val s = QifParser.parse(qif.toByteArray())
        assertEquals(2, s.bookings.size)
        assertEquals(listOf("REWE", "Cafe"), s.bookings.map { it.partnerName })
    }
}

class MoreFormatsIntegrationTest {
    @TempDir
    lateinit var tmp: File

    private lateinit var data: TestData
    private val service = StatementImportService()
    private var account = 0

    @BeforeEach
    fun setup() {
        TestDatabase.init()
        data = TestData()
        account = data.account(iban = "DE89370400440532013000", balance = "0")
    }

    private fun file(name: String, text: String) = File(tmp, name).apply { writeText(text) }

    private fun mt940Text() = """
        :20:STARTUMS
        :25:DE89370400440532013000
        :28C:00001/001
        :60F:C231030EUR1234,56
        :61:2310301031D45,67N066NONREF
        :86:166?00KARTENZAHLUNG?100005?20REWE SAGT DANKE?211234?32REWE Markt GmbH
        :61:2310311102C500,00N066NONREF
        :86:166?00GUTSCHRIFT?100005?20Gehalt Oktober?32Müller & Söhne GmbH
        :62F:C231031EUR1688,89
    """.trimIndent()

    @Test
    fun `service routes sta ofx qif and txt-mt940 to correct parsers`() = runBlocking {
        val sta = file("stmt.sta", mt940Text())
        assertEquals(StatementFormat.MT940, service.read(sta).format)

        val txt = file("stmt.txt", mt940Text())
        assertEquals(StatementFormat.MT940, service.read(txt).format)

        val ofx = file(
            "stmt.ofx",
            """
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
            <BANKACCTFROM>
            <ACCTID>DE89370400440532013000</ACCTID>
            </BANKACCTFROM>
            <BANKTRANLIST>
            <STMTTRN>
            <TRNTYPE>DEBIT</TRNTYPE>
            <DTPOSTED>20261003000000</DTPOSTED>
            <TRNAMT>-45.67</TRNAMT>
            <FITID>REF-1</FITID>
            <NAME>REWE Markt GmbH</NAME>
            <MEMO>REWE SAGT DANKE 1234</MEMO>
            </STMTTRN>
            </BANKTRANLIST>
            </STMTRS>
            </STMTTRNRS>
            </BANKMSGSRSV1>
            </OFX>
            """.trimIndent(),
        )
        assertEquals(StatementFormat.OFX, service.read(ofx).format)

        val qif = file(
            "stmt.qif",
            """
            !Type:Bank
            D10/03/2026
            T-45.67
            PREWE Markt GmbH
            MREWE SAGT DANKE 1234
            ^
            """.trimIndent(),
        )
        assertEquals(StatementFormat.QIF, service.read(qif).format)
    }

    @Test
    fun `re-importing the same mt940 reports all bookings as already known`() = runBlocking {
        val sta = file("stmt.sta", mt940Text())
        val first = service.import(account, service.read(sta))
        assertEquals(2, first.imported)

        val again = service.import(account, service.read(sta))
        assertEquals(0, again.imported)
        assertEquals(2, again.duplicates)
    }

    @Test
    fun `qif identical records with N type import both`() = runBlocking {
        val qif = file(
            "duplicates.qif",
            """
            !Type:Bank
            D10/03/2026
            T-4.50
            PBäcker
            MKaffee
            NDEBIT
            ^
            D10/03/2026
            T-4.50
            PBäcker
            MKaffee
            NDEBIT
            ^
            """.trimIndent(),
        )
        val statement = service.read(qif)
        assertEquals(2, statement.bookings.size)
        val report = service.import(account, statement)
        assertEquals(2, report.imported, "two identical same-day purchases are both real")
        assertEquals(0, report.duplicates)
        assertEquals(2, data.transactionRepo.findItems().size)
    }
}
