package de.findesk.util

import de.findesk.model.AccountGroupItem
import de.findesk.model.AccountItem
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class AccountGroupingTest {
    private fun account(
        id: Int,
        type: String = "checking",
        currency: String = "EUR",
        balance: String = "0",
        groupId: Int? = null,
        newCount: Int = 0,
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
        name = null,
        groupId = groupId,
        newCount = newCount,
    )

    private fun customGroup(id: Int, name: String, sortOrder: Int = id) = AccountGroupItem(id, name, sortOrder)

    @Test
    fun `groups by type in the fixed order`() {
        val accounts = listOf(account(1, "depot"), account(2, "checking"), account(3, "savings"), account(4, "credit_card"))
        val views = group(accounts, emptyList(), "EUR")
        assertEquals(listOf("checking", "savings", "credit_card", "depot"), views.map { (it.key as GroupKey.Type).type })
        assertEquals(listOf(2), views[0].accounts.map { it.id })
    }

    @Test
    fun `a loan group sorts after credit card and before depot`() {
        val accounts = listOf(account(1, "depot"), account(2, "loan"), account(3, "credit_card"), account(4, "checking"), account(5, "savings"))
        val views = group(accounts, emptyList(), "EUR")
        assertEquals(listOf("checking", "savings", "credit_card", "loan", "depot"), views.map { (it.key as GroupKey.Type).type })
        assertEquals(listOf(2), views.first { it.key == GroupKey.Type("loan") }.accounts.map { it.id })
    }

    @Test
    fun `an unknown type follows the fixed ones alphabetically`() {
        val accounts = listOf(account(1, "checking"), account(2, "zebra"), account(3, "apple"))
        val views = group(accounts, emptyList(), "EUR")
        assertEquals(listOf("checking", "apple", "zebra"), views.map { (it.key as GroupKey.Type).type })
    }

    @Test
    fun `a custom group overrides the account type`() {
        val accounts = listOf(account(1, "savings", groupId = 10))
        val views = group(accounts, listOf(customGroup(10, "Reisen")), "EUR")
        assertEquals(listOf(GroupKey.Custom(10)), views.map { it.key })
        assertEquals("Reisen", views.single().name)
        assertEquals(listOf(1), views.single().accounts.map { it.id })
        assertTrue(views.none { it.key is GroupKey.Type })
    }

    @Test
    fun `an unknown group id falls back to the type`() {
        val accounts = listOf(account(1, "checking", groupId = 99))
        val views = group(accounts, listOf(customGroup(10, "Reisen")), "EUR")
        assertEquals(listOf(GroupKey.Type("checking"), GroupKey.Custom(10)), views.map { it.key })
        assertEquals(listOf(1), views[0].accounts.map { it.id }, "the account is in its type group")
        assertTrue(views[1].accounts.isEmpty(), "the custom group stays empty")
    }

    @Test
    fun `empty type groups are hidden but empty custom groups are shown`() {
        val accounts = listOf(account(1, "checking"))
        val views = group(accounts, listOf(customGroup(10, "Reisen")), "EUR")
        assertEquals(listOf(GroupKey.Type("checking"), GroupKey.Custom(10)), views.map { it.key })
        assertTrue(views.first { it.key == GroupKey.Custom(10) }.accounts.isEmpty(), "an empty custom group can receive accounts")
    }

    @Test
    fun `mixed-currency totals are never summed and the primary currency comes first`() {
        val accounts = listOf(
            account(1, "checking", currency = "USD", balance = "50.00"),
            account(2, "checking", currency = "EUR", balance = "100.00"),
            account(3, "depot", currency = "EUR", balance = "2000.00"),
        )
        val checking = group(accounts, emptyList(), "EUR").first { it.key == GroupKey.Type("checking") }
        assertEquals(listOf("EUR", "USD"), checking.totals.keys.toList(), "primary first")
        assertEquals(BigDecimal("100.00"), checking.totals["EUR"])
        assertEquals(BigDecimal("50.00"), checking.totals["USD"])
        assertEquals(2, checking.totals.size, "EUR and USD are never added up")

        val depot = group(accounts, emptyList(), "EUR").first { it.key == GroupKey.Type("depot") }
        assertEquals(BigDecimal("2000.00"), depot.totals["EUR"], "a depot is included")
    }

    @Test
    fun `the grand total sums every account per currency`() {
        val accounts = listOf(
            account(1, "checking", currency = "EUR", balance = "100.00"),
            account(2, "savings", currency = "EUR", balance = "25.00"),
            account(3, "depot", currency = "USD", balance = "10.00"),
        )
        val total = grandTotal(accounts, "EUR")
        assertEquals(listOf("EUR", "USD"), total.keys.toList())
        assertEquals(BigDecimal("125.00"), total["EUR"])
        assertEquals(BigDecimal("10.00"), total["USD"])
        assertTrue(grandTotal(emptyList(), "EUR").isEmpty())
    }

    @Test
    fun `group key ids are stable`() {
        assertEquals("type:checking", GroupKey.Type("checking").id)
        assertEquals("custom:7", GroupKey.Custom(7).id)
    }

    @Test
    fun `a group's new count is the sum of its accounts`() {
        val accounts = listOf(account(1, "checking", newCount = 2), account(2, "checking", newCount = 3), account(3, "savings"))
        val views = group(accounts, emptyList(), "EUR")
        assertEquals(5, views.first { it.key == GroupKey.Type("checking") }.newCount)
        assertEquals(0, views.first { it.key == GroupKey.Type("savings") }.newCount, "nothing new stays zero")
    }

    @Test
    fun `a custom group's new count sums the accounts moved into it`() {
        val accounts = listOf(account(1, "savings", groupId = 10, newCount = 4), account(2, "savings", groupId = 10, newCount = 1))
        val views = group(accounts, listOf(customGroup(10, "Reisen")), "EUR")
        assertEquals(5, views.single { it.key == GroupKey.Custom(10) }.newCount)
    }
}
