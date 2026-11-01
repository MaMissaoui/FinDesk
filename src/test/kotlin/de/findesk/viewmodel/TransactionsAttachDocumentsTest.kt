package de.findesk.viewmodel

import de.findesk.TestData
import de.findesk.TestDatabase
import de.findesk.document.DocumentService
import de.findesk.document.OcrEngine
import de.findesk.document.OcrService
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.awt.image.BufferedImage
import java.io.File
import java.time.LocalDate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO

/** Receipt uploads are busy and may only publish into the details panel that still shows their booking. */
class TransactionsAttachDocumentsTest {
    @TempDir
    lateinit var tmp: File

    private val today = LocalDate.of(2026, 10, 15)
    private lateinit var data: TestData

    @BeforeEach
    fun setup() {
        TestDatabase.init()
        data = TestData()
    }

    @AfterEach
    fun resetBusy() {
        // Busy is process-wide; leave it idle for the next test even if an assertion failed early
        repeat(10) { Busy.end() }
    }

    private fun png(name: String = "bon.png"): File = File(tmp, name).also {
        ImageIO.write(BufferedImage(5, 5, BufferedImage.TYPE_INT_RGB), "png", it)
    }

    private fun service(engine: OcrEngine, name: String = "docs") = DocumentService(
        ocrService = OcrService(engine = engine),
        documentsDir = File(tmp, name),
    )

    @Test
    fun `an upload keeps Busy active until the job finishes`() = runBlocking {
        val account = data.account()
        val booking = data.txn(account, today, "-12.00", partner = "Shop")
        val engine = BlockingEngine()
        val vm = TransactionsViewModel(today = { today }, documentService = service(engine))
        vm.start(accountId = account).join()
        vm.selectTransaction(vm.state.value.transactions.first { it.id == booking }).join()

        val upload = vm.attachDocuments(booking, listOf(png()))
        assertTrue(engine.entered.await(5, TimeUnit.SECONDS), "the upload reached OCR")
        assertTrue(Busy.active.value, "the top bar's switchers are disabled while the file is copied and read")

        engine.release.countDown()
        upload.join()
        assertFalse(Busy.active.value, "the busy section ends with the upload job")
        assertEquals(listOf("bon.png"), vm.state.value.selectedDocuments.map { it.fileName })
        vm.close()
    }

    @Test
    fun `a panel switched to another booking keeps that booking's documents`() = runBlocking {
        val account = data.account()
        val a = data.txn(account, today, "-12.00", partner = "Booking A")
        val b = data.txn(account, today.minusDays(1), "-8.00", partner = "Booking B")
        val engine = BlockingEngine()
        val vm = TransactionsViewModel(today = { today }, documentService = service(engine))
        vm.start(accountId = account).join()
        val bookings = vm.state.value.transactions.associateBy { it.id }
        vm.selectTransaction(bookings.getValue(a)).join()

        val upload = vm.attachDocuments(a, listOf(png()))
        assertTrue(engine.entered.await(5, TimeUnit.SECONDS), "the upload for A is running")

        // The panel changes to B before A's upload finishes.
        vm.selectTransaction(bookings.getValue(b)).join()
        assertEquals(b, vm.state.value.selected?.id)

        engine.release.countDown()
        upload.join()

        assertEquals(b, vm.state.value.selected?.id, "the panel stays on B")
        assertTrue(vm.state.value.selectedDocuments.isEmpty(), "A's uploaded receipt is not shown for B")
        vm.close()
    }

    /** Stops inside OCR until the test releases it, so an upload is still running. */
    private class BlockingEngine : OcrEngine {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)

        override fun recognize(image: BufferedImage): OcrEngine.Recognition {
            entered.countDown()
            release.await()
            return OcrEngine.Recognition("receipt", 1.0)
        }
    }
}
