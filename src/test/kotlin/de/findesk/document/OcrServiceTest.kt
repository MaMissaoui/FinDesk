package de.findesk.document

import de.findesk.data.database.DatabaseFactory
import org.jetbrains.exposed.sql.Database
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import java.io.File
import java.math.BigDecimal

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OcrServiceTest {
    private lateinit var service: OcrService

    @BeforeAll
    fun setup() {
        Database.connect("jdbc:sqlite::memory:", driver = "org.sqlite.JDBC")
        DatabaseFactory.init()
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
}
