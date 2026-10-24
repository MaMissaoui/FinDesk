package de.findesk.data

import de.findesk.data.database.BankTextCleanup
import de.findesk.data.database.DatabaseFactory
import de.findesk.data.entity.AuditLogEntry
import de.findesk.data.entity.PaymentOrder
import de.findesk.data.entity.Setting
import de.findesk.data.table.AuditLogTable
import de.findesk.data.table.PaymentOrdersTable
import de.findesk.data.table.SettingsTable
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.math.BigDecimal
import java.nio.file.Files
import java.time.LocalDateTime

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BankTextCleanupTest {

    @AfterAll
    fun tearDown() {
        DatabaseFactory.close()
    }

    @Test
    fun `rewriteOrderDetails extracts failed outcome and return codes`() {
        val result =
            BankTextCleanup.rewriteOrderDetails(
                "10.00 EUR from …3000 to Max Mustermann …4931: failed: 9050 IBAN DE89 3704 0044 0532 0130 00 ungültig",
            )
        assertEquals(
            "10.00 EUR from …3000 to Max Mustermann …4931: failed (bank codes 9050)",
            result,
        )
    }

    @Test
    fun `rewriteOrderDetails extracts outcome unknown without codes`() {
        val result =
            BankTextCleanup.rewriteOrderDetails(
                "5.00 EUR to Erika …1234: outcome unknown: Read timed out",
            )
        assertEquals("5.00 EUR to Erika …1234: outcome unknown", result)
    }

    @Test
    fun `rewriteOrderDetails stops at first marker and drops bank text containing markers`() {
        val result =
            BankTextCleanup.rewriteOrderDetails(
                "20.00 EUR: failed: 9210 Auftrag failed: Konto 1234567890",
            )
        assertEquals("20.00 EUR: failed (bank codes 9210)", result)
        assertFalse(result!!.contains("Auftrag"))
        assertFalse(result.contains("1234567890"))
    }

    @Test
    fun `rewriteOrderDetails returns null for already clean details`() {
        assertNull(
            BankTextCleanup.rewriteOrderDetails(
                "...: failed (NOT_EXECUTED, bank codes 9050)",
            ),
        )
        assertNull(BankTextCleanup.rewriteOrderDetails("...: sent"))
    }

    @Test
    fun `cleanup rewrites legacy audit rows and masks order messages only once`() {
        val dir = Files.createTempDirectory("findesk-cleanup-once").toFile()
        try {
            DatabaseFactory.init(dir.absolutePath)

            val transferDetails =
                "10.00 EUR from …3000 to Max Mustermann …4931: failed: 9050 IBAN DE89 3704 0044 0532 0130 00 ungültig"
            val scheduledDetails = "5.00 EUR to Erika …1234: outcome unknown: Read timed out"
            val changeBankCodeDetails = "Bank access 1: BLZ 12030000 -> 79020076"
            val syncBankDetails = "Synced accounts"

            transaction {
                AuditLogEntry.new {
                    timestamp = LocalDateTime.now()
                    action = "TRANSFER"
                    user = "user"
                    details = transferDetails
                }
                AuditLogEntry.new {
                    timestamp = LocalDateTime.now()
                    action = "SCHEDULED_TRANSFER"
                    user = "user"
                    details = scheduledDetails
                }
                AuditLogEntry.new {
                    timestamp = LocalDateTime.now()
                    action = "CHANGE_BANK_CODE"
                    user = "user"
                    details = changeBankCodeDetails
                }
                AuditLogEntry.new {
                    timestamp = LocalDateTime.now()
                    action = "SYNC_BANK"
                    user = "user"
                    details = syncBankDetails
                }

                PaymentOrder.new {
                    createdAt = LocalDateTime.now()
                    updatedAt = LocalDateTime.now()
                    action = "TRANSFER"
                    accountIban = "DE89370400440532013000"
                    recipientName = "Empfänger"
                    recipientIban = "DE02120300000000202052"
                    amount = BigDecimal("10.00")
                    currency = "EUR"
                    status = "FAILED"
                    message = "9050 Empfänger DE89370400440532013000"
                }

                Setting.find { SettingsTable.key eq BankTextCleanup.SETTING_KEY }.forEach { it.delete() }
            }

            DatabaseFactory.init(dir.absolutePath)

            transaction {
                val transfer = AuditLogEntry.find { AuditLogTable.action eq "TRANSFER" }.single()
                assertEquals(
                    "10.00 EUR from …3000 to Max Mustermann …4931: failed (bank codes 9050)",
                    transfer.details,
                )

                val scheduled = AuditLogEntry.find { AuditLogTable.action eq "SCHEDULED_TRANSFER" }.single()
                assertEquals("5.00 EUR to Erika …1234: outcome unknown", scheduled.details)

                val changeBankCode = AuditLogEntry.find { AuditLogTable.action eq "CHANGE_BANK_CODE" }.single()
                assertEquals(changeBankCodeDetails, changeBankCode.details)

                val syncBank = AuditLogEntry.find { AuditLogTable.action eq "SYNC_BANK" }.single()
                assertEquals(syncBankDetails, syncBank.details)

                val order = PaymentOrder.find { PaymentOrdersTable.message eq "9050 Empfänger …3000" }.single()
                assertNotNull(order)

                val cleanupEntries = AuditLogEntry.find { AuditLogTable.action eq "BANK_TEXT_CLEANUP" }.toList()
                assertEquals(1, cleanupEntries.size)
                val cleanup = cleanupEntries.single()
                assertEquals("system", cleanup.user)
                assertEquals("Bank text removed or masked: audit entries: 2, order records: 1", cleanup.details)

                val setting = Setting.find { SettingsTable.key eq BankTextCleanup.SETTING_KEY }.single()
                assertEquals("done", setting.value)
            }

            transaction {
                AuditLogEntry.new {
                    timestamp = LocalDateTime.now()
                    action = "STANDING_ORDER_CREATE"
                    user = "user"
                    details = "20.00 EUR: failed: 9210 Auftrag failed: Konto 1234567890"
                }
            }

            DatabaseFactory.init(dir.absolutePath)

            transaction {
                val standingOrder = AuditLogEntry.find { AuditLogTable.action eq "STANDING_ORDER_CREATE" }.single()
                assertEquals(
                    "20.00 EUR: failed: 9210 Auftrag failed: Konto 1234567890",
                    standingOrder.details,
                )

                val cleanupEntries = AuditLogEntry.find { AuditLogTable.action eq "BANK_TEXT_CLEANUP" }.toList()
                assertEquals(1, cleanupEntries.size)
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `fresh database gets flag and no cleanup audit entry`() {
        val dir = Files.createTempDirectory("findesk-fresh").toFile()
        try {
            DatabaseFactory.init(dir.absolutePath)
            transaction {
                val setting = Setting.find { SettingsTable.key eq BankTextCleanup.SETTING_KEY }.firstOrNull()
                assertEquals("done", setting?.value)

                val cleanupEntries = AuditLogEntry.find { AuditLogTable.action eq "BANK_TEXT_CLEANUP" }.toList()
                assertEquals(0, cleanupEntries.size)
            }
        } finally {
            dir.deleteRecursively()
        }
    }
}
