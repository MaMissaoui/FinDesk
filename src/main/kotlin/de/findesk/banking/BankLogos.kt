package de.findesk.banking

/**
 * The bundled bank logos. A bank gets one from [BankLogos.bankLogo]; the value's [id] is also the resource name
 * (`banks/<id>.svg`). Banks without a logo fall back to a monogram (see [BankLogos.monogram]).
 */
enum class BankLogo(val id: String) {
    HYPOVEREINSBANK("hypovereinsbank"),
    DKB("dkb"),
    POSTBANK("postbank"),
    DEUTSCHE_BANK("deutsche-bank"),
    COMMERZBANK("commerzbank"),
    SANTANDER("santander"),
    N26("n26"),
    SPARKASSE("sparkasse"),
    VOLKSBANK("volksbank"),
}

/**
 * The letters and background colour of a monogram badge: up to three letters (an all-caps acronym when the name has
 * one) and an index into the eight fixed monogram colours. Pure data, so the matching can be tested without Compose.
 */
data class Monogram(val letters: String, val colorIndex: Int)

/**
 * Maps a bank connection (or the bank of an account without one) to its bundled logo, and every other bank to a
 * monogram. The rules run in the order of the shared spec; the first match wins. Matching is case-insensitive and
 * ignores surrounding spaces. Pure Kotlin: no Compose or I/O types, so the same rules can be unit-tested directly.
 */
object BankLogos {
    /**
     * Brands with their own look that use another bank's BIC or BLZ range. They never inherit that bank's logo,
     * however their name, BIC or BLZ looks (this check comes before every other rule).
     */
    private val OWN_LOOK = listOf("comdirect", "consors", "targo", "sparda", "psd", "apobank", "apotheker")

    fun bankLogo(name: String?, blz: String?, bic: String?): BankLogo? {
        val n = name?.trim()?.lowercase().orEmpty()
        val z = blz?.trim().orEmpty()
        val b = bic?.trim()?.uppercase().orEmpty()

        fun nameContains(vararg parts: String): Boolean = parts.any { n.contains(it) }

        if (OWN_LOOK.any { n.contains(it) }) return null

        if (nameContains("hypovereinsbank", "hvb", "unicredit") || b.startsWith("HYVE")) return BankLogo.HYPOVEREINSBANK
        if (nameContains("dkb", "deutsche kreditbank") || b.startsWith("BYLADEM1001") || z == "12030000") return BankLogo.DKB
        if (nameContains("postbank") || b.startsWith("PBNK")) return BankLogo.POSTBANK
        if (nameContains("deutsche bank") || b.startsWith("DEUT")) return BankLogo.DEUTSCHE_BANK
        if (nameContains("commerzbank") || b.startsWith("COBA")) return BankLogo.COMMERZBANK
        if (nameContains("santander") || b.startsWith("SCFB")) return BankLogo.SANTANDER
        if (nameContains("n26") || b.startsWith("NTSB")) return BankLogo.N26
        // The fourth digit of a Sparkasse's BLZ is 5; a name match wins for a bank with another BLZ
        if (nameContains("sparkasse") || (z.length >= 4 && z[3] == '5')) return BankLogo.SPARKASSE
        if (nameContains("volksbank", "raiffeisenbank", "vr bank", "vr-bank", "vrbank") || b.startsWith("GENO")) return BankLogo.VOLKSBANK

        return null
    }

    /**
     * Up to three letters for a bank without a logo: an all-caps word of two to four letters ("HVB", "DKB", "ING")
     * if the name has one, otherwise the initials of the first two words that start with a letter ("Bank Norwegian"
     * → "BN"); a single such word contributes its first two letters. The colour is a stable sum of the lower-cased
     * name's UTF-8 bytes modulo 8 (not a language hash, so the same name gets the same colour everywhere).
     */
    fun monogram(name: String): Monogram {
        val trimmed = name.trim()
        val words = trimmed.split(WHITESPACE).filter { it.isNotEmpty() }
        val acronym = words.firstOrNull(::isAcronym)
        val letters = acronym ?: run {
            val starting = words.filter { word -> word.firstOrNull()?.isLetter() == true }
            when {
                starting.isEmpty() -> ""
                starting.size == 1 -> starting.single().take(2)
                else -> starting.take(2).map { word -> word.first().uppercaseChar() }.joinToString("")
            }
        }
        return Monogram(letters.uppercase(), colourIndex(trimmed))
    }

    /** A word of two to four capital letters, e.g. "HVB" or "AG"; a digit or lowercase letter disqualifies it. */
    private fun isAcronym(word: String): Boolean = word.length in 2..4 && word.all { it.isLetter() && it.isUpperCase() }

    private fun colourIndex(name: String): Int = name.lowercase().toByteArray(Charsets.UTF_8).sumOf { byte -> byte.toInt() and 0xFF } % MONOGRAM_COLOUR_COUNT

    /** How many monogram colours the UI defines; the hash maps onto this range. */
    const val MONOGRAM_COLOUR_COUNT = 8

    private val WHITESPACE = Regex("\\s+")
}
