package de.findesk.repository

import de.findesk.data.entity.BankAccess
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.LocalDateTime

interface BankAccessRepository {
    fun findAll(): List<BankAccess>
    fun findById(id: Int): BankAccess?
    fun findByBlz(blz: String): List<BankAccess>
    fun create(bankName: String, blz: String, bic: String, userId: String, hbciVersion: String = "300"): BankAccess
    fun update(id: Int, bankName: String? = null, tanMethod: String? = null): BankAccess?
    fun delete(id: Int): Boolean
    fun updateLastSync(id: Int): BankAccess?
}

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
        BankAccess.findById(id)?.delete() != null
    }

    override fun updateLastSync(id: Int): BankAccess? = transaction {
        BankAccess.findById(id)?.apply {
            lastSync = LocalDateTime.now()
        }
    }
}
