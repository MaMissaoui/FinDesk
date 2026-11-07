package de.findesk.viewmodel

import de.findesk.TestDatabase
import de.findesk.document.DocumentService
import de.findesk.document.OcrEngine
import de.findesk.document.OcrService
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.awt.image.BufferedImage
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.imageio.ImageIO

/** A multi-file upload holds one busy section for the whole batch, not one per file. */
class DocumentUploadBusyTest {
    @TempDir
    lateinit var tmp: File

    @BeforeEach
    fun setup() {
        TestDatabase.init()
    }

    /** Blocks on the second recognition, so the batch is provably still running. */
    private class BlockingSecondEngine(
        private val entered: CountDownLatch,
        private val release: CountDownLatch,
    ) : OcrEngine {
        private val calls = AtomicInteger(0)

        override fun recognize(image: BufferedImage): OcrEngine.Recognition {
            if (calls.incrementAndGet() == 2) {
                entered.countDown()
                release.await(5, TimeUnit.SECONDS)
            }
            return OcrEngine.Recognition("", 0.0)
        }
    }

    private fun png(name: String): File = File(tmp, name).apply {
        ImageIO.write(BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png", this)
    }

    @Test
    fun `the whole batch is busy and ends when the job completes`() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val service = DocumentService(
            ocrService = OcrService(engine = BlockingSecondEngine(entered, release)),
            documentsDir = File(tmp, "documents"),
        )
        val vm = DocumentsViewModel(documentService = service)

        val job = vm.uploadDocuments(listOf(png("first.png"), png("second.png")))
        assertTrue(entered.await(5, TimeUnit.SECONDS), "the second file reached OCR")
        assertTrue(Busy.active.value, "the batch stays busy while the second file is processed")

        release.countDown()
        job.join()

        assertFalse(Busy.active.value, "the busy section ends with the job")
        vm.close()
    }
}
