package de.findesk.util

import java.io.File
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission

/**
 * Best-effort owner-only permissions for the data directory and the files that hold financial data.
 *
 * The database itself is encrypted, but `documents/` (attachments) and the raw files are not, so they
 * should not be world-readable on a shared machine. On filesystems without POSIX permissions (e.g.
 * Windows) this is a no-op. Failures are ignored: permissions are defence in depth, not a precondition.
 */
object FileSecurity {
    private val POSIX = FileSystems.getDefault().supportedFileAttributeViews().contains("posix")

    private val DIRECTORY_PERMISSIONS = setOf(
        PosixFilePermission.OWNER_READ,
        PosixFilePermission.OWNER_WRITE,
        PosixFilePermission.OWNER_EXECUTE,
    )

    private val FILE_PERMISSIONS = setOf(
        PosixFilePermission.OWNER_READ,
        PosixFilePermission.OWNER_WRITE,
    )

    /** Creates [dir] (and parents) if needed and restricts it to the owner. Returns [dir]. */
    fun secureDirectory(dir: File): File {
        if (!dir.isDirectory) dir.mkdirs()
        if (POSIX) runCatching { Files.setPosixFilePermissions(dir.toPath(), DIRECTORY_PERMISSIONS) }
        return dir
    }

    /** Restricts an existing [file] to the owner. Returns [file]. */
    fun secureFile(file: File) {
        if (POSIX && file.exists()) runCatching { Files.setPosixFilePermissions(file.toPath(), FILE_PERMISSIONS) }
    }

    /** Restricts [file] and its `-wal`/`-shm` siblings. */
    fun secureDatabaseFile(file: File) {
        secureFile(file)
        secureFile(File(file.parentFile, "${file.name}-wal"))
        secureFile(File(file.parentFile, "${file.name}-shm"))
    }
}
