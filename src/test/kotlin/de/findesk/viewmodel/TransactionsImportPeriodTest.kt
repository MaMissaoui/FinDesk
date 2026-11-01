package de.findesk.viewmodel

import de.findesk.TestData
import de.findesk.TestDatabase
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.LocalDate
import java.time.YearMonth

/** After an import the list shows every period, even when a month or a custom range filtered it before. */
class TransactionsImportPeriodTest {
    @TempDir
    lateinit var tmp: File

    private val iban = "DE44500105175407324931"
    private lateinit var data: TestData
    private var accountId = 0

    @BeforeEach
    fun setup() {
        TestDatabase.init()
        data = TestData()
        accountId = data.account(iban = iban, balance = "0")
    }

    private val header = "Buchung;Valuta;Auftraggeber/Empfänger;Buchungstext;Verwendungszweck;Saldo;Währung;Betrag;Währung\n"

    private fun row(date: String, partner: String, saldo: String, amount: String) = "$date;$date;$partner;Lastschrift;Import;$saldo;EUR;$amount;EUR\n"

    private fun csv(name: String, rows: List<String>): File = File(tmp, name).apply {
        writeText("IBAN;$iban\n\n" + header + rows.joinToString(""))
    }

    @Test
    fun `an import clears the exact month and shows bookings outside it`() = runBlocking {
        val vm = TransactionsViewModel(today = { LocalDate.of(2026, 3, 20) })
        vm.start(month = YearMonth.of(2026, 1)).join()
        assertEquals(YearMonth.of(2026, 1), vm.state.value.month)

        val file = csv("october.csv", listOf(row("02.10.2025", "Stadtwerke", "80,00", "-20,00")))
        vm.openImport(file).join()
        vm.confirmImport(updateBalance = false).join()

        assertNull(vm.state.value.month, "the exact month no longer filters the list")
        assertNull(vm.state.value.customRange)
        assertEquals(Period.ALL, vm.state.value.period)
        assertEquals(listOf("Stadtwerke"), vm.state.value.transactions.map { it.partnerName }, "the imported booking is shown")
        vm.close()
    }

    @Test
    fun `an import clears a custom range and shows bookings outside it`() = runBlocking {
        val vm = TransactionsViewModel(today = { LocalDate.of(2026, 3, 20) })
        vm.start(period = Period.ALL).join()
        vm.setCustomRange(LocalDate.of(2025, 1, 1), LocalDate.of(2025, 3, 31)).join()
        assertEquals(LocalDate.of(2025, 1, 1) to LocalDate.of(2025, 3, 31), vm.state.value.customRange)

        val file = csv("october.csv", listOf(row("02.10.2025", "Stadtwerke", "80,00", "-20,00")))
        vm.openImport(file).join()
        vm.confirmImport(updateBalance = false).join()

        assertNull(vm.state.value.customRange, "the custom range no longer filters the list")
        assertNull(vm.state.value.month)
        assertEquals(Period.ALL, vm.state.value.period)
        assertEquals(listOf("Stadtwerke"), vm.state.value.transactions.map { it.partnerName }, "the imported booking is shown")
        assertEquals(accountId, vm.state.value.accountId)
        vm.close()
    }
}
