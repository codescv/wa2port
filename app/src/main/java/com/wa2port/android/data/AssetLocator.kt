package com.wa2port.android.data

import android.content.Context
import com.wa2port.core.archive.Wa2Assets
import com.wa2port.core.i18n.Wa2I18n
import com.wa2port.core.i18n.Wa2Strings
import java.io.File

enum class AssetCandidateKind {
    CUSTOM,
    APP_STORAGE,
    ADB_TMP,
    ;

    fun localizedLabel(s: Wa2Strings = Wa2I18n.s): String = when (this) {
        CUSTOM -> s.assetsLabelCustom
        APP_STORAGE -> s.assetsLabelAppStorage
        ADB_TMP -> s.assetsLabelAdb
    }
}

/** One place where WA2 assets may live, with its validation result. */
data class AssetCandidate(
    val dir: File,
    val kind: AssetCandidateKind,
    val exists: Boolean,
    val valid: Boolean,
) {
    val label: String get() = kind.localizedLabel()
}

/**
 * Finds a WA2 install on the device. Search order: user-chosen path → app-specific external
 * storage `Android/data/<pkg>/files/wa2` (SAF import target, `adb push` target) → `/data/local/tmp/wa2`.
 */
object AssetLocator {
    const val TMP_DIR = "/data/local/tmp/wa2"

    /** The SAF import destination (= `/sdcard/Android/data/com.wa2port.android/files/wa2`). */
    fun importDir(context: Context): File =
        File(context.getExternalFilesDir(null) ?: context.filesDir, "wa2")

    fun candidates(context: Context, settings: Settings): List<AssetCandidate> {
        val dirs = buildList {
            settings.assetPath?.takeIf { it.isNotBlank() }?.let { add(File(it) to AssetCandidateKind.CUSTOM) }
            add(importDir(context) to AssetCandidateKind.APP_STORAGE)
            add(File(TMP_DIR) to AssetCandidateKind.ADB_TMP)
        }.distinctBy { it.first.absolutePath }
        return dirs.map { (dir, kind) -> AssetCandidate(dir, kind, dir.isDirectory, isValid(dir)) }
    }

    /** First valid candidate, or null. */
    fun locate(context: Context, settings: Settings): File? =
        candidates(context, settings).firstOrNull { it.valid }?.dir

    fun isValid(dir: File): Boolean =
        dir.isDirectory && runCatching { Wa2Assets(dir).use { it.isValid } }.getOrDefault(false)
}
