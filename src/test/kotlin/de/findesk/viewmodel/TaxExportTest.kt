package de.findesk.viewmodel

import de.findesk.TestData
import de.findesk.TestDatabase
import de.findesk.document.TaxPrinter
import de.findesk.i18n.AppStrings
import de.findesk.i18n.StringKey
import de.findesk.repository.AuditLogRepositoryImpl
import kotlinx.coroutines.runBlocking
import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.PDDocument
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
 * The Tax bookings tab's exports and print through the view model: files land in a temp dir, the print
 * hands the fake printer the in-memory document and the view model closes it, and a cancelled or failing
 * print only sets the localized message — no audit entry, since exporting is read-only.
 */
class TaxExportTest {
    @TempDir
    lateinit var tmp: File

    private lateinit var data: TestData
    private val audit = AuditLogRepositoryImpl()

    /** Records what it is given; never opens a dialog or a real printer. */
    private class FakePrinter(private val result: Boolean = true, private val fail: Boolean = false) : TaxPrinter {
        val documents = mutableListOf<PDDocument>()
        val jobNames = mutableListOf<String>()

        override fun print(document: PDDocument, jobName: String): Boolean {
            documents += document
            jobNames += jobName
            if (fail) throw IllegalStateException("printer offline")
            return result
        }
    }

    @BeforeEach
    fun setup() {
        TestDatabase.init()
        data = TestData()
        val account = data.account()
        val donations = data.category("Spenden", taxRelevant = true, color = "#9C27B0")
        data.txn(account, LocalDate.of(2026, 3, 1), "-120.00", partner = "Beispielhilfe e. V.", purpose = "Spende", categoryId = donations)
    }

    @Test
    fun `exports write the files and set the saved message`() = runBlocking {
        val vm = ReportsViewModel()
        vm.loadTaxReport(2026).join()
        val auditBefore = audit.findAll().size

        val xlsx = File(tmp, "tax.xlsx")
        vm.exportTaxXlsx(xlsx).join()
        assertTrue(xlsx.isFile, "the workbook was written")
        assertEquals(AppStrings.format(StringKey.MessageSavedTo, xlsx.name), vm.state.value.message)
        assertNull(vm.state.value.error)
        assertFalse(vm.state.value.isExporting, "the busy flag is cleared")

        val pdf = File(tmp, "tax.pdf")
        vm.exportTaxPdf(pdf).join()
        assertTrue(pdf.isFile, "the PDF was written")
        assertEquals(AppStrings.format(StringKey.MessageSavedTo, pdf.name), vm.state.value.message)
        assertNull(vm.state.value.error)

        val pages = Loader.loadPDF(pdf).use { it.numberOfPages }
        assertTrue(pages >= 1)
        assertEquals(auditBefore, audit.findAll().size, "exports are read-only: no audit entry")
        vm.close()
    }

    @Test
    fun `printTax hands the fake printer the document and closes it`() = runBlocking {
        val printer = FakePrinter()
        val vm = ReportsViewModel(printer = printer)
        vm.loadTaxReport(2026).join()

        vm.printTax().join()
        val printed = printer.documents.single()
        assertEquals(1, printed.numberOfPages, "the synthetic booking fits on one page")
        assertEquals(
            AppStrings.format(StringKey.TaxExportTitle, vm.state.value.taxBookings!!.title),
            printer.jobNames.single(),
            "the job names the report",
        )
        assertEquals(AppStrings[StringKey.MessagePrintSent], vm.state.value.message)
        assertNull(vm.state.value.error)
        assertFalse(vm.state.value.isExporting)
        assertTrue(printed.document.isClosed, "the document is closed after printing")
        vm.close()
    }

    @Test
    fun `a cancelling printer sets no message and a failing one the localized error`() = runBlocking {
        val cancelling = FakePrinter(result = false)
        val cancelledVm = ReportsViewModel(printer = cancelling)
        cancelledVm.loadTaxReport(2026).join()
        cancelledVm.printTax().join()
        assertNull(cancelledVm.state.value.message, "a cancelled dialog shows no message")
        assertNull(cancelledVm.state.value.error)
        assertTrue(cancelling.documents.single().document.isClosed, "closed even when cancelled")
        cancelledVm.close()

        val failing = FakePrinter(fail = true)
        val failingVm = ReportsViewModel(printer = failing)
        failingVm.loadTaxReport(2026).join()
        failingVm.printTax().join()
        assertEquals(AppStrings[StringKey.ErrorPrintFailed], failingVm.state.value.error, "the error carries no exception text")
        assertNull(failingVm.state.value.message)
        assertTrue(failing.documents.single().document.isClosed, "closed even when printing failed")
        failingVm.close()
    }
}
