package de.findesk.banking

import de.findesk.TestDatabase
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import java.math.BigDecimal
import java.time.LocalDate

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FinTSClientTest {
    private lateinit var client: FinTSClient

    @BeforeAll
    fun setup() {
        TestDatabase.init()
        client = FinTSClient()
    }

    @Test
    fun `test create transaction hash`() {
        val hash1 = FinTSClient.transactionHash(
            date = LocalDate.of(2026, 10, 1),
            amount = BigDecimal("-49.99"),
            partnerName = "REWE",
            purpose = "Shopping",
        )

        assertNotNull(hash1)
        assertTrue(hash1.isNotEmpty())

        // Same input should produce same hash
        val hash2 = FinTSClient.transactionHash(
            date = LocalDate.of(2026, 10, 1),
            amount = BigDecimal("-49.99"),
            partnerName = "REWE",
            purpose = "Shopping",
        )

        assertEquals(hash1, hash2)
    }

    @Test
    fun `test different inputs produce different hashes`() {
        val hash1 = FinTSClient.transactionHash(
            date = LocalDate.of(2026, 10, 1),
            amount = BigDecimal("-49.99"),
            partnerName = "REWE",
            purpose = "Shopping",
        )

        val hash2 = FinTSClient.transactionHash(
            date = LocalDate.of(2026, 10, 2),
            amount = BigDecimal("-49.99"),
            partnerName = "REWE",
            purpose = "Shopping",
        )

        assertNotEquals(hash1, hash2)
    }

    @Test
    fun `test connection status starts as disconnected`() {
        assertEquals(ConnectionStatus.DISCONNECTED, client.connectionStatus.value)
    }

    @Test
    fun `test fin ts config creation`() {
        val config = FinTSConfig(
            bankName = "Deutsche Bank",
            blz = "37040044",
            bic = "DEUTDEDBXXX",
            userId = "testuser",
            hbciVersion = "300",
        )

        assertEquals("Deutsche Bank", config.bankName)
        assertEquals("37040044", config.blz)
        assertEquals("testuser", config.customerId) // defaults to userId
    }

    @Test
    fun `test tan method data class`() {
        val method = TanMethod(
            id = "942",
            name = "chipTAN optisch",
            mediumName = "TAN-Generator",
        )

        assertEquals("942", method.id)
        assertEquals("chipTAN optisch", method.name)
        assertEquals("TAN-Generator", method.mediumName)
    }
}
