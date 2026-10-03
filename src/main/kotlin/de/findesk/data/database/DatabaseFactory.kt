package de.findesk.data.database

import de.findesk.data.table.*
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.StdOutSqlLogger
import org.jetbrains.exposed.sql.addLogger
import org.jetbrains.exposed.sql.transactions.transaction
import java.io.File

object DatabaseFactory {
    private const val DB_NAME = "findesk.db"

    fun init(dataDir: String = getDefaultDataDir()) {
        val dbPath = File(dataDir, DB_NAME).absolutePath
        Database.connect("jdbc:sqlite:$dbPath", driver = "org.sqlite.JDBC")

        transaction {
            addLogger(StdOutSqlLogger)
            SchemaUtils.create(
                BankAccessTable,
                AccountsTable,
                TransactionsTable,
                CategoriesTable,
                RulesTable,
                DocumentsTable,
                OcrTextsTable,
                SettingsTable,
                PluginsTable,
                AuditLogTable
            )
        }
    }

    private fun getDefaultDataDir(): String {
        val userHome = System.getProperty("user.home")
        val appDir = File(userHome, ".findesk")
        if (!appDir.exists()) {
            appDir.mkdirs()
        }
        return appDir.absolutePath
    }
}