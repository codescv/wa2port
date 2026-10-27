package com.wa2port.core.update

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/** One downloadable file of a release. */
data class ReleaseAsset(
    val name: String,
    val downloadUrl: String,
    val sizeBytes: Long,
    val contentType: String,
)

/** The parts of a GitHub release the updater needs. */
data class ReleaseInfo(
    val tagName: String,
    /** [tagName] without the leading `v`. */
    val version: String,
    val title: String,
    val body: String,
    val publishedAt: String?,
    val htmlUrl: String,
    /** Best APK asset ([ReleaseJson.pickApk]); null when the release has none. */
    val apkAsset: ReleaseAsset?,
    val isPreRelease: Boolean = false,
)

/** Result of [AppUpdater.checkLatestRelease]. */
data class UpdateCheckResult(
    val currentVersion: String,
    val latest: ReleaseInfo,
    val updateAvailable: Boolean,
)

/** GitHub Releases "latest" JSON → [ReleaseInfo]; tolerant of nulls, missing fields and extra keys. */
object ReleaseJson {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val WA2PORT_APK = Regex("^wa2port-.*-release\\.apk$", RegexOption.IGNORE_CASE)

    /**
     * Parses one release object — or a `/releases` list, taking the first published non-pre-release
     * (else the first non-draft); throws [UpdateException] (INVALID_RESPONSE) on malformed JSON /
     * missing `tag_name`.
     */
    fun parse(text: String): ReleaseInfo {
        val root = try {
            json.parseToJsonElement(text)
        } catch (e: Exception) {
            throw UpdateException(UpdateErrorCode.INVALID_RESPONSE, "更新信息不是有效的 JSON Invalid release JSON", e)
        }
        val obj = when (root) {
            is JsonObject -> root
            is JsonArray -> {
                val releases = root.filterIsInstance<JsonObject>().filter { !it.bool("draft") }
                releases.firstOrNull { !it.bool("prerelease") } ?: releases.firstOrNull()
                    ?: throw UpdateException(UpdateErrorCode.INVALID_RESPONSE, "没有找到发布版本 No release found")
            }
            else -> throw UpdateException(UpdateErrorCode.INVALID_RESPONSE, "更新信息格式错误 Release JSON is not an object")
        }
        val tag = obj.str("tag_name")?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw UpdateException(UpdateErrorCode.INVALID_RESPONSE, "更新信息缺少版本号 Release JSON has no tag_name")
        val assets = (obj["assets"] as? JsonArray).orEmpty().mapNotNull { el ->
            val a = el as? JsonObject ?: return@mapNotNull null
            val name = a.str("name") ?: return@mapNotNull null
            val url = a.str("browser_download_url") ?: return@mapNotNull null
            ReleaseAsset(
                name = name,
                downloadUrl = url,
                sizeBytes = (a["size"] as? JsonPrimitive)?.longOrNull ?: -1L,
                contentType = a.str("content_type").orEmpty(),
            )
        }
        return ReleaseInfo(
            tagName = tag,
            version = tag.removePrefix("v").removePrefix("V"),
            title = obj.str("name")?.takeIf { it.isNotBlank() } ?: tag,
            body = obj.str("body").orEmpty(),
            publishedAt = obj.str("published_at"),
            htmlUrl = obj.str("html_url")?.takeIf { it.isNotBlank() } ?: UpdateEndpointConfig.DEFAULT_RELEASE_HTML_URL,
            apkAsset = pickApk(assets),
            isPreRelease = obj.bool("prerelease"),
        )
    }

    /**
     * Picks the APK to install: 1) `wa2port-*-release.apk`, 2) `app-release.apk`, 3) any `.apk`
     * (all case-insensitive); ties → first in list.
     */
    fun pickApk(assets: List<ReleaseAsset>): ReleaseAsset? =
        assets.firstOrNull { WA2PORT_APK.matches(it.name) }
            ?: assets.firstOrNull { it.name.equals("app-release.apk", ignoreCase = true) }
            ?: assets.firstOrNull { it.name.endsWith(".apk", ignoreCase = true) }

    private fun JsonObject.bool(key: String): Boolean = (this[key] as? JsonPrimitive)?.booleanOrNull ?: false

    private fun JsonObject.str(key: String): String? {
        val el: JsonElement = this[key] ?: return null
        if (el is JsonNull) return null
        return (el as? JsonPrimitive)?.contentOrNull
    }
}
