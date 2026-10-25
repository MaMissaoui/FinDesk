package de.findesk.viewmodel

import de.findesk.TestData
import de.findesk.TestDatabase
import de.findesk.i18n.AppStrings
import de.findesk.i18n.StringKey
import de.findesk.importer.CsvMapping
import de.findesk.importer.ParsedStatement
import de.findesk.importer.StatementImportService
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.math.BigDecimal
import java.time.LocalDate

/**
 * Statement imports that span several selected files at once: preview ordering, per-file import and dedup,
 * the balance from the newest file only, failures and limits. All data is synthetic.
 */
class MultiFileImportTest {
    @TempDir
    lateinit var tmp: File

    private lateinit var data: TestData
    private val iban = "DE44500105175407324931"
    private var accountId = 0

    @BeforeEach
    fun setup() {
        TestDatabase.init()
        data = TestData()
        accountId = data.account(iban = iban, balance = "0")
    }

    private val header = "Buchung;Valuta;Auftraggeber/Empfänger;Buchungstext;Verwendungszweck;Saldo;Währung;Betrag;Währung\n"

    private fun row(date: String, partner: String, purpose: String, saldo: String, amount: String) = "$date;$date;$partner;Lastschrift;$purpose;$saldo;EUR;$amount;EUR\n"

    /** A standard-layout CSV file for [ibanLine] with the given data rows. */
    private fun csv(
        name: String,
        ibanLine: String? = iban,
        rows: List<String> = emptyList(),
    ): File = File(tmp, name).apply {
        writeText((ibanLine?.let { "IBAN;$it\n\n" } ?: "") + header + rows.joinToString(""))
    }

    private fun stored(vararg partners: String): Int = data.transactionRepo.findItems().count { it.partnerName in partners.toSet() }

    @Test
    fun `two files are previewed in booking order and imported with the newest file's balance`() = runBlocking {
        val older = csv(
            "october.csv",
            rows = listOf(
                row("02.10.2025", "Stadtwerke", "Strom", "80,00", "-20,00"),
                row("15.10.2025", "Obststand", "Obst", "50,00", "-30,00"),
            ),
        )
        val newer = csv(
            "november.csv",
            rows = listOf(
                row("05.11.2025", "Arbeitgeber", "Gehalt", "150,00", "100,00"),
                row("20.11.2025", "Kino", "Tickets", "140,00", "-10,00"),
            ),
        )
        val vm = TransactionsViewModel(today = { LocalDate.of(2026, 3, 20) })
        // Selected newest first
        vm.openImport(listOf(newer, older)).join()

        val preview = vm.state.value.importPreview!!
        assertEquals(2, preview.entries.size)
        assertEquals(older, preview.entries[0].file, "imported oldest first")
        assertEquals(newer, preview.entries[1].file)
        assertEquals(newer, preview.newest.file, "the newest entry has the greatest latest booking date")
        assertEquals(4, preview.bookingCount)
        assertEquals(accountId, preview.accountId)
        assertTrue(preview.balanceUpdateAllowed)
        assertEquals(0, stored("Stadtwerke", "Kino"), "nothing is stored before confirming")

        vm.confirmImport(updateBalance = true).join()
        assertNull(vm.state.value.importPreview)
        assertEquals(4, stored("Stadtwerke", "Obststand", "Arbeitgeber", "Kino"), "every booking stored")
        // The balance comes from the newest file's last running balance, not the older file's 50
        assertEquals(BigDecimal("140.00"), data.accountRepo.findAllItems().first { it.id == accountId }.balance)
        assertTrue(vm.state.value.message!!.contains(AppStrings.format(StringKey.ImportSummaryFiles, 4, 2)), vm.state.value.message)
        vm.close()
    }

    @Test
    fun `importing the same two files again stores no duplicates`() = runBlocking {
        val older = csv("october.csv", rows = listOf(row("02.10.2025", "Stadtwerke", "Strom", "80,00", "-20,00")))
        val newer = csv("november.csv", rows = listOf(row("20.11.2025", "Kino", "Tickets", "140,00", "-10,00")))
        val vm = TransactionsViewModel(today = { LocalDate.of(2026, 3, 20) })
        vm.openImport(listOf(older, newer)).join()
        vm.confirmImport(updateBalance = true).join()
        assertEquals(2, stored("Stadtwerke", "Kino"))

        vm.openImport(listOf(newer, older)).join()
        vm.confirmImport(updateBalance = true).join()
        assertEquals(2, stored("Stadtwerke", "Kino"), "all bookings recognised, no duplicate rows")
        assertTrue(vm.state.value.message!!.contains(AppStrings.format(StringKey.ImportSummaryKnown, 2)), vm.state.value.message)
        vm.close()
    }

    @Test
    fun `the balance comes only from the newest file and only when updateBalance is true`() = runBlocking {
        val older = csv("october.csv", rows = listOf(row("02.10.2025", "Stadtwerke", "Strom", "50,00", "-20,00")))
        val newer = csv("november.csv", rows = listOf(row("20.11.2025", "Kino", "Tickets", "140,00", "-10,00")))
        // The account starts at 1000.00
        data.accountRepo.updateBalance(accountId, BigDecimal("1000.00"))
        val vm = TransactionsViewModel(today = { LocalDate.of(2026, 3, 20) })

        vm.openImport(listOf(older, newer)).join()
        vm.confirmImport(updateBalance = false).join()
        assertEquals(BigDecimal("1000.00"), data.accountRepo.findAllItems().first { it.id == accountId }.balance, "updateBalance = false leaves the balance unchanged")

        vm.openImport(listOf(newer, older)).join()
        vm.confirmImport(updateBalance = true).join()
        val balance = data.accountRepo.findAllItems().first { it.id == accountId }.balance
        assertEquals(BigDecimal("140.00"), balance, "the newer file's last running balance wins")
        assertFalse(balance.compareTo(BigDecimal("50.00")) == 0, "the older file never sets the balance")
        vm.close()
    }

    @Test
    fun `a good file next to an unreadable one still imports`() = runBlocking {
        val good = csv("good.csv", rows = listOf(row("02.10.2025", "Stadtwerke", "Strom", "80,00", "-20,00")))
        val ghost = File(tmp, "ghost.csv") // never created, so reading it fails
        val vm = TransactionsViewModel(today = { LocalDate.now() })
        vm.openImport(listOf(good, ghost)).join()

        val preview = vm.state.value.importPreview!!
        assertEquals(listOf(good, ghost), preview.files, "every selected file, in selection order")
        assertEquals(1, preview.entries.size)
        assertEquals(1, preview.failures.size)
        assertEquals("ghost.csv", preview.failures[0].fileName)
        assertEquals(1, preview.bookingCount)

        vm.confirmImport(updateBalance = true).join()
        assertEquals(1, stored("Stadtwerke"))
        assertNull(vm.state.value.error)
        vm.close()
    }

    @Test
    fun `all files unreadable sets an error and no preview`() = runBlocking {
        val vm = TransactionsViewModel(today = { LocalDate.now() })
        vm.openImport(listOf(File(tmp, "ghost1.csv"), File(tmp, "ghost2.csv"))).join()

        assertNull(vm.state.value.importPreview)
        assertEquals(
            AppStrings.format(
                StringKey.ErrorImportNoReadableFile,
                "ghost1.csv: File not found: ghost1.csv, ghost2.csv: File not found: ghost2.csv",
            ),
            vm.state.value.error,
        )
        vm.close()
    }

    @Test
    fun `files for different accounts are refused`() = runBlocking {
        data.account(iban = "DE02120300000000202051", balance = "0")
        val one = csv("one.csv", iban, rows = listOf(row("02.10.2025", "Stadtwerke", "Strom", "80,00", "-20,00")))
        val two = csv("two.csv", "DE02120300000000202051", rows = listOf(row("02.11.2025", "Kino", "Tickets", "90,00", "-10,00")))
        val vm = TransactionsViewModel(today = { LocalDate.of(2026, 3, 20) })
        vm.openImport(listOf(one, two)).join()

        assertNull(vm.state.value.importPreview, "no preview for different accounts")
        val error = vm.state.value.error!!
        assertTrue(error.contains("…4931") && error.contains("…2051"), error)
        assertEquals(0, stored("Stadtwerke", "Kino"), "nothing stored")
        vm.close()
    }

    @Test
    fun `too many files sets the limit error without reading any file`() = runBlocking {
        val files = (1..241).map { File(tmp, "missing-$it.csv") } // 241, above the limit of 240
        val vm = TransactionsViewModel(today = { LocalDate.now() })
        vm.openImport(files).join()

        assertNull(vm.state.value.importPreview)
        assertEquals(
            AppStrings.format(StringKey.ErrorImportTooManyFiles, StatementImportService.MAX_FILES),
            vm.state.value.error,
            "the limit message wins, before any file-not-found failure",
        )
        vm.close()
    }

    @Test
    fun `a card statement without an IBAN line picks the selected card account`() = runBlocking {
        val offline = data.bankAccessRepo.createOffline("Cards Bank").id.value
        val card = data.accountRepo.create(offline, "CARD1234", balance = BigDecimal("100.00"), accountType = "credit_card").id.value
        val file = csv("card.csv", ibanLine = null, rows = listOf(row("05.05.2026", "Restaurant", "Essen", "90,00", "-10,00")))
        val vm = TransactionsViewModel(today = { LocalDate.of(2026, 5, 20) })
        vm.start(accountId = card).join()
        vm.openImport(file).join()

        val preview = vm.state.value.importPreview!!
        assertEquals(card, preview.accountId, "selected state account wins when the file names no IBAN")
        assertTrue(preview.balanceUpdateAllowed)
        vm.confirmImport(updateBalance = true).join()
        assertEquals(BigDecimal("90.00"), data.accountRepo.findAllItems().first { it.id == card }.balance)
        vm.close()
    }

    @Test
    fun `a column remap re-reads only the files with the same header layout`() = runBlocking {
        val a1 = csv("a1.csv", rows = listOf(row("05.01.2026", "A1Partner", "A1Purpose", "100,00", "-10,00")))
        val a2 = csv("a2.csv", rows = listOf(row("05.02.2026", "A2Partner", "A2Purpose", "90,00", "-10,00")))
        val other = File(tmp, "other.csv").apply {
            writeText("IBAN;$iban\n\nBuchung;Betrag;Verwendungszweck;Empfänger;Saldo;Währung\n05.03.2026;-10,00;OtherPurpose;OtherPartner;80,00;EUR\n")
        }
        val vm = TransactionsViewModel(today = { LocalDate.of(2026, 3, 20) })
        vm.openImport(listOf(a1, a2, other)).join()
        val preview = vm.state.value.importPreview!!
        assertEquals(listOf(a1, a2, other), preview.entries.map { it.file })
        assertEquals(listOf("A1Purpose", "A2Purpose", "OtherPurpose"), preview.entries.map { it.statement.bookings.single().purpose })

        // Use the third column (Buchungstext) as the purpose for the files with this exact header layout
        vm.remapImport(CsvMapping(date = 0, amount = 7, balance = 5, partner = 2, purpose = 3)).join()
        val remapped = vm.state.value.importPreview!!
        assertEquals(listOf("Lastschrift", "Lastschrift", "OtherPurpose"), remapped.entries.map { it.statement.bookings.single().purpose }, "the other layout keeps its auto-detected mapping")
        vm.close()
    }

    @Test
    fun `ordering puts an empty file first and the quarter file newest`() = runBlocking {
        val quarter = csv(
            "quarter.csv",
            rows = listOf(
                row("10.01.2026", "QShop", "Q", "290,00", "-10,00"),
                row("10.02.2026", "QShop", "Q", "280,00", "-10,00"),
                row("10.03.2026", "QLast", "Q", "270,00", "-10,00"),
            ),
        )
        val january = csv("january.csv", rows = listOf(row("20.01.2026", "JanShop", "J", "95,00", "-5,00")))
        val empty = csv("empty.csv")
        val vm = TransactionsViewModel(today = { LocalDate.of(2026, 3, 20) })
        vm.openImport(listOf(january, quarter, empty)).join()

        val preview = vm.state.value.importPreview!!
        assertEquals(empty, preview.entries[0].file, "an empty file imports nothing, comes first")
        assertEquals(january, preview.entries[1].file)
        assertEquals(quarter, preview.newest.file, "the file with the latest booking date is the newest entry")
        assertTrue(preview.balanceUpdateAllowed)

        vm.confirmImport(updateBalance = true).join()
        assertEquals(BigDecimal("270.00"), data.accountRepo.findAllItems().first { it.id == accountId }.balance, "only the quarter file's last running balance")
        vm.close()
    }

    /** Fails the import of one file (marked by an artificial partner name) and delegates everything else. */
    private class FlakyService(marker: String) : StatementImportService() {
        private val fail = marker

        override suspend fun import(accountId: Int, statement: ParsedStatement, updateBalance: Boolean): StatementImportService.ImportReport {
            if (statement.bookings.any { it.partnerName == fail }) throw IllegalArgumentException("synthetic import failure")
            return super.import(accountId, statement, updateBalance)
        }
    }

    @Test
    fun `a failing file stops the import while the earlier files stay stored`() = runBlocking {
        val older = csv("october.csv", rows = listOf(row("02.10.2025", "Stadtwerke", "Strom", "80,00", "-20,00")))
        val newer = csv("november.csv", rows = listOf(row("20.11.2025", "FlakyStand", "Boom", "140,00", "-10,00")))
        val vm = TransactionsViewModel(today = { LocalDate.of(2026, 3, 20) }, importService = FlakyService("FlakyStand"))
        vm.openImport(listOf(older, newer)).join()
        assertNotNull(vm.state.value.importPreview)

        vm.confirmImport(updateBalance = true).join()
        assertNull(vm.state.value.importPreview, "the preview is cleared")
        assertFalse(vm.state.value.isImporting)
        assertEquals(1, stored("Stadtwerke"), "the file imported before the failure stays stored")
        assertEquals(0, stored("FlakyStand"))
        val error = vm.state.value.error!!
        assertTrue(error.startsWith(AppStrings[StringKey.ErrorImportStopped].substringBefore("%s")), error)
        assertTrue(error.contains("november.csv"), "$error names the failed file")
        vm.close()
    }

    @Test
    fun `addAccount stores an optional name, blank keeps it unset`() = runBlocking {
        val vm = AccountsViewModel()
        val offline = data.bankAccessRepo.createOffline("Cards Test Bank").id.value
        vm.loadAccounts().join()
        vm.addAccount(offline, "1234", "credit_card", BigDecimal.ZERO, name = "  Visa Card  ").join()
        val named = data.accountRepo.findAllItems().first { it.iban == "CARD1234" }
        assertEquals("Visa Card", named.name, "trimmed")

        vm.addAccount(offline, "4321", "credit_card", BigDecimal.ZERO, name = "   ").join()
        val unnamed = data.accountRepo.findAllItems().first { it.iban == "CARD4321" }
        assertNull(unnamed.name, "a blank name leaves the account unnamed, exactly as before")
        vm.close()
    }
}
