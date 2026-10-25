package de.findesk.viewmodel

import de.findesk.TestData
import de.findesk.TestDatabase
import de.findesk.document.TaxSuggestions
import de.findesk.repository.AuditLogRepositoryImpl
import de.findesk.repository.DocumentRepositoryImpl
import de.findesk.repository.SettingsRepositoryImpl
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.LocalDate

/**
 * The Reports screen's Tax bookings tab as a view model: loading fills the tab together with the report,
 * the suggestions turn into manual overrides with one audit entry each, and the filter is display-only.
 */
class TaxTabTest {
    @TempDir
    lateinit var tmp: File

    private lateinit var data: TestData
    private val audit = AuditLogRepositoryImpl()
    private var income = 0
    private var deduction = 0

    @BeforeEach
    fun setup() {
        TestDatabase.init()
        data = TestData()
        income = data.category("Gehalt", taxRelevant = true, color = "#4CAF50")
        deduction = data.category("Spenden", taxRelevant = true, color = "#9C27B0")
    }

    /** A booking with a receipt, so it has documentCount 1 and appears under "with receipt". */
    private fun withReceipt(account: Int, date: LocalDate, amount: String, partner: String, purpose: String, categoryId: Int?): Int {
        val id = data.txn(account, date, amount, partner = partner, purpose = purpose, categoryId = categoryId)
        DocumentRepositoryImpl().create(fileName = "Beleg.pdf", filePath = "documents/beleg-$id.pdf", mimeType = "application/pdf", linkedTransactionId = id)
        return id
    }

    @Test
    fun `loading a year fills the tab and accepting and dismissing suggestions write one audit entry each`() = runBlocking {
        val account = data.account()
        val salary = data.txn(account, LocalDate.of(2026, 2, 1), "2000.00", partner = "Beispiel AG", categoryId = income)
        val donation = data.txn(account, LocalDate.of(2026, 3, 1), "-120.00", partner = "Beispielhilfe e. V.", purpose = "Spende", categoryId = deduction)
        val suggested = data.txn(account, LocalDate.of(2026, 4, 1), "-30.00", partner = "Beispiel-Apotheke", purpose = "Medikamente")
        val notMatched = data.txn(account, LocalDate.of(2026, 5, 1), "-9.00", partner = "Bäckerei Beispiel")
        val vm = ReportsViewModel()

        vm.loadTaxReport(2026).join()
        val loaded = vm.state.value
        val taxBookings = loaded.taxBookings
        assertTrue(taxBookings != null, "loading fills taxBookings")
        with(taxBookings!!) {
            assertEquals(listOf(income), incomeGroups.map { it.categoryId }, "the salary category is an income group")
            assertEquals(listOf(deduction), deductionGroups.map { it.categoryId })
            assertEquals(2, bookingCount, "the two tax-relevant bookings are in")
        }
        assertEquals(listOf(suggested), loaded.suggestions.map { it.booking.id }, "the Apotheke booking is suggested, the 'Spende' one is already relevant")
        assertEquals("Apotheke", loaded.suggestions.single().keyword)

        vm.acceptSuggestion(suggested).join()
        val afterAccept = vm.state.value
        assertTrue(afterAccept.suggestions.isEmpty(), "the accepted suggestion is gone")
        val groups = afterAccept.taxBookings!!
        assertEquals(3, groups.bookingCount, "the accepted booking joined the tax bookings")
        assertTrue(groups.deductionGroups.flatMap { it.bookings }.any { it.id == suggested }, "the accepted booking is a deduction")

        val dismissed = data.txn(account, LocalDate.of(2026, 6, 1), "-200.00", partner = "Handwerker Beispiel")
        vm.loadTaxReport(2026).join()
        assertEquals(listOf(dismissed), vm.state.value.suggestions.map { it.booking.id })
        vm.dismissSuggestion(dismissed).join()
        assertTrue(vm.state.value.suggestions.isEmpty(), "the dismissed suggestion is hidden")
        vm.updateYear(2026).join()
        assertTrue(vm.state.value.suggestions.isEmpty(), "the dismissed suggestion stays hidden after a reload")

        val entries = audit.findByAction("SET_TAX_OVERRIDE")
        assertEquals(2, entries.size, "one entry per action: accept and dismiss")
        val words = entries.map { it.details.orEmpty() }
        assertTrue(words.any { it.contains("1") && it.contains("relevant") }, "the accept entry names count and value: $words")
        assertTrue(words.any { it.contains("1") && it.contains("not relevant") }, "the dismiss entry names count and value: $words")
        assertTrue(words.none { it.contains("Apotheke") }, "no partner text in the audit entries: $words")
        assertTrue(words.none { it.contains("-30.00") }, "no amount in the audit entries: $words")
        // The dismissed booking's override is stored for good
        val byId = data.transactionRepo.findItems().associateBy { it.id }
        assertEquals(false, byId[dismissed]!!.taxOverride)
        assertEquals(true, byId[suggested]!!.taxOverride)
        assertTrue(byId[salary]!!.isTaxRelevant, "the salary booking keeps its category relevance")
        vm.close()
    }

    @Test
    fun `markNotTaxRelevant removes a category relevant booking and resetTaxAutomatic brings it back`() = runBlocking {
        val account = data.account()
        val donation = data.txn(account, LocalDate.of(2026, 3, 1), "-120.00", partner = "Beispielhilfe e. V.", categoryId = deduction)
        val vm = ReportsViewModel()
        vm.loadTaxReport(2026).join()
        assertEquals(listOf(donation), vm.state.value.taxBookings!!.deductionGroups.single().bookings.map { it.id })

        vm.markNotTaxRelevant(donation).join()
        assertTrue(vm.state.value.taxBookings!!.deductionGroups.isEmpty(), "the booking is out of the groups")
        assertEquals(0, vm.state.value.taxBookings!!.bookingCount, "no tax-relevant bookings left in the period")
        val marked = data.transactionRepo.findItems().associateBy { it.id }[donation]!!
        assertFalse(marked.isTaxRelevant)
        assertEquals(de.findesk.model.TaxSource.MANUAL, marked.taxSource)
        assertEquals(false, marked.taxOverride)

        vm.resetTaxAutomatic(donation).join()
        val reset = data.transactionRepo.findItems().associateBy { it.id }[donation]!!
        assertNull(reset.taxOverride, "null resets to automatic")
        assertTrue(reset.isTaxRelevant, "the category flag decides again")
        assertEquals(listOf(donation), vm.state.value.taxBookings!!.deductionGroups.single().bookings.map { it.id })

        val entries = audit.findByAction("SET_TAX_OVERRIDE")
        assertEquals(2, entries.size, "one entry per action")
        val details = entries.map { it.details.orEmpty() }
        assertTrue(details.any { it.contains("1") && it.contains("not relevant") }, "$details")
        assertTrue(details.any { it.contains("1") && it.contains("automatic") }, "$details")
        assertTrue(details.none { it.contains("Beispiel") }, "no booking text in the audit entries: $details")
        vm.close()
    }

    @Test
    fun `setMissingReceiptsOnly filters rows but keeps the whole period's totals`() = runBlocking {
        val account = data.account()
        val withReceipt = withReceipt(account, LocalDate.of(2026, 3, 1), "-120.00", "Beispielhilfe e. V.", "Spende", deduction)
        data.txn(account, LocalDate.of(2026, 4, 1), "-30.00", partner = "Beispiel-Apotheke", purpose = "Medikamente", categoryId = deduction)
        val vm = ReportsViewModel()
        vm.loadTaxReport(2026).join()

        val before = vm.state.value.taxBookings!!
        assertEquals(2, before.deductionGroups.single().bookings.size)

        vm.setMissingReceiptsOnly(true).join()
        val after = vm.state.value
        assertTrue(after.missingReceiptsOnly, "the filter flag is on for the shown rows")
        // The stored tab data keeps the whole period; the UI drops only the receipt-carrying rows
        assertEquals(before.deductionTotal, after.taxBookings!!.deductionTotal, "the totals stay those of the whole period")
        assertEquals(before.bookingCount, after.taxBookings!!.bookingCount)
        assertEquals(before.deductionGroups.single().bookings.size, after.taxBookings!!.deductionGroups.single().bookings.size, "the data itself is unfiltered")
        vm.setMissingReceiptsOnly(false).join()
        assertFalse(vm.state.value.missingReceiptsOnly)
        vm.close()
    }

    @Test
    fun `saving keywords stores the cleaned list and reset returns to the defaults`() = runBlocking {
        val settings = SettingsRepositoryImpl()
        val vm = SettingsViewModel(dataDirectory = tmp.absolutePath)
        vm.load().join()
        assertEquals(TaxSuggestions.DEFAULT_KEYWORDS.joinToString("\n"), vm.state.value.taxKeywords)

        vm.saveTaxKeywords("  Steuerberater \n\n handwerker\nKita\nHANDWERKER  \n").join()
        assertEquals(listOf("Steuerberater", "handwerker", "Kita"), TaxSuggestions.keywords(settings), "trimmed, non-blank, de-duplicated case-insensitively")
        assertEquals("Steuerberater\nhandwerker\nKita", vm.state.value.taxKeywords)

        val entries = audit.findByAction("SAVE_TAX_KEYWORDS")
        assertEquals(1, entries.size)
        val details = entries.single().details.orEmpty()
        assertTrue(details.contains("3"), "the entry names the count only: $details")

        vm.resetTaxKeywords().join()
        assertEquals(TaxSuggestions.DEFAULT_KEYWORDS, TaxSuggestions.keywords(settings))
        assertEquals(TaxSuggestions.DEFAULT_KEYWORDS.joinToString("\n"), vm.state.value.taxKeywords)
        val resets = audit.findByAction("RESET_TAX_KEYWORDS")
        assertEquals(1, resets.size)
        assertTrue(resets.single().details.orEmpty().contains("3"))
        assertNull(settings.getValue(TaxSuggestions.KEY_KEYWORDS), "the custom list is gone")
        vm.close()
    }
}
