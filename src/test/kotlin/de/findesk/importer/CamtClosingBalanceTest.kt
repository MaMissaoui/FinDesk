package de.findesk.importer

import de.findesk.TestData
import de.findesk.TestDatabase
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.File
import java.math.BigDecimal
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * The closing balance from CAMT reports: reconciliation against the file's entries, the Banking4 pending-entry
 * case, the report choice across a ZIP, and the service wiring. All data is synthetic.
 */
class CamtClosingBalanceTest {
    private val iban = "DE89370400440532013000"

    private fun balance(code: String, amount: String, date: String = "2026-02-28", ccy: String = "EUR", sign: String = "CRDT") = "<Bal><Tp><CdOrPrtry><Cd>$code</Cd></CdOrPrtry></Tp>" +
        "<Amt Ccy=\"$ccy\">$amount</Amt>" +
        "<CdtDbtInd>$sign</CdtDbtInd>" +
        (if (date.isEmpty()) "" else "<Dt><Dt>$date</Dt></Dt>") +
        "</Bal>"

    private fun entry(amount: String, sign: String, status: String, date: String = "2026-02-05", ref: String) = "<Ntry>" +
        "<Amt Ccy=\"EUR\">$amount</Amt><CdtDbtInd>$sign</CdtDbtInd>" +
        (if (status.isEmpty()) "" else "<Sts>$status</Sts>") +
        "<BookgDt><Dt>$date</Dt></BookgDt><ValDt><Dt>$date</Dt></ValDt>" +
        "<AcctSvcrRef>$ref</AcctSvcrRef>" +
        "<NtryDtls><TxDtls><RltdPties><Cdtr><Nm>Café</Nm></Cdtr></RltdPties></TxDtls></NtryDtls>" +
        "</Ntry>"

    private fun debitedEntry(amount: String, date: String, ref: String) = "<Ntry>" +
        "<Amt Ccy=\"EUR\">$amount</Amt><CdtDbtInd>DBIT</CdtDbtInd><Sts>BOOK</Sts>" +
        "<BookgDt><Dt>$date</Dt></BookgDt><ValDt><Dt>$date</Dt></ValDt>" +
        "<AcctSvcrRef>$ref</AcctSvcrRef>" +
        "<NtryDtls><TxDtls><RltdPties><Cdtr><Nm>Café</Nm></Cdtr></RltdPties></TxDtls></NtryDtls>" +
        "</Ntry>"

    private fun camt(iban: String, vararg parts: String) = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
        "<Document xmlns=\"urn:iso:std:iso:20022:tech:xsd:camt.053.001.02\">\n" +
        "<BkToCstmrStmt><Stmt>\n<Acct><Id><IBAN>$iban</IBAN></Id></Acct>\n" +
        parts.joinToString("\n") + "\n" +
        "</Stmt></BkToCstmrStmt>\n</Document>"

    private fun zip(vararg xmls: String): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            xmls.forEachIndexed { i, xml ->
                z.putNextEntry(ZipEntry("statement$i.xml"))
                z.write(xml.toByteArray())
                z.closeEntry()
            }
        }
        return out.toByteArray()
    }

    @Test
    fun `opening plus booked entries matches the closing balance`() {
        val xml = camt(iban, balance("OPBD", "100.00"), balance("CLBD", "80.00"), debitedEntry("30.00", "2026-02-05", "R1"), entry("10.00", "CRDT", "BOOK", ref = "R2"))

        val s = CamtParser.parse(xml.toByteArray())

        assertEquals(2, s.bookings.size)
        assertEquals(BigDecimal("80.00"), s.closingBalance!!.amount)
        assertEquals(0, s.closingBalance!!.pendingExcluded)
        assertEquals("EUR", s.closingBalance!!.currency)
        assertEquals(java.time.LocalDate.of(2026, 2, 28), s.closingBalance!!.date)
    }

    @Test
    fun `a pending entry folded into the closing balance is taken back out`() {
        val xml = camt(iban, balance("OPBD", "100.00"), balance("CLBD", "60.00"), debitedEntry("30.00", "2026-02-05", "R1"), entry("10.00", "CRDT", "BOOK", ref = "R2"), entry("20.00", "DBIT", "PDNG", date = "", ref = "R3"))

        val s = CamtParser.parse(xml.toByteArray())

        assertEquals(2, s.bookings.size, "the pending entry is not a booking")
        assertEquals(BigDecimal("80.00"), s.closingBalance!!.amount)
        assertEquals(1, s.closingBalance!!.pendingExcluded)
    }

    @Test
    fun `a closing balance matching neither sum is rejected`() {
        val xml = camt(iban, balance("OPBD", "100.00"), balance("CLBD", "55.00"), debitedEntry("30.00", "2026-02-05", "R1"), entry("10.00", "CRDT", "BOOK", ref = "R2"), entry("20.00", "DBIT", "PDNG", date = "", ref = "R3"))

        assertNull(CamtParser.parse(xml.toByteArray()).closingBalance)
    }

    @Test
    fun `debit balances are negated`() {
        val xml = camt(iban, balance("OPBD", "50.00", sign = "DBIT"), balance("CLBD", "75.00", sign = "DBIT"), debitedEntry("25.00", "2026-02-05", "R1"))

        val s = CamtParser.parse(xml.toByteArray())

        assertEquals(BigDecimal("-75.00"), s.closingBalance!!.amount)
        assertEquals(0, s.closingBalance!!.pendingExcluded)
    }

    @Test
    fun `PRCD and ITBD balance types work like OPBD and CLBD`() {
        val xml = camt(iban, balance("PRCD", "100.00"), balance("ITBD", "80.00"), debitedEntry("30.00", "2026-02-05", "R1"), entry("10.00", "CRDT", "BOOK", ref = "R2"))

        val s = CamtParser.parse(xml.toByteArray())

        assertEquals(BigDecimal("80.00"), s.closingBalance!!.amount)
    }

    @Test
    fun `only available balances, a missing opening balance or a dateless closing balance yield none`() {
        val onlyAvailable = camt(iban, balance("OPAV", "100.00"), balance("CLAV", "80.00"), debitedEntry("30.00", "2026-02-05", "R1"))
        assertNull(CamtParser.parse(onlyAvailable.toByteArray()).closingBalance)

        val noOpening = camt(iban, balance("CLBD", "50.00"), debitedEntry("30.00", "2026-02-05", "R1"))
        assertNull(CamtParser.parse(noOpening.toByteArray()).closingBalance)

        val noClosingDate = camt(iban, balance("OPBD", "100.00"), balance("CLBD", "50.00", date = ""), debitedEntry("30.00", "2026-02-05", "R1"))
        assertNull(CamtParser.parse(noClosingDate.toByteArray()).closingBalance)
    }

    @Test
    fun `a skipped entry disables the closing balance`() {
        val xml = camt(iban, balance("OPBD", "100.00"), balance("CLBD", "50.00"), "<Ntry><Amt Ccy=\"EUR\">abc</Amt><CdtDbtInd>DBIT</CdtDbtInd><BookgDt><Dt>2026-02-05</Dt></BookgDt></Ntry>")

        val s = CamtParser.parse(xml.toByteArray())

        assertEquals(1, s.skipped.size)
        assertNull(s.closingBalance)
    }

    @Test
    fun `a closing currency differing from the entry currencies is rejected`() {
        val xml = camt(iban, balance("OPBD", "100.00", ccy = "USD"), balance("CLBD", "50.00", ccy = "USD"), debitedEntry("30.00", "2026-02-05", "R1"))

        assertNull(CamtParser.parse(xml.toByteArray()).closingBalance)
    }

    @Test
    fun `the zip picks the report with the latest closing date`() {
        val january = camt(iban, balance("OPBD", "100.00", date = "2026-01-31"), balance("CLBD", "70.00", date = "2026-01-31"), debitedEntry("30.00", "2026-01-15", "R1"))
        val february = camt(iban, balance("OPBD", "70.00", date = "2026-02-28"), balance("CLBD", "80.00", date = "2026-02-28"), entry("10.00", "CRDT", "BOOK", date = "2026-02-10", ref = "R2"))

        assertEquals(BigDecimal("80.00"), CamtParser.parse(zip(january, february)).closingBalance!!.amount)
    }

    @Test
    fun `a latest report that does not reconcile leaves no fallback to an older one`() {
        val january = camt(iban, balance("OPBD", "100.00", date = "2026-01-31"), balance("CLBD", "70.00", date = "2026-01-31"), debitedEntry("30.00", "2026-01-15", "R1"))
        val february = camt(iban, balance("OPBD", "70.00", date = "2026-02-28"), balance("CLBD", "99.00", date = "2026-02-28"), entry("10.00", "CRDT", "BOOK", date = "2026-02-10", ref = "R2"))

        assertNull(CamtParser.parse(zip(january, february)).closingBalance)
    }

    @Test
    fun `two reports sharing the latest date with different closing balances yield none`() {
        val first = camt(iban, balance("OPBD", "100.00", date = "2026-02-28"), balance("CLBD", "80.00", date = "2026-02-28"), debitedEntry("30.00", "2026-02-05", ref = "R1"))
        val second = camt(iban, balance("OPBD", "110.00", date = "2026-02-28"), balance("CLBD", "95.00", date = "2026-02-28"), debitedEntry("15.00", "2026-02-05", ref = "R2"))

        assertNull(CamtParser.parse(zip(first, second)).closingBalance)
    }

    @Test
    fun `a batch entry reconciles on the entry amount, not the split details`() {
        val batch = "<Ntry>" +
            "<Amt Ccy=\"EUR\">10.00</Amt><CdtDbtInd>CRDT</CdtDbtInd><Sts>BOOK</Sts>" +
            "<BookgDt><Dt>2026-02-05</Dt></BookgDt><ValDt><Dt>2026-02-05</Dt></ValDt>" +
            "<AcctSvcrRef>R1</AcctSvcrRef>" +
            "<NtryDtls><TxDtls><Amt Ccy=\"EUR\">6.00</Amt><RltdPties><Cdtr><Nm>A</Nm></Cdtr></RltdPties></TxDtls>" +
            "<TxDtls><Amt Ccy=\"EUR\">4.00</Amt><RltdPties><Cdtr><Nm>B</Nm></Cdtr></RltdPties></TxDtls></NtryDtls>" +
            "</Ntry>"

        val xml = camt(iban, balance("OPBD", "100.00"), balance("CLBD", "110.00"), batch)

        val s = CamtParser.parse(xml.toByteArray())

        assertEquals(2, s.bookings.size)
        assertEquals(BigDecimal("110.00"), s.closingBalance!!.amount)
        assertEquals(0, s.closingBalance!!.pendingExcluded)
    }

    @Test
    fun `a malformed balance is no parse failure and keeps the bookings`() {
        val xml = camt(iban, balance("OPBD", "100.00"), balance("CLBD", "abc"), debitedEntry("30.00", "2026-02-05", "R1"))

        val s = CamtParser.parse(xml.toByteArray())

        assertEquals(1, s.bookings.size)
        assertNull(s.closingBalance)
    }
}

class CamtClosingBalanceImportTest {
    @TempDir
    lateinit var tmp: File

    private lateinit var data: TestData
    private val service = StatementImportService()
    private var account = 0

    @BeforeEach
    fun setup() {
        TestDatabase.init()
        data = TestData()
        account = data.account(balance = "0")
    }

    private fun camtFile(name: String): File = File(tmp, name).apply {
        writeText(
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <Document xmlns="urn:iso:std:iso:20022:tech:xsd:camt.053.001.02">
              <BkToCstmrStmt><Stmt>
                <Acct><Id><IBAN>DE89370400440532013000</IBAN></Id></Acct>
                <Bal><Tp><CdOrPrtry><Cd>OPBD</Cd></CdOrPrtry></Tp><Amt Ccy="EUR">110.00</Amt><CdtDbtInd>CRDT</CdtDbtInd><Dt><Dt>2026-02-01</Dt></Dt></Bal>
                <Bal><Tp><CdOrPrtry><Cd>CLBD</Cd></CdOrPrtry></Tp><Amt Ccy="EUR">80.00</Amt><CdtDbtInd>CRDT</CdtDbtInd><Dt><Dt>2026-02-28</Dt></Dt></Bal>
                <Ntry>
                  <Amt Ccy="EUR">30.00</Amt><CdtDbtInd>DBIT</CdtDbtInd><Sts>BOOK</Sts>
                  <BookgDt><Dt>2026-02-05</Dt></BookgDt><ValDt><Dt>2026-02-05</Dt></ValDt>
                  <AcctSvcrRef>R1</AcctSvcrRef>
                  <NtryDtls><TxDtls><RltdPties><Cdtr><Nm>Cafe</Nm></Cdtr></RltdPties></TxDtls></NtryDtls>
                </Ntry>
              </Stmt></BkToCstmrStmt>
            </Document>
            """.trimIndent(),
        )
    }

    @Test
    fun `the account balance is set from the closing balance`() = runBlocking {
        val statement = service.read(camtFile("statement.xml"))

        val report = service.import(account, statement)

        assertEquals(1, report.imported)
        assertEquals(BigDecimal("80.00"), report.balanceUpdated)
        assertEquals(BigDecimal("80.00"), data.accountRepo.findAllItems().single { it.id == account }.balance)
    }

    @Test
    fun `without updateBalance the account balance is untouched`() = runBlocking {
        val statement = service.read(camtFile("statement.xml"))

        val report = service.import(account, statement, updateBalance = false)

        assertNull(report.balanceUpdated)
        assertEquals(0, accountBalance(account).compareTo(BigDecimal.ZERO))
    }

    @Test
    fun `a booking newer than the closing date blocks the balance update`() = runBlocking {
        data.txn(account, java.time.LocalDate.of(2026, 3, 1), "-5.00", partner = "Later")
        val statement = service.read(camtFile("statement.xml"))

        assertFalse(service.balanceUpdateAllowed(account, statement))

        val report = service.import(account, statement)
        assertNull(report.balanceUpdated)
        assertEquals(0, accountBalance(account).compareTo(BigDecimal.ZERO))
    }

    @Test
    fun `a closing currency differing from the account currency leaves the balance untouched`() = runBlocking {
        val currencyAccount = data.account(iban = "DE23700202700012345678", balance = "0", currency = "USD")
        val statement = service.read(camtFile("statement.xml"))

        val report = service.import(currencyAccount, statement)

        assertNull(report.balanceUpdated)
        assertEquals(0, accountBalance(currencyAccount).compareTo(BigDecimal.ZERO))
    }

    @Test
    fun `a csv with running balances still sets the balance from the newest booking`() = runBlocking {
        val csv = File(tmp, "statement.csv").apply {
            writeText(
                "IBAN;DE89 3704 0044 0532 0130 00\n\n" +
                    "Buchung;Valuta;Auftraggeber/Empfänger;Buchungstext;Verwendungszweck;Saldo;Währung;Betrag;Währung\n" +
                    "02.10.2026;02.10.2026;AMAZON;Lastschrift;Monitor;910,01;EUR;-89,99;EUR\n" +
                    "01.10.2026;01.10.2026;Employer;Gehalt;Gehalt;1.000,00;EUR;1.000,00;EUR\n",
            )
        }
        val statement = service.read(csv)

        val report = service.import(account, statement)

        assertEquals(BigDecimal("910.01"), report.balanceUpdated)
        assertEquals(BigDecimal("910.01"), accountBalance(account))
    }

    private fun accountBalance(id: Int): BigDecimal = data.accountRepo.findAllItems().single { it.id == id }.balance

    @Test
    fun `the closing balance is allowed when it is at least as recent as the account's newest booking`() = runBlocking {
        data.txn(account, java.time.LocalDate.of(2026, 2, 20), "-5.00", partner = "Earlier")
        val statement = service.read(camtFile("statement.xml"))

        assertTrue(service.balanceUpdateAllowed(account, statement))
    }
}
