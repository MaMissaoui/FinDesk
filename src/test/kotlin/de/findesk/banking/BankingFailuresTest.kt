package de.findesk.banking

import de.findesk.i18n.AppStrings
import de.findesk.i18n.StringKey
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.kapott.hbci.exceptions.HBCI_Exception
import java.io.IOException
import java.net.ConnectException

class BankingFailuresTest {
    @Test
    fun `a socket exception in the cause chain is a network failure`() {
        assertTrue(BankingFailures.isNetworkFailure(RuntimeException("dialog failed", IOException("boom")), emptyList()))
    }

    @Test
    fun `the English send failure is recognised without a cause`() {
        assertTrue(BankingFailures.isNetworkFailure(RuntimeException("error while sending message to HBCI server"), emptyList()))
    }

    @Test
    fun `the German send failure is recognised in the diagnostics`() {
        val diagnostics = listOf("Fehler beim Senden der HBCI-Nachricht zum Server")
        assertTrue(BankingFailures.isNetworkFailure(RuntimeException(diagnostics.first()), diagnostics))
    }

    @Test
    fun `a socket exception named only in the diagnostics is recognised`() {
        assertTrue(BankingFailures.isNetworkFailure(RuntimeException("dialog failed"), listOf("java.net.ConnectException: Connection refused")))
    }

    @Test
    fun `connection refused counts even without an exception type`() {
        assertTrue(BankingFailures.isNetworkFailure(ConnectException("Connection refused"), emptyList()))
    }

    @Test
    fun `a bank rejection is not a network failure`() {
        assertFalse(BankingFailures.isNetworkFailure(RuntimeException("997 Falscher PIN"), listOf("997 Falscher PIN")))
    }

    @Test
    fun `a base64 failure is reported as an answer that isn't FinTS`() {
        val error = HBCI_Exception("Fehler beim Empfangen der Daten vom HBCI-Server", IllegalArgumentException("Illegal base64 character 3c"))

        val message = BankingFailures.describe(error, emptyList(), emptyList())

        assertEquals(AppStrings.format(StringKey.ErrorServerNotFints, "Illegal base64 character 3c"), message)
        assertTrue(message.contains("Illegal base64 character 3c"))
    }

    @Test
    fun `the base64 detection also matches when only the text survives`() {
        val error = HBCI_Exception("wrapper", RuntimeException("Illegal base64 character 3c"))

        assertTrue(BankingFailures.describe(error, emptyList(), emptyList()).contains("Illegal base64 character 3c"))
    }

    @Test
    fun `an IllegalArgumentException without base64 is not called a non-FinTS answer`() {
        val error = HBCI_Exception("Job parameter rejected", IllegalArgumentException("invalid value for BTG.value"))

        val message = BankingFailures.describe(error, emptyList(), emptyList())

        assertNotEquals(AppStrings.format(StringKey.ErrorServerNotFints, "invalid value for BTG.value"), message)
        assertEquals("Job parameter rejected (invalid value for BTG.value)", message)
    }

    @Test
    fun `an HTTP status is reported instead of the network branch`() {
        val error = HBCI_Exception("Fehler beim Empfangen der Daten vom HBCI-Server", IOException("Server returned HTTP response code: 403 for URL: https://x.example/fints"))

        // The IOException alone would classify as a network failure, so the HTTP check must come first
        assertTrue(BankingFailures.isNetworkFailure(error, emptyList()))
        assertEquals(403, BankingFailures.httpStatus(error, emptyList()))

        val message = BankingFailures.describe(error, emptyList(), emptyList())

        assertEquals(AppStrings.format(StringKey.ErrorServerHttpStatus, 403), message)
        assertNotEquals(AppStrings[StringKey.ErrorBankUnreachable], message)
    }

    @Test
    fun `the real HVB trace becomes an HTTP status message without a stack frame`() {
        val trace = "org.kapott.hbci.exceptions.HBCI_Exception: Fehler beim Empfangen der Daten vom HBCI-Server\n" +
            "\tat org.kapott.hbci.comm.CommPinTan.pong(CommPinTan.java:198)\n" +
            "Caused by: java.io.IOException: Server returned HTTP response code: 500 for URL: https://hbci-01.hypovereinsbank.de:443/bank/hbci\n" +
            "\tat java.base/sun.net.www.protocol.http.HttpURLConnection.getInputStream0(Unknown Source)"
        // The thrown exception has no IOException in its chain; the status only survives in the logged text
        val error = HBCI_Exception("Fehler beim Empfangen der Daten vom HBCI-Server")

        val message = BankingFailures.describe(error, emptyList(), listOf(trace))

        assertEquals(AppStrings.format(StringKey.ErrorServerHttpStatus, 500), message)
        assertFalse(message.contains("at org."))
        assertFalse(message.contains("\n"))
    }

    @Test
    fun `a diagnostics stack trace never leaks into the message`() {
        val trace = "org.kapott.hbci.exceptions.HBCI_Exception: Fehler beim Empfangen der Daten vom HBCI-Server\n" +
            "\tat org.kapott.hbci.comm.CommPinTan.pong(CommPinTan.java:198)"
        val error = HBCI_Exception("Fehler beim Empfangen der Daten vom HBCI-Server")

        val message = BankingFailures.describe(error, emptyList(), listOf(trace))

        assertFalse(message.contains("at org."))
        assertFalse(message.contains("\n"))
    }

    @Test
    fun `a bank message wins over the generic text`() {
        val error = HBCI_Exception("Fehler beim Empfangen der Daten vom HBCI-Server", RuntimeException("Read timed out"))

        assertEquals("997 Falscher PIN", BankingFailures.describe(error, listOf("997 Falscher PIN"), emptyList()))
    }

    @Test
    fun `the generic fallback names the root cause`() {
        val error = HBCI_Exception("Fehler beim Empfangen der Daten vom HBCI-Server", RuntimeException("Read timed out"))

        assertEquals("Fehler beim Empfangen der Daten vom HBCI-Server (Read timed out)", BankingFailures.describe(error, emptyList(), emptyList()))
    }

    @Test
    fun `a network failure keeps the unreachable message and names the root cause`() {
        val error = HBCI_Exception("error while sending message to HBCI server", IOException("Connection reset"))

        assertTrue(BankingFailures.isNetworkFailure(error, emptyList()))
        assertEquals(AppStrings[StringKey.ErrorBankUnreachable] + " (Connection reset)", BankingFailures.networkMessage(error))
    }
}
