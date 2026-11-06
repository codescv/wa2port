package com.wa2port.core.ai

/**
 * Where the offline AI pack is published: the fixed GitHub Release tag [TAG] of `codescv/wa2port`.
 * [apiUrl] gives size + `sha256` digest; [assetUrl] is the direct CDN download, used whenever the
 * REST API is unavailable (anonymous mobile IPs are often HTTP 403 rate-limited). An optional
 * GitHub proxy prefix (ends with `/`, e.g. `https://ghproxy.net/`) is prepended to both.
 */
data class AiPackEndpointConfig(
    val apiUrl: String = DEFAULT_API_URL,
    val assetUrl: String = DEFAULT_ASSET_URL,
    val assetName: String = OfflineAiPack.FILE_NAME,
    val proxyPrefix: String = "",
) {
    private val prefix: String get() = proxyPrefix.trim().takeIf { it.endsWith("/") && (it.startsWith("https://") || it.startsWith("http://")) }.orEmpty()

    /** Release JSON URL (through the proxy prefix, if any). */
    fun resolvedApiUrl(): String = route(apiUrl)

    /** Direct asset URL (through the proxy prefix, if any). */
    fun resolvedAssetUrl(): String = route(assetUrl)

    /** [url] routed through the proxy prefix (if any, and not already prefixed). */
    fun route(url: String): String = if (prefix.isNotEmpty() && !url.startsWith(prefix)) prefix + url else url

    /** Optional sidecar checksum (`sha256sum` format) next to [url]. */
    fun sidecarUrl(url: String): String = "$url$SHA256_SUFFIX"

    companion object {
        const val OWNER_REPO = "codescv/wa2port"
        const val TAG = "ai-pack-v1"
        const val DEFAULT_API_URL = "https://api.github.com/repos/$OWNER_REPO/releases/tags/$TAG"
        const val DEFAULT_ASSET_URL = "https://github.com/$OWNER_REPO/releases/download/$TAG/${OfflineAiPack.FILE_NAME}"
        const val SHA256_SUFFIX = ".sha256"

        /** Uses the in-app updater's custom URL as a proxy prefix when it is one (ends with `/`). */
        fun fromUpdateUrl(customUpdateUrl: String?): AiPackEndpointConfig =
            AiPackEndpointConfig(proxyPrefix = customUpdateUrl?.trim()?.takeIf { it.endsWith("/") }.orEmpty())
    }
}
