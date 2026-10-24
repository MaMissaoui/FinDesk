package de.findesk.data.database

import de.findesk.banking.BankingRedactor
import de.findesk.data.entity.Setting
import de.findesk.data.table.AuditLogTable
import de.findesk.data.table.PaymentOrdersTable
import de.findesk.data.table.SettingsTable
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.time.LocalDateTime

/**
 * One-off cleanup of bank text in old audit entries and order records.
 *
 * Rows written before the audit kept codes only may hold bank text with full account numbers.
 * Runs once per database, the first time it is opened.
 */
internal object BankTextCleanup {
    const val SETTING_KEY = "cleanup.bankText.v1"

    val ORDER_ACTIONS = setOf("TRANSFER", "SCHEDULED_TRANSFER", "STANDING_ORDER_CREATE", "STANDING_ORDER_DELETE")

    private val FAILED_MARKER = ": failed: "
    private val OUTCOME_UNKNOWN_MARKER = ": outcome unknown: "

    /**
     * Rewrites an old order audit detail so only the outcome and FinTS return codes remain.
     * Returns null when no bank text was found or when the result equals the input.
     */
    internal fun rewriteOrderDetails(details: String): String? {
        val failedIndex = details.indexOf(FAILED_MARKER)
        val unknownIndex = details.indexOf(OUTCOME_UNKNOWN_MARKER)

        val (marker, outcomeWord) = when {
            failedIndex == -1 && unknownIndex == -1 -> return null
            unknownIndex == -1 || (failedIndex != -1 && failedIndex < unknownIndex) -> FAILED_MARKER to "failed"
            else -> OUTCOME_UNKNOWN_MARKER to "outcome unknown"
        }

        val markerIndex = if (marker == FAILED_MARKER) failedIndex else unknownIndex
        val prefix = details.substring(0, markerIndex)
        val message = details.substring(markerIndex + marker.length)
        val codes = BankingRedactor.returnCodes(BankingRedactor.maskAccountNumbers(message))
        val suffix = if (codes.isEmpty()) "" else " (bank codes ${codes.joinToString(", ")})"
        val result = "$prefix: $outcomeWord$suffix"
        return result.takeIf { it != details }
    }

    /**
     * Cleans up old audit entries and order messages in a single transaction.
     * The setting is written even when there is nothing to clean, so a fresh database
     * never re-enters this method.
     */
    fun run() {
        transaction {
            if (Setting.find { SettingsTable.key eq SETTING_KEY }.any()) return@transaction

            val failedPattern = "%$FAILED_MARKER%"
            val unknownPattern = "%$OUTCOME_UNKNOWN_MARKER%"

            val auditUpdates = AuditLogTable
                .select(AuditLogTable.id, AuditLogTable.details)
                .where {
                    (AuditLogTable.action inList ORDER_ACTIONS) and
                        ((AuditLogTable.details like failedPattern) or (AuditLogTable.details like unknownPattern))
                }
                .mapNotNull { row ->
                    val id = row[AuditLogTable.id].value
                    val newDetails = rewriteOrderDetails(row[AuditLogTable.details] ?: return@mapNotNull null)
                        ?: return@mapNotNull null
                    id to newDetails
                }
            auditUpdates.forEach { (id, newDetails) ->
                AuditLogTable.update({ AuditLogTable.id eq id }) { it[details] = newDetails }
            }

            val messageUpdates = PaymentOrdersTable
                .select(PaymentOrdersTable.id, PaymentOrdersTable.message)
                .where { PaymentOrdersTable.message.isNotNull() }
                .mapNotNull { row ->
                    val message = row[PaymentOrdersTable.message] ?: return@mapNotNull null
                    val masked = BankingRedactor.maskAccountNumbers(message)
                    if (masked == message) return@mapNotNull null
                    row[PaymentOrdersTable.id].value to masked
                }
            messageUpdates.forEach { (id, masked) ->
                PaymentOrdersTable.update({ PaymentOrdersTable.id eq id }) { it[message] = masked }
            }

            val auditCount = auditUpdates.size
            val orderCount = messageUpdates.size
            if (auditCount > 0 || orderCount > 0) {
                AuditLogTable.insert {
                    it[timestamp] = LocalDateTime.now()
                    it[action] = "BANK_TEXT_CLEANUP"
                    it[user] = "system"
                    it[details] = "Bank text removed or masked: audit entries: $auditCount, order records: $orderCount"
                }
            }

            val existing = Setting.find { SettingsTable.key eq SETTING_KEY }.firstOrNull()
            val now = LocalDateTime.now()
            if (existing != null) {
                existing.value = "done"
                existing.updatedAt = now
            } else {
                Setting.new {
                    key = SETTING_KEY
                    value = "done"
                    updatedAt = now
                }
            }
        }
    }
}
