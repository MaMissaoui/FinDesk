package de.findesk.banking

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.time.LocalDateTime

class ProtocolTraceTest {
    @Test
    fun `recorder redacts secrets that are only known after the line was logged`() {
        val recorder = TraceRecorder("Test Bank", "12345678", "300")

        recorder.record(3, "userId=1111111111 iban DE89370400440532013000 message body")
        recorder.addSecret("1111111111") // discovered later in the dialog

        val text = recorder.build().render("1.0.0")

        assertFalse(text.contains("1111111111"))
        assertFalse(text.contains("DE89370400440532013000"))
        assertTrue(text.contains("message body"))
        assertTrue(text.contains("bank: Test Bank"))
    }

    @Test
    fun `recorder masks spaced IBANs in the built trace`() {
        val recorder = TraceRecorder("Test Bank", "12345678", "300")

        recorder.record(3, "purpose IBAN DE89 3704 0044 0532 0130 00 Miete")

        val line = recorder.build().lines.single().message

        assertEquals("purpose IBAN ${BankingRedactor.MASK} Miete", line)
    }

    @Test
    fun `store keeps only the last trace and refuses to save when empty`() {
        ProtocolTraceStore.clear()
        val dir = Files.createTempDirectory("findesk-trace").toFile()
        val out = dir.resolve("trace.txt")

        assertThrows(IllegalStateException::class.java) { ProtocolTraceStore.save(out, "1.0.0") }

        ProtocolTraceStore.publish(sample())
        assertNotNull(ProtocolTraceStore.last())
        ProtocolTraceStore.save(out, "1.0.0")
        assertTrue(out.readText().contains("entries: 1"))
        assertTrue(out.readText().contains("the bank's answers are NOT masked"))
        // The trace holds the bank's answers; other users must not read it
        if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
            assertEquals(setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE), Files.getPosixFilePermissions(out.toPath()))
        }

        ProtocolTraceStore.publish(sample())
        ProtocolTraceStore.clear()
        assertNull(ProtocolTraceStore.last())
    }

    private fun sample() = ProtocolTrace(
        startedAt = LocalDateTime.of(2026, 1, 2, 3, 4, 5),
        bankName = "Test Bank",
        blz = "12345678",
        hbciVersion = "300",
        lines = listOf(TraceLine(1, "hello")),
    )
}
