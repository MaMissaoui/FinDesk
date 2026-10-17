package de.findesk.i18n

import java.util.Locale

enum class AppLanguage(val code: String, val nativeName: String) {
    EN("en", "English"),
    DE("de", "Deutsch"),
    FR("fr", "Français"),
    ;

    val locale: Locale get() = Locale.forLanguageTag(code)

    /** Whether [count] takes the singular: in French 0 does too ("0 compte"), in English and German only 1. */
    fun isSingular(count: Long): Boolean = if (this == FR) count == 0L || count == 1L else count == 1L

    companion object {
        const val KEY = "ui.language"

        fun fromCode(code: String?): AppLanguage = entries.firstOrNull { it.code.equals(code, ignoreCase = true) } ?: fromSystem()

        fun fromSystem(): AppLanguage = entries.firstOrNull { it.code == Locale.getDefault().language } ?: EN
    }
}
