package de.findesk.repository

import de.findesk.TestData
import de.findesk.TestDatabase
import de.findesk.model.Direction
import de.findesk.model.TransactionFilter
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate

class TransactionItemsTest {
    private lateinit var data: TestData
    private var account = 0
    private var food = 0
    private var office = 0

    @BeforeEach
    fun setup() {
        TestDatabase.init()
        data = TestData()
        account = data.account()
        food = data.category("Food")
        office = data.category("Office", taxRelevant = true)
        data.txn(account, LocalDate.of(2026, 3, 1), "-10.00", partner = "REWE", purpose = "Groceries", categoryId = food)
        data.txn(account, LocalDate.of(2026, 3, 15), "-99.00", partner = "Staples", purpose = "Printer paper", categoryId = office)
        data.txn(account, LocalDate.of(2026, 3, 31), "3000.00", partner = "Employer GmbH", purpose = "Gehalt März")
        data.txn(account, LocalDate.of(2026, 4, 1), "-5.00", partner = null, purpose = "Kartenzahlung Kiosk")
    }

    @Test
    fun `items expose category data outside the transaction`() {
        val items = data.transactionRepo.findItems()
        val rewe = items.first { it.partnerName == "REWE" }
        // This used to throw "Can't init value outside the transaction"
        assertEquals("Food", rewe.categoryName)
        assertFalse(rewe.isTaxRelevant)
        assertEquals(LocalDate.of(2026, 4, 1), items.first().date, "newest first")
    }

    @Test
    fun `filters by date range direction category and text`() {
        val march = TransactionFilter(startDate = LocalDate.of(2026, 3, 1), endDate = LocalDate.of(2026, 3, 31))
        assertEquals(3, data.transactionRepo.findItems(march).size)
        assertEquals(1, data.transactionRepo.findItems(march.copy(direction = Direction.INCOME)).size)
        assertEquals(2, data.transactionRepo.findItems(march.copy(direction = Direction.EXPENSES)).size)
        assertEquals(listOf("Staples"), data.transactionRepo.findItems(TransactionFilter(categoryId = office)).map { it.partnerName })
        assertEquals(2, data.transactionRepo.findItems(TransactionFilter(uncategorizedOnly = true)).size)
        assertEquals(1, data.transactionRepo.findItems(TransactionFilter(taxRelevantOnly = true)).size)
    }

    @Test
    fun `text search matches purpose as well as partner name, case-insensitively`() {
        assertEquals(1, data.transactionRepo.findItems(TransactionFilter(text = "kiosk")).size)
        assertEquals(1, data.transactionRepo.findItems(TransactionFilter(text = "printer")).size)
        assertEquals(1, data.transactionRepo.searchByText("Kiosk").size)
    }

    @Test
    fun `limit returns the newest rows`() {
        val items = data.transactionRepo.findItems(limit = 2)
        assertEquals(2, items.size)
        assertEquals(BigDecimal("-5.00"), items[0].amount)
    }

    @Test
    fun `deleting an account removes its transactions and unlinks documents`() {
        val txnId = data.transactionRepo.findItems().first().id
        val doc = data.documentRepo.create("r.pdf", "/tmp/r.pdf", "application/pdf", linkedTransactionId = txnId)

        assertTrue(data.accountRepo.deleteWithTransactions(account))

        assertTrue(data.transactionRepo.findItems().isEmpty())
        assertNull(data.documentRepo.findItem(doc.id.value)!!.linkedTransactionId)
    }

    @Test
    fun `rule matching ignores nulls and survives invalid regex`() {
        data.ruleRepo.create("[unclosed", food, priority = 10)
        data.ruleRepo.create("null", office, priority = 5)
        // Invalid regex falls back to substring match instead of throwing
        assertEquals("Food", data.ruleRepo.matchTransaction("buy [unclosed thing", null)?.let { c -> data.categoryRepo.findAllItems().first { it.id == c.id.value }.name })
        // Null fields must not turn into the literal text "null"
        assertNull(data.ruleRepo.matchTransaction("Coffee", null))
    }
}
