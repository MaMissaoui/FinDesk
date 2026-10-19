package de.findesk.repository

import de.findesk.data.entity.BankAccess
import de.findesk.data.table.AccountsTable
import de.findesk.data.table.BankAccessTable
import de.findesk.model.BankAccessItem
import de.findesk.model.toItem
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.LocalDate
import java.time.LocalDateTime

interface BankAccessRepository {
    fun findAll(): List<BankAccess>
    fun findById(id: Int): BankAccess?
    fun findByBlz(blz: String): List<BankAccess>
    fun create(bankName: String, blz: String, bic: String, userId: String, hbciVersion: String = "300"): BankAccess
    fun update(id: Int, bankName: String? = null, tanMethod: String? = null): BankAccess?
    fun delete(id: Int): Boolean
    fun updateLastSync(id: Int): BankAccess?
    fun findAllItems(): List<BankAccessItem>
    fun findItem(id: Int): BankAccessItem?

    /** Stores (or clears, with null) the start date the next sync fetches history from for every account. */
    fun setSyncFrom(id: Int, date: LocalDate?): BankAccessItem?

    /** Stores whether "Sync all" syncs this connection. */
    fun setIncludeInSyncAll(id: Int, include: Boolean): BankAccessItem?

    /**
     * Stores the BLZ, BIC, FinTS server URL, the chosen TAN method, the login and/or the display name (null leaves a
     * value unchanged). The name is only a label: renaming changes nothing about the FinTS connection. A blank BIC
     * stores "" (an unknown BIC), unlike the other fields.
     */
    fun updateConnection(
        id: Int,
        serverUrl: String? = null,
        tanMethod: String? = null,
        userId: String? = null,
        bankName: String? = null,
        blz: String? = null,
        bic: String? = null,
    ): BankAccessItem?

    /**
     * Clears the login's capabilities and remembered TAN method after the login changed, so data that belonged
     * to the old institute can't gate or allow orders.
     */
    fun clearLoginState(id: Int): BankAccessItem?

    /** Stores the login's supported business transactions (see BankCapabilities). */
    fun updateCapabilities(id: Int, capabilities: String): BankAccessItem?

    /** Deletes the bank access together with its accounts and their transactions. */
    fun deleteWithAccounts(id: Int): Boolean
}

/** Length of the `bank_name` column. */
const val MAX_BANK_NAME_LENGTH = 255

class BankAccessRepositoryImpl : BankAccessRepository {
    override fun findAll(): List<BankAccess> = transaction {
        BankAccess.all().toList()
    }

    override fun findById(id: Int): BankAccess? = transaction {
        BankAccess.findById(id)
    }

    override fun findByBlz(blz: String): List<BankAccess> = transaction {
        BankAccess.find { de.findesk.data.table.BankAccessTable.blz eq blz }.toList()
    }

    override fun create(bankName: String, blz: String, bic: String, userId: String, hbciVersion: String): BankAccess = transaction {
        BankAccess.new {
            this.bankName = bankName
            this.blz = blz
            this.bic = bic
            this.userId = userId
            this.hbciVersion = hbciVersion
        }
    }

    override fun update(id: Int, bankName: String?, tanMethod: String?): BankAccess? = transaction {
        BankAccess.findById(id)?.apply {
            bankName?.let { this.bankName = it }
            tanMethod?.let { this.tanMethod = it }
        }
    }

    override fun delete(id: Int): Boolean = transaction {
        // Deleting only the row would orphan its accounts and their transactions.
        deleteBankAccessCascade(id)
    }

    override fun updateLastSync(id: Int): BankAccess? = transaction {
        BankAccess.findById(id)?.apply {
            lastSync = LocalDateTime.now()
        }
    }

    override fun setSyncFrom(id: Int, date: LocalDate?): BankAccessItem? = transaction {
        BankAccess.findById(id)?.apply { syncFrom = date }?.toItem()
    }

    override fun setIncludeInSyncAll(id: Int, include: Boolean): BankAccessItem? = transaction {
        BankAccess.findById(id)?.apply { includeInSyncAll = include }?.toItem()
    }

    override fun findAllItems(): List<BankAccessItem> = transaction {
        BankAccess.all().map { it.toItem() }
    }

    override fun deleteWithAccounts(id: Int): Boolean = transaction { deleteBankAccessCascade(id) }

    override fun findItem(id: Int): BankAccessItem? = transaction { BankAccess.findById(id)?.toItem() }

    override fun updateConnection(id: Int, serverUrl: String?, tanMethod: String?, userId: String?, bankName: String?, blz: String?, bic: String?): BankAccessItem? = transaction {
        BankAccess.findById(id)?.apply {
            bankName?.trim()?.takeIf { it.isNotEmpty() }?.let { this.bankName = it.take(MAX_BANK_NAME_LENGTH) }
            userId?.let { this.userId = it.trim() }
            serverUrl?.let { this.serverUrl = it.trim().ifEmpty { null } }
            blz?.let { this.blz = it.trim() }
            bic?.let { this.bic = it.trim() }
            tanMethod?.let { this.tanMethod = it }
        }?.toItem()
    }

    override fun clearLoginState(id: Int): BankAccessItem? = transaction {
        BankAccess.findById(id)?.apply {
            capabilities = null
            tanMethod = null
        }?.toItem()
    }

    override fun updateCapabilities(id: Int, capabilities: String): BankAccessItem? = transaction {
        BankAccess.findById(id)?.apply { this.capabilities = capabilities.trim().ifEmpty { null } }?.toItem()
    }
}

/** Must run inside a transaction. Deletes the bank access with its accounts and their transactions. */
private fun deleteBankAccessCascade(id: Int): Boolean {
    AccountsTable.select(AccountsTable.id)
        .where { AccountsTable.bankAccessId eq id }
        .map { it[AccountsTable.id].value }
        .forEach { deleteAccountCascade(it) }
    return BankAccessTable.deleteWhere { BankAccessTable.id eq id } > 0
}
