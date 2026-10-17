package de.findesk.data.database

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import de.findesk.data.backup.BackupService
import de.findesk.data.table.*
import de.findesk.util.AppPaths
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.StdOutSqlLogger
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.Transaction
import org.jetbrains.exposed.sql.addLogger
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.vendors.currentDialect
import java.io.File
import java.io.PrintWriter
import java.sql.Connection
import java.util.logging.Logger
import javax.sql.DataSource

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
            PaymentOrdersTable,
            SettingsTable,
            PluginsTable,
            AuditLogTable,
        )

    /** Folder holding the data replaced by a restore applied at this start, if any. */
    @Volatile
    var restoredFrom: File? = null
        private set

    @Volatile
    private var preparedDir: File? = null

    @Volatile
    private var pool: HikariDataSource? = null

    @Volatile
    private var openDb: File? = null

    @Volatile
    private var passphrase: String? = null

    /** Whether the open database is encrypted. */
    val isEncrypted: Boolean get() = passphrase != null

    fun databaseFile(dataDir: File): File = File(dataDir, DB_NAME)

    /** Applies a restore staged in Settings. Runs before the database is opened, so the startup gate sees the restored file. */
    fun applyPendingRestore(dataDir: File) {
        restoredFrom = BackupService(dataDir).applyPendingRestore()
        preparedDir = dataDir.absoluteFile
    }

    /**
     * Opens the database in [dataDir]. [passphrase] null opens it unencrypted (tests); the app always passes one
     * (see the startup gate in Main.kt). Throws [DatabaseCrypto.WrongPassphraseException] for a wrong passphrase.
     */
    fun init(dataDir: String = getDefaultDataDir(), passphrase: String? = null) {
        if (preparedDir != File(dataDir).absoluteFile) applyPendingRestore(File(dataDir))
        preparedDir = null
        val db = databaseFile(File(dataDir))
        DatabaseCrypto.open(db, passphrase).close()

        // A keyed open runs the KDF (~100 ms), so connections are pooled instead of opened per transaction
        val newPool = HikariDataSource(
            HikariConfig().apply {
                dataSource = KeyedDataSource(db, passphrase)
                maximumPoolSize = 4
                minimumIdle = 1
                poolName = "findesk-db"
            },
        )
        pool?.close()
        pool = newPool
        openDb = db
        this.passphrase = passphrase
        Database.connect(newPool)

        transaction {
            // Prints every statement with its values (bank passport passphrases included), so only on request
            if (System.getenv("FINDESK_SQL_LOG") == "1") addLogger(StdOutSqlLogger)
            updateSchema()
        }
    }

    /**
     * Creates missing tables, then adds columns introduced after a database was created (e.g. bank_access.server_url)
     * and missing indices. Only ever adds: Exposed's createMissingTablesAndColumns also "repairs" existing columns
     * with ALTER TABLE ... MODIFY when the driver reports ALTER TABLE support (sqlite-jdbc 3.50.2+), and SQLite has
     * no such statement.
     */
    private fun Transaction.updateSchema() {
        db.dialect.resetCaches()
        val created = SchemaUtils.createStatements(*tables)
        created.forEach { exec(it) }
        commit()

        val existing = currentDialect.tableColumns(*tables)
        val added = tables.flatMap { table ->
            val names = existing[table].orEmpty().map { it.name.lowercase() }.toSet()
            table.columns.filter { it.nameUnquoted().lowercase() !in names }.flatMap { it.createStatement() }
        }
        added.forEach { exec(it) }
        commit()

        SchemaUtils.checkMappingConsistence(*tables, withLogs = false).filter { it !in created + added }.forEach { exec(it) }
        commit()
        db.dialect.resetCaches()
    }

    /**
     * A plain autocommit connection to [db] (default: the open database) with the open database's key, for work that
     * can't run inside an Exposed transaction (`VACUUM INTO`). The caller closes it.
     */
    fun rawConnection(db: File? = null): Connection {
        val target = db ?: checkNotNull(openDb) { "Database not initialised" }
        val key = if (DatabaseCrypto.state(target) == DatabaseCrypto.State.ENCRYPTED) passphrase else null
        return DatabaseCrypto.open(target, key)
    }

    private fun getDefaultDataDir(): String {
        val appDir = AppPaths.dataDir
        if (!appDir.exists()) {
            appDir.mkdirs()
        }
        return appDir.absolutePath
    }

    /** Hands the pool keyed connections without the key ever passing through the pool's configuration or logging. */
    private class KeyedDataSource(private val db: File, private val passphrase: String?) : DataSource {
        override fun getConnection(): Connection = DatabaseCrypto.open(db, passphrase)

        override fun getConnection(username: String?, password: String?): Connection = getConnection()

        override fun getLogWriter(): PrintWriter? = null

        override fun setLogWriter(out: PrintWriter?) {}

        override fun setLoginTimeout(seconds: Int) {}

        override fun getLoginTimeout(): Int = 0

        override fun getParentLogger(): Logger = Logger.getLogger("de.findesk.db")

        override fun <T : Any?> unwrap(iface: Class<T>?): T = throw java.sql.SQLFeatureNotSupportedException()

        override fun isWrapperFor(iface: Class<*>?): Boolean = false
    }
}
