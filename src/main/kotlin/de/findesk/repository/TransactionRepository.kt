package de.findesk.repository

import de.findesk.data.entity.Account
import de.findesk.data.entity.Category
import de.findesk.data.entity.Rule
import de.findesk.data.entity.Transaction
import de.findesk.data.table.AccountsTable
import de.findesk.data.table.DeletedBookingsTable
import de.findesk.data.table.DocumentsTable
import de.findesk.data.table.RulesTable
import de.findesk.data.table.TransactionsTable
import de.findesk.model.Direction
import de.findesk.model.RuleDirection
import de.findesk.model.RuleField
import de.findesk.model.TransactionFilter
import de.findesk.model.TransactionItem
import de.findesk.model.toItem
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.Op
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.count
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.min
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
    /** The bank's running balance after this booking, when the statement reported one. */
    val balanceAfter: BigDecimal? = null,
    /** Only a bank sync sets this; see [BankingService.importTransactions]. */
    val isNew: Boolean = false,
)

interface TransactionRepository {
    fun findAll(): List<Transaction>
    fun findById(id: Int): Transaction?
    fun findByCategory(categoryId: Int): List<Transaction>
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
        balanceAfter: BigDecimal? = null,
        isNew: Boolean = false,
    ): Transaction

    /** Inserts all [bookings] in one database transaction: all or none, and far faster than one commit per booking. */
    fun createAll(bookings: List<NewTransaction>): Int
    fun updateCategory(id: Int, categoryId: Int?): Transaction?

    /**
     * One batch recategorization: applies every given category in one `transaction { }` (all or none) and returns
     * the number of rows that actually changed. A booking already sitting on its target category is skipped,
     * not overwritten.
     */
    fun updateCategories(changes: Map<Int, Int?>): Int

    /**
     * Deletes one booking and remembers it as deleted, so a later import or sync cannot bring the same booking
     * back: the booking's account and dedup hash go into [DeletedBookingsTable] in the same `transaction { }`,
     * before the row goes, so both happen or neither. Returns whether the booking existed. A booking whose hash
     * is already a deleted one never writes a second row. A linked document is unlinked (SQLite doesn't enforce
     * the foreign key), and since the booking is gone for good, remembering it survives the delete.
     */
    fun delete(id: Int): Boolean

    /**
     * Deletes every booking of [ids] in one `transaction { }` (all or nothing) and unlinks their documents first,
     * like [delete] does (SQLite doesn't enforce the foreign key). Each deleted booking is remembered as deleted
     * (account and hash, same `transaction { }`) so a later import or sync cannot bring it back; an (account, hash)
     * pair already remembered is not recorded twice. Returns how many rows were deleted; unknown ids are ignored
     * and an empty collection does nothing. The ids are processed in chunks of at most
     * [TransactionRepositoryImpl.DELETE_CHUNK] per statement, so a selection can hold the whole history without
     * ever exceeding SQLite's bound-parameter limit.
     */
    fun deleteAll(ids: Collection<Int>): Int

    /**
     * Sets the manual tax override of every booking in [ids] in one `transaction { }` and returns how many rows
     * changed; `value = null` resets them to automatic. Unknown ids are ignored and an empty collection does
     * nothing. The ids are processed in chunks of at most [TransactionRepositoryImpl.TAX_CHUNK] per statement,
     * like [deleteAll], so a selection can hold the whole history.
     */
    fun setTaxOverride(ids: Collection<Int>, value: Boolean?): Int
    fun findByHash(hash: String): Transaction?

    /**
     * The dedup hashes remembered as deleted for [accountId] (see [deleteAll]): the bookings a later import or
     * sync of this account must not bring back again.
     */
    fun deletedHashes(accountId: Int): Set<String>

    /**
     * Lets the bookings deleted in [accountId] be imported again: removes every remembered deletion of the
     * account and returns how many pairs were remembered.
     */
    fun forgetDeleted(accountId: Int): Int

    /** Whether [accountId] has any booking yet; used to keep a bank's first import from looking all new. */
    fun hasBookings(accountId: Int): Boolean

    /** New (unread) bookings per account; accounts with none are absent from the map. */
    fun countNew(): Map<Int, Int>

    /**
     * Marks every booking of [accountIds] as read (null = all accounts; an empty set does nothing). Covers the whole
     * account, not only the period on screen, so a late booking dated last month cannot keep the counter up.
     */
    fun markRead(accountIds: Set<Int>?)

    /** Filtered, newest-first snapshot safe to use outside a transaction. */
    fun findItems(filter: TransactionFilter = TransactionFilter(), limit: Int? = null): List<TransactionItem>

    /** Number of bookings on [accountId] with this booking date and amount (for cross-source duplicate checks). */
    fun countSameBooking(accountId: Int, date: LocalDate, amount: BigDecimal): Int

    /**
     * The oldest booking date (in [currency] when given, e.g. the primary one), from a single aggregate query.
     * Null when there is no booking at all.
     */
    fun oldestDate(currency: String? = null): LocalDate?
}

class TransactionRepositoryImpl : TransactionRepository {
    override fun findAll(): List<Transaction> = transaction {
        Transaction.all().orderBy(TransactionsTable.date to SortOrder.DESC).toList()
    }

    override fun findById(id: Int): Transaction? = transaction {
        Transaction.findById(id)
    }

    override fun findByCategory(categoryId: Int): List<Transaction> = transaction {
        Transaction.find { TransactionsTable.categoryId eq categoryId }
            .orderBy(TransactionsTable.date to SortOrder.DESC)
            .toList()
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
        balanceAfter: BigDecimal?,
        isNew: Boolean,
    ): Transaction = transaction {
        val account = Account.findById(accountId)
            ?: throw IllegalArgumentException("Account with id $accountId not found")
        val category = categoryId?.let { Category.findById(it) }
        Transaction.new {
            this.account = account
            this.date = date
            this.amount = amount
            this.balanceAfter = balanceAfter
            this.currency = currency
            this.partnerName = partnerName
            this.purpose = purpose
            this.category = category
            this.hash = hash
            this.createdAt = LocalDateTime.now()
            this.isNew = isNew
        }
    }

    override fun createAll(bookings: List<NewTransaction>): Int = transaction {
        bookings.forEach { b ->
            create(b.accountId, b.date, b.amount, b.currency, b.partnerName, b.purpose, b.categoryId, hash = b.hash, balanceAfter = b.balanceAfter, isNew = b.isNew)
        }
        bookings.size
    }

    override fun updateCategory(id: Int, categoryId: Int?): Transaction? = transaction {
        Transaction.findById(id)?.apply {
            category = categoryId?.let { Category.findById(it) }
        }
    }

    override fun updateCategories(changes: Map<Int, Int?>): Int = transaction {
        var updated = 0
        changes.forEach { (id, categoryId) ->
            val booking = Transaction.findById(id) ?: return@forEach
            // A booking that already sits on its target would be a no-op write: skip it, so the count stays honest
            if (booking.category?.id?.value != categoryId) {
                booking.category = categoryId?.let { Category.findById(it) }
                updated++
            }
        }
        updated
    }

    override fun delete(id: Int): Boolean = transaction {
        // The booking's account and hash are read before the row goes, so the deletion is remembered for dedup
        rememberDeleted(chunk = listOf(id))
        // Documents linked to the booking would dangle otherwise; SQLite doesn't enforce the FK.
        DocumentsTable.update({ DocumentsTable.linkedTransactionId eq id }) { it[linkedTransactionId] = null }
        Transaction.findById(id)?.delete() != null
    }

    override fun deleteAll(ids: Collection<Int>): Int {
        if (ids.isEmpty()) return 0
        return transaction {
            var deleted = 0
            ids.chunked(DELETE_CHUNK).forEach { chunk ->
                with(SqlExpressionBuilder) {
                    val entityIds = chunk.map { EntityID(it, TransactionsTable) }
                    // Read before the rows go, so the deletion is remembered for dedup
                    rememberDeleted(chunk)
                    // Documents linked to the bookings would dangle otherwise; SQLite doesn't enforce the FK.
                    DocumentsTable.update({ DocumentsTable.linkedTransactionId inList entityIds }) { it[linkedTransactionId] = null }
                    deleted += TransactionsTable.deleteWhere { TransactionsTable.id inList entityIds }
                }
            }
            deleted
        }
    }

    /**
     * Must run inside a transaction. Reads the (account, hash) pairs of the bookings [chunk] names and records
     * each pair in [DeletedBookingsTable] that isn't remembered yet, so a later import or sync skips those
     * bookings instead of bringing them back.
     */
    private fun rememberDeleted(chunk: Collection<Int>) {
        if (chunk.isEmpty()) return
        val entityIds = chunk.map { EntityID(it, TransactionsTable) }
        val pairs = TransactionsTable
            .select(TransactionsTable.accountId, TransactionsTable.hash)
            .where { TransactionsTable.id inList entityIds }
            .map { it[TransactionsTable.accountId].value to it[TransactionsTable.hash] }
            .distinct()
            .toSet()
        if (pairs.isEmpty()) return
        val accountIds = pairs.map { it.first }
        val hashes = pairs.map { it.second }
        val already = DeletedBookingsTable
            .select(DeletedBookingsTable.accountId, DeletedBookingsTable.hash)
            .where { (DeletedBookingsTable.accountId inList accountIds.map { EntityID(it, AccountsTable) }) and (DeletedBookingsTable.hash inList hashes) }
            .map { it[DeletedBookingsTable.accountId].value to it[DeletedBookingsTable.hash] }
            .toSet()
        pairs.forEach { pair ->
            if (pair !in already) {
                DeletedBookingsTable.insert {
                    it[DeletedBookingsTable.accountId] = EntityID(pair.first, AccountsTable)
                    it[DeletedBookingsTable.hash] = pair.second
                    it[deletedAt] = LocalDateTime.now()
                }
            }
        }
    }

    override fun findByHash(hash: String): Transaction? = transaction {
        Transaction.find { TransactionsTable.hash eq hash }.firstOrNull()
    }

    override fun deletedHashes(accountId: Int): Set<String> = transaction {
        DeletedBookingsTable
            .select(DeletedBookingsTable.hash)
            .where { DeletedBookingsTable.accountId eq accountId }
            .map { it[DeletedBookingsTable.hash] }
            .toSet()
    }

    override fun forgetDeleted(accountId: Int): Int = transaction {
        DeletedBookingsTable.deleteWhere { DeletedBookingsTable.accountId eq accountId }
    }

    override fun setTaxOverride(ids: Collection<Int>, value: Boolean?): Int {
        if (ids.isEmpty()) return 0
        return transaction {
            var changed = 0
            ids.chunked(TAX_CHUNK).forEach { chunk ->
                with(SqlExpressionBuilder) {
                    val entityIds = chunk.map { EntityID(it, TransactionsTable) }
                    changed += TransactionsTable.update({ TransactionsTable.id inList entityIds }) { it[taxOverride] = value }
                }
            }
            changed
        }
    }

    override fun findItems(filter: TransactionFilter, limit: Int?): List<TransactionItem> = transaction {
        val conditions = with(SqlExpressionBuilder) {
            buildList<Op<Boolean>> {
                filter.startDate?.let { add(TransactionsTable.date greaterEq it) }
                filter.endDate?.let { add(TransactionsTable.date lessEq it) }
                filter.accountId?.let { add(TransactionsTable.accountId eq it) }
                filter.accountIds?.let { ids ->
                    // An empty set must match nothing (an empty group shows no bookings), not everything.
                    if (ids.isEmpty()) add(Op.FALSE) else add(TransactionsTable.accountId inList ids)
                }
                filter.categoryId?.let { add(TransactionsTable.categoryId eq it) }
                filter.currency?.let { add(TransactionsTable.currency eq it) }
                if (filter.uncategorizedOnly) add(TransactionsTable.categoryId.isNull())
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
        // taxRelevantOnly decides on the effective value (override, rule, category) below, so its limit applies
        // after that filter, not here
        val taxOnly = filter.taxRelevantOnly
        val transactions = (if (limit != null && !taxOnly) query.limit(limit) else query).toList()

        // The rule layer of the effective tax relevance: the auto-apply rules with a tax flag, loaded and compiled
        // once per call (usually few) and matched per booking against its current category, in the matcher's order
        // and with the same match as categorization. Evaluated at read time; never stored on the bookings.
        val taxRules = Rule
            .find { (RulesTable.autoApply eq true) and (RulesTable.taxRelevant.isNotNull()) }
            .orderBy(RulesTable.priority to SortOrder.DESC, RulesTable.id to SortOrder.ASC)
            .toList()
            .map { rule ->
                TaxRuleSpec(
                    RuleSpec(rule.pattern, rule.category.id.value, RuleField.fromKey(rule.matchField), RuleDirection.fromKey(rule.direction)),
                    taxRelevant = rule.taxRelevant == true,
                )
            }

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
        val items = transactions.map { txn ->
            // A stored override wins without looking at the rules; a rule must own the booking's current category
            val ruleTax = if (txn.taxOverride == null) {
                taxRules.firstOrNull { it.spec.categoryId == txn.category?.id?.value && it.matches(txn.purpose, txn.partnerName, txn.amount) }
            } else {
                null
            }
            txn.toItem(docCounts[txn.id.value] ?: 0, ruleTax?.taxRelevant, ruleTax?.spec?.pattern)
        }
        return@transaction if (taxOnly) {
            val relevant = items.filter { it.isTaxRelevant }
            if (limit != null) relevant.take(limit) else relevant
        } else {
            items
        }
    }

    override fun countSameBooking(accountId: Int, date: LocalDate, amount: BigDecimal): Int = transaction {
        TransactionsTable.selectAll().where {
            (TransactionsTable.accountId eq accountId) and (TransactionsTable.date eq date) and (TransactionsTable.amount eq amount)
        }.count().toInt()
    }

    override fun hasBookings(accountId: Int): Boolean = transaction {
        Transaction.find { TransactionsTable.accountId eq accountId }.limit(1).any()
    }

    override fun oldestDate(currency: String?): LocalDate? = transaction {
        val oldest = TransactionsTable.date.min()
        val condition = with(SqlExpressionBuilder) {
            if (currency != null) TransactionsTable.currency eq currency else Op.TRUE
        }
        TransactionsTable.select(oldest).where { condition }.firstOrNull()?.get(oldest)
    }

    override fun countNew(): Map<Int, Int> = transaction {
        val count = TransactionsTable.id.count()
        TransactionsTable
            .select(TransactionsTable.accountId, count)
            .where { TransactionsTable.isNew eq true }
            .groupBy(TransactionsTable.accountId)
            .associate { it[TransactionsTable.accountId].value to it[count].toInt() }
    }

    override fun markRead(accountIds: Set<Int>?) {
        // An empty set names no account, so there is nothing to clear
        if (accountIds != null && accountIds.isEmpty()) return
        transaction {
            with(SqlExpressionBuilder) {
                val condition = if (accountIds == null) {
                    TransactionsTable.isNew eq true
                } else {
                    (TransactionsTable.accountId inList accountIds) and (TransactionsTable.isNew eq true)
                }
                TransactionsTable.update({ condition }) { it[isNew] = false }
            }
        }
    }

    private companion object {
        /** Batch-delete chunk size: SQLite's bound-parameter limit is at least 999, so 500 ids per statement is safe. */
        const val DELETE_CHUNK = 500

        /** Chunk size for the batch tax override, same limit as [DELETE_CHUNK]. */
        const val TAX_CHUNK = 500
    }
}

/**
 * One auto-apply rule with a tax flag, loaded and compiled once per [TransactionRepositoryImpl.findItems] call:
 * the rule layer of the effective tax relevance. Matching goes through [RuleMatcher.matches] over every view of
 * [RuleMatcher.views], so a tax-flagged shop rule applies to the PayPal bookings the shop view categorized, and
 * the tax lookup can never match categorization differently.
 */
private class TaxRuleSpec(val spec: RuleSpec, val taxRelevant: Boolean) {
    private val test: (String) -> Boolean = RuleMatcher.compile(spec.pattern)

    fun matches(purpose: String?, partnerName: String?, amount: BigDecimal?): Boolean = RuleMatcher.views(purpose, partnerName).any { (viewPurpose, viewPartner) ->
        RuleMatcher.matches(spec, test, viewPurpose, viewPartner, amount)
    }
}
