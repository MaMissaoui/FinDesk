package de.findesk.data.database

import de.findesk.data.backup.BackupService
import de.findesk.util.ProfileStore
import java.io.File

/**
 * Plaintext copies of the database that encrypting the live one left behind.
 *
 * The startup gate only converts the file it opens. Copies FinDesk made earlier keep the `SQLite format 3`
 * header and stay readable without the passphrase — by any tool that reads SQLite, which is what the
 * encryption exists to prevent. The ones the app itself produces are:
 *
 *  - `pre-restore-<timestamp>/findesk.db`: the data a restore replaced, moved aside instead of deleted
 *    ([BackupService.applyPendingRestore]).
 *  - `profiles-deleted/<slug>-<timestamp>/findesk.db`: a deleted profile, moved aside instead of erased
 *    ([ProfileStore.delete]).
 *  - `restore-pending/findesk.db`: a validated backup waiting for the next start
 *    ([BackupService.stageRestore]).
 *  - another profile's `findesk.db`: those are only encrypted once that profile has been opened.
 *
 * A backup ZIP copied outside the data directory can't be found from here and is not covered.
 */
object PlaintextLeftovers {
    /** Where a leftover came from, so the UI can explain why it is there. */
    enum class Kind {
        /** The data a restore replaced. */
        PRE_RESTORE,

        /** The data directory of a deleted profile. */
        DELETED_PROFILE,

        /** A backup unpacked by [BackupService.stageRestore] and not applied yet. */
        PENDING_RESTORE,

        /** Another profile's data directory, which this start never opened. */
        OTHER_PROFILE,
    }

    /** [path] is relative to the data root, with `/` separators, for display. */
    data class Leftover(val file: File, val kind: Kind, val path: String)

    /**
     * The plaintext databases under [rootDir], except the live database of [activeDataDir] — that one is the
     * startup gate's business and is encrypted by the same start that reports these. Sorted by path, so the
     * list is stable.
     */
    fun scan(rootDir: File, activeDataDir: File): List<Leftover> {
        val leftovers = mutableListOf<Leftover>()
        val dataDirs = listOf(rootDir) + (File(rootDir, ProfileStore.PROFILES_DIR).listFiles()?.filter { it.isDirectory }.orEmpty())
        dataDirs.forEach { dataDir ->
            // Another profile stays plaintext until it is opened, so report it from here
            if (dataDir != activeDataDir) leftovers.addIfPlaintext(File(dataDir, BackupService.DB_NAME), Kind.OTHER_PROFILE, rootDir)
            leftovers.addIfPlaintext(File(File(dataDir, BackupService.PENDING_DIR), BackupService.DB_NAME), Kind.PENDING_RESTORE, rootDir)
            dataDir.listFiles()
                ?.filter { it.isDirectory && it.name.startsWith(BackupService.PRE_RESTORE_PREFIX) }
                ?.forEach { leftovers.addIfPlaintext(File(it, BackupService.DB_NAME), Kind.PRE_RESTORE, rootDir) }
        }
        File(rootDir, ProfileStore.DELETED_DIR).listFiles()?.filter { it.isDirectory }?.forEach {
            leftovers.addIfPlaintext(File(it, BackupService.DB_NAME), Kind.DELETED_PROFILE, rootDir)
        }
        return leftovers.sortedBy { it.path }
    }

    private fun MutableList<Leftover>.addIfPlaintext(file: File, kind: Kind, rootDir: File) {
        if (DatabaseCrypto.state(file) == DatabaseCrypto.State.PLAINTEXT) {
            add(Leftover(file, kind, file.relativeTo(rootDir).invariantSeparatorsPath))
        }
    }
}
