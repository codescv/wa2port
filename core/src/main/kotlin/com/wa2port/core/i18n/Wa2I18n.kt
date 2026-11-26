package com.wa2port.core.i18n

import java.util.Locale

/**
 * Global runtime holder for the active UI language so pure-JVM `:core` modules and non-Composable
 * controllers resolve user-facing strings in the active language without threading Android `Context`.
 *
 * Compose UI reads [Wa2Strings] from `LocalAppStrings` for instant recomposition on language switch;
 * `AppViewModel` and `Wa2App` keep [currentLanguage] synchronized with `Settings.appLanguage`.
 */
object Wa2I18n {
    @Volatile
    var currentLanguage: ResolvedLanguage = ResolvedLanguage.fromLocale(Locale.getDefault())

    /** Active localized string bundle. */
    val s: Wa2Strings
        get() = Wa2Strings.forLanguage(currentLanguage)

    /** Resolves [preference] against [systemLocale], updates [currentLanguage], and returns it. */
    fun applyPreference(
        preference: AppLanguage,
        systemLocale: Locale = Locale.getDefault(),
    ): ResolvedLanguage = preference.resolve(systemLocale).also { currentLanguage = it }

    /** Executes [block] with [language] active, restoring the previous language afterward (for tests). */
    inline fun <T> withLanguage(language: ResolvedLanguage, block: () -> T): T {
        val prev = currentLanguage
        currentLanguage = language
        return try {
            block()
        } finally {
            currentLanguage = prev
        }
    }
}
