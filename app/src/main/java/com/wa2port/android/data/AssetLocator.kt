package com.wa2port.android.data

import android.content.Context
import com.wa2port.core.archive.Wa2Assets
import java.io.File

/** One place where WA2 assets may live, with its validation result. */
data class AssetCandidate(val dir: File, val label: String, val exists: Boolean, val valid: Boolean)

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
            settings.assetPath?.takeIf { it.isNotBlank() }?.let { add(File(it) to "自定义 Custom") }
            add(importDir(context) to "应用目录 App storage")
            add(File(TMP_DIR) to "adb /data/local/tmp")
        }.distinctBy { it.first.absolutePath }
        return dirs.map { (dir, label) -> AssetCandidate(dir, label, dir.isDirectory, isValid(dir)) }
    }

    /** First valid candidate, or null. */
    fun locate(context: Context, settings: Settings): File? =
        candidates(context, settings).firstOrNull { it.valid }?.dir

    fun isValid(dir: File): Boolean =
        dir.isDirectory && runCatching { Wa2Assets(dir).use { it.isValid } }.getOrDefault(false)
}
