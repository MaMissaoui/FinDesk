package de.findesk.banking

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.LocalDate
import java.util.concurrent.TimeUnit

/**
 * Exercises the real HBCI4Java stack against a closed local port: no bank is contacted. Verifies the
 * client/passport wiring and that a failed connection becomes an error instead of a hang or crash.
 */
class Hbci4JavaGatewaySmokeTest {
    @TempDir
    lateinit var tmp: File

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    fun `unreachable server fails cleanly`() {
        val asked = mutableListOf<String>()
        val ui = object : BankingInteraction {
            override fun pin(bankName: String, userId: String) = "00000".also { asked += "pin" }
            override fun selectTanMethod(options: List<TanMethod>) = options.firstOrNull()?.id.also { asked += "secmech" }
            override fun selectTanMedium(options: List<String>) = options.firstOrNull()
            override fun tan(challenge: TanChallenge): String? = null
            override fun confirmDecoupled(message: String) = false
            override fun confirmPayee(message: String) = false
        }
        val connection = FinTSConnection(
            bankName = "Nowhere",
            blz = "37050198",
            userId = "nobody",
            serverUrl = "https://localhost/fints", // nothing listens on local 443; nothing leaves the machine
            passportFile = File(tmp, "passport.dat"),
            passportPassphrase = "test-passphrase-123",
        )

        val error = assertThrows(Exception::class.java) {
            Hbci4JavaGateway().fetchAll(connection, ui, { LocalDate.now().minusDays(1) }, LocalDate.now())
        }
        assertFalse(error is BankingCancelledException)
        assertTrue(error.message!!.startsWith("Could not reach the bank's FinTS server"), error.message)
        assertTrue(connection.passportFile.exists(), "passport file created")
        println("SMOKE error: ${error.javaClass.simpleName}: ${error.message?.take(200)} | asked=$asked | passport=${connection.passportFile.exists()}")
    }
}
