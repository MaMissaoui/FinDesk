package de.findesk.document

import de.findesk.repository.AuditLogRepository
import de.findesk.repository.AuditLogRepositoryImpl
import de.findesk.repository.DocumentRepository
import de.findesk.repository.DocumentRepositoryImpl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.pdfbox.Loader
import org.apache.pdfbox.rendering.ImageType
import org.apache.pdfbox.rendering.PDFRenderer
import org.apache.pdfbox.text.PDFTextStripper
import java.awt.image.BufferedImage
import java.io.File
import java.math.BigDecimal
import java.math.RoundingMode
import javax.imageio.ImageIO

/** Recognises text in a raster image. Implemented by [TesseractOcrEngine]. */
interface OcrEngine {
    data class Recognition(val text: String, val confidence: Double)

    fun recognize(image: BufferedImage): Recognition
}

/**
 * Extracts text from PDFs and images.
 *
 * - PDFs with a text layer: text is read directly with PDFBox (exact, confidence 100).
 * - Scanned PDFs and images: rendered/loaded and passed to the [engine] (Tesseract) if one is available.
 */
class OcrService(
    private val engine: OcrEngine? = TesseractOcrEngine.createOrNull(),
    private val auditLogRepository: AuditLogRepository = AuditLogRepositoryImpl(),
    private val documentRepository: DocumentRepository = DocumentRepositoryImpl(),
) {
    companion object {
        /** A PDF text layer with fewer characters than this is treated as a scan. */
        private const val MIN_TEXT_LAYER_CHARS = 20
        private const val RENDER_DPI = 300f
        private const val MAX_OCR_PAGES = 10
        private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "tiff", "tif")
    }

    enum class Source { TEXT_LAYER, OCR }

    val isOcrAvailable: Boolean get() = engine != null

    /**
     * Process a document. Returns null if the file type is unsupported, or if it needs OCR and no engine is available.
     */
    suspend fun processDocument(file: File): OcrResult? = withContext(Dispatchers.IO) {
        try {
            when (file.extension.lowercase()) {
                "pdf" -> processPdf(file)
                in IMAGE_EXTENSIONS -> processImage(file)
                else -> null
            }
        } catch (e: Exception) {
            auditLogRepository.log("OCR_ERROR", "system", "OCR failed for ${file.name}: ${e.message}")
            null
        }
    }

    private fun processPdf(file: File): OcrResult? = Loader.loadPDF(file).use { doc ->
        val text = PDFTextStripper().getText(doc).trim()
        if (text.length >= MIN_TEXT_LAYER_CHARS) {
            return OcrResult(
                text = text,
                language = detectLanguage(text),
                confidence = BigDecimal("100.00"),
                pageCount = doc.numberOfPages,
                source = Source.TEXT_LAYER,
            )
        }
        val ocr = engine ?: return null
        val renderer = PDFRenderer(doc)
        val pages = (0 until minOf(doc.numberOfPages, MAX_OCR_PAGES)).map { page ->
            ocr.recognize(renderer.renderImageWithDPI(page, RENDER_DPI, ImageType.GRAY))
        }
        val combined = pages.joinToString("\n\n") { it.text.trim() }.trim()
        OcrResult(
            text = combined,
            language = detectLanguage(combined),
            confidence = averageConfidence(pages),
            pageCount = doc.numberOfPages,
            source = Source.OCR,
        )
    }

    private fun processImage(file: File): OcrResult? {
        val ocr = engine ?: return null
        val image = ImageIO.read(file) ?: throw IllegalArgumentException(de.findesk.i18n.AppStrings.format(de.findesk.i18n.StringKey.ErrorUnreadableImage, file.name))
        val result = ocr.recognize(image)
        val text = result.text.trim()
        return OcrResult(
            text = text,
            language = detectLanguage(text),
            confidence = averageConfidence(listOf(result)),
            pageCount = 1,
            source = Source.OCR,
        )
    }

    private fun averageConfidence(results: List<OcrEngine.Recognition>): BigDecimal {
        if (results.isEmpty()) return BigDecimal.ZERO
        return BigDecimal(results.map { it.confidence }.average()).setScale(2, RoundingMode.HALF_UP)
    }

    /**
     * Detect language from text ("de" or "en") by counting common whole words.
     */
    internal fun detectLanguage(text: String): String {
        val words = text.lowercase().split(Regex("[^\\p{L}]+")).filter { it.isNotEmpty() }
        val german = setOf("der", "die", "das", "und", "ist", "von", "zu", "mit", "für", "rechnung", "summe", "betrag")
        val english = setOf("the", "and", "is", "of", "to", "for", "with", "invoice", "total", "amount")
        val germanCount = words.count { it in german }
        val englishCount = words.count { it in english }
        return if (englishCount > germanCount) "en" else "de"
    }

    /**
     * Search stored OCR text. Returns a snippet around the first match per document.
     */
    suspend fun searchOcrText(query: String): List<SearchResult> = withContext(Dispatchers.IO) {
        val q = query.trim()
        if (q.isEmpty()) return@withContext emptyList()
        documentRepository.findItems(query = q).mapNotNull { doc ->
            val content = documentRepository.getOcrText(doc.id) ?: return@mapNotNull null
            val index = content.indexOf(q, ignoreCase = true)
            if (index < 0) return@mapNotNull null
            val start = (index - 40).coerceAtLeast(0)
            val end = (index + q.length + 40).coerceAtMost(content.length)
            SearchResult(
                documentId = doc.id,
                fileName = doc.fileName,
                matchingText = content.substring(start, end).replace(Regex("\\s+"), " "),
                confidence = doc.ocrConfidence ?: BigDecimal.ZERO,
            )
        }
    }

    /**
     * OCR processing result
     */
    data class OcrResult(
        val text: String,
        val language: String,
        val confidence: BigDecimal,
        val pageCount: Int,
        val source: Source = Source.OCR,
    )

    /**
     * Search result from OCR text
     */
    data class SearchResult(
        val documentId: Int,
        val fileName: String,
        val matchingText: String,
        val confidence: BigDecimal,
    )
}
