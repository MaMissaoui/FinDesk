package de.findesk.importer

import de.findesk.TestData
import de.findesk.TestDatabase
import de.findesk.model.RuleDirection
import de.findesk.model.RuleField
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** An EXPENSE-only rule matches the expense of a partner but not the income booked with the same name. */
class RuleDirectionImportTest {
    @TempDir
    lateinit var tmp: File

    private lateinit var data: TestData
    private val importer = StatementImportService()
    private var account = 0
    private var groceries = 0

    @BeforeEach
    fun setup() {
        TestDatabase.init()
        data = TestData()
        account = data.account()
        groceries = data.category("Groceries")
        data.ruleRepo.create(
            "Erika Mustermann",
            groceries,
            priority = 5,
            matchField = RuleField.PARTNER,
            direction = RuleDirection.EXPENSE,
        )
    }

    private fun csv(vararg rows: String) = File(tmp, "s${System.nanoTime()}.csv").apply {
        writeText("Buchungstag;Betrag;Beguenstigter/Zahlungspflichtiger;Verwendungszweck\n" + rows.joinToString("\n") + "\n")
    }

    @Test
    fun `statement import categorizes only the expense`() = runBlocking {
        importer.import(account, importer.read(csv("03.10.2026;-45,67;Erika Mustermann;Kartenzahlung", "03.10.2026;1.000,00;Erika Mustermann;Rueckzahlung")))

        val stored = data.transactionRepo.findItems().associate { it.amount.signum() to it.categoryId }
        assertEquals(groceries, stored[-1], "the expense gets the rule's category")
        assertNull(stored[1], "the income does not match an EXPENSE-only rule")
    }

    @Test
    fun `auto categorize via the view model matches only the expense`() = runBlocking {
        data.txn(data.account(), java.time.LocalDate.of(2026, 10, 3), "-45.67", partner = "Erika Mustermann")
        data.txn(data.account(), java.time.LocalDate.of(2026, 10, 3), "1000.00", partner = "Erika Mustermann")

        de.findesk.viewmodel.TransactionsViewModel()
            .also {
                it.autoCategorizeTransactions().join()
                it.close()
            }

        val stored = data.transactionRepo.findItems().associate { it.amount.signum() to it.categoryId }
        assertEquals(groceries, stored[-1], "the expense gets the rule's category")
        assertNull(stored[1], "the income does not match an EXPENSE-only rule")
    }
}
