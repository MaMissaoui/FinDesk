package de.findesk.ui.components

import org.jetbrains.skia.Codec
import org.jetbrains.skia.Data
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater
import javax.imageio.ImageIO

class TanImageTest {
    @Test
    fun `allowed accepts reasonable sizes and rejects huge or zero ones`() {
        assertTrue(TanImage.allowed(400, 400))
        assertTrue(TanImage.allowed(4000, 4000))
        assertFalse(TanImage.allowed(4001, 4000))
        assertFalse(TanImage.allowed(60000, 60000))
        assertFalse(TanImage.allowed(0, 10))
    }

    @Test
    fun `decodes a real small png`() {
        val bytes = ByteArrayOutputStream().use { out ->
            val image = BufferedImage(200, 200, BufferedImage.TYPE_INT_RGB)
            ImageIO.write(image, "PNG", out)
            out.toByteArray()
        }

        val bitmap = TanImage.decode(bytes)

        assertNotNull(bitmap)
        assertEquals(200, bitmap!!.width)
        assertEquals(200, bitmap.height)
    }

    @Test
    fun `rejects a png that only declares a huge size`() {
        val bytes = pngHeaderOnly(60000, 60000)
        // The header is readable, so it is the size check that refuses it, not a decode failure
        Codec.makeFromData(Data.makeFromBytes(bytes)).use { assertEquals(60000, it.width) }

        assertNull(TanImage.decode(bytes))
    }

    @Test
    fun `random bytes decode to null`() {
        assertNull(TanImage.decode(byteArrayOf(1, 2, 3)))
    }

    private fun pngHeaderOnly(width: Int, height: Int): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))

        fun writeChunk(type: String, data: ByteArray) {
            val typeBytes = type.toByteArray(Charsets.US_ASCII)
            val chunk = ByteArrayOutputStream()
            chunk.write(data.size ushr 24 and 0xFF)
            chunk.write(data.size ushr 16 and 0xFF)
            chunk.write(data.size ushr 8 and 0xFF)
            chunk.write(data.size and 0xFF)
            chunk.write(typeBytes)
            chunk.write(data)

            val crc = CRC32()
            crc.update(typeBytes)
            crc.update(data)
            val value = crc.value.toInt()
            chunk.write(value ushr 24 and 0xFF)
            chunk.write(value ushr 16 and 0xFF)
            chunk.write(value ushr 8 and 0xFF)
            chunk.write(value and 0xFF)

            out.write(chunk.toByteArray())
        }

        val ihdr = ByteArrayOutputStream()
        ihdr.write(width ushr 24 and 0xFF)
        ihdr.write(width ushr 16 and 0xFF)
        ihdr.write(width ushr 8 and 0xFF)
        ihdr.write(width and 0xFF)
        ihdr.write(height ushr 24 and 0xFF)
        ihdr.write(height ushr 16 and 0xFF)
        ihdr.write(height ushr 8 and 0xFF)
        ihdr.write(height and 0xFF)
        ihdr.write(8) // bit depth
        ihdr.write(2) // colour type: RGB
        ihdr.write(0) // compression method
        ihdr.write(0) // filter method
        ihdr.write(0) // interlace method
        writeChunk("IHDR", ihdr.toByteArray())

        val raw = byteArrayOf(0, 0, 0, 0) // filter byte + one RGB pixel
        val deflater = Deflater()
        deflater.setInput(raw)
        deflater.finish()
        val idat = ByteArrayOutputStream()
        val buffer = ByteArray(1024)
        while (!deflater.finished()) {
            val count = deflater.deflate(buffer)
            idat.write(buffer, 0, count)
        }
        deflater.end()
        writeChunk("IDAT", idat.toByteArray())

        writeChunk("IEND", byteArrayOf())

        return out.toByteArray()
    }
}
