package de.findesk.document

import de.findesk.i18n.AppStrings
import de.findesk.i18n.StringKey
import java.io.File
import javax.imageio.ImageIO
import javax.imageio.ImageReader
import javax.imageio.stream.ImageInputStream
import kotlin.math.sqrt

/**
 * Size and resolution limits for attached documents.
 *
 * Attached files can come from anyone, so a crafted file must not be able to exhaust memory
 * while it is being copied, parsed, rendered or OCR-ed.
 */
internal object DocumentLimits {
    const val MAX_DOCUMENT_BYTES = 100L * 1024 * 1024
    const val MAX_PIXELS = 100_000_000L
    const val MAX_TEXT_PAGES = 500

    /**
     * Reads the width and height from an image file's header without decoding the pixels.
     * Returns null when no reader is available or the header cannot be read.
     */
    fun imageDimensions(file: File): Pair<Int, Int>? {
        var stream: ImageInputStream? = null
        var reader: ImageReader? = null
        return try {
            stream = ImageIO.createImageInputStream(file) ?: return null
            val readers = ImageIO.getImageReaders(stream)
            if (!readers.hasNext()) return null
            reader = readers.next()
            reader.setInput(stream, true, true)
            reader.getWidth(0) to reader.getHeight(0)
        } catch (_: Exception) {
            null
        } finally {
            reader?.dispose()
            stream?.close()
        }
    }

    /**
     * Throws [IllegalArgumentException] when the image's pixel count exceeds [MAX_PIXELS].
     * Images whose dimensions cannot be read are left to the caller's decoder.
     */
    fun checkImage(file: File) {
        val (width, height) = imageDimensions(file) ?: return
        if (width.toLong() * height > MAX_PIXELS) {
            throw IllegalArgumentException(AppStrings.format(StringKey.ErrorImageTooLarge, file.name))
        }
    }

    /**
     * Lowers [scale] so that `widthPt * scale` by `heightPt * scale` does not exceed [MAX_PIXELS].
     * Non-positive or non-finite page sizes leave [scale] unchanged.
     */
    fun cappedScale(widthPt: Float, heightPt: Float, scale: Float): Float {
        if (widthPt <= 0f || heightPt <= 0f || !widthPt.isFinite() || !heightPt.isFinite()) {
            return scale
        }
        val maxScale = sqrt(MAX_PIXELS.toDouble() / (widthPt.toDouble() * heightPt.toDouble()))
        return minOf(scale, maxScale.toFloat())
    }
}
