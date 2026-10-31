package de.findesk.repository

import de.findesk.TestData
import de.findesk.TestDatabase
import de.findesk.data.table.TransactionsTable
import de.findesk.model.TransactionFilter
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate

/** The `IN (...)` lists built from a whole account history stay below SQLite's bound-parameter limit. */
class ChunkedInListTest {
    private lateinit var data: TestData
    private var account = 0

    @BeforeEach
    fun setup() {
        TestDatabase.init()
        data = TestData()
        account = data.account()
    }

    @Test
    fun `deleteAccountCascade unlinks documents in every chunk`() {
        val ids = createBookings("cascade", 1_250)
        // The 1st, 600th and 1,250th booking each sit in a different chunk
        val docs = listOf(0, 599, 1_249).mapIndexed { index, position ->
            document("cascade-$index", ids["cascade-$position"]!!)
        }

        assertTrue(data.accountRepo.deleteWithTransactions(account), "the account is deleted")

        assertNull(data.accountRepo.findById(account))
        assertTrue(
            data.transactionRepo.findItems(TransactionFilter(accountId = account)).isEmpty(),
            "all 1,250 bookings are gone",
        )
        docs.forEach { doc ->
            val item = data.documentRepo.findItem(doc.id.value)
            assertNotNull(item, "the document itself still exists")
            assertNull(item!!.linkedTransactionId, "the document is unlinked from the deleted booking")
        }
    }

    @Test
    fun `findItems merges document counts across chunks`() {
        val ids = createBookings("count", 1_250)
        // The first booking carries two documents; the other two sit in different chunks
        val first = ids["count-0"]!!
        val middle = ids["count-600"]!!
        val last = ids["count-1249"]!!
        document("count-first-a", first)
        document("count-first-b", first)
        document("count-middle", middle)
        document("count-last", last)

        val items = data.transactionRepo.findItems(TransactionFilter(accountId = account))

        assertEquals(1_250, items.size)
        val counts = items.associate { it.id to it.documentCount }
        assertEquals(2, counts[first], "both documents of the first booking are counted")
        assertEquals(1, counts[middle])
        assertEquals(1, counts[last])
        assertEquals(4, counts.values.sum(), "no document is counted twice across the chunks")
        val linked = setOf(first, middle, last)
        assertTrue(counts.filterKeys { it !in linked }.values.all { it == 0 }, "every other booking has no documents")
    }

    private fun createBookings(prefix: String, count: Int): Map<String, Int> {
        val bookings = (0 until count).map { n ->
            NewTransaction(
                accountId = account,
                date = LocalDate.of(2026, 1, 1).plusDays((n % 28).toLong()),
                amount = BigDecimal("-1.00"),
                currency = "EUR",
                partnerName = "Shop $n",
                purpose = null,
                categoryId = null,
                hash = "$prefix-$n",
            )
        }
        assertEquals(count, data.transactionRepo.createAll(bookings))
        return transaction {
            TransactionsTable
                .select(TransactionsTable.id, TransactionsTable.hash)
                .where { TransactionsTable.accountId eq account }
                .associate { it[TransactionsTable.hash] to it[TransactionsTable.id].value }
        }
    }

    private fun document(name: String, transactionId: Int) = data.documentRepo.create("$name.pdf", "/tmp/$name.pdf", "application/pdf", linkedTransactionId = transactionId)
}
