package de.findesk.util

import java.lang.management.ManagementFactory
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/** Application identity, used in exports and diagnostics. Keep [VERSION] in sync with the packaging version. */
object AppInfo {
    const val NAME = "FinDesk"
    const val VERSION = "1.1.0"

    /** When this process started; anything still in flight from before it was interrupted by a quit or crash. */
    val startedAt: LocalDateTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(ManagementFactory.getRuntimeMXBean().startTime), ZoneId.systemDefault())
}
