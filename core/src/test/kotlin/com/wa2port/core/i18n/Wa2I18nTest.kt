package com.wa2port.core.i18n

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.lang.reflect.Modifier
import java.util.Locale

class Wa2I18nTest {

    @Test
    fun systemLanguageResolvesFromOsLocale() {
        assertEquals(ResolvedLanguage.ZH, AppLanguage.SYSTEM.resolve(Locale.SIMPLIFIED_CHINESE))
        assertEquals(ResolvedLanguage.ZH, AppLanguage.SYSTEM.resolve(Locale("zh", "CN")))
        assertEquals(ResolvedLanguage.ZH, AppLanguage.SYSTEM.resolve(Locale("zh", "TW")))
        assertEquals(ResolvedLanguage.ZH, AppLanguage.SYSTEM.resolve(Locale("zh", "HK")))
        assertEquals(ResolvedLanguage.ZH, AppLanguage.SYSTEM.resolve(Locale("zh")))

        assertEquals(ResolvedLanguage.JA, AppLanguage.SYSTEM.resolve(Locale.JAPANESE))
        assertEquals(ResolvedLanguage.JA, AppLanguage.SYSTEM.resolve(Locale.JAPAN))
        assertEquals(ResolvedLanguage.JA, AppLanguage.SYSTEM.resolve(Locale("ja", "JP")))

        assertEquals(ResolvedLanguage.EN, AppLanguage.SYSTEM.resolve(Locale.US))
        assertEquals(ResolvedLanguage.EN, AppLanguage.SYSTEM.resolve(Locale.UK))
        assertEquals(ResolvedLanguage.EN, AppLanguage.SYSTEM.resolve(Locale.FRANCE))
        assertEquals(ResolvedLanguage.EN, AppLanguage.SYSTEM.resolve(Locale.GERMANY))
        assertEquals(ResolvedLanguage.EN, AppLanguage.SYSTEM.resolve(Locale.KOREA))
        assertEquals(ResolvedLanguage.EN, AppLanguage.SYSTEM.resolve(Locale.ROOT))
    }

    @Test
    fun explicitOverridesIgnoreSystemLocale() {
        for (locale in listOf(Locale.US, Locale.CHINA, Locale.JAPAN, Locale.FRANCE)) {
            assertEquals(ResolvedLanguage.ZH, AppLanguage.ZH.resolve(locale))
            assertEquals(ResolvedLanguage.EN, AppLanguage.EN.resolve(locale))
            assertEquals(ResolvedLanguage.JA, AppLanguage.JA.resolve(locale))
        }
    }

    @Test
    fun applyPreferenceAndWithLanguageUpdateAndRestore() {
        val prev = Wa2I18n.currentLanguage
        try {
            assertEquals(ResolvedLanguage.JA, Wa2I18n.applyPreference(AppLanguage.SYSTEM, Locale.JAPAN))
            assertEquals(Wa2StringsJa as Wa2Strings, Wa2I18n.s)

            assertEquals(ResolvedLanguage.EN, Wa2I18n.applyPreference(AppLanguage.EN, Locale.CHINA))
            assertEquals(Wa2StringsEn as Wa2Strings, Wa2I18n.s)

            val inside = Wa2I18n.withLanguage(ResolvedLanguage.ZH) {
                assertEquals(Wa2StringsZh as Wa2Strings, Wa2I18n.s)
                Wa2I18n.s.langSystemDefault
            }
            assertEquals("跟随系统", inside)
            assertEquals(ResolvedLanguage.EN, Wa2I18n.currentLanguage)
            assertEquals("System Default", Wa2I18n.s.langSystemDefault)
            assertEquals("システムに従う", Wa2StringsJa.langSystemDefault)
        } finally {
            Wa2I18n.currentLanguage = prev
        }
    }

    @Test
    fun allStringPropertiesAreNonBlankAcrossAllLanguages() {
        val getters = Wa2Strings::class.java.methods
            .filter { m ->
                m.parameterCount == 0 &&
                    m.returnType == String::class.java &&
                    Modifier.isPublic(m.modifiers) &&
                    m.name.startsWith("get")
            }
            .sortedBy { it.name }

        assertTrue(getters.size >= 300, "Expected at least 300 Wa2Strings properties, found ${getters.size}")

        val bundles = listOf(
            "ZH" to Wa2StringsZh,
            "EN" to Wa2StringsEn,
            "JA" to Wa2StringsJa,
        )
        for ((lang, bundle) in bundles) {
            for (getter in getters) {
                val value = getter.invoke(bundle) as String
                assertTrue(value.isNotBlank(), "$lang.${getter.name} must not be blank")
            }
        }
    }

    @Test
    fun englishBundleContainsNoCjkLeakage() {
        val cjkRegex = Regex("[\\u4E00-\\u9FFF\\u3040-\\u309F\\u30A0-\\u30FF]")
        val getters = Wa2Strings::class.java.methods
            .filter { m ->
                m.parameterCount == 0 &&
                    m.returnType == String::class.java &&
                    Modifier.isPublic(m.modifiers) &&
                    m.name.startsWith("get")
            }
        val leaks = mutableListOf<String>()
        for (getter in getters) {
            val value = getter.invoke(Wa2StringsEn) as String
            if (cjkRegex.containsMatchIn(value)) {
                leaks += "${getter.name}=$value"
            }
        }
        assertTrue(leaks.isEmpty(), "Unexpected CJK characters in Wa2StringsEn: $leaks")
    }

    @Test
    fun zhAndJaBundlesContainNoBilingualChromeArtifacts() {
        val chromeWords = "Cancel|Retry|Close|Back|Settings|Config|Load|Save|Delete|Download|Update|Confirm|Feedback|Title|Language|Display|Reset|Clear|Copy|Sign in|Sign out|Account|Gallery|Replay|Notebook|Offline|Online|Support|About|Check|Enable|Disable|Yes|No|OK"
        val bilingualPairs = listOf(
            Regex("[\\u4E00-\\u9FFF\\u3040-\\u30FF].*\\b($chromeWords)\\b"),
            Regex("\\b($chromeWords)\\b.*[\\u4E00-\\u9FFF\\u3040-\\u30FF]"),
        )
        val getters = Wa2Strings::class.java.methods
            .filter { m ->
                m.parameterCount == 0 &&
                    m.returnType == String::class.java &&
                    Modifier.isPublic(m.modifiers) &&
                    m.name.startsWith("get")
            }
        val offenders = mutableListOf<String>()
        for ((lang, bundle) in listOf("ZH" to Wa2StringsZh, "JA" to Wa2StringsJa)) {
            for (getter in getters) {
                val value = getter.invoke(bundle) as String
                for (rx in bilingualPairs) {
                    if (rx.containsMatchIn(value)) {
                        offenders += "$lang.${getter.name}=$value"
                        break
                    }
                }
            }
        }
        assertTrue(offenders.isEmpty(), "Bilingual chrome artifacts found: $offenders")
        // Spot-check that App Language and Script Language are distinct in all 3 locales.
        for (bundle in listOf(Wa2StringsZh, Wa2StringsEn, Wa2StringsJa)) {
            assertFalse(bundle.configAppLanguage == bundle.configScriptLanguage)
        }
    }
}
