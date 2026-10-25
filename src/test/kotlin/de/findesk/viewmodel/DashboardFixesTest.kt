package de.findesk.viewmodel

import de.findesk.TestData
import de.findesk.TestDatabase
import de.findesk.i18n.AppLanguage
import de.findesk.i18n.Translations
import de.findesk.model.AccountItem
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.YearMonth

/**
 * The three dashboard fixes at once: only the running month's bar is partial (never a past selected month's),
 * and `Strings.accountName` distinguishes unnamed accounts with the IBAN's or account number's last 4 characters.
 */
class DashboardFixesTest {
    private val today = LocalDate.of(2026, 10, 15)

    private lateinit var data: TestData

    @BeforeEach
    fun setup() {
        TestDatabase.init()
        data = TestData()
    }

    @Test
    fun `the default load marks only October, the running month, as partial`() = runBlocking {
        // Bookings in August and October, so the window has non-empty bars besides the partial one
        val account = data.account()
        data.txn(account, LocalDate.of(2026, 8, 2), "-120.00")
        data.txn(account, LocalDate.of(2026, 10, 3), "-40.00")
        val vm = DashboardViewModel(today = { today })
        vm.loadDashboard().join()

        val bars = vm.state.value.lastMonths
        assertEquals(listOf(5, 6, 7, 8, 9, 10), bars.map { it.month }, "the six months ending with the current month")
        val partial = bars.filter { it.partial }
        assertEquals(listOf(YearMonth.of(2026, 10)), partial.map { YearMonth.of(it.year, it.month) }, "exactly October 2026 is partial")
        assertEquals(bars.last(), partial.single(), "the running month is the last bar")
        vm.close()
    }

    @Test
    fun `a selected past month has no partial bars`() = runBlocking {
        val account = data.account()
        data.txn(account, LocalDate.of(2026, 8, 2), "-120.00")
        val vm = DashboardViewModel(today = { today })
        vm.loadDashboard().join()
        vm.selectMonth(YearMonth.of(2026, 8)).join()

        val bars = vm.state.value.lastMonths
        assertEquals(listOf(3, 4, 5, 6, 7, 8), bars.map { it.month }, "the six months ending with August")
        assertTrue(bars.none { it.partial }, "October is outside the six-month window ending in August")
        vm.close()
    }

    @Test
    fun `account names fall back to the type plus the number's last four`() {
        fun item(type: String, iban: String, accountNumber: String?, name: String?) = AccountItem(
            id = 1,
            bankAccessId = 1,
            bankName = "Test Bank",
            iban = iban,
            accountNumber = accountNumber,
            balance = BigDecimal.ZERO,
            currency = "EUR",
            accountType = type,
            lastUpdate = null,
            name = name,
        )
        val s = Translations.of(AppLanguage.EN)
        // Grouped IBAN: the last 4 are taken after all whitespace is removed
        assertEquals("Checking …3000", s.accountName(item("checking", "DE89 3704 0044 0532 0130 00", null, null)))
        assertEquals("Loan …4567", s.accountName(item("loan", "", "1234567", null)), "no IBAN falls back to the account number")
        assertEquals(
            "Checking",
            s.accountName(item("checking", "   ", null, null)),
            "no IBAN and no account number keeps the bare localized type",
        )
        assertEquals("Haushalt", s.accountName(item("checking", "DE89 3704 0044 0532 0130 00", null, "Haushalt")), "a user-set name is unchanged")
        assertEquals(
            "Girokonto …3000",
            Translations.of(AppLanguage.DE).accountName(item("checking", "DE89 3704 0044 0532 0130 00", null, null)),
            "the type is localized",
        )
    }
}
