package de.findesk.util

import java.math.BigInteger

object Iban {
    /** Strips spaces and upper-cases. */
    fun normalize(iban: String): String = iban.replace(" ", "").uppercase()

    /** ISO 13616 mod-97 check. */
    fun isValid(iban: String): Boolean {
        val s = normalize(iban)
        if (s.length !in 15..34 || !s.all { it.isLetterOrDigit() }) return false
        if (!s[0].isLetter() || !s[1].isLetter() || !s[2].isDigit() || !s[3].isDigit()) return false
        if (s.startsWith("DE") && s.length != 22) return false
        val rearranged = s.substring(4) + s.substring(0, 4)
        val numeric = rearranged.map { c -> if (c.isDigit()) c.toString() else (c - 'A' + 10).toString() }.joinToString("")
        return BigInteger(numeric).mod(BigInteger.valueOf(97)) == BigInteger.ONE
    }
}
