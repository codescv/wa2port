package com.wa2port.core.sync

import com.wa2port.core.engine.GFLAG_COUNT
import com.wa2port.core.engine.SystemDataJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.util.Base64
import java.util.BitSet
import java.util.Locale
import kotlin.math.abs

/**
 * Shared cross-platform Google Drive Cloud Save Sync schema (must match WA2Port iOS 100%).
 *
 * Remote layout under My Drive:
 * ```
 * WA2Port Sync/
 *   system.json
 *   saves/
 *     auto.json
 *     quick.json
 *     slot_01.json .. slot_36.json
 *     locks.json
 * ```
 *
 * Never synced: `saves/undo.json`, `saves/quick.prev.json`, `*.tmp`, `*.lock`, `*.bak`.
 */
object CloudSyncSchema {
    const val ROOT_FOLDER_NAME = "WA2Port Sync"
    const val SAVES_FOLDER_NAME = "saves"
    const val SYSTEM_FILE_NAME = "system.json"
    const val SYSTEM_REMOTE_PATH = "system.json"
    const val LOCKS_FILE_NAME = "locks.json"
    const val LOCKS_REMOTE_PATH = "saves/locks.json"
    const val SYNC_STATE_FILE_NAME = "sync_state.json"
    const val CONFLICTS_BACKUP_DIR_NAME = "saves_conflicts"

    const val AUTO_SLOT = -1
    const val QUICK_SLOT = 0
    const val UNDO_SLOT = -2
    const val MAX_USER_SLOT = 36

    /** 5-second clock skew / simultaneous-save tie guard. */
    const val TIMESTAMP_TIE_GUARD_MS = 5_000L

    /** All save slots synchronized across devices: auto (-1), quick (0), and slots 1..36. */
    val SYNCED_SLOTS: List<Int> = listOf(AUTO_SLOT, QUICK_SLOT) + (1..MAX_USER_SLOT)

    /** Explicitly excluded save files that must never leave the local device. */
    val EXCLUDED_SAVE_FILES: Set<String> = setOf("undo.json", "quick.prev.json")

    private val SLOT_FILE_REGEX = Regex("^slot_(\\d{2})\\.json$")

    fun saveFileNameForSlot(slot: Int): String? = when (slot) {
        AUTO_SLOT -> "auto.json"
        QUICK_SLOT -> "quick.json"
        in 1..MAX_USER_SLOT -> "slot_%02d.json".format(Locale.ROOT, slot)
        else -> null
    }

    fun relativePathForSlot(slot: Int): String? =
        saveFileNameForSlot(slot)?.let { "$SAVES_FOLDER_NAME/$it" }

    fun slotForSaveFileName(fileName: String): Int? = when {
        fileName in EXCLUDED_SAVE_FILES -> null
        fileName == "auto.json" -> AUTO_SLOT
        fileName == "quick.json" -> QUICK_SLOT
        else -> {
            val match = SLOT_FILE_REGEX.matchEntire(fileName) ?: return null
            val num = match.groupValues[1].toIntOrNull() ?: return null
            if (num in 1..MAX_USER_SLOT) num else null
        }
    }

    fun slotForRelativePath(relativePath: String): Int? {
        if (!relativePath.startsWith("$SAVES_FOLDER_NAME/")) return null
        val name = relativePath.removePrefix("$SAVES_FOLDER_NAME/")
        if ('/' in name) return null
        return slotForSaveFileName(name)
    }

    fun isSyncedSaveFileName(fileName: String): Boolean = slotForSaveFileName(fileName) != null

    fun displayLabelForPath(relativePath: String): String {
        if (relativePath == SYSTEM_REMOTE_PATH) return "系统数据 System"
        if (relativePath == LOCKS_REMOTE_PATH) return "存档锁定 Locks"
        return when (val slot = slotForRelativePath(relativePath)) {
            AUTO_SLOT -> "自动存档 Auto"
            QUICK_SLOT -> "快速存档 Quick"
            in 1..MAX_USER_SLOT -> "存档 %02d".format(Locale.ROOT, slot)
            else -> relativePath
        }
    }
}

internal val coreSyncJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    prettyPrint = false
    coerceInputValues = true
}

/**
 * Cross-platform representation of `saves/locks.json`:
 * `{"version":1,"updatedAtMs":<epochMs>,"lockedSlots":[0,1,...]}` (sorted distinct ints).
 */
@Serializable
data class LocksDocument(
    val version: Int = 1,
    val updatedAtMs: Long = 0L,
    val lockedSlots: List<Int> = emptyList(),
) {
    fun canonical(): LocksDocument = LocksDocument(
        version = 1,
        updatedAtMs = updatedAtMs.coerceAtLeast(0L),
        lockedSlots = lockedSlots
            .filter { it in CloudSyncSchema.QUICK_SLOT..CloudSyncSchema.MAX_USER_SLOT }
            .distinct()
            .sorted(),
    )
}

object LocksCodec {
    fun encode(doc: LocksDocument): String = coreSyncJson.encodeToString(doc.canonical())

    fun decodeOrNull(text: String): LocksDocument? = runCatching {
        val el = coreSyncJson.parseToJsonElement(text)
        if (el !is JsonObject) return null
        coreSyncJson.decodeFromString<LocksDocument>(text).canonical()
    }.getOrNull()

    fun decodeOrNull(bytes: ByteArray): LocksDocument? = decodeOrNull(bytes.toString(Charsets.UTF_8))

    /**
     * Resolves a `saves/locks.json` conflict:
     * - If `|local.updatedAtMs - remote.updatedAtMs| >= 5000ms` (with both positive), newer wins.
     * - Otherwise takes the sorted union of `lockedSlots`.
     */
    fun resolveConflict(
        local: LocksDocument,
        remote: LocksDocument,
        nowMs: Long,
    ): LocksDocument {
        val l = local.canonical()
        val r = remote.canonical()
        if (l.lockedSlots == r.lockedSlots) {
            return l.copy(updatedAtMs = maxOf(l.updatedAtMs, r.updatedAtMs))
        }
        val bothReliable = l.updatedAtMs > 0L && r.updatedAtMs > 0L
        if (bothReliable && abs(l.updatedAtMs - r.updatedAtMs) >= CloudSyncSchema.TIMESTAMP_TIE_GUARD_MS) {
            return if (l.updatedAtMs > r.updatedAtMs) l else r
        }
        return LocksDocument(
            version = 1,
            updatedAtMs = maxOf(l.updatedAtMs, r.updatedAtMs, nowMs),
            lockedSlots = (l.lockedSlots + r.lockedSlots).distinct().sorted(),
        )
    }
}

/** Lossless union merge for `system.json` (`gflags`, `read` bitsets, `cgFlags`, `bgmFlags`). */
object SystemDataMerger {
    /**
     * Merges two `system.json` payloads without losing unlocked progress or read lines.
     * Returns `null` if either payload is not a valid JSON object matching [SystemDataJson].
     */
    fun mergeJson(localText: String, remoteText: String): String? {
        val local = parseStrictOrNull(localText) ?: return null
        val remote = parseStrictOrNull(remoteText) ?: return null
        val merged = merge(local, remote)
        return coreSyncJson.encodeToString(merged)
    }

    fun parseStrictOrNull(text: String): SystemDataJson? = runCatching {
        val el = coreSyncJson.parseToJsonElement(text)
        if (el !is JsonObject) return null
        coreSyncJson.decodeFromString<SystemDataJson>(text)
    }.getOrNull()

    fun merge(local: SystemDataJson, remote: SystemDataJson): SystemDataJson {
        val gflagLen = maxOf(GFLAG_COUNT, local.gflags.size, remote.gflags.size)
        val mergedGflags = IntArray(gflagLen) { i ->
            val a = local.gflags.getOrElse(i) { 0 }
            val b = remote.gflags.getOrElse(i) { 0 }
            mergeGflag(a, b)
        }

        val mergedRead = linkedMapOf<String, BitSet>()
        fun absorbRead(source: Map<String, String>) {
            for ((scene, b64) in source) {
                val key = scene.lowercase(Locale.ROOT)
                val decoded = runCatching { Base64.getDecoder().decode(b64) }.getOrNull() ?: continue
                val bits = BitSet.valueOf(decoded)
                val existing = mergedRead[key]
                if (existing == null) {
                    mergedRead[key] = bits
                } else {
                    existing.or(bits)
                }
            }
        }
        absorbRead(local.read)
        absorbRead(remote.read)
        val exportedRead = mergedRead.entries
            .sortedBy { it.key }
            .associate { (k, v) -> k to Base64.getEncoder().encodeToString(v.toByteArray()) }

        val mergedCg = (local.cgFlags + remote.cgFlags).distinct().sorted()
        val mergedBgm = (local.bgmFlags + remote.bgmFlags).distinct().sorted()
        for (id in mergedBgm) {
            if (id in 1..199 && (100 + id) in mergedGflags.indices) {
                mergedGflags[100 + id] = mergeGflag(mergedGflags[100 + id], 1)
            }
        }

        return SystemDataJson(
            gflags = mergedGflags.toList(),
            read = exportedRead,
            cgFlags = mergedCg,
            bgmFlags = mergedBgm,
        )
    }

    private fun mergeGflag(a: Int, b: Int): Int = when {
        a == b -> a
        a == 0 -> b
        b == 0 -> a
        else -> maxOf(a, b)
    }
}

/** Lightweight metadata extracted from a save slot JSON for conflict resolution & UI display. */
data class SavePreviewMeta(
    val timestampMs: Long = 0L,
    val timestampReliable: Boolean = false,
    val scene: String = "",
    val previewJp: String = "",
    val previewCn: String = "",
    val thumbnailBase64: String? = null,
)

object SavePreviewParser {
    fun parse(bytes: ByteArray, fallbackMtimeMs: Long = 0L): SavePreviewMeta =
        parse(bytes.toString(Charsets.UTF_8), fallbackMtimeMs)

    fun parse(text: String, fallbackMtimeMs: Long = 0L): SavePreviewMeta {
        val obj = runCatching { coreSyncJson.parseToJsonElement(text) as? JsonObject }.getOrNull()
            ?: return SavePreviewMeta(
                timestampMs = fallbackMtimeMs.coerceAtLeast(0L),
                timestampReliable = false,
            )
        val jsonTs = obj["timestamp"]?.jsonPrimitive?.longOrNull
            ?: obj["timestamp"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
            ?: 0L
        val reliable = jsonTs > 0L
        val effectiveTs = if (reliable) jsonTs else fallbackMtimeMs.coerceAtLeast(0L)
        return SavePreviewMeta(
            timestampMs = effectiveTs,
            timestampReliable = reliable,
            scene = obj["scene"]?.jsonPrimitive?.contentOrNull ?: "",
            previewJp = obj["previewJp"]?.jsonPrimitive?.contentOrNull ?: "",
            previewCn = obj["previewCn"]?.jsonPrimitive?.contentOrNull ?: "",
            thumbnailBase64 = obj["thumbnail"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() },
        )
    }
}

enum class ConflictReason(val label: String) {
    TIMESTAMP_TIE("本地与云端存档时间非常接近，请手动选择"),
    MISSING_TIMESTAMP("存档缺少可靠时间戳，无法自动判断"),
    LOCAL_LOCKED("本地存档已锁定，需手动确认是否使用云端版本"),
    MODIFY_VS_DELETE("一侧已修改而另一侧已删除，请手动选择"),
    UNPARSEABLE("文件无法自动合并，请手动选择保留哪一侧"),
}

sealed interface SaveConflictDecision {
    data object AutoLocalWins : SaveConflictDecision
    data object AutoRemoteWins : SaveConflictDecision
    data class NeedsUserChoice(val reason: ConflictReason) : SaveConflictDecision
}

object SaveConflictEvaluator {
    /**
     * Evaluates a both-modified / first-sync save slot conflict according to the WA2Port policy:
     * - Auto-resolve by timestamp iff both sides have reliable positive timestamps,
     *   `|localTs - remoteTs| >= 5000ms`, and local slot is NOT locked when remote would win.
     * - Otherwise require interactive user choice (`NeedsUserChoice`).
     */
    fun evaluate(
        localMeta: SavePreviewMeta,
        remoteMeta: SavePreviewMeta,
        isLocallyLocked: Boolean,
    ): SaveConflictDecision {
        if (!localMeta.timestampReliable || !remoteMeta.timestampReliable) {
            return SaveConflictDecision.NeedsUserChoice(
                if (isLocallyLocked) ConflictReason.LOCAL_LOCKED else ConflictReason.MISSING_TIMESTAMP,
            )
        }
        val diff = abs(localMeta.timestampMs - remoteMeta.timestampMs)
        if (diff < CloudSyncSchema.TIMESTAMP_TIE_GUARD_MS) {
            return SaveConflictDecision.NeedsUserChoice(
                if (isLocallyLocked) ConflictReason.LOCAL_LOCKED else ConflictReason.TIMESTAMP_TIE,
            )
        }
        return if (localMeta.timestampMs > remoteMeta.timestampMs) {
            SaveConflictDecision.AutoLocalWins
        } else if (isLocallyLocked) {
            SaveConflictDecision.NeedsUserChoice(ConflictReason.LOCAL_LOCKED)
        } else {
            SaveConflictDecision.AutoRemoteWins
        }
    }
}
