package de.findesk.repository

import de.findesk.data.entity.Account
import de.findesk.data.entity.Category
import de.findesk.data.entity.Transaction
import de.findesk.data.table.TransactionsTable
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.transactions.transaction
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime

interface TransactionRepository {
    fun findAll(): List<Transaction>
    fun findById(id: Int): Transaction?
    fun findByAccount(accountId: Int): List<Transaction>
    fun findByDateRange(startDate: LocalDate, endDate: LocalDate): List<Transaction>
    fun findByCategory(categoryId: Int): List<Transaction>
    fun findByAmountRange(minAmount: BigDecimal, maxAmount: BigDecimal): List<Transaction>
    fun searchByText(searchText: String): List<Transaction>
    fun findRecent(limit: Int = 50): List<Transaction>
    fun create(
        accountId: Int,
        date: LocalDate,
        amount: BigDecimal,
        currency: String = "EUR",
        partnerName: String? = null,
        purpose: String? = null,
        categoryId: Int? = null,
        documentId: Int? = null,
        hash: String,
    ): Transaction
    fun updateCategory(id: Int, categoryId: Int?): Transaction?
    fun updateDocument(id: Int, documentId: Int?): Transaction?
    fun delete(id: Int): Boolean
    fun findByHash(hash: String): Transaction?
    fun getMonthlyTotal(year: Int, month: Int): BigDecimal
    fun getMonthlyByCategory(year: Int, month: Int): Map<String, BigDecimal>
}

class TransactionRepositoryImpl : TransactionRepository {
    override fun findAll(): List<Transaction> = transaction {
        Transaction.all().orderBy(TransactionsTable.date to SortOrder.DESC).toList()
    }

    override fun findById(id: Int): Transaction? = transaction {
        Transaction.findById(id)
    }

    override fun findByAccount(accountId: Int): List<Transaction> = transaction {
        Transaction.find { TransactionsTable.accountId eq accountId }
            .orderBy(TransactionsTable.date to SortOrder.DESC)
            .toList()
    }

    override fun findByDateRange(startDate: LocalDate, endDate: LocalDate): List<Transaction> = transaction {
        Transaction.find {
            (TransactionsTable.date greaterEq startDate) and (TransactionsTable.date lessEq endDate)
        }.orderBy(TransactionsTable.date to SortOrder.DESC).toList()
    }

    override fun findByCategory(categoryId: Int): List<Transaction> = transaction {
        Transaction.find { TransactionsTable.categoryId eq categoryId }
            .orderBy(TransactionsTable.date to SortOrder.DESC)
            .toList()
    }

    override fun findByAmountRange(minAmount: BigDecimal, maxAmount: BigDecimal): List<Transaction> = transaction {
        Transaction.find {
            (TransactionsTable.amount greaterEq minAmount) and (TransactionsTable.amount lessEq maxAmount)
        }.orderBy(TransactionsTable.date to SortOrder.DESC).toList()
    }

    override fun searchByText(searchText: String): List<Transaction> = transaction {
        val pattern = "%$searchText%"
        Transaction.find {
            (TransactionsTable.partnerName like pattern)
        }.orderBy(TransactionsTable.date to SortOrder.DESC).toList()
    }

    override fun findRecent(limit: Int): List<Transaction> = transaction {
        Transaction.all()
            .orderBy(TransactionsTable.date to SortOrder.DESC)
            .limit(limit)
            .toList()
    }

    override fun create(
        accountId: Int,
        date: LocalDate,
        amount: BigDecimal,
        currency: String,
        partnerName: String?,
        purpose: String?,
        categoryId: Int?,
        documentId: Int?,
        hash: String,
    ): Transaction = transaction {
        val account = Account.findById(accountId)
            ?: throw IllegalArgumentException("Account with id $accountId not found")
        val category = categoryId?.let { Category.findById(it) }
        Transaction.new {
            this.account = account
            this.date = date
            this.amount = amount
            this.currency = currency
            this.partnerName = partnerName
            this.purpose = purpose
            this.category = category
            this.hash = hash
            this.createdAt = LocalDateTime.now()
        }
    }

    override fun updateCategory(id: Int, categoryId: Int?): Transaction? = transaction {
        Transaction.findById(id)?.apply {
            category = categoryId?.let { Category.findById(it) }
        }
    }

    override fun updateDocument(id: Int, documentId: Int?): Transaction? = transaction {
        Transaction.findById(id)?.apply {
            document = documentId?.let { de.findesk.data.entity.Document.findById(it) }
        }
    }

    override fun delete(id: Int): Boolean = transaction {
        Transaction.findById(id)?.delete() != null
    }

    override fun findByHash(hash: String): Transaction? = transaction {
        Transaction.find { TransactionsTable.hash eq hash }.firstOrNull()
    }

    override fun getMonthlyTotal(year: Int, month: Int): BigDecimal = transaction {
        val startDate = LocalDate.of(year, month, 1)
        val endDate = startDate.plusMonths(1).minusDays(1)
        Transaction.find {
            (TransactionsTable.date greaterEq startDate) and (TransactionsTable.date lessEq endDate)
        }.sumOf { it.amount }
    }

    override fun getMonthlyByCategory(year: Int, month: Int): Map<String, BigDecimal> = transaction {
        val startDate = LocalDate.of(year, month, 1)
        val endDate = startDate.plusMonths(1).minusDays(1)
        Transaction.find {
            (TransactionsTable.date greaterEq startDate) and (TransactionsTable.date lessEq endDate)
        }.groupBy { it.category?.name ?: "Uncategorized" }
            .mapValues { (_, transactions) -> transactions.sumOf { it.amount } }
    }
}
