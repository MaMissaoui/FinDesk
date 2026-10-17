package de.findesk.repository

import de.findesk.data.entity.Account
import de.findesk.data.entity.Category
import de.findesk.data.entity.Transaction
import de.findesk.data.table.CategoriesTable
import de.findesk.data.table.DocumentsTable
import de.findesk.data.table.TransactionsTable
import de.findesk.model.Direction
import de.findesk.model.TransactionFilter
import de.findesk.model.TransactionItem
import de.findesk.model.toItem
import org.jetbrains.exposed.sql.Op
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime

/** A booking to insert with [TransactionRepository.createAll]. */
data class NewTransaction(
    val accountId: Int,
    val date: LocalDate,
    val amount: BigDecimal,
    val currency: String,
    val partnerName: String?,
    val purpose: String?,
    val categoryId: Int?,
    val hash: String,
)

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

    /** Inserts all [bookings] in one database transaction: all or none, and far faster than one commit per booking. */
    fun createAll(bookings: List<NewTransaction>): Int
    fun updateCategory(id: Int, categoryId: Int?): Transaction?
    fun updateDocument(id: Int, documentId: Int?): Transaction?
    fun delete(id: Int): Boolean
    fun findByHash(hash: String): Transaction?
    fun getMonthlyTotal(year: Int, month: Int, currency: String): BigDecimal
    fun getMonthlyByCategory(year: Int, month: Int, currency: String): Map<String, BigDecimal>

    /** Filtered, newest-first snapshot safe to use outside a transaction. */
    fun findItems(filter: TransactionFilter = TransactionFilter(), limit: Int? = null): List<TransactionItem>

    /** Number of bookings on [accountId] with this booking date and amount (for cross-source duplicate checks). */
    fun countSameBooking(accountId: Int, date: LocalDate, amount: BigDecimal): Int
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
            (TransactionsTable.partnerName like pattern) or (TransactionsTable.purpose like pattern)
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

    override fun createAll(bookings: List<NewTransaction>): Int = transaction {
        bookings.forEach { b ->
            create(b.accountId, b.date, b.amount, b.currency, b.partnerName, b.purpose, b.categoryId, hash = b.hash)
        }
        bookings.size
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
        // Documents linked to the booking would dangle otherwise; SQLite doesn't enforce the FK.
        DocumentsTable.update({ DocumentsTable.linkedTransactionId eq id }) { it[linkedTransactionId] = null }
        Transaction.findById(id)?.delete() != null
    }

    override fun findByHash(hash: String): Transaction? = transaction {
        Transaction.find { TransactionsTable.hash eq hash }.firstOrNull()
    }

    override fun getMonthlyTotal(year: Int, month: Int, currency: String): BigDecimal = transaction {
        val startDate = LocalDate.of(year, month, 1)
        val endDate = startDate.plusMonths(1).minusDays(1)
        Transaction.find {
            (TransactionsTable.date greaterEq startDate) and (TransactionsTable.date lessEq endDate) and (TransactionsTable.currency eq currency)
        }.sumOf { it.amount }
    }

    override fun getMonthlyByCategory(year: Int, month: Int, currency: String): Map<String, BigDecimal> = transaction {
        val startDate = LocalDate.of(year, month, 1)
        val endDate = startDate.plusMonths(1).minusDays(1)
        Transaction.find {
            (TransactionsTable.date greaterEq startDate) and (TransactionsTable.date lessEq endDate) and (TransactionsTable.currency eq currency)
        }.groupBy { it.category?.name ?: "Uncategorized" }
            .mapValues { (_, transactions) -> transactions.sumOf { it.amount } }
    }

    override fun findItems(filter: TransactionFilter, limit: Int?): List<TransactionItem> = transaction {
        val conditions = with(SqlExpressionBuilder) {
            buildList<Op<Boolean>> {
                filter.startDate?.let { add(TransactionsTable.date greaterEq it) }
                filter.endDate?.let { add(TransactionsTable.date lessEq it) }
                filter.accountId?.let { add(TransactionsTable.accountId eq it) }
                filter.categoryId?.let { add(TransactionsTable.categoryId eq it) }
                filter.currency?.let { add(TransactionsTable.currency eq it) }
                if (filter.uncategorizedOnly) add(TransactionsTable.categoryId.isNull())
                if (filter.taxRelevantOnly) {
                    add(
                        TransactionsTable.categoryId inSubQuery
                            CategoriesTable.select(CategoriesTable.id).where { CategoriesTable.isTaxRelevant eq true },
                    )
                }
                when (filter.direction) {
                    Direction.INCOME -> add(TransactionsTable.amount greater BigDecimal.ZERO)
                    Direction.EXPENSES -> add(TransactionsTable.amount less BigDecimal.ZERO)
                    Direction.ALL -> Unit
                }
                filter.minAmount?.abs()?.let { min ->
                    add((TransactionsTable.amount greaterEq min) or (TransactionsTable.amount lessEq min.negate()))
                }
                filter.maxAmount?.abs()?.let { max ->
                    add((TransactionsTable.amount lessEq max) and (TransactionsTable.amount greaterEq max.negate()))
                }
                filter.text?.trim()?.takeIf { it.isNotEmpty() }?.let {
                    val pattern = "%$it%"
                    add((TransactionsTable.partnerName like pattern) or (TransactionsTable.purpose like pattern))
                }
            }
        }
        val where = conditions.reduceOrNull { a, b -> a and b }
        val query = (if (where != null) Transaction.find(where) else Transaction.all())
            .orderBy(TransactionsTable.date to SortOrder.DESC, TransactionsTable.id to SortOrder.DESC)
        val transactions = (if (limit != null) query.limit(limit) else query).toList()

        val ids = transactions.map { it.id }
        val docCounts = if (ids.isEmpty()) {
            emptyMap()
        } else {
            DocumentsTable.select(DocumentsTable.linkedTransactionId)
                .where { DocumentsTable.linkedTransactionId inList ids }
                .mapNotNull { it[DocumentsTable.linkedTransactionId]?.value }
                .groupingBy { it }
                .eachCount()
        }
        transactions.map { it.toItem(docCounts[it.id.value] ?: 0) }
    }

    override fun countSameBooking(accountId: Int, date: LocalDate, amount: BigDecimal): Int = transaction {
        TransactionsTable.selectAll().where {
            (TransactionsTable.accountId eq accountId) and (TransactionsTable.date eq date) and (TransactionsTable.amount eq amount)
        }.count().toInt()
    }
}
