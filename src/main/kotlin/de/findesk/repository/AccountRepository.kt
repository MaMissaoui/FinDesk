package de.findesk.repository

import de.findesk.data.entity.Account
import de.findesk.data.entity.BankAccess
import de.findesk.data.table.AccountsTable
import de.findesk.data.table.DocumentsTable
import de.findesk.data.table.TransactionsTable
import de.findesk.model.AccountItem
import de.findesk.model.toItem
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.math.BigDecimal
import java.time.LocalDateTime

/** Length of the `name` column on accounts. */
const val MAX_ACCOUNT_NAME_LENGTH = 255

interface AccountRepository {
    fun findAll(): List<Account>
    fun findById(id: Int): Account?
    fun findByIban(iban: String): Account?
    fun findByType(type: String): List<Account>
    fun create(
        bankAccessId: Int,
        iban: String,
        accountNumber: String? = null,
        balance: BigDecimal = BigDecimal.ZERO,
        currency: String = "EUR",
        accountType: String = "checking",
    ): Account
    fun updateBalance(id: Int, balance: BigDecimal): Account?
    fun update(id: Int, iban: String? = null, accountType: String? = null): Account?

    /**
     * Sets (or clears, with null or blank) the user's own name for the account. The name is trimmed and capped at
     * [MAX_ACCOUNT_NAME_LENGTH]; unlike [update] it may also clear the value. Returns the updated item, or null for
     * an unknown id.
     */
    fun rename(id: Int, name: String?): AccountItem?
    fun delete(id: Int): Boolean

    /** Sum of the balances of accounts in [currency]; balances in different currencies can't be added. */
    fun getTotalBalance(currency: String = "EUR"): BigDecimal
    fun findAllItems(): List<AccountItem>

    /** Deletes the account, its transactions, and unlinks documents attached to them. */
    fun deleteWithTransactions(id: Int): Boolean
}

class AccountRepositoryImpl : AccountRepository {
    override fun findAll(): List<Account> = transaction {
        Account.all().toList()
    }

    override fun findById(id: Int): Account? = transaction {
        Account.findById(id)
    }

    override fun findByIban(iban: String): Account? = transaction {
        Account.find { AccountsTable.iban eq iban }.firstOrNull()
    }

    override fun findByType(type: String): List<Account> = transaction {
        Account.find { AccountsTable.accountType eq type }.toList()
    }

    override fun create(
        bankAccessId: Int,
        iban: String,
        accountNumber: String?,
        balance: BigDecimal,
        currency: String,
        accountType: String,
    ): Account = transaction {
        val bankAccess = BankAccess.findById(bankAccessId)
            ?: throw IllegalArgumentException("BankAccess with id $bankAccessId not found")
        Account.new {
            this.bankAccess = bankAccess
            this.iban = iban
            this.accountNumber = accountNumber
            this.balance = balance
            this.currency = currency
            this.accountType = accountType
        }
    }

    override fun updateBalance(id: Int, balance: BigDecimal): Account? = transaction {
        Account.findById(id)?.apply {
            this.balance = balance
            this.lastUpdate = LocalDateTime.now()
        }
    }

    override fun update(id: Int, iban: String?, accountType: String?): Account? = transaction {
        Account.findById(id)?.apply {
            iban?.let { this.iban = it }
            accountType?.let { this.accountType = it }
        }
    }

    override fun rename(id: Int, name: String?): AccountItem? = transaction {
        val account = Account.findById(id) ?: return@transaction null
        account.name = name?.trim()?.takeIf { it.isNotEmpty() }?.take(MAX_ACCOUNT_NAME_LENGTH)
        account.toItem()
    }

    override fun delete(id: Int): Boolean = transaction {
        // Deleting only the row would orphan transactions and document links.
        deleteAccountCascade(id)
    }

    override fun getTotalBalance(currency: String): BigDecimal = transaction {
        Account.find { AccountsTable.currency eq currency }.sumOf { it.balance }
    }

    override fun findAllItems(): List<AccountItem> = transaction {
        Account.all().orderBy(AccountsTable.id to org.jetbrains.exposed.sql.SortOrder.ASC).map { it.toItem() }
    }

    override fun deleteWithTransactions(id: Int): Boolean = transaction { deleteAccountCascade(id) }
}

/** Must run inside a transaction. */
internal fun deleteAccountCascade(accountId: Int): Boolean {
    val txnIds = TransactionsTable.select(TransactionsTable.id)
        .where { TransactionsTable.accountId eq accountId }
        .map { it[TransactionsTable.id] }
    if (txnIds.isNotEmpty()) {
        DocumentsTable.update({ DocumentsTable.linkedTransactionId inList txnIds }) {
            it[linkedTransactionId] = null
        }
        TransactionsTable.deleteWhere { TransactionsTable.accountId eq accountId }
    }
    return AccountsTable.deleteWhere { AccountsTable.id eq accountId } > 0
}
