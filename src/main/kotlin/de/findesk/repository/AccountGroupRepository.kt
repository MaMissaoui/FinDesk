package de.findesk.repository

import de.findesk.data.entity.AccountGroup
import de.findesk.data.table.AccountGroupsTable
import de.findesk.data.table.AccountsTable
import de.findesk.model.AccountGroupItem
import de.findesk.model.toItem
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update

/** Length of the `name` column on account groups. */
const val MAX_ACCOUNT_GROUP_NAME_LENGTH = 100

/** Custom account groups shown in the Transactions account panel. */
interface AccountGroupRepository {
    /** Every group, ordered by [AccountGroupItem.sortOrder] then id. */
    fun findAllItems(): List<AccountGroupItem>

    /**
     * Creates a group from [name]: trimmed, non-blank and capped at [MAX_ACCOUNT_GROUP_NAME_LENGTH], placed after all
     * existing groups. Returns the created item.
     */
    fun create(name: String): AccountGroupItem

    fun rename(id: Int, name: String): AccountGroupItem?

    /** Moves the group's accounts back to their type group, then deletes it, in one transaction. */
    fun delete(id: Int): Boolean

    /** Swaps [id]'s sort order with its neighbour's; a no-op at either end. */
    fun move(id: Int, up: Boolean)
}

class AccountGroupRepositoryImpl : AccountGroupRepository {
    override fun findAllItems(): List<AccountGroupItem> = transaction {
        AccountGroup.all()
            .orderBy(AccountGroupsTable.sortOrder to SortOrder.ASC, AccountGroupsTable.id to SortOrder.ASC)
            .map { it.toItem() }
    }

    override fun create(name: String): AccountGroupItem = transaction {
        val clean = name.trim().take(MAX_ACCOUNT_GROUP_NAME_LENGTH)
        require(clean.isNotEmpty()) { "Account group name must not be blank" }
        val nextOrder = (AccountGroup.all().maxOfOrNull { it.sortOrder } ?: 0) + 1
        AccountGroup.new {
            this.name = clean
            this.sortOrder = nextOrder
        }.toItem()
    }

    override fun rename(id: Int, name: String): AccountGroupItem? = transaction {
        val clean = name.trim().take(MAX_ACCOUNT_GROUP_NAME_LENGTH)
        require(clean.isNotEmpty()) { "Account group name must not be blank" }
        AccountGroup.findById(id)?.apply { this.name = clean }?.toItem()
    }

    override fun delete(id: Int): Boolean = transaction {
        // SQLite doesn't enforce the (plain integer) group reference, so unlink the accounts by hand first.
        AccountsTable.update({ AccountsTable.groupId eq id }) { it[AccountsTable.groupId] = null }
        AccountGroupsTable.deleteWhere { AccountGroupsTable.id eq id } > 0
    }

    override fun move(id: Int, up: Boolean) = transaction {
        val groups = AccountGroup.all()
            .orderBy(AccountGroupsTable.sortOrder to SortOrder.ASC, AccountGroupsTable.id to SortOrder.ASC)
            .toList()
        val index = groups.indexOfFirst { it.id.value == id }
        if (index < 0) return@transaction
        val neighbour = if (up) index - 1 else index + 1
        if (neighbour !in groups.indices) return@transaction
        val current = groups[index]
        val other = groups[neighbour]
        val order = current.sortOrder
        current.sortOrder = other.sortOrder
        other.sortOrder = order
    }
}
