package de.findesk.importer

import de.findesk.TestData
import de.findesk.TestDatabase
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class CamtMultiAccountTest {
    private fun camt(statements: String): String = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
        "<Document xmlns=\"urn:iso:std:iso:20022:tech:xsd:camt.053.001.02\">\n" +
        statements + "\n" +
        "</Document>"

    private fun stmt(iban: String, entries: String): String = "<BkToCstmrStmt><Stmt>\n" +
        "<Acct><Id><IBAN>$iban</IBAN></Id></Acct>\n" +
        entries + "\n" +
        "</Stmt></BkToCstmrStmt>"

    private fun entry(amount: String, date: String, ref: String): String = "<Ntry>\n" +
        "<Amt Ccy=\"EUR\">$amount</Amt><CdtDbtInd>DBIT</CdtDbtInd><Sts>BOOK</Sts>\n" +
        "<BookgDt><Dt>$date</Dt></BookgDt><ValDt><Dt>$date</Dt></ValDt>\n" +
        "<AcctSvcrRef>$ref</AcctSvcrRef>\n" +
        "<NtryDtls><TxDtls>\n" +
        "<RltdPties><Cdtr><Nm>Partner</Nm></Cdtr></RltdPties>\n" +
        "<RmtInf><Ustrd>Test</Ustrd></RmtInf>\n" +
        "</TxDtls></NtryDtls>\n" +
        "</Ntry>"

    @Test
    fun `two Stmt blocks for different IBANs lists both accounts and is refused`() {
        val xml = camt(
            stmt("DE89370400440532013000", entry("10.00", "2026-10-01", "A1")) +
                stmt("DE44500105175407324931", entry("20.00", "2026-10-02", "A2")),
        )
        val s = CamtParser.parse(xml.toByteArray())
        assertEquals(listOf("DE89370400440532013000", "DE44500105175407324931"), s.accounts)
        assertEquals(2, s.bookings.size)
    }

    @Test
    fun `two Stmt blocks for the same IBAN lists one account and keeps all bookings`() {
        val xml = camt(
            stmt("DE89370400440532013000", entry("10.00", "2026-10-01", "A1")) +
                stmt("DE89370400440532013000", entry("20.00", "2026-10-02", "A2")),
        )
        val s = CamtParser.parse(xml.toByteArray())
        assertEquals(listOf("DE89370400440532013000"), s.accounts)
        assertEquals(2, s.bookings.size)
    }
}

class MultiAccountImportIntegrationTest {
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

    private fun camt(iban: String, ref: String) = """
        <?xml version="1.0" encoding="UTF-8"?>
        <Document xmlns="urn:iso:std:iso:20022:tech:xsd:camt.053.001.02">
          <BkToCstmrStmt><Stmt>
            <Acct><Id><IBAN>$iban</IBAN></Id></Acct>
            <Ntry>
              <Amt Ccy="EUR">10.00</Amt><CdtDbtInd>DBIT</CdtDbtInd><Sts>BOOK</Sts>
              <BookgDt><Dt>2026-10-01</Dt></BookgDt><ValDt><Dt>2026-10-01</Dt></ValDt>
              <AcctSvcrRef>$ref</AcctSvcrRef>
              <NtryDtls><TxDtls>
                <RltdPties><Cdtr><Nm>Partner</Nm></Cdtr></RltdPties>
                <RmtInf><Ustrd>Test</Ustrd></RmtInf>
              </TxDtls></NtryDtls>
            </Ntry>
          </Stmt></BkToCstmrStmt>
        </Document>
    """.trimIndent()

    @Test
    fun `camt with two accounts is refused by read and import stores nothing`() = runBlocking {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <Document xmlns="urn:iso:std:iso:20022:tech:xsd:camt.053.001.02">
              <BkToCstmrStmt><Stmt>
                <Acct><Id><IBAN>DE89370400440532013000</IBAN></Id></Acct>
                <Ntry><Amt Ccy="EUR">10.00</Amt><CdtDbtInd>DBIT</CdtDbtInd><Sts>BOOK</Sts>
                  <BookgDt><Dt>2026-10-01</Dt></BookgDt><ValDt><Dt>2026-10-01</Dt></ValDt>
                  <AcctSvcrRef>A1</AcctSvcrRef></Ntry>
              </Stmt></BkToCstmrStmt>
              <BkToCstmrStmt><Stmt>
                <Acct><Id><IBAN>DE44500105175407324931</IBAN></Id></Acct>
                <Ntry><Amt Ccy="EUR">20.00</Amt><CdtDbtInd>DBIT</CdtDbtInd><Sts>BOOK</Sts>
                  <BookgDt><Dt>2026-10-02</Dt></BookgDt><ValDt><Dt>2026-10-02</Dt></ValDt>
                  <AcctSvcrRef>A2</AcctSvcrRef></Ntry>
              </Stmt></BkToCstmrStmt>
            </Document>
        """.trimIndent()
        val f = file("two-accounts.xml", xml)

        val ex = assertThrows(IllegalArgumentException::class.java) {
            runBlocking { service.read(f) }
        }
        assertTrue(ex.message!!.contains("2"), ex.message)
        assertTrue(ex.message!!.contains("…3000"), ex.message)
        assertTrue(ex.message!!.contains("…4931"), ex.message)

        val statement = CamtParser.parse(xml.toByteArray())
        val importEx = assertThrows(IllegalArgumentException::class.java) {
            runBlocking { service.import(account, statement) }
        }
        assertTrue(importEx.message!!.contains("2"), importEx.message)
        assertEquals(0, data.transactionRepo.findItems().size)
    }

    @Test
    fun `camt with two blocks for the same account is accepted and imports all bookings`() = runBlocking {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <Document xmlns="urn:iso:std:iso:20022:tech:xsd:camt.053.001.02">
              <BkToCstmrStmt><Stmt>
                <Acct><Id><IBAN>DE89370400440532013000</IBAN></Id></Acct>
                <Ntry><Amt Ccy="EUR">10.00</Amt><CdtDbtInd>DBIT</CdtDbtInd><Sts>BOOK</Sts>
                  <BookgDt><Dt>2026-10-01</Dt></BookgDt><ValDt><Dt>2026-10-01</Dt></ValDt>
                  <AcctSvcrRef>A1</AcctSvcrRef></Ntry>
              </Stmt></BkToCstmrStmt>
              <BkToCstmrStmt><Stmt>
                <Acct><Id><IBAN>DE89370400440532013000</IBAN></Id></Acct>
                <Ntry><Amt Ccy="EUR">20.00</Amt><CdtDbtInd>DBIT</CdtDbtInd><Sts>BOOK</Sts>
                  <BookgDt><Dt>2026-10-02</Dt></BookgDt><ValDt><Dt>2026-10-02</Dt></ValDt>
                  <AcctSvcrRef>A2</AcctSvcrRef></Ntry>
              </Stmt></BkToCstmrStmt>
            </Document>
        """.trimIndent()
        val f = file("same-account.xml", xml)
        val statement = service.read(f)
        assertEquals(listOf("DE89370400440532013000"), statement.accounts)
        assertEquals(2, statement.bookings.size)
        val report = service.import(account, statement)
        assertEquals(2, report.imported)
        assertEquals(2, data.transactionRepo.findItems().size)
    }

    @Test
    fun `zip with two camt files for different accounts is refused`() = runBlocking {
        val zip = ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { z ->
                z.putNextEntry(ZipEntry("a.xml"))
                z.write(camt("DE89370400440532013000", "A1").toByteArray())
                z.closeEntry()
                z.putNextEntry(ZipEntry("b.xml"))
                z.write(camt("DE44500105175407324931", "A2").toByteArray())
                z.closeEntry()
            }
        }.toByteArray()
        val f = File(tmp, "two-accounts.zip").apply { writeBytes(zip) }
        val ex = assertThrows(IllegalArgumentException::class.java) {
            runBlocking { service.read(f) }
        }
        assertTrue(ex.message!!.contains("2"), ex.message)
    }

    @Test
    fun `zip with two camt files for the same account is accepted`() = runBlocking {
        val zip = ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { z ->
                z.putNextEntry(ZipEntry("a.xml"))
                z.write(camt("DE89370400440532013000", "A1").toByteArray())
                z.closeEntry()
                z.putNextEntry(ZipEntry("b.xml"))
                z.write(camt("DE89370400440532013000", "A2").toByteArray())
                z.closeEntry()
            }
        }.toByteArray()
        val f = File(tmp, "same-account.zip").apply { writeBytes(zip) }
        val statement = service.read(f)
        assertEquals(listOf("DE89370400440532013000"), statement.accounts)
        val report = service.import(account, statement)
        assertEquals(2, report.imported)
    }

    @Test
    fun `mt940 with two statements for different accounts is refused`() = runBlocking {
        val mt940 = """
            :20:STARTUMS
            :25:DE89370400440532013000
            :28C:1
            :60F:C241001EUR0,00
            :61:241001D10,00N066NONREF
            :86:?20Test
            :62F:C241001EUR10,00
            -
            :20:STARTUMS
            :25:DE44500105175407324931
            :28C:1
            :60F:C241001EUR0,00
            :61:241001D20,00N066NONREF
            :86:?20Test
            :62F:C241001EUR20,00
        """.trimIndent()
        val f = file("two-accounts.sta", mt940)
        val ex = assertThrows(IllegalArgumentException::class.java) {
            runBlocking { service.read(f) }
        }
        assertTrue(ex.message!!.contains("2"), ex.message)
    }

    @Test
    fun `mt940 with two statements for the same account is accepted`() = runBlocking {
        val mt940 = """
            :20:STARTUMS
            :25:DE89370400440532013000
            :28C:1
            :60F:C241001EUR0,00
            :61:241001D10,00N066NONREF
            :86:?20Test
            :62F:C241001EUR10,00
            -
            :20:STARTUMS
            :25:DE89370400440532013000
            :28C:2
            :60F:C241001EUR10,00
            :61:241002D20,00N066NONREF
            :86:?20Test
            :62F:C241002EUR30,00
        """.trimIndent()
        val f = file("same-account.sta", mt940)
        val statement = service.read(f)
        assertEquals(listOf("DE89370400440532013000"), statement.accounts)
        val report = service.import(account, statement)
        assertEquals(2, report.imported)
    }

    @Test
    fun `ofx sgml with two statement blocks for different accounts is refused`() = runBlocking {
        val ofx = ofxSgml("DE89370400440532013000") + ofxSgml("DE44500105175407324931")
        val f = file("two-accounts.ofx", ofx)
        val ex = assertThrows(IllegalArgumentException::class.java) {
            runBlocking { service.read(f) }
        }
        assertTrue(ex.message!!.contains("2"), ex.message)
    }

    @Test
    fun `ofx sgml with one statement block is accepted`() = runBlocking {
        val f = file("one-account.ofx", ofxSgml("DE89370400440532013000"))
        val statement = service.read(f)
        assertEquals(listOf("DE89370400440532013000"), statement.accounts)
        val report = service.import(account, statement)
        assertEquals(1, report.imported)
    }

    @Test
    fun `ofx xml with two statement blocks for different accounts is refused`() = runBlocking {
        val ofx = ofxXmlHeader + ofxXmlStatement("DE89370400440532013000") + ofxXmlStatement("DE44500105175407324931") + "</OFX>"
        val f = file("two-accounts-xml.ofx", ofx)
        val ex = assertThrows(IllegalArgumentException::class.java) {
            runBlocking { service.read(f) }
        }
        assertTrue(ex.message!!.contains("2"), ex.message)
    }

    @Test
    fun `ofx xml with one statement block is accepted`() = runBlocking {
        val ofx = ofxXmlHeader + ofxXmlStatement("DE89370400440532013000") + "</OFX>"
        val f = file("one-account-xml.ofx", ofx)
        val statement = service.read(f)
        assertEquals(listOf("DE89370400440532013000"), statement.accounts)
        val report = service.import(account, statement)
        assertEquals(1, report.imported)
    }

    private fun ofxSgml(acctId: String) = """
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
        <ACCTID>$acctId</ACCTID>
        <ACCTTYPE>CHECKING</ACCTTYPE>
        </BANKACCTFROM>
        <BANKTRANLIST>
        <DTSTART>20261003000000</DTSTART>
        <DTEND>20261003000000</DTEND>
        <STMTTRN>
        <TRNTYPE>DEBIT</TRNTYPE>
        <DTPOSTED>20261003000000</DTPOSTED>
        <TRNAMT>-10.00</TRNAMT>
        <FITID>REF-$acctId</FITID>
        <NAME>Partner</NAME>
        <MEMO>Test</MEMO>
        </STMTTRN>
        </BANKTRANLIST>
        </STMTRS>
        </STMTTRNRS>
        </BANKMSGSRSV1>
        </OFX>
    """.trimIndent()

    private val ofxXmlHeader = """
        <?xml version="1.0" encoding="UTF-8"?>
        <OFX>
    """.trimIndent()

    private fun ofxXmlStatement(acctId: String) = """
          <BANKMSGSRSV1>
            <STMTTRNRS>
              <STMTRS>
                <CURDEF>EUR</CURDEF>
                <BANKACCTFROM>
                  <ACCTID>$acctId</ACCTID>
                </BANKACCTFROM>
                <BANKTRANLIST>
                  <STMTTRN>
                    <TRNTYPE>DEBIT</TRNTYPE>
                    <DTPOSTED>20261003000000</DTPOSTED>
                    <TRNAMT>-10.00</TRNAMT>
                    <FITID>REF-$acctId</FITID>
                    <NAME>Partner</NAME>
                    <MEMO>Test</MEMO>
                  </STMTTRN>
                </BANKTRANLIST>
              </STMTRS>
            </STMTTRNRS>
          </BANKMSGSRSV1>
    """.trimIndent()
}
