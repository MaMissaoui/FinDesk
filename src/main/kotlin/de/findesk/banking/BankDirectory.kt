package de.findesk.banking

/**
 * Offline lookup of German banks by BLZ, using the `blz.properties` list bundled with HBCI4Java
 * (format: `BLZ=name|city|BIC|checksum|rdh-host|pintan-url|rdh-version|pintan-version|`).
 */
object BankDirectory {
    data class Entry(
        val blz: String,
        val name: String,
        val city: String,
        val bic: String,
        val pinTanUrl: String?,
        val pinTanVersion: String?,
    )

    private val entries: Map<String, Entry> by lazy { load() }

    fun lookup(blz: String): Entry? = entries[blz.trim()]

    private fun load(): Map<String, Entry> {
        val stream = BankDirectory::class.java.classLoader.getResourceAsStream("blz.properties") ?: return emptyMap()
        return stream.bufferedReader(Charsets.UTF_8).useLines { lines ->
            lines.mapNotNull { line ->
                val eq = line.indexOf('=')
                if (eq <= 0 || line.startsWith("#")) return@mapNotNull null
                val blz = line.substring(0, eq).trim()
                val f = line.substring(eq + 1).split('|')
                if (f.size < 3) return@mapNotNull null
                blz to Entry(
                    blz = blz,
                    name = f[0],
                    city = f.getOrElse(1) { "" },
                    bic = f.getOrElse(2) { "" },
                    pinTanUrl = f.getOrNull(5)?.takeIf { it.isNotBlank() },
                    pinTanVersion = f.getOrNull(7)?.takeIf { it.isNotBlank() },
                )
            }.toMap()
        }
    }
}
