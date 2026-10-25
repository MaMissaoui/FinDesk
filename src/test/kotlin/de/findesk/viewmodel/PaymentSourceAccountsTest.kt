package de.findesk.viewmodel

import de.findesk.TestDatabase
import de.findesk.repository.AccountRepositoryImpl
import de.findesk.repository.BankAccessRepositoryImpl
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/** Which accounts the Payments screen offers as the transfer's source: no loans or depots, ordered by bank and type. */
class PaymentSourceAccountsTest {
    @BeforeEach
    fun setup() {
        TestDatabase.init()
    }

    @Test
    fun `loans and depots are never offered, checking savings and credit card are`() = runBlocking {
        val bank = BankAccessRepositoryImpl().create("Beispielbank", "37050198", "COLSDE33XXX", "demo-user").id.value
        val accounts = AccountRepositoryImpl()
        val checking = accounts.create(bank, "DE89370400440532013000", balance = BigDecimal("2450.37")).id.value
        val savings = accounts.create(bank, "DE44500105175407324931", balance = BigDecimal("15000.00"), accountType = "savings").id.value
        val creditCard = accounts.create(bank, "DE02120300000000202051", balance = BigDecimal("-40.10"), accountType = "credit_card").id.value
        accounts.create(bank, "DE44500105175407324001", balance = BigDecimal("-12000.00"), accountType = "loan")
        accounts.create(bank, "DE02120300000000202052", balance = BigDecimal("8000.00"), accountType = "depot")

        val vm = PaymentsViewModel()
        vm.load().join()

        assertEquals(listOf(checking, savings, creditCard), vm.state.value.accounts.map { it.id }, "only the three sendable types are offered, in the type order")
        vm.close()
    }

    @Test
    fun `banks are ordered by name, so A Bank's accounts come before B Bank's`() = runBlocking {
        val banks = BankAccessRepositoryImpl()
        val bBank = banks.create("B Bank", "50010517", "ESSBDE77XXX", "demo-user").id.value
        val aBank = banks.create("A Bank", "37050198", "COLSDE33XXX", "demo-user").id.value
        val accounts = AccountRepositoryImpl()
        val bAccount = accounts.create(bBank, "DE89370400440532013000", balance = BigDecimal("100.00")).id.value
        val aAccount = accounts.create(aBank, "DE02120300000000202051", balance = BigDecimal("200.00")).id.value

        val vm = PaymentsViewModel()
        vm.load().join()

        assertEquals(listOf(aAccount, bAccount), vm.state.value.accounts.map { it.id }, "A Bank comes first whatever the creation order")
        vm.close()
    }
}
