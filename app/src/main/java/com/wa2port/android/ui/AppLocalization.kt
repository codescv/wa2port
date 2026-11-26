package com.wa2port.android.ui

import androidx.compose.runtime.compositionLocalOf
import com.wa2port.core.i18n.ResolvedLanguage
import com.wa2port.core.i18n.Wa2I18n
import com.wa2port.core.i18n.Wa2Strings

/** Currently resolved UI language for Compose screens (updates immediately when Settings changes). */
val LocalResolvedLanguage = compositionLocalOf<ResolvedLanguage> { Wa2I18n.currentLanguage }

/** Active localized UI string table for Compose screens (updates immediately when Settings changes). */
val LocalAppStrings = compositionLocalOf<Wa2Strings> { Wa2I18n.s }
