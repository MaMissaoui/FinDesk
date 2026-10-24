package de.findesk.ui.components

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.jetbrains.skia.Codec
import org.jetbrains.skia.Data
import org.jetbrains.skia.Image

/** Decodes the bank's photoTAN challenge image, refusing ones too large to be a real challenge (a crafted size would allocate gigabytes). */
internal object TanImage {
    /** 16 megapixels: far above any photoTAN graphic (a few hundred pixels square). */
    const val MAX_PIXELS = 16_000_000L

    fun allowed(width: Int, height: Int): Boolean = width > 0 && height > 0 && width.toLong() * height <= MAX_PIXELS

    /** Null when the bytes aren't an image Skia can read or the image is over [MAX_PIXELS]. */
    fun decode(bytes: ByteArray): ImageBitmap? = runCatching {
        Codec.makeFromData(Data.makeFromBytes(bytes)).use { codec ->
            if (!allowed(codec.width, codec.height)) return@runCatching null
            Image.makeFromEncoded(bytes).toComposeImageBitmap()
        }
    }.getOrNull()
}
