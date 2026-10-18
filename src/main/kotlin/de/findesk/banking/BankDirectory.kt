package de.findesk.banking

import de.findesk.util.Iban

/**
 * Offline lookup of German banks, using the `blz.properties` list bundled with HBCI4Java
 * (format: `BLZ=name|city|BIC|checksum|rdh-host|pintan-url|rdh-version|pintan-version|`).
 *
 * The list is keyed by BLZ, but users rarely know their bank's BLZ by heart. [search] therefore
 * matches on whatever they do know — the bank name, the BIC or a (German) IBAN — which feeds the
 * suggestions in the add-bank dialog.
 */
object BankDirectory {
    data class Entry(
        val blz: String,
        val name: String,
        val city: String,
        val bic: String,
        val pinTanUrl: String?,
        val pinTanVersion: String?,
    ) {
        /** Whether the directory lists a FinTS (PIN/TAN) endpoint, i.e. online sync can be offered. */
        val hasOnlineBanking: Boolean get() = !pinTanUrl.isNullOrBlank()
    }

    private val entries: Map<String, Entry> by lazy { load() }
    private val all: List<Entry> by lazy { entries.values.toList() }

    /**
     * Banks whose FinTS server only accepts the central bank code, keyed by the first 8 characters of the BIC.
     * The evidence is a real dialog: a login with the branch BLZ got HTTP 500 on the first message, while the
     * central BLZ initialised the dialog. Don't add other banks without such evidence.
     */
    private val CENTRAL_FINTS_BLZ = mapOf("HYVEDEMM" to "70020270")

    /** One representative bank per BIC, preferring entries that have a FinTS server. */
    private val bicIndex: Map<String, Entry> by lazy {
        val index = LinkedHashMap<String, Entry>()
        all.sortedWith(compareByDescending<Entry> { it.hasOnlineBanking }.thenBy { it.blz })
            .forEach { entry -> if (entry.bic.isNotBlank()) index.putIfAbsent(entry.bic.uppercase(), entry) }
        index
    }

    fun lookup(blz: String): Entry? = entries[blz.trim()]

    /**
     * The BLZ whose FinTS server speaks for [blz]'s bank: the central bank code for the few banks in
     * [CENTRAL_FINTS_BLZ], otherwise [blz] unchanged. The add-bank dialog stores this one as the connection's
     * BLZ; the directory lookups (BIC, IBAN) keep using the real branch entries.
     */
    fun finTsBlz(blz: String): String {
        val trimmed = blz.trim()
        val bic = lookup(trimmed)?.bic ?: return trimmed
        return CENTRAL_FINTS_BLZ.entries.firstOrNull { bic.startsWith(it.key) }?.value ?: trimmed
    }

    /** A bank with this BIC, if the directory knows it. */
    fun byBic(bic: String): Entry? = bicIndex[bic.trim().uppercase()]

    /** The BLZ embedded in a German IBAN (characters 5–12), or null for a non-German/malformed IBAN. */
    fun blzFromIban(iban: String): String? {
        val normalized = Iban.normalize(iban)
        if (!normalized.startsWith("DE") || normalized.length != 22) return null
        return normalized.substring(4, 12).takeIf { blz -> blz.all(Char::isDigit) }
    }

    /** The bank behind a German IBAN, or null if the IBAN is invalid or the bank unknown. */
    fun byIban(iban: String): Entry? {
        val normalized = Iban.normalize(iban)
        if (!Iban.isValid(normalized)) return null
        return blzFromIban(normalized)?.let(::lookup)
    }

    /**
     * Free-text bank search for the add-bank dialog: matches a BLZ prefix, a BIC prefix, or the bank
     * name/city as a substring (case-insensitive, mirroring HBCI4Java's own `searchBankInfo`). A
     * complete German IBAN resolves straight to its bank. Results are ranked (BLZ prefix, then BIC
     * prefix, then name, then city; entries with a FinTS server first) and capped at [limit].
     */
    fun search(query: String, limit: Int = 15): List<Entry> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return emptyList()
        byIban(trimmed)?.let { return listOf(it) }
        if (trimmed.length < 3) return emptyList()
        val needle = trimmed.lowercase()
        return all.asSequence()
            .mapNotNull { entry -> entry.rank(needle)?.let { rank -> rank to entry } }
            .sortedWith(
                compareBy(
                    { (rank, entry): Pair<Int, Entry> -> rank },
                    { (_, entry): Pair<Int, Entry> -> !entry.hasOnlineBanking },
                    { (_, entry): Pair<Int, Entry> -> entry.name },
                    { (_, entry): Pair<Int, Entry> -> entry.city },
                    { (_, entry): Pair<Int, Entry> -> entry.blz },
                ),
            )
            .map { (_, entry) -> entry }
            .take(limit)
            .toList()
    }

    /** Lower is better; null means no match. */
    private fun Entry.rank(needle: String): Int? = when {
        blz.startsWith(needle) -> 0
        bic.lowercase().startsWith(needle) -> 1
        name.lowercase().startsWith(needle) -> 2
        name.lowercase().contains(needle) -> 3
        city.lowercase().contains(needle) -> 4
        else -> null
    }

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
