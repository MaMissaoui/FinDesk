package de.findesk.viewmodel

import de.findesk.TestDatabase
import de.findesk.repository.AccountRepositoryImpl
import de.findesk.repository.BankAccessRepositoryImpl
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate

/**
 * The dashboard's balance card splits loans from the money on accounts: a mortgage must not turn "Available"
 * into a large negative number that hides what can actually be spent.
 */
class DashboardBalanceSplitTest {
    private val today = LocalDate.of(2026, 10, 15)
    private val accountRepo = AccountRepositoryImpl()

    @BeforeEach
    fun setup() {
        TestDatabase.init()
    }

    /** Adds an EUR account of the given type and loads the dashboard; loans and depots test the split. */
    private suspend fun load(vararg types: Pair<String, String>): DashboardState {
        val bank = BankAccessRepositoryImpl().create("Test Bank", "37040044", "COBADEFFXXX", "user1")
        types.forEachIndexed { index, (type, balance) ->
            accountRepo.create(bank.id.value, "DE8937040044053201300$index", balance = BigDecimal(balance), accountType = type)
        }
        val vm = DashboardViewModel(today = { today })
        vm.loadDashboard().join()
        return vm.state.value
    }

    @Test
    fun `availableBalance excludes loans and depots, loanBalance sums loan accounts`() = runBlocking {
        val s = load(
            "checking" to "100.00",
            "savings" to "50.00",
            "credit_card" to "-30.00",
            "loan" to "-1000.00",
            "depot" to "500.00",
        )

        assertEquals(BigDecimal("120.00"), s.availableBalance, "checking + savings + the card's money owed")
        assertEquals(BigDecimal("-1000.00"), s.loanBalance)
        assertEquals(BigDecimal("-380.00"), s.totalBalance, "as before: every account, the loan included")
        assertTrue(s.hasLoans, "there is a loan account, so the card shows the loans subtitle")
    }

    @Test
    fun `without loan accounts loanBalance is zero and hasLoans is false`() = runBlocking {
        val s = load("checking" to "250.00", "depot" to "1000.00")

        assertEquals(BigDecimal("250.00"), s.availableBalance, "the depot is neither available nor a loan")
        assertEquals(0, s.loanBalance.signum(), "no loans, so loanBalance is 0")
        assertFalse(s.hasLoans, "the flag (not a null) is what keeps the loans subtitle hidden")
    }
}
