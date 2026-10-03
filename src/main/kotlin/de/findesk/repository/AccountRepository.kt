package de.findesk.repository

import de.findesk.data.entity.Account
import de.findesk.data.entity.BankAccess
import de.findesk.data.table.AccountsTable
import org.jetbrains.exposed.sql.transactions.transaction
import java.math.BigDecimal
import java.time.LocalDateTime

interface AccountRepository {
    fun findAll(): List<Account>
    fun findById(id: Int): Account?
    fun findByBankAccess(bankAccessId: Int): List<Account>
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
    fun delete(id: Int): Boolean
    fun getTotalBalance(): BigDecimal
}

class AccountRepositoryImpl : AccountRepository {
    override fun findAll(): List<Account> = transaction {
        Account.all().toList()
    }

    override fun findById(id: Int): Account? = transaction {
        Account.findById(id)
    }

    override fun findByBankAccess(bankAccessId: Int): List<Account> = transaction {
        Account.find { AccountsTable.bankAccessId eq bankAccessId }.toList()
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

    override fun delete(id: Int): Boolean = transaction {
        Account.findById(id)?.delete() != null
    }

    override fun getTotalBalance(): BigDecimal = transaction {
        Account.all().sumOf { it.balance }
    }
}
