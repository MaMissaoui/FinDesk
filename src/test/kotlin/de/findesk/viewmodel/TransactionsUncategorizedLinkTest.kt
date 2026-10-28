package de.findesk.viewmodel

import de.findesk.TestData
import de.findesk.TestDatabase
import de.findesk.model.Direction
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.math.BigDecimal
import java.time.LocalDate
import java.time.YearMonth

/**
 * The dashboard's pending-tasks card opens the uncategorized bookings of all accounts, without search,
 * direction or amount filter — the click-through must show exactly what the count counted.
 */
class TransactionsUncategorizedLinkTest {
    @TempDir
    lateinit var tmp: File

    private val today = LocalDate.of(2026, 6, 15)

    @BeforeEach
    fun setup() {
        TestDatabase.init()
    }

    private fun vm() = TransactionsViewModel(today = { today })

    @Test
    fun `the uncategorized link shows both accounts' bookings and clears search, direction, amount and selection`() = runBlocking {
        val data = TestData()
        val acc1 = data.account(iban = "DE89370400440532013000")
        val acc2 = data.account(iban = "DE625 Casablanca 02011002 071903")
        val unc1 = data.txn(acc1, LocalDate.of(2026, 6, 5), "5.00")
        val unc2 = data.txn(acc2, LocalDate.of(2026, 6, 7), "7.00")
        data.txn(acc1, LocalDate.of(2026, 6, 8), "9.00", categoryId = data.category("Groceries"))
        val vm = vm()
        vm.loadTransactions().join()
        vm.selectAccounts(AccountSelection.Account(acc1)).join()
        // A search that matches nothing, plus direction and amount filters that hide the bookings above
        vm.updateSearchQuery("no such partner").join()
        vm.updateDirection(Direction.INCOME).join()
        vm.updateAmountRange(BigDecimal("10.00"), null).join()

        vm.start(uncategorizedOnly = true, period = Period.THIS_MONTH).join()

        val state = vm.state.value
        assertEquals(setOf(unc1, unc2), state.transactions.mapTo(mutableSetOf()) { it.id })
        assertEquals("", state.searchQuery)
        assertEquals(Direction.ALL, state.direction)
        assertNull(state.minAmount)
        assertNull(state.maxAmount)
        assertEquals(AccountSelection.All, state.selection)
        assertNull(state.accountId)
        assertEquals(Period.THIS_MONTH, state.period)
        vm.close()
    }

    @Test
    fun `the uncategorized link with an exact month shows that month on all accounts`() = runBlocking {
        val data = TestData()
        val acc1 = data.account(iban = "DE89370400440532013000")
        val acc2 = data.account(iban = "DE625 Casablanca 02011002 071903")
        val may1 = data.txn(acc1, LocalDate.of(2026, 5, 3), "1.00")
        val may2 = data.txn(acc2, LocalDate.of(2026, 5, 20), "2.00")
        val june = data.txn(acc1, LocalDate.of(2026, 6, 5), "3.00")
        val vm = vm()
        vm.loadTransactions().join()
        vm.updateSearchQuery("no such partner").join()
        vm.updateDirection(Direction.INCOME).join()
        vm.updateAmountRange(BigDecimal("10.00"), null).join()

        vm.start(uncategorizedOnly = true, month = YearMonth.of(2026, 5)).join()

        val state = vm.state.value
        assertEquals(setOf(may1, may2), state.transactions.mapTo(mutableSetOf()) { it.id })
        assertEquals("", state.searchQuery)
        assertEquals(Direction.ALL, state.direction)
        assertNull(state.minAmount)
        assertNull(state.maxAmount)
        assertEquals(AccountSelection.All, state.selection)
        assertNull(state.accountId)
        assertEquals(Period.ALL, state.period, "an exact month needs no period")
        vm.close()
    }

    @Test
    fun `the uncategorized link ignores a saved panel selection`() = runBlocking {
        val data = TestData()
        val acc1 = data.account(iban = "DE89370400440532013000")
        val acc2 = data.account(iban = "DE625 Casablanca 02011002 071903")
        val unc1 = data.txn(acc1, LocalDate.of(2026, 6, 5), "5.00")
        val unc2 = data.txn(acc2, LocalDate.of(2026, 6, 7), "7.00")
        // One view model leaves its account selected; the panel remembers it for this profile
        val previous = vm()
        previous.loadTransactions().join()
        previous.selectAccounts(AccountSelection.Account(acc1)).join()
        previous.close()
        // The next view model would start on the saved account...
        val vm = vm()
        vm.start().join()
        assertEquals(AccountSelection.Account(acc1), vm.state.value.selection)
        // ...but the uncategorized link must not inherit it
        vm.start(uncategorizedOnly = true, period = Period.THIS_MONTH).join()

        val state = vm.state.value
        assertEquals(setOf(unc1, unc2), state.transactions.mapTo(mutableSetOf()) { it.id })
        assertEquals(AccountSelection.All, state.selection)
        assertNull(state.accountId)
        vm.close()
    }
}
