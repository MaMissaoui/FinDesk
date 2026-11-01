package de.findesk.viewmodel

import de.findesk.TestData
import de.findesk.TestDatabase
import de.findesk.document.ReportService
import de.findesk.importer.XlsxReader
import de.findesk.model.TransactionFilter
import de.findesk.model.TransactionItem
import de.findesk.repository.TransactionRepository
import de.findesk.repository.TransactionRepositoryImpl
import kotlinx.coroutines.runBlocking
import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.LocalDate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Exports and print may not use a cached report and bookings that belong to another period. */
class ReportsPeriodDataTest {
    @TempDir
    lateinit var tmp: File

    private lateinit var data: TestData

    @BeforeEach
    fun setup() {
        TestDatabase.init()
        data = TestData()
    }

    @Test
    fun `exports use the newly selected year while its load is still blocked`() = runBlocking {
        val account = data.account()
        val donations = data.category("Spenden", taxRelevant = true)
        data.txn(account, LocalDate.of(2026, 3, 1), "-100.00", partner = "YearOneSpende", categoryId = donations)
        data.txn(account, LocalDate.of(2027, 3, 1), "-200.00", partner = "YearTwoSpende", categoryId = donations)

        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val repository = BlockingYearRepository(2027, entered, release)
        val vm = ReportsViewModel(reportService = ReportService(transactionRepository = repository))

        vm.loadTaxReport(2026).join()
        assertTrue(vm.state.value.loadedRange!!.first == LocalDate.of(2026, 1, 1))

        // The year changes immediately, but its load stays blocked, so only year 1 is cached.
        val yearTwoLoad = vm.loadTaxReport(2027)
        assertTrue(entered.await(5, TimeUnit.SECONDS), "the year 2 load reached the repository")
        assertTrue(vm.state.value.loadedRange!!.first == LocalDate.of(2026, 1, 1), "the cached data is still year 1")

        val xlsx = File(tmp, "tax.xlsx")
        vm.exportTaxXlsx(xlsx).join()
        val rows = XlsxReader.firstSheetRows(xlsx.readBytes())
        assertTrue(rows.first().any { it.contains("2027") }, "the workbook is titled for the selected year: ${rows.first()}")
        assertTrue(rows.flatten().contains("200"), "the workbook holds year 2's deduction: $rows")

        val pdf = File(tmp, "tax.pdf")
        vm.exportToPdf(pdf).join()
        val text = Loader.loadPDF(pdf).use { PDFTextStripper().getText(it) }
        assertTrue(text.contains("YearTwoSpende"), "the PDF holds year 2's booking: $text")
        assertFalse(text.contains("YearOneSpende"), "the PDF does not hold year 1's booking: $text")

        release.countDown()
        yearTwoLoad.join()
        vm.close()
    }

    /** Blocks the first query of [blockedYear], so the previously loaded year stays cached. */
    private class BlockingYearRepository(
        private val blockedYear: Int,
        private val entered: CountDownLatch,
        private val release: CountDownLatch,
        private val delegate: TransactionRepository = TransactionRepositoryImpl(),
    ) : TransactionRepository by delegate {
        private val first = AtomicBoolean(true)

        override fun findItems(filter: TransactionFilter, limit: Int?): List<TransactionItem> {
            if (filter.startDate == LocalDate.of(blockedYear, 1, 1) && first.compareAndSet(true, false)) {
                entered.countDown()
                release.await()
            }
            return delegate.findItems(filter, limit)
        }
    }
}
