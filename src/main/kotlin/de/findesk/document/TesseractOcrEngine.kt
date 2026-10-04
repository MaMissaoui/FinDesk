package de.findesk.document

import net.sourceforge.tess4j.ITessAPI.TessPageIteratorLevel
import net.sourceforge.tess4j.Tesseract
import java.awt.image.BufferedImage
import java.io.File

/**
 * Tesseract via tess4j (JNA). Requires the native library and `deu`/`eng` traineddata,
 * e.g. `brew install tesseract tesseract-lang` on macOS or `apt install tesseract-ocr tesseract-ocr-deu`.
 */
class TesseractOcrEngine private constructor(
    private val tessdataPath: String,
    private val language: String,
) : OcrEngine {

    // Tesseract instances aren't thread-safe
    @Synchronized
    override fun recognize(image: BufferedImage): OcrEngine.Recognition {
        val tess = Tesseract().apply {
            setDatapath(tessdataPath)
            setLanguage(language)
            setPageSegMode(3) // fully automatic page segmentation
        }
        val lines = tess.getWords(image, TessPageIteratorLevel.RIL_TEXTLINE)
            .filter { it.text.isNotBlank() }
        val text = lines.joinToString("\n") { it.text.trimEnd() }
        val totalChars = lines.sumOf { it.text.length }
        val confidence = if (totalChars == 0) 0.0 else lines.sumOf { it.confidence.toDouble() * it.text.length } / totalChars
        return OcrEngine.Recognition(text, confidence)
    }

    companion object {
        private const val DEFAULT_LANGUAGE = "deu+eng"

        private val libraryDirs = listOf("/opt/homebrew/lib", "/usr/local/lib", "/usr/lib/x86_64-linux-gnu", "/usr/lib")
        private val tessdataDirs = listOfNotNull(
            System.getenv("TESSDATA_PREFIX"),
            "/opt/homebrew/share/tessdata",
            "/usr/local/share/tessdata",
            "/usr/share/tesseract-ocr/5/tessdata",
            "/usr/share/tesseract-ocr/4.00/tessdata",
            "/usr/share/tessdata",
            "C:\\Program Files\\Tesseract-OCR\\tessdata",
        )

        private val instance: TesseractOcrEngine? by lazy { probe() }

        /** Returns a working engine, or null if Tesseract or its language data isn't installed. */
        fun createOrNull(): OcrEngine? = instance

        private fun probe(): TesseractOcrEngine? {
            val tessdata = tessdataDirs.firstOrNull { dir ->
                DEFAULT_LANGUAGE.split('+').all { File(dir, "$it.traineddata").exists() }
            } ?: return null

            val existing = System.getProperty("jna.library.path").orEmpty()
            val extra = libraryDirs.filter { File(it).isDirectory }
            System.setProperty("jna.library.path", (listOf(existing) + extra).filter { it.isNotEmpty() }.joinToString(File.pathSeparator))

            return try {
                val engine = TesseractOcrEngine(tessdata, DEFAULT_LANGUAGE)
                engine.recognize(BufferedImage(32, 32, BufferedImage.TYPE_BYTE_GRAY)) // loads the native library
                engine
            } catch (e: Throwable) {
                // UnsatisfiedLinkError / NoClassDefFoundError when the native library is missing
                null
            }
        }
    }
}
