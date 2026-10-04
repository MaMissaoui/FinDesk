package de.findesk.data.database

import de.findesk.data.table.*
import de.findesk.util.AppPaths
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.StdOutSqlLogger
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.addLogger
import org.jetbrains.exposed.sql.transactions.transaction
import java.io.File

object DatabaseFactory {
    private const val DB_NAME = "findesk.db"

    /** Every table, in creation order (referenced tables first). Tests reuse this list. */
    val tables: Array<Table> =
        arrayOf(
            BankAccessTable,
            AccountsTable,
            TransactionsTable,
            CategoriesTable,
            RulesTable,
            DocumentsTable,
            OcrTextsTable,
            SettingsTable,
            PluginsTable,
            AuditLogTable,
        )

    fun init(dataDir: String = getDefaultDataDir()) {
        val dbPath = File(dataDir, DB_NAME).absolutePath
        Database.connect("jdbc:sqlite:$dbPath", driver = "org.sqlite.JDBC")

        transaction {
            addLogger(StdOutSqlLogger)
            SchemaUtils.create(*tables)
        }
    }

    private fun getDefaultDataDir(): String {
        val appDir = AppPaths.dataDir
        if (!appDir.exists()) {
            appDir.mkdirs()
        }
        return appDir.absolutePath
    }
}
