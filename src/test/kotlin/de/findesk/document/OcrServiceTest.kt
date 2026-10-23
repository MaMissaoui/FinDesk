package de.findesk.document

import de.findesk.TestDatabase
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.io.TempDir
import java.awt.image.BufferedImage
import java.io.File
import java.math.BigDecimal

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OcrServiceTest {
    @TempDir
    lateinit var tmp: File

    private lateinit var service: OcrService

    @BeforeAll
    fun setup() {
        TestDatabase.init()
        service = OcrService()
    }

    @Test
    fun `test ocr result data class`() {
        val result = OcrService.OcrResult(
            text = "Sample OCR text",
            language = "de",
            confidence = BigDecimal("85.5"),
            pageCount = 1,
        )

        assertEquals("Sample OCR text", result.text)
        assertEquals("de", result.language)
        assertEquals(BigDecimal("85.5"), result.confidence)
        assertEquals(1, result.pageCount)
    }

    @Test
    fun `test search result data class`() {
        val result = OcrService.SearchResult(
            documentId = 1,
            fileName = "test.pdf",
            matchingText = "matching text",
            confidence = BigDecimal("90.0"),
        )

        assertEquals(1, result.documentId)
        assertEquals("test.pdf", result.fileName)
    }

    @Test
    fun `processDocument returns null without calling the engine for an oversized image`() = runBlocking {
        val file = File(tmp, "huge.png").apply { craftPng(60000, 60000, this) }
        val engine = CountingEngine()
        val ocr = OcrService(engine = engine)

        assertNull(ocr.processDocument(file))
        assertEquals(0, engine.calls)
    }

    private class CountingEngine : OcrEngine {
        var calls = 0

        override fun recognize(image: BufferedImage): OcrEngine.Recognition {
            calls++
            return OcrEngine.Recognition("", 0.0)
        }
    }
}
