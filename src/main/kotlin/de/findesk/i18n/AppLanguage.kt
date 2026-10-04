package de.findesk.i18n

import java.util.Locale

enum class AppLanguage(val code: String, val nativeName: String) {
    EN("en", "English"),
    DE("de", "Deutsch"),
    FR("fr", "Français"),
    ;

    val locale: Locale get() = Locale.forLanguageTag(code)

    companion object {
        const val KEY = "ui.language"

        fun fromCode(code: String?): AppLanguage = entries.firstOrNull { it.code.equals(code, ignoreCase = true) } ?: fromSystem()

        fun fromSystem(): AppLanguage = entries.firstOrNull { it.code == Locale.getDefault().language } ?: EN
    }
}
