package de.findesk.document

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.awt.image.BufferedImage
import java.io.File
import java.util.zip.CRC32
import javax.imageio.ImageIO

class DocumentLimitsTest {
    @TempDir
    lateinit var tmp: File

    @Test
    fun `imageDimensions reads a crafted huge PNG header without decoding pixels`() {
        val file = File(tmp, "huge.png").apply { craftPng(60000, 60000, this) }
        assertEquals(60000 to 60000, DocumentLimits.imageDimensions(file))
    }

    @Test
    fun `checkImage rejects a crafted huge PNG`() {
        val file = File(tmp, "huge.png").apply { craftPng(60000, 60000, this) }
        val ex = assertThrows<IllegalArgumentException> { DocumentLimits.checkImage(file) }
        assertTrue(ex.message!!.contains(file.name))
    }

    @Test
    fun `checkImage accepts a small real PNG`() {
        val file = File(tmp, "tiny.png")
        ImageIO.write(BufferedImage(10, 10, BufferedImage.TYPE_INT_RGB), "png", file)
        assertEquals(10 to 10, DocumentLimits.imageDimensions(file))
        assertDoesNotThrow { DocumentLimits.checkImage(file) }
    }

    @Test
    fun `checkImage accepts a non-image file`() {
        val file = File(tmp, "notes.txt").apply { writeText("not an image") }
        assertNull(DocumentLimits.imageDimensions(file))
        assertDoesNotThrow { DocumentLimits.checkImage(file) }
    }

    @Test
    fun `cappedScale leaves A4 at 300 DPI unchanged and caps huge pages`() {
        val a4Scale = 300f / 72f
        assertEquals(a4Scale, DocumentLimits.cappedScale(595f, 842f, a4Scale))

        val hugeScale = DocumentLimits.cappedScale(14400f, 14400f, a4Scale)
        assertTrue(hugeScale > 0f)
        val pixels = 14400.0 * hugeScale * 14400.0 * hugeScale
        assertTrue(pixels <= DocumentLimits.MAX_PIXELS)
    }
}

/** A PNG that is only a header declaring [width] x [height] (valid CRCs, no pixel data), so nothing is decoded. */
internal fun craftPng(width: Int, height: Int, file: File) {
    val signature = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

    fun writeInt(value: Int): ByteArray = byteArrayOf(
        (value ushr 24).toByte(),
        (value ushr 16).toByte(),
        (value ushr 8).toByte(),
        value.toByte(),
    )

    fun chunk(type: String, data: ByteArray): ByteArray {
        val typeBytes = type.toByteArray(Charsets.US_ASCII)
        val crc = CRC32().apply {
            update(typeBytes, 0, typeBytes.size)
            update(data, 0, data.size)
        }.value.toInt()
        return writeInt(data.size) + typeBytes + data + writeInt(crc)
    }

    val ihdrData = writeInt(width) + writeInt(height) + byteArrayOf(8, 2, 0, 0, 0)
    val ihdr = chunk("IHDR", ihdrData)
    val iend = chunk("IEND", byteArrayOf())
    file.writeBytes(signature + ihdr + iend)
}
