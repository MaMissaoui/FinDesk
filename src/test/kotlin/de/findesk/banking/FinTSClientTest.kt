package de.findesk.banking

import de.findesk.data.database.DatabaseFactory
import org.jetbrains.exposed.sql.Database
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import java.math.BigDecimal
import java.time.LocalDate

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FinTSClientTest {
    private lateinit var client: FinTSClient
    private lateinit var tanManager: TanManager

    @BeforeAll
    fun setup() {
        Database.connect("jdbc:sqlite::memory:", driver = "org.sqlite.JDBC")
        DatabaseFactory.init()
        client = FinTSClient()
        tanManager = TanManager()
    }

    @Test
    fun `test create transaction hash`() {
        val hash1 = client.createTransactionHash(
            date = LocalDate.of(2026, 10, 1),
            amount = BigDecimal("-49.99"),
            partnerName = "REWE",
            purpose = "Shopping",
        )

        assertNotNull(hash1)
        assertTrue(hash1.isNotEmpty())

        // Same input should produce same hash
        val hash2 = client.createTransactionHash(
            date = LocalDate.of(2026, 10, 1),
            amount = BigDecimal("-49.99"),
            partnerName = "REWE",
            purpose = "Shopping",
        )

        assertEquals(hash1, hash2)
    }

    @Test
    fun `test different inputs produce different hashes`() {
        val hash1 = client.createTransactionHash(
            date = LocalDate.of(2026, 10, 1),
            amount = BigDecimal("-49.99"),
            partnerName = "REWE",
            purpose = "Shopping",
        )

        val hash2 = client.createTransactionHash(
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
    fun `test tan manager supported methods`() {
        val methods = tanManager.getSupportedMethods()

        assertNotNull(methods)
        assertTrue(methods.isNotEmpty())
        assertTrue(methods.any { it.name.contains("chipTAN") })
        assertTrue(methods.any { it.name.contains("photoTAN") })
        assertTrue(methods.any { it.name.contains("pushTAN") })
        assertTrue(methods.any { it.name.contains("SMS") })
    }

    @Test
    fun `test tan manager request and submit`() {
        var receivedTan: String? = null

        tanManager.requestTan(
            method = TanMethod(id = "900", name = "SMS-TAN"),
            challenge = "Please enter TAN",
        ) { tan ->
            receivedTan = tan
        }

        assertNotNull(tanManager.pendingTanRequest.value)

        tanManager.submitTan("123456")

        assertEquals("123456", receivedTan)
        assertNull(tanManager.pendingTanRequest.value)
    }

    @Test
    fun `test tan manager cancel`() {
        var receivedTan: String? = "notnull"

        tanManager.requestTan(
            method = TanMethod(id = "900", name = "SMS-TAN"),
            challenge = "Test",
        ) { tan ->
            receivedTan = tan
        }

        tanManager.cancelTan()

        assertNull(receivedTan)
        assertNull(tanManager.pendingTanRequest.value)
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
