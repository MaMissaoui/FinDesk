package de.findesk.repository

import de.findesk.TestData
import de.findesk.TestDatabase
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class AccountGroupRepositoryTest {
    private lateinit var groups: AccountGroupRepository
    private lateinit var accounts: AccountRepository
    private lateinit var data: TestData
    private var account = 0

    @BeforeEach
    fun setup() {
        TestDatabase.init()
        groups = AccountGroupRepositoryImpl()
        accounts = AccountRepositoryImpl()
        data = TestData()
        account = data.account()
    }

    private fun names() = groups.findAllItems().map { it.name }

    @Test
    fun `create trims, caps and orders after the existing groups`() {
        val first = groups.create("  Reisen  ")
        assertEquals("Reisen", first.name, "trimmed")
        val second = groups.create("x".repeat(150))
        assertEquals(MAX_ACCOUNT_GROUP_NAME_LENGTH, second.name.length, "cut to the column length")
        assertEquals(1, first.sortOrder)
        assertEquals(2, second.sortOrder)
        assertEquals(listOf("Reisen", "x".repeat(MAX_ACCOUNT_GROUP_NAME_LENGTH)), names())
    }

    @Test
    fun `create rejects a blank name`() {
        assertThrows(IllegalArgumentException::class.java) { groups.create("   ") }
        assertThrows(IllegalArgumentException::class.java) { groups.create("") }
        assertTrue(groups.findAllItems().isEmpty())
    }

    @Test
    fun `rename trims and rejects a blank name`() {
        val group = groups.create("Reisen")
        assertEquals("Trips", groups.rename(group.id, "  Trips  ")!!.name)
        assertEquals("Trips", groups.findAllItems().single().name)
        assertThrows(IllegalArgumentException::class.java) { groups.rename(group.id, "  ") }
        assertNull(groups.rename(999_999, "Nope"), "an unknown id returns null")
    }

    @Test
    fun `move swaps with the neighbour and is a no-op at the ends`() {
        val a = groups.create("A")
        val b = groups.create("B")
        val c = groups.create("C")

        groups.move(c.id, up = true)
        assertEquals(listOf("A", "C", "B"), names())

        groups.move(a.id, up = true)
        assertEquals(listOf("A", "C", "B"), names(), "the first group can't move up")

        groups.move(a.id, up = false)
        assertEquals(listOf("C", "A", "B"), names())

        groups.move(b.id, up = false)
        assertEquals(listOf("C", "A", "B"), names(), "the last group can't move down")

        assertEquals(b.id, groups.findAllItems().last().id)
    }

    @Test
    fun `delete nulls the group on its accounts and leaves other accounts alone`() {
        val a = groups.create("A")
        val b = groups.create("B")
        accounts.setGroup(account, a.id)
        val other = data.account(iban = "DE44500105175407324931")
        accounts.setGroup(other, b.id)

        assertTrue(groups.delete(a.id))

        assertNull(accounts.findAllItems().first { it.id == account }.groupId, "the deleted group's account moves back")
        assertEquals(b.id, accounts.findAllItems().first { it.id == other }.groupId, "another group's account is untouched")
        assertNull(groups.findAllItems().firstOrNull { it.id == a.id })
        assertEquals(listOf("B"), names())
    }
}
