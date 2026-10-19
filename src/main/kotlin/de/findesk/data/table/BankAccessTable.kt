package de.findesk.data.table

import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.sql.javatime.date
import org.jetbrains.exposed.sql.javatime.datetime

object BankAccessTable : IntIdTable("bank_access") {
    val bankName = varchar("bank_name", 255)
    val blz = varchar("blz", 20).index()
    val bic = varchar("bic", 20)
    val userId = varchar("user_id", 100)
    val hbciVersion = varchar("hbci_version", 10).default("300")
    val tanMethod = varchar("tan_method", 50).nullable()
    val serverUrl = varchar("server_url", 255).nullable()
    val lastSync = datetime("last_sync").nullable()

    /** Chosen start date for the next sync of every account (fetch older history); null uses the normal delta sync. */
    val syncFrom = date("sync_from").nullable()

    /** Comma-separated BPD/UPD job names the login supports (see BankCapabilities); null until the first sync. */
    val capabilities = text("capabilities").nullable()

    /** Whether "Sync all" syncs this connection; the default keeps existing connections included. */
    val includeInSyncAll = bool("include_in_sync_all").default(true)
}
