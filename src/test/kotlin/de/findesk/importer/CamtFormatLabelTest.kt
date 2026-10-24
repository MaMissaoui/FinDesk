package de.findesk.importer

import de.findesk.TestData
import de.findesk.TestDatabase
import de.findesk.repository.AuditLogRepositoryImpl
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class CamtFormatLabelTest {
    private fun camt(namespace: String, message: String) = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
        (if (namespace.isEmpty()) "<Document>" else "<Document xmlns=\"$namespace\">") + "\n" +
        message + "\n" +
        "</Document>"

    private fun rpt(iban: String, entries: String): String = "<BkToCstmrAcctRpt><Rpt>\n" +
        "<Acct><Id><IBAN>$iban</IBAN></Id></Acct>\n" +
        entries + "\n" +
        "</Rpt></BkToCstmrAcctRpt>"

    private fun stmt(iban: String, entries: String): String = "<BkToCstmrStmt><Stmt>\n" +
        "<Acct><Id><IBAN>$iban</IBAN></Id></Acct>\n" +
        entries + "\n" +
        "</Stmt></BkToCstmrStmt>"

    private fun entry(amount: String, date: String, ref: String): String = "<Ntry>\n" +
        "<Amt Ccy=\"EUR\">$amount</Amt><CdtDbtInd>DBIT</CdtDbtInd><Sts>BOOK</Sts>\n" +
        "<BookgDt><Dt>$date</Dt></BookgDt><ValDt><Dt>$date</Dt></ValDt>\n" +
        "<AcctSvcrRef>$ref</AcctSvcrRef>\n" +
        "<NtryDtls><TxDtls><RltdPties><Cdtr><Nm>Partner</Nm></Cdtr></RltdPties></TxDtls></NtryDtls>\n" +
        "</Ntry>"

    private val iban = "DE89370400440532013000"

    @Test
    fun `a camt-052 account report is labelled CAMT-052 while the format stays CAMT053`() {
        val s = CamtParser.parse(camt("urn:iso:std:iso:20022:tech:xsd:camt.052.001.08", rpt(iban, entry("10.00", "2026-10-01", "A1"))).toByteArray())

        assertEquals(StatementFormat.CAMT053, s.format)
        assertEquals("CAMT.052", s.formatDetail)
        assertEquals("CAMT.052 XML", s.formatLabel)
        assertEquals(1, s.bookings.size)
    }

    @Test
    fun `a camt-053 statement is labelled CAMT-053`() {
        val s = CamtParser.parse(camt("urn:iso:std:iso:20022:tech:xsd:camt.053.001.02", stmt(iban, entry("5.00", "2026-10-03", "A2"))).toByteArray())

        assertEquals("CAMT.053", s.formatDetail)
        assertEquals("CAMT.053 XML", s.formatLabel)
    }

    @Test
    fun `without a namespace the message type comes from the root's first child`() {
        val noNamespace = camt("", rpt(iban, entry("10.00", "2026-10-01", "A3")))
        assertEquals("CAMT.052", CamtParser.parse(noNamespace.toByteArray()).formatDetail)

        val stmt54 = camt("", stmt(iban, entry("10.00", "2026-10-01", "A4")).replace("BkToCstmrStmt", "BkToCstmrDbtCdtNtfctn"))
        assertEquals("CAMT.054", CamtParser.parse(stmt54.toByteArray()).formatDetail)
    }

    @Test
    fun `a zip of a camt-052 and a camt-053 file names both types in file order`() {
        val zip = ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { z ->
                z.putNextEntry(ZipEntry("report.xml"))
                z.write(camt("urn:iso:std:iso:20022:tech:xsd:camt.052.001.08", rpt(iban, entry("10.00", "2026-10-01", "B1"))).toByteArray())
                z.closeEntry()
                z.putNextEntry(ZipEntry("statement.xml"))
                z.write(camt("urn:iso:std:iso:20022:tech:xsd:camt.053.001.02", stmt(iban, entry("20.00", "2026-10-02", "B2"))).toByteArray())
                z.closeEntry()
            }
        }.toByteArray()

        val s = CamtParser.parse(zip)

        assertEquals(listOf(iban), s.accounts)
        assertEquals(2, s.bookings.size)
        assertEquals("CAMT.052/CAMT.053", s.formatDetail)
        assertEquals("CAMT.052/CAMT.053 XML", s.formatLabel)
    }

    @Test
    fun `csv statements keep the plain csv label`() {
        val s = CsvStatementParser.parse("Buchungstag;Betrag;Verwendungszweck\n03.10.2026;-1,00;Kaffee\n".toByteArray())

        assertEquals(StatementFormat.CSV, s.format)
        assertNull(s.formatDetail)
        assertEquals("CSV", s.formatLabel)
    }
}

class CamtFormatLabelImportAuditTest {
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

    @Test
    fun `importing a camt-052 report writes the message type into the audit log`() = runBlocking {
        val xml = File(tmp, "report.xml").apply {
            writeText(
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <Document xmlns="urn:iso:std:iso:20022:tech:xsd:camt.052.001.08">
                  <BkToCstmrAcctRpt><Rpt>
                    <Acct><Id><IBAN>DE89370400440532013000</IBAN></Id></Acct>
                    <Ntry>
                      <Amt Ccy="EUR">10.00</Amt><CdtDbtInd>DBIT</CdtDbtInd><Sts>BOOK</Sts>
                      <BookgDt><Dt>2026-10-01</Dt></BookgDt><ValDt><Dt>2026-10-01</Dt></ValDt>
                      <AcctSvcrRef>R1</AcctSvcrRef>
                      <NtryDtls><TxDtls><RltdPties><Cdtr><Nm>Partner</Nm></Cdtr></RltdPties></TxDtls></NtryDtls>
                    </Ntry>
                  </Rpt></BkToCstmrAcctRpt>
                </Document>
                """.trimIndent(),
            )
        }
        assertEquals(1, service.import(account, service.read(xml)).imported)

        val audit = AuditLogRepositoryImpl().findByAction("IMPORT_STATEMENT")
        assertTrue(audit.any { it.details!!.startsWith("CAMT.052 XML into") }, audit.map { it.details }.toString())
    }
}
