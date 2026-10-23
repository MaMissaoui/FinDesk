package de.findesk.document

import org.apache.pdfbox.Loader
import org.apache.pdfbox.rendering.ImageType
import org.apache.pdfbox.rendering.PDFRenderer
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/** Renders a thumbnail-sized preview of a stored document (first page for PDFs). */
object DocumentPreview {
    /** Returns null if the file is missing or can't be rendered. */
    fun render(file: File, maxWidth: Int = 600): BufferedImage? = runCatching {
        if (!file.isFile) return null
        val image = when (file.extension.lowercase()) {
            "pdf" -> Loader.loadPDF(file).use { doc ->
                if (doc.numberOfPages == 0) return null
                // Scale so the page is about maxWidth pixels wide (PDF units are 1/72 inch)
                val pageWidth = doc.getPage(0).mediaBox.width.takeIf { it > 0f } ?: 595f
                val scale = (maxWidth / pageWidth).coerceIn(0.2f, 4f)
                val cropBox = doc.getPage(0).cropBox
                val cappedScale = DocumentLimits.cappedScale(cropBox.width, cropBox.height, scale)
                PDFRenderer(doc).renderImage(0, cappedScale, ImageType.RGB)
            }

            else -> {
                DocumentLimits.checkImage(file)
                ImageIO.read(file) ?: return null
            }
        }
        scale(image, maxWidth)
    }.getOrNull()

    private fun scale(image: BufferedImage, maxWidth: Int): BufferedImage {
        if (image.width <= maxWidth) return image
        val height = (image.height.toLong() * maxWidth / image.width).toInt().coerceAtLeast(1)
        return BufferedImage(maxWidth, height, BufferedImage.TYPE_INT_RGB).also { out ->
            val g = out.createGraphics()
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            g.drawImage(image, 0, 0, maxWidth, height, null)
            g.dispose()
        }
    }
}
