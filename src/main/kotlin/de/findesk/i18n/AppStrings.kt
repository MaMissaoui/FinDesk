package de.findesk.i18n

/**
 * Non-Compose access to the current language's strings for view models and services that build
 * user-visible status and error text outside a composable.
 *
 * The UI keeps this in sync with the selected language ([de.findesk.App] and the unlock window);
 * it defaults to English, which is also what tests see.
 */
object AppStrings {
    @Volatile
    private var strings: Strings = Translations.of(AppLanguage.EN)

    val current: Strings get() = strings

    fun set(language: AppLanguage) {
        strings = Translations.of(language)
    }

    operator fun get(key: StringKey): String = strings[key]

    fun format(key: StringKey, vararg args: Any?): String = strings.format(key, *args)
}
