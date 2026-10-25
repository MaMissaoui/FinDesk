package de.findesk.util

import de.findesk.model.AccountItem
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * The loan rule for warning-red balances ([isWarningBalance]): red only below zero when the money is not a loan's,
 * because a loan is negative by nature and red there is noise.
 */
class LoanBalanceTest {
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
    fun `a non-loan account warns below zero and not at zero or above`() {
        assertTrue(isWarningBalance(listOf(account(1, "checking", balance = "-10.00")), "EUR"))
        assertFalse(isWarningBalance(listOf(account(1, "checking", balance = "0.00")), "EUR"))
        assertFalse(isWarningBalance(listOf(account(1, "checking", balance = "5.00")), "EUR"))
    }

    @Test
    fun `a loan account never warns however deeply negative`() {
        assertFalse(isWarningBalance(listOf(account(1, LOAN_TYPE, balance = "-90000.00")), "EUR"))
    }

    @Test
    fun `a total negative only because of loans does not warn`() {
        val accounts = listOf(account(1, "checking", balance = "500.00"), account(2, LOAN_TYPE, balance = "-90000.00"))
        assertFalse(isWarningBalance(accounts, "EUR"))
    }

    @Test
    fun `an overdrawn total warns even with loans in it`() {
        val accounts = listOf(account(1, "checking", balance = "-20.00"), account(2, LOAN_TYPE, balance = "-90000.00"))
        assertTrue(isWarningBalance(accounts, "EUR"))
    }

    @Test
    fun `a positive non-loan total on a card plus savings outweighs the loan`() {
        val accounts = listOf(
            account(1, "credit_card", balance = "-300.00"),
            account(2, "savings", balance = "1000.00"),
            account(3, LOAN_TYPE, balance = "-50000.00"),
        )
        assertFalse(isWarningBalance(accounts, "EUR"))
    }

    @Test
    fun `currencies are judged separately`() {
        val accounts = listOf(account(1, "checking", currency = "EUR", balance = "-10.00"), account(2, "checking", currency = "USD", balance = "100.00"))
        assertFalse(isWarningBalance(listOf(accounts[1]), "EUR"), "no euro account: no euro total, no warning")
        assertTrue(isWarningBalance(accounts, "EUR"))
        assertFalse(isWarningBalance(accounts, "USD"))
    }
}
