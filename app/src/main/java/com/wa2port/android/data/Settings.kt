package com.wa2port.android.data

import com.wa2port.core.text.TextScale
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/** Which language(s) the message window, choices and backlog show. Toggled at runtime (中/日/双). */
@Serializable
enum class DisplayLang(val label: String) {
    BOTH("双"), CN("中"), JP("日");

    val showCn: Boolean get() = this != JP
    val showJp: Boolean get() = this != CN

    fun next(): DisplayLang = when (this) {
        BOTH -> CN
        CN -> JP
        JP -> BOTH
    }
}

/** ADV = bottom message window; NOVEL = full-screen page that accumulates lines. */
@Serializable
enum class WindowMode(val label: String) {
    ADV("ADV"), NOVEL("NOVEL");

    fun next(): WindowMode = if (this == ADV) NOVEL else ADV
}

/** User preferences, persisted as JSON in the app files dir. */
@Serializable
data class Settings(
    /** Typewriter speed in glyphs per second; [TEXT_SPEED_INSTANT] or more = instant. */
    val textSpeed: Int = 45,
    /** AUTO mode: delay after the line is fully shown (and voice finished) before advancing. */
    val autoDelayMs: Int = 1500,
    val bgmVolume: Int = 70,
    val seVolume: Int = 80,
    val voiceVolume: Int = 100,
    /** false = skip only already-read lines (既読スキップ, default); true = skip everything. */
    val skipAll: Boolean = false,
    val lang: DisplayLang = DisplayLang.BOTH,
    val windowMode: WindowMode = WindowMode.ADV,
    /** Message window background opacity, percent. */
    val windowOpacity: Int = 70,
    /**
     * Font size, percent of the default size ([TextScale.MIN]..[TextScale.MAX]; presets 85/100/
     * 115/130/145). Scales the message window, NOVEL page, choices and backlog.
     */
    val textScale: Int = TextScale.DEFAULT,
    /** Explicit asset root chosen by the user (null = auto-detect). */
    val assetPath: String? = null,
    /** Last SAF tree used for import (to offer "resume import"). */
    val importTreeUri: String? = null,
) {
    val instantText: Boolean get() = textSpeed >= TEXT_SPEED_INSTANT

    /** [textScale] as a clamped factor (1.0 = 100 %), safe against hand-edited settings files. */
    val textScaleFactor: Float get() = TextScale.factor(textScale)

    /** Clamped copy — every writer (slider, ±, presets, quick bar) goes through this. */
    fun withTextScale(percent: Int): Settings = copy(textScale = TextScale.clamp(percent))

    /** Next quick-bar font size: 100 → 115 → 130 → 145 → 85 → 100 (off-preset → next preset up). */
    fun nextTextScale(): Int = TextScale.nextPreset(textScale)

    companion object {
        const val TEXT_SPEED_INSTANT = 120
    }
}

/** Loads and stores [Settings] as `settings.json`. */
class SettingsStore(private val file: File) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun load(): Settings = runCatching { json.decodeFromString<Settings>(file.readText()) }.getOrDefault(Settings())

    fun save(settings: Settings) {
        runCatching {
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(json.encodeToString(Settings.serializer(), settings))
            tmp.renameTo(file)
        }
    }
}
