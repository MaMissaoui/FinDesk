package de.findesk.importer

import de.findesk.TestData
import de.findesk.TestDatabase
import de.findesk.model.TransactionFilter
import de.findesk.util.RunningBalance
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.File
import java.math.BigDecimal
import java.nio.charset.Charset
import java.time.LocalDate
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class CsvStatementParserTest {
    private val cp1252 = Charset.forName("windows-1252")

    @Test
    fun `sparkasse csv-camt export in windows-1252 with pending rows`() {
        val csv = """
            "Auftragskonto";"Buchungstag";"Valutadatum";"Buchungstext";"Verwendungszweck";"Glaeubiger ID";"Mandatsreferenz";"Kundenreferenz (End-to-End)";"Sammlerreferenz";"Lastschrift Ursprungsbetrag";"Auslagenersatz Ruecklastschrift";"Beguenstigter/Zahlungspflichtiger";"Kontonummer/IBAN";"BIC (SWIFT-Code)";"Betrag";"Waehrung";"Info"
            "DE89370400440532013000";"03.10.26";"03.10.26";"KARTENZAHLUNG";"REWE SAGT DANKE 1234";"";"";"";"";"";"";"REWE Markt GmbH";"DE44500105175407324931";"COLSDE33XXX";"-45,67";"EUR";"Umsatz gebucht"
            "DE89370400440532013000";"01.10.26";"01.10.26";"GUTSCHR. UEBERWEISUNG";"Gehalt Oktober";"";"";"";"";"";"";"Müller & Söhne GmbH";"";"";"3.200,00";"EUR";"Umsatz gebucht"
            "DE89370400440532013000";"04.10.26";"04.10.26";"KARTENZAHLUNG";"Tankstelle";"";"";"";"";"";"";"Aral";"";"";"-60,00";"EUR";"Umsatz vorgemerkt"
        """.trimIndent()

        val s = CsvStatementParser.parse(csv.toByteArray(cp1252))

        assertEquals("windows-1252", s.csv!!.charsetName)
        assertEquals(';', s.csv!!.delimiter)
        assertEquals(2, s.bookings.size)
        assertEquals(1, s.pendingSkipped)
        val rewe = s.bookings[0]
        assertEquals(LocalDate.of(2026, 10, 3), rewe.date)
        assertEquals(BigDecimal("-45.67"), rewe.amount)
        assertEquals("REWE Markt GmbH", rewe.partnerName)
        assertEquals("DE44500105175407324931", rewe.partnerIban)
        assertEquals("REWE SAGT DANKE 1234", rewe.purpose)
        assertEquals("Müller & Söhne GmbH", s.bookings[1].partnerName, "umlauts survive windows-1252")
        assertEquals(BigDecimal("3200.00"), s.bookings[1].amount)
    }

    @Test
    fun `ing export with preamble, account iban and running balance`() {
        val csv = """
            Umsatzanzeige;Datei erstellt am: 04.10.2026 10:00
            ;
            IBAN;DE89 3704 0044 0532 0130 00
            Kontoname;Girokonto
            Bank;ING
            Kunde;Max Mustermann
            Zeitraum;01.09.2026 - 04.10.2026
            Saldo;5.234,56;EUR

            Sortierung;Datum absteigend

            Buchung;Valuta;Auftraggeber/Empfänger;Buchungstext;Verwendungszweck;Saldo;Währung;Betrag;Währung
            02.10.2026;02.10.2026;AMAZON EU;Lastschrift;"Bestellung 303-123; Monitor";5.234,56;EUR;-89,99;EUR
            01.10.2026;01.10.2026;Employer GmbH;Gehalt/Rente;Gehalt;5.324,55;EUR;3.200,00;EUR
        """.trimIndent()

        val s = CsvStatementParser.parse(csv.toByteArray(Charsets.UTF_8))

        assertEquals("DE89370400440532013000", s.accountIban)
        assertEquals(2, s.bookings.size)
        assertEquals("AMAZON EU", s.bookings[0].partnerName)
        assertEquals("Bestellung 303-123; Monitor", s.bookings[0].purpose, "quoted delimiter")
        assertEquals(BigDecimal("5234.56"), s.bookings[0].balanceAfter)
        assertEquals(BigDecimal("-89.99"), s.bookings[0].amount)
    }

    @Test
    fun `dkb export uses payer for incoming and payee for outgoing`() {
        val csv = """
            "Konto";"Girokonto DE89370400440532013000"
            ""
            "Buchungsdatum";"Wertstellung";"Status";"Zahlungspflichtige*r";"Zahlungsempfänger*in";"Verwendungszweck";"Umsatztyp";"IBAN";"Betrag (€)";"Gläubiger-ID";"Mandatsreferenz";"Kundenreferenz"
            "02.10.26";"02.10.26";"Gebucht";"Max Mustermann";"Stadtwerke";"Abschlag Strom";"Ausgang";"DE44500105175407324931";"-80,00";"";"";""
            "01.10.26";"01.10.26";"Gebucht";"Erika Musterfrau";"Max Mustermann";"Miete Anteil";"Eingang";"DE44500105175407324931";"400,00";"";"";""
            "05.10.26";"05.10.26";"Vorgemerkt";"Max Mustermann";"Kino";"Tickets";"Ausgang";"";"-20,00";"";"";""
        """.trimIndent()

        val s = CsvStatementParser.parse(csv.toByteArray(Charsets.UTF_8))

        assertEquals(listOf("Stadtwerke", "Erika Musterfrau"), s.bookings.map { it.partnerName })
        assertEquals("DE89370400440532013000", s.accountIban, "IBAN after a label in the preamble")
        assertEquals(1, s.pendingSkipped)
        assertEquals(BigDecimal("-80.00"), s.bookings[0].amount)
    }

    @Test
    fun `english export with debit and credit columns and multiline purpose`() {
        val csv = "Date,Description,Payee,Debit,Credit,Balance\r\n" +
            "2026-09-30,\"Card payment\nLondon\",Pret A Manger,4.50,,100.00\r\n" +
            "2026-09-29,Salary,ACME Ltd,,\"1,500.00\",104.50\r\n"

        val s = CsvStatementParser.parse(csv.toByteArray())

        assertEquals(listOf(BigDecimal("-4.50"), BigDecimal("1500.00")), s.bookings.map { it.amount })
        assertEquals("Card payment London", s.bookings[0].purpose)
        assertEquals("Pret A Manger", s.bookings[0].partnerName)
    }

    @Test
    fun `unknown layouts report what is missing and accept a manual mapping`() {
        val csv = "When;What;How much\n03.10.2026;Coffee;-3,20\n"
        val detected = CsvStatementParser.parse(csv.toByteArray())
        assertTrue(detected.bookings.isEmpty())
        assertFalse(detected.csv!!.mapping.isComplete)

        val manual = CsvStatementParser.parse(csv.toByteArray(), CsvMapping(date = 0, purpose = 1, amount = 2))
        assertEquals(BigDecimal("-3.20"), manual.bookings.single().amount)
    }

    @Test
    fun `bad rows are skipped with a reason`() {
        val csv = "Buchungstag;Betrag;Verwendungszweck\n03.10.2026;-1,00;ok\nkein Datum;-2,00;bad\n04.10.2026;abc;bad\n"
        val s = CsvStatementParser.parse(csv.toByteArray())
        assertEquals(1, s.bookings.size)
        assertEquals(2, s.skipped.size)
        assertTrue(s.skipped[0].startsWith("Line 3"), s.skipped[0])
    }
}

class CamtParserTest {
    private fun camt(entries: String) = """
        <?xml version="1.0" encoding="UTF-8"?>
        <Document xmlns="urn:iso:std:iso:20022:tech:xsd:camt.053.001.02">
          <BkToCstmrStmt><Stmt>
            <Acct><Id><IBAN>DE89370400440532013000</IBAN></Id></Acct>
            $entries
          </Stmt></BkToCstmrStmt>
        </Document>
    """.trimIndent()

    private val debitEntry = """
        <Ntry>
          <Amt Ccy="EUR">45.67</Amt><CdtDbtInd>DBIT</CdtDbtInd><Sts>BOOK</Sts>
          <BookgDt><Dt>2026-10-03</Dt></BookgDt><ValDt><Dt>2026-10-03</Dt></ValDt>
          <AcctSvcrRef>REF-1</AcctSvcrRef>
          <NtryDtls><TxDtls>
            <RltdPties><Cdtr><Nm>REWE Markt GmbH</Nm></Cdtr><CdtrAcct><Id><IBAN>DE44500105175407324931</IBAN></Id></CdtrAcct></RltdPties>
            <RmtInf><Ustrd>REWE SAGT DANKE</Ustrd><Ustrd>1234</Ustrd></RmtInf>
          </TxDtls></NtryDtls>
        </Ntry>
    """

    @Test
    fun `reads booked entries with parties, purpose and reference`() {
        val credit = """
            <Ntry><Amt Ccy="EUR">3200.00</Amt><CdtDbtInd>CRDT</CdtDbtInd><Sts>BOOK</Sts><BookgDt><Dt>2026-10-01</Dt></BookgDt>
              <NtryDtls><TxDtls><RltdPties><Dbtr><Nm>Employer GmbH</Nm></Dbtr><Cdtr><Nm>Me</Nm></Cdtr></RltdPties><RmtInf><Ustrd>Gehalt</Ustrd></RmtInf></TxDtls></NtryDtls></Ntry>
            <Ntry><Amt Ccy="EUR">60.00</Amt><CdtDbtInd>DBIT</CdtDbtInd><Sts>PDNG</Sts><BookgDt><Dt>2026-10-04</Dt></BookgDt></Ntry>
        """
        val s = CamtParser.parse(camt(debitEntry + credit).toByteArray())

        assertEquals("DE89370400440532013000", s.accountIban)
        assertEquals(2, s.bookings.size, "pending entry skipped")
        val rewe = s.bookings[0]
        assertEquals(BigDecimal("-45.67"), rewe.amount)
        assertEquals("REWE Markt GmbH", rewe.partnerName)
        assertEquals("DE44500105175407324931", rewe.partnerIban)
        assertEquals("REWE SAGT DANKE 1234", rewe.purpose)
        assertEquals("REF-1", rewe.bankReference)
        assertEquals("Employer GmbH", s.bookings[1].partnerName, "debtor is the partner for a credit")
    }

    @Test
    fun `batch bookings are split into their transactions`() {
        val batch = """
            <Ntry><Amt Ccy="EUR">30.00</Amt><CdtDbtInd>DBIT</CdtDbtInd><Sts>BOOK</Sts><BookgDt><Dt>2026-10-02</Dt></BookgDt><AcctSvcrRef>B1</AcctSvcrRef>
              <NtryDtls>
                <TxDtls><Amt Ccy="EUR">10.00</Amt><RltdPties><Cdtr><Nm>A</Nm></Cdtr></RltdPties></TxDtls>
                <TxDtls><Amt Ccy="EUR">20.00</Amt><RltdPties><Cdtr><Nm>B</Nm></Cdtr></RltdPties></TxDtls>
              </NtryDtls></Ntry>
        """
        val s = CamtParser.parse(camt(batch).toByteArray())
        assertEquals(listOf(BigDecimal("-10.00"), BigDecimal("-20.00")), s.bookings.map { it.amount })
        assertEquals(listOf("B1/0", "B1/1"), s.bookings.map { it.bankReference })
    }

    @Test
    fun `zip of statements and xxe protection`() {
        val zip = ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { z ->
                z.putNextEntry(ZipEntry("2026-10.xml"))
                z.write(camt(debitEntry).toByteArray())
                z.closeEntry()
                z.putNextEntry(ZipEntry("readme.txt"))
                z.write("ignore".toByteArray())
                z.closeEntry()
            }
        }.toByteArray()
        assertTrue(CamtParser.looksLikeCamt(zip))
        assertEquals(1, CamtParser.parse(zip).bookings.size)

        val xxe = """<?xml version="1.0"?><!DOCTYPE d [<!ENTITY x SYSTEM "file:///etc/passwd">]><Document><BkToCstmrStmt>&x;</BkToCstmrStmt></Document>"""
        assertThrows(Exception::class.java) { CamtParser.parse(xxe.toByteArray()) }
    }
}

class StatementImportServiceTest {
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

    @Test
    fun `re-importing and overlapping exports skip known bookings but keep genuine repeats`() = runBlocking {
        val food = data.category("Food")
        data.ruleRepo.create("bäcker|kaffee", food)
        val first = file(
            "a.csv",
            "Buchungstag;Betrag;Beguenstigter/Zahlungspflichtiger;Verwendungszweck\n" +
                "03.10.2026;-4,50;Bäcker;Kaffee\n03.10.2026;-4,50;Bäcker;Kaffee\n02.10.2026;-10,00;Kiosk;Zeitung\n",
        )
        val r1 = service.import(account, service.read(first))
        assertEquals(3, r1.imported, "two identical coffees on one day are both real")
        assertEquals(2, r1.categorized)

        val again = service.import(account, service.read(first))
        assertEquals(0, again.imported)
        assertEquals(3, again.duplicates)

        val overlap = file(
            "b.csv",
            "Buchungstag;Betrag;Beguenstigter/Zahlungspflichtiger;Verwendungszweck\n" +
                "04.10.2026;-2,00;Kiosk;Wasser\n03.10.2026;-4,50;Bäcker;Kaffee\n03.10.2026;-4,50;Bäcker;Kaffee\n",
        )
        val r3 = service.import(account, service.read(overlap))
        assertEquals(1, r3.imported)
        assertEquals(2, r3.duplicates)
        assertEquals(4, data.transactionRepo.findItems().size)
    }

    @Test
    fun `statement iban selects the account and balance is taken from the newest booking`() = runBlocking {
        val ing = file(
            "ing.csv",
            "IBAN;DE89 3704 0044 0532 0130 00\n\nBuchung;Valuta;Auftraggeber/Empfänger;Buchungstext;Verwendungszweck;Saldo;Währung;Betrag;Währung\n" +
                "02.10.2026;02.10.2026;AMAZON;Lastschrift;Monitor;910,01;EUR;-89,99;EUR\n" +
                "01.10.2026;01.10.2026;Employer;Gehalt;Gehalt;1.000,00;EUR;1.000,00;EUR\n",
        )
        val statement = service.read(ing)
        assertEquals(account, service.matchAccount(statement))

        val report = service.import(account, statement)
        assertEquals(BigDecimal("910.01"), report.balanceUpdated)
        assertEquals(BigDecimal("910.01"), data.accountRepo.findAllItems().single().balance)
        assertTrue(report.summary().contains("Balance set to €910.01"))
    }

    @Test
    fun `newest-first files with running balances are inserted chronologically`() = runBlocking {
        val newestFirst = file(
            "newest.csv",
            "IBAN;DE89 3704 0044 0532 0130 00\n\nBuchung;Valuta;Auftraggeber/Empfänger;Buchungstext;Verwendungszweck;Saldo;Währung;Betrag;Währung\n" +
                "02.10.2026;02.10.2026;AMAZON;Lastschrift;Monitor;910,01;EUR;-89,99;EUR\n" +
                "01.10.2026;01.10.2026;Employer;Gehalt;Gehalt;1.000,00;EUR;1.000,00;EUR\n",
        )
        val report = service.import(account, service.read(newestFirst))

        assertEquals(2, report.imported)
        assertEquals(BigDecimal("910.01"), data.accountRepo.findAllItems().single { it.id == account }.balance)
        assertBalanceChainHasNoMismatch()
    }

    @Test
    fun `oldest-first files still import the same way`() = runBlocking {
        val oldestFirst = file(
            "oldest.csv",
            "IBAN;DE89 3704 0044 0532 0130 00\n\nBuchung;Valuta;Auftraggeber/Empfänger;Buchungstext;Verwendungszweck;Saldo;Währung;Betrag;Währung\n" +
                "01.10.2026;01.10.2026;Employer;Gehalt;Gehalt;1.000,00;EUR;1.000,00;EUR\n" +
                "02.10.2026;02.10.2026;AMAZON;Lastschrift;Monitor;910,01;EUR;-89,99;EUR\n",
        )
        val report = service.import(account, service.read(oldestFirst))

        assertEquals(2, report.imported)
        assertEquals(BigDecimal("910.01"), data.accountRepo.findAllItems().single { it.id == account }.balance)
        assertBalanceChainHasNoMismatch()
    }

    @Test
    fun `a newest-first file covering one day is recognised by its running balances`() = runBlocking {
        // Same date on every row, so only the balances tell the order: 1000 → 990 (−10) → 985 (−5), newest first
        val oneDay = file(
            "one-day.csv",
            "IBAN;DE89 3704 0044 0532 0130 00\n\nBuchung;Valuta;Auftraggeber/Empfänger;Buchungstext;Verwendungszweck;Saldo;Währung;Betrag;Währung\n" +
                "02.10.2026;02.10.2026;Kiosk;Lastschrift;Zeitung;985,00;EUR;-5,00;EUR\n" +
                "02.10.2026;02.10.2026;Bäcker;Lastschrift;Brot;990,00;EUR;-10,00;EUR\n" +
                "02.10.2026;02.10.2026;Employer;Gehalt;Bonus;1.000,00;EUR;1.000,00;EUR\n",
        )
        val report = service.import(account, service.read(oneDay))

        assertEquals(3, report.imported)
        assertEquals(BigDecimal("985.00"), data.accountRepo.findAllItems().single { it.id == account }.balance)
        assertBalanceChainHasNoMismatch()
    }

    /** The stored balances must chain back from the account balance without any gap. */
    private fun assertBalanceChainHasNoMismatch() {
        val balance = data.accountRepo.findAllItems().single { it.id == account }.balance
        val entries = RunningBalance.compute(balance, data.transactionRepo.findItems(TransactionFilter(accountId = account)))
        assertTrue(entries.values.all { it.bankDifference == null }, entries.toString())
    }

    @Test
    fun `camt files are detected by content`() = runBlocking {
        val xml = file(
            "export.dat",
            """<?xml version="1.0"?><Document xmlns="urn:iso:std:iso:20022:tech:xsd:camt.053.001.08"><BkToCstmrStmt><Stmt><Acct><Id><IBAN>DE89370400440532013000</IBAN></Id></Acct>""" +
                """<Ntry><Amt Ccy="EUR">5.00</Amt><CdtDbtInd>DBIT</CdtDbtInd><Sts><Cd>BOOK</Cd></Sts><BookgDt><Dt>2026-10-01</Dt></BookgDt><AcctSvcrRef>X9</AcctSvcrRef></Ntry>""" +
                """</Stmt></BkToCstmrStmt></Document>""",
        )
        val s = service.read(xml)
        assertEquals(StatementFormat.CAMT053, s.format)
        assertEquals(1, service.import(account, s).imported)
        assertEquals(1, service.import(account, service.read(xml)).duplicates)
    }
}
