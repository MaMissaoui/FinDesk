package de.findesk.viewmodel

import de.findesk.TestData
import de.findesk.TestDatabase
import de.findesk.model.AccountItem
import de.findesk.repository.AccountRepository
import de.findesk.repository.AccountRepositoryImpl
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** A refresh must not reset a selection the user made while the repository reads ran. */
class AccountsRefreshSelectionTest {
    private lateinit var data: TestData

    @BeforeEach
    fun setup() {
        TestDatabase.init()
        data = TestData()
    }

    /** Delegates to the real repository and blocks the next `findAllItems` once, until released. */
    private class BlockingAccountRepository(
        private val delegate: AccountRepository,
        private val entered: CountDownLatch,
        private val release: CountDownLatch,
    ) : AccountRepository by delegate {
        @Volatile
        var blockNext = false

        override fun findAllItems(): List<AccountItem> {
            if (blockNext) {
                blockNext = false
                entered.countDown()
                release.await(5, TimeUnit.SECONDS)
            }
            return delegate.findAllItems()
        }
    }

    @Test
    fun `a selection made during a refresh survives it`() = runBlocking {
        val first = data.account(iban = "DE89370400440532013000")
        val second = data.account(iban = "DE44500105175407324931")
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val repository = BlockingAccountRepository(AccountRepositoryImpl(), entered, release)
        val vm = AccountsViewModel(accountRepository = repository)
        vm.loadAccounts().join()

        repository.blockNext = true
        val refresh = vm.loadAccounts()
        entered.await(5, TimeUnit.SECONDS)

        vm.selectAccounts(AccountSelection.Account(second)).join()
        release.countDown()
        refresh.join()

        assertEquals(AccountSelection.Account(second), vm.state.value.selection)
        assertEquals(setOf(first, second), vm.state.value.accounts.map { it.id }.toSet())
        vm.close()
    }
}
