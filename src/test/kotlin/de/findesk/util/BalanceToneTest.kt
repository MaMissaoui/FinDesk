package de.findesk.util

import de.findesk.model.AccountItem
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * The colour tone of an account balance or a total of balances: green above zero, warning red for an overdrawn
 * non-loan total, calm grey for a loan's negative balance, and plain text at zero.
 */
class BalanceToneTest {
    private fun account(
        id: Int,
        type: String,
        currency: String = "EUR",
        balance: String = "0",
    ) = AccountItem(
        id = id,
        bankAccessId = 1,
        bankName = "Bank",
        iban = "DE%022d".format(id),
        accountNumber = null,
        balance = BigDecimal(balance),
        currency = currency,
        accountType = type,
        lastUpdate = null,
    )

    @Test
    fun `one account follows the sign and the loan rule`() {
        assertEquals(BalanceTone.POSITIVE, balanceTone(listOf(account(1, "checking", balance = "5.00")), "EUR"))
        assertEquals(BalanceTone.ZERO, balanceTone(listOf(account(1, "checking", balance = "0.00")), "EUR"))
        assertEquals(BalanceTone.NEGATIVE, balanceTone(listOf(account(1, "checking", balance = "-10.00")), "EUR"))
        assertEquals(BalanceTone.LOAN, balanceTone(listOf(account(1, LOAN_TYPE, balance = "-90000.00")), "EUR"))
    }

    @Test
    fun `a total negative only because of loans is a loan tone`() {
        val accounts = listOf(account(1, "checking", balance = "2450.00"), account(2, LOAN_TYPE, balance = "-96500.00"))
        assertEquals(BalanceTone.LOAN, balanceTone(accounts, "EUR"))
    }

    @Test
    fun `an overdrawn total stays a warning even with loans in it`() {
        val accounts = listOf(account(1, "checking", balance = "-100.00"), account(2, LOAN_TYPE, balance = "-1000.00"))
        assertEquals(BalanceTone.NEGATIVE, balanceTone(accounts, "EUR"))
    }

    @Test
    fun `a positive total outweighing a loan is positive`() {
        val accounts = listOf(account(1, "checking", balance = "100.00"), account(2, LOAN_TYPE, balance = "-50.00"))
        assertEquals(BalanceTone.POSITIVE, balanceTone(accounts, "EUR"))
    }

    @Test
    fun `currencies are judged separately`() {
        val accounts = listOf(account(1, "checking", currency = "EUR", balance = "-10.00"), account(2, "checking", currency = "USD", balance = "100.00"))
        assertEquals(BalanceTone.NEGATIVE, balanceTone(accounts, "EUR"), "the euro total ignores the positive dollar account")
        assertEquals(BalanceTone.POSITIVE, balanceTone(accounts, "USD"), "the dollar total ignores the negative euro account")
    }

    @Test
    fun `one balance has all four tones`() {
        assertEquals(BalanceTone.POSITIVE, balanceTone(BigDecimal("5.00"), isLoan = false))
        assertEquals(BalanceTone.ZERO, balanceTone(BigDecimal.ZERO, isLoan = false))
        assertEquals(BalanceTone.NEGATIVE, balanceTone(BigDecimal("-10.00"), isLoan = false))
        assertEquals(BalanceTone.LOAN, balanceTone(BigDecimal("-90000.00"), isLoan = true))
    }

    @Test
    fun `a negative list tone is exactly the warning flag`() {
        val fixtures = listOf(
            listOf(account(1, "checking", balance = "-10.00")),
            listOf(account(1, LOAN_TYPE, balance = "-90000.00")),
            listOf(account(1, "checking", balance = "500.00"), account(2, LOAN_TYPE, balance = "-90000.00")),
            listOf(account(1, "checking", balance = "-20.00"), account(2, LOAN_TYPE, balance = "-90000.00")),
            listOf(account(1, "credit_card", balance = "-300.00"), account(2, "savings", balance = "1000.00"), account(3, LOAN_TYPE, balance = "-50000.00")),
            listOf(account(1, "checking", balance = "5.00")),
            listOf(account(1, "checking", balance = "0.00")),
        )
        fixtures.forEach { accounts ->
            assertEquals(
                isWarningBalance(accounts, "EUR"),
                balanceTone(accounts, "EUR") == BalanceTone.NEGATIVE,
                "tone and warning flag disagree for $accounts",
            )
        }
    }

    @Test
    fun `a warning-free negative total is never a warning`() {
        assertFalse(isWarningBalance(listOf(account(1, LOAN_TYPE, balance = "-1.00")), "EUR"))
        assertTrue(balanceTone(listOf(account(1, LOAN_TYPE, balance = "-1.00")), "EUR") != BalanceTone.NEGATIVE)
    }
}
