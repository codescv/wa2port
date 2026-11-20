package com.wa2port.android.sync

import com.wa2port.core.sync.CloudSyncSchema
import com.wa2port.core.sync.ConflictReason
import com.wa2port.core.sync.SavePreviewMeta
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileInputStream
import java.net.URLEncoder
import java.security.MessageDigest
import java.time.Instant
import java.time.OffsetDateTime
import java.util.Locale

const val DRIVE_SCOPE = "https://www.googleapis.com/auth/drive.file"
const val DEFAULT_OAUTH_AUTH_ENDPOINT = "https://accounts.google.com/o/oauth2/v2/auth"
const val DEFAULT_OAUTH_TOKEN_ENDPOINT = "https://oauth2.googleapis.com/token"
const val DEFAULT_OAUTH_REVOKE_ENDPOINT = "https://oauth2.googleapis.com/revoke"
const val DEFAULT_DRIVE_API_BASE = "https://www.googleapis.com/drive/v3"
const val DEFAULT_DRIVE_UPLOAD_BASE = "https://www.googleapis.com/upload/drive/v3"

const val DRIVE_FOLDER_MIME = "application/vnd.google-apps.folder"
const val JSON_MIME = "application/json"

internal val syncJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    prettyPrint = false
    coerceInputValues = true
}

internal val syncPrettyJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    prettyPrint = true
    coerceInputValues = true
}

fun interface SyncClock {
    fun nowMs(): Long

    companion object {
        val SYSTEM = SyncClock { System.currentTimeMillis() }
    }
}

/** Per-file baseline entry stored inside `<filesDir>/sync_state.json`. */
@Serializable
data class SyncBaselineEntry(
    val baseMd5: String,
    val remoteFileId: String,
    val remoteModifiedMs: Long = 0L,
    val localSaveTsMs: Long = 0L,
)

/** Local sync state persisted to `<filesDir>/sync_state.json`. */
@Serializable
data class SyncBaselineState(
    val version: Int = 1,
    val rootFolderId: String? = null,
    val savesFolderId: String? = null,
    val entries: Map<String, SyncBaselineEntry> = emptyMap(),
)

data class SyncAccountInfo(
    val email: String,
    val displayName: String,
)

enum class ConflictChoice {
    KEEP_LOCAL,
    USE_CLOUD,
}

/** Snapshot of one side (local or cloud) of an unresolvable conflict for UI comparison and resolution. */
data class ConflictSideInfo(
    val exists: Boolean,
    val bytes: ByteArray? = null,
    val md5: String? = null,
    val timestampMs: Long = 0L,
    val timestampReliable: Boolean = false,
    val scene: String = "",
    val previewJp: String = "",
    val previewCn: String = "",
    val thumbnailBase64: String? = null,
    val isLocked: Boolean = false,
) {
    companion object {
        fun deleted(): ConflictSideInfo = ConflictSideInfo(exists = false)

        fun fromSave(
            bytes: ByteArray,
            md5: String,
            meta: SavePreviewMeta,
            isLocked: Boolean = false,
        ): ConflictSideInfo = ConflictSideInfo(
            exists = true,
            bytes = bytes,
            md5 = md5,
            timestampMs = meta.timestampMs,
            timestampReliable = meta.timestampReliable,
            scene = meta.scene,
            previewJp = meta.previewJp,
            previewCn = meta.previewCn,
            thumbnailBase64 = meta.thumbnailBase64,
            isLocked = isLocked,
        )

        fun fromRaw(
            bytes: ByteArray,
            md5: String,
            timestampMs: Long,
            summary: String = "",
        ): ConflictSideInfo = ConflictSideInfo(
            exists = true,
            bytes = bytes,
            md5 = md5,
            timestampMs = timestampMs,
            timestampReliable = timestampMs > 0L,
            previewCn = summary,
        )
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ConflictSideInfo) return false
        return exists == other.exists &&
            md5 == other.md5 &&
            timestampMs == other.timestampMs &&
            timestampReliable == other.timestampReliable &&
            scene == other.scene &&
            previewJp == other.previewJp &&
            previewCn == other.previewCn &&
            thumbnailBase64 == other.thumbnailBase64 &&
            isLocked == other.isLocked &&
            bytes.contentEquals(other.bytes)
    }

    override fun hashCode(): Int {
        var result = exists.hashCode()
        result = 31 * result + (md5?.hashCode() ?: 0)
        result = 31 * result + timestampMs.hashCode()
        result = 31 * result + scene.hashCode()
        return result
    }
}

/** Interactive conflict requiring two-choice resolution (`KEEP_LOCAL` or `USE_CLOUD`). */
data class SyncConflict(
    val relativePath: String,
    val slot: Int?,
    val reason: ConflictReason,
    val local: ConflictSideInfo,
    val remote: ConflictSideInfo,
    val remoteFileId: String?,
    val remoteModifiedMs: Long,
    val parentFolderId: String,
) {
    val id: String get() = relativePath
    val displayLabel: String get() = CloudSyncSchema.displayLabelForPath(relativePath)
    val isSystemFile: Boolean get() = relativePath == CloudSyncSchema.SYSTEM_REMOTE_PATH
}

data class SyncResult(
    val uploaded: Int = 0,
    val downloaded: Int = 0,
    val deletedLocal: Int = 0,
    val deletedRemote: Int = 0,
    val skipped: Int = 0,
    val autoResolvedConflicts: Int = 0,
    val pendingConflicts: List<SyncConflict> = emptyList(),
    val systemUpdated: Boolean = false,
    val savesUpdated: Boolean = false,
    val errors: List<String> = emptyList(),
    val syncedAtMs: Long = 0L,
) {
    val hasErrors: Boolean get() = errors.isNotEmpty()
    val hasPendingConflicts: Boolean get() = pendingConflicts.isNotEmpty()

    fun summaryText(): String = when {
        hasErrors && uploaded == 0 && downloaded == 0 && pendingConflicts.isEmpty() -> errors.first()
        pendingConflicts.isNotEmpty() ->
            "上传 $uploaded · 下载 $downloaded · 待处理冲突 ${pendingConflicts.size}"
        uploaded == 0 && downloaded == 0 && deletedLocal == 0 && deletedRemote == 0 ->
            "已是最新 Up to date"
        else -> buildList {
            if (uploaded > 0) add("上传 $uploaded")
            if (downloaded > 0) add("下载 $downloaded")
            if (deletedLocal > 0 || deletedRemote > 0) add("删除 ${deletedLocal + deletedRemote}")
            if (autoResolvedConflicts > 0) add("自动合并 $autoResolvedConflicts")
        }.joinToString(" · ")
    }
}

data class SyncUiState(
    val isSignedIn: Boolean = false,
    val accountEmail: String = "",
    val accountName: String = "",
    val clientId: String = "",
    val hasClientSecret: Boolean = false,
    val isSigningIn: Boolean = false,
    val isSyncing: Boolean = false,
    val isResolvingConflict: Boolean = false,
    val progressMessage: String? = null,
    val lastResult: SyncResult? = null,
    val lastSyncedAtMs: Long = 0L,
    val lastError: String? = null,
    val pendingConflicts: List<SyncConflict> = emptyList(),
)

internal fun md5Hex(bytes: ByteArray): String {
    val digest = MessageDigest.getInstance("MD5").digest(bytes)
    return digest.joinToString("") { "%02x".format(Locale.US, it.toInt() and 0xFF) }
}

internal fun md5Hex(file: File): String {
    val md = MessageDigest.getInstance("MD5")
    val buf = ByteArray(64 * 1024)
    FileInputStream(file).use { input ->
        while (true) {
            val n = input.read(buf)
            if (n <= 0) break
            md.update(buf, 0, n)
        }
    }
    return md.digest().joinToString("") { "%02x".format(Locale.US, it.toInt() and 0xFF) }
}

internal fun urlEncode(value: String): String =
    URLEncoder.encode(value, "UTF-8").replace("+", "%20")

internal fun escapeDriveQueryLiteral(value: String): String =
    value.replace("\\", "\\\\").replace("'", "\\'")

internal fun parseRfc3339Ms(raw: String?): Long {
    if (raw.isNullOrBlank()) return 0L
    return try {
        Instant.parse(raw).toEpochMilli()
    } catch (_: Exception) {
        try {
            OffsetDateTime.parse(raw).toInstant().toEpochMilli()
        } catch (_: Exception) {
            0L
        }
    }
}

internal fun formatRfc3339(epochMs: Long): String = Instant.ofEpochMilli(epochMs).toString()

private val SECRET_QUERY_OR_JSON_REGEX = Regex(
    """(?i)(access_token|refresh_token|client_secret|code_verifier|Authorization)\s*([=:])\s*([^\s&",}]+)""",
)

internal fun redactSecrets(input: String): String =
    SECRET_QUERY_OR_JSON_REGEX.replace(input) { m ->
        "${m.groupValues[1]}${m.groupValues[2]}<redacted>"
    }

internal fun writeFileAtomic(target: File, bytes: ByteArray) {
    target.parentFile?.mkdirs()
    val tmp = File(target.parentFile, "${target.name}.tmp")
    tmp.writeBytes(bytes)
    if (!tmp.renameTo(target)) {
        target.delete()
        if (!tmp.renameTo(target)) {
            target.writeBytes(bytes)
            tmp.delete()
        }
    }
}
