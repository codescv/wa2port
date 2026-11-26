package com.wa2port.core.i18n

import kotlinx.serialization.Serializable
import java.util.Locale

/** User-selectable app UI language preference (persisted in `settings.json`). */
@Serializable
enum class AppLanguage {
    SYSTEM,
    ZH,
    EN,
    JA,
    ;

    /** Resolves this preference against [systemLocale] (`zh*` -> ZH, `ja*` -> JA, else EN). */
    fun resolve(systemLocale: Locale = Locale.getDefault()): ResolvedLanguage = when (this) {
        SYSTEM -> ResolvedLanguage.fromLocale(systemLocale)
        ZH -> ResolvedLanguage.ZH
        EN -> ResolvedLanguage.EN
        JA -> ResolvedLanguage.JA
    }
}

/** Concrete resolved UI language (Simplified Chinese, English, or Japanese). */
enum class ResolvedLanguage(val languageTag: String) {
    ZH("zh-CN"),
    EN("en"),
    JA("ja"),
    ;

    fun toLocale(): Locale = when (this) {
        ZH -> Locale.SIMPLIFIED_CHINESE
        EN -> Locale.ENGLISH
        JA -> Locale.JAPANESE
    }

    companion object {
        /** Resolves an OS [Locale]: `zh*` -> [ZH], `ja*` -> [JA], anything else -> [EN]. */
        fun fromLocale(locale: Locale?): ResolvedLanguage {
            val lang = locale?.language?.lowercase(Locale.ROOT).orEmpty()
            return when {
                lang == "zh" || lang.startsWith("zh-") || lang.startsWith("zh_") -> ZH
                lang == "ja" || lang.startsWith("ja-") || lang.startsWith("ja_") -> JA
                else -> EN
            }
        }
    }
}
