package de.findesk.document

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.math.BigDecimal
import java.time.LocalDate

/** Runs only where Tesseract + deu/eng traineddata are installed. */
class TesseractOcrEngineTest {
    private fun receiptImage(vararg lines: String): BufferedImage {
        val image = BufferedImage(1200, 120 + lines.size * 70, BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()
        g.color = Color.WHITE
        g.fillRect(0, 0, image.width, image.height)
        g.color = Color.BLACK
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.font = Font(Font.SANS_SERIF, Font.PLAIN, 44)
        lines.forEachIndexed { i, line -> g.drawString(line, 60, 100 + i * 70) }
        g.dispose()
        return image
    }

    @Test
    fun `recognizes a rendered receipt and parser finds total and date`() {
        val engine = TesseractOcrEngine.createOrNull()
        assumeTrue(engine != null, "Tesseract not installed")

        val result = engine!!.recognize(
            receiptImage("REWE Markt GmbH", "Datum: 03.10.2026", "Milch 1,29", "Brot 2,49", "SUMME EUR 3,78"),
        )

        assertTrue(result.text.contains("REWE", ignoreCase = true), result.text)
        assertTrue(result.confidence > 50.0, "confidence ${result.confidence}")
        val fields = ReceiptParser.parse(result.text)
        assertEquals(BigDecimal("3.78"), fields.total, result.text)
        assertEquals(LocalDate.of(2026, 10, 3), fields.date, result.text)
    }
}
