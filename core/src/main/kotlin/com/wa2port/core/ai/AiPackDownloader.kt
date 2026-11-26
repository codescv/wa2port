package com.wa2port.core.ai

import com.wa2port.core.i18n.Wa2I18n
import com.wa2port.core.update.UpdateErrorCode
import com.wa2port.core.update.UpdateException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.MalformedURLException
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** What the release offers for download (from the REST API, or the direct-URL fallback). */
data class AiPackRemoteInfo(
    val tag: String,
    val assetName: String,
    /** Always the stable `browser_download_url` (the signed CDN URL it redirects to expires). */
    val downloadUrl: String,
    /** -1 when unknown (direct fallback). */
    val sizeBytes: Long,
    /** Lower-case hex SHA-256, from the asset `digest` (`sha256:…`) when the API provides it. */
    val sha256: String?,
    val updatedAt: String?,
    /** True when the REST API failed and [downloadUrl] is the fixed direct asset URL. */
    val fromFallback: Boolean = false,
    /** Why the API was skipped (rate limit…), for diagnostics. */
    val apiError: String? = null,
)

/**
 * One-click download of the offline AI pack: release lookup with direct-URL fallback, up to
 * [MAX_REDIRECTS] cross-host HTTPS redirects (never https → http), `Accept-Encoding: identity`,
 * resumable `.part` (`Range` → `206` appends, `200` overwrites), progress, size + SHA-256
 * verification, [OfflineAiPack.open] structural validation, then an atomic rename. Blocking: call
 * from `Dispatchers.IO`. [openConnection] is the test seam.
 */
class AiPackDownloader(
    private val openConnection: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection },
) {
    companion object {
        const val MAX_REDIRECTS = 5
        val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
        const val BUFFER = 64 * 1024
        const val MAX_JSON_BYTES = 2 * 1024 * 1024
        const val MAX_UNKNOWN_SIZE_BYTES = 1024L * 1024 * 1024
        const val PART_SUFFIX = ".part"
        private const val PROGRESS_INTERVAL_MS = 100L
        private const val PROGRESS_INTERVAL_BYTES = 256L * 1024
        private val SHA256_HEX = Regex("^[0-9a-fA-F]{64}$")

        /** Parses a GitHub release JSON for [assetName]; null when the release has no such asset. */
        fun parseRelease(text: String, assetName: String): AiPackRemoteInfo? {
            val root = runCatching { AiJson.parseToJsonElement(text) }.getOrNull() as? JsonObject
                ?: throw UpdateException(UpdateErrorCode.INVALID_RESPONSE, Wa2I18n.s.errPackInvalidReleaseJson)
            fun JsonObject.s(k: String) = (this[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull
            val tag = root.s("tag_name") ?: AiPackEndpointConfig.TAG
            val asset = (root["assets"] as? JsonArray)?.filterIsInstance<JsonObject>()?.firstOrNull { it.s("name") == assetName } ?: return null
            val url = asset.s("browser_download_url") ?: return null
            val digest = asset.s("digest")?.trim()?.lowercase()
            val sha = digest?.removePrefix("sha256:")?.takeIf { digest.startsWith("sha256:") && SHA256_HEX.matches(it) }
            return AiPackRemoteInfo(
                tag = tag,
                assetName = assetName,
                downloadUrl = url,
                sizeBytes = (asset["size"] as? JsonPrimitive)?.longOrNull ?: -1L,
                sha256 = sha,
                updatedAt = asset.s("updated_at") ?: root.s("published_at"),
            )
        }

        /** First 64-hex token of a `sha256sum`-style sidecar, or null. */
        fun parseSidecar(text: String): String? =
            text.trim().split(Regex("\\s+")).firstOrNull()?.takeIf { SHA256_HEX.matches(it) }?.lowercase()

        fun sha256(file: File): String {
            val md = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { ins ->
                val buf = ByteArray(BUFFER)
                while (true) {
                    val n = ins.read(buf)
                    if (n < 0) break
                    md.update(buf, 0, n)
                }
            }
            return md.digest().joinToString("") { "%02x".format(it) }
        }
    }

    /**
     * Release info from the REST API; when it fails for any reason other than cancellation
     * (rate limit 403/429, 404, DNS, proxy…), the fixed direct asset URL is returned instead so
     * the download can still go straight to the CDN.
     */
    fun fetchInfo(endpoint: AiPackEndpointConfig = AiPackEndpointConfig(), cancel: CancelToken? = null, timeoutMs: Int = 15_000): AiPackRemoteInfo {
        val apiUrl = endpoint.resolvedApiUrl()
        val headers = mapOf(
            "Accept" to "application/vnd.github+json",
            "X-GitHub-Api-Version" to "2022-11-28",
            "User-Agent" to USER_AGENT,
        )
        var conn: HttpURLConnection? = null
        val apiError: String = try {
            conn = openFollowingRedirects(apiUrl, headers, cancel, timeoutMs, timeoutMs)
            val status = conn.responseCode
            if (status in 200..299) {
                val info = parseRelease(readLimited(conn.inputStream, MAX_JSON_BYTES, cancel), endpoint.assetName)
                if (info != null) return info.copy(downloadUrl = endpoint.route(info.downloadUrl))
                Wa2I18n.s.errPackAssetMissing(endpoint.assetName)
            } else {
                httpError(conn, status, Wa2I18n.s.errPackQueryFailed).message ?: "HTTP $status"
            }
        } catch (e: Exception) {
            val mapped = mapError(e, cancel, apiUrl, timeoutMs)
            if (mapped.code == UpdateErrorCode.CANCELLED) throw mapped
            mapped.message ?: mapped.code.name
        } finally {
            cancel?.detach()
            conn?.disconnect()
        }
        return AiPackRemoteInfo(
            tag = AiPackEndpointConfig.TAG,
            assetName = endpoint.assetName,
            downloadUrl = endpoint.resolvedAssetUrl(),
            sizeBytes = -1,
            sha256 = null,
            updatedAt = null,
            fromFallback = true,
            apiError = apiError,
        )
    }

    /** `.part` file used for [destFile]'s resumable download. */
    fun partFile(destFile: File): File = File(destFile.path + PART_SUFFIX)

    /**
     * Downloads [info] into `destFile.part` (resuming an existing partial file with `Range`),
     * verifies size / SHA-256 (from [info] or the optional `.sha256` sidecar) and the pack
     * structure, then atomically replaces [destFile]. A dropped connection or cancel keeps the
     * `.part` for resume; a corrupt result deletes it. [onProgress] gets `(downloaded, total)`
     * including resumed bytes (`total = -1` when unknown).
     */
    fun download(
        info: AiPackRemoteInfo,
        destFile: File,
        cancel: CancelToken? = null,
        connectTimeoutMs: Int = 20_000,
        readTimeoutMs: Int = 60_000,
        sidecarLookup: Boolean = true,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit = { _, _ -> },
    ): File {
        destFile.parentFile?.mkdirs()
        val part = partFile(destFile)
        if (info.sizeBytes > 0 && part.length() > info.sizeBytes) part.delete()
        var attempt = 0
        while (true) {
            val existing = if (part.isFile) part.length() else 0L
            val complete = info.sizeBytes > 0 && existing == info.sizeBytes
            if (!complete) {
                val restart = transfer(info, part, existing, cancel, connectTimeoutMs, readTimeoutMs, onProgress)
                if (restart && attempt++ == 0) continue
            } else {
                onProgress(existing, info.sizeBytes)
            }
            break
        }
        try {
            verifyAndInstall(info, part, destFile, cancel, connectTimeoutMs, readTimeoutMs, sidecarLookup)
        } catch (e: UpdateException) {
            if (e.code == UpdateErrorCode.CORRUPT_DOWNLOAD) part.delete()
            throw e
        }
        return destFile
    }

    /** One HTTP transfer into [part]; returns true when the caller should restart from 0 (stale range). */
    private fun transfer(
        info: AiPackRemoteInfo,
        part: File,
        existing: Long,
        cancel: CancelToken?,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
        onProgress: (Long, Long) -> Unit,
    ): Boolean {
        val headers = LinkedHashMap<String, String>()
        headers["Accept"] = "application/octet-stream"
        headers["Accept-Encoding"] = "identity"
        headers["User-Agent"] = USER_AGENT
        if (existing > 0) headers["Range"] = "bytes=$existing-"
        var conn: HttpURLConnection? = null
        try {
            conn = openFollowingRedirects(info.downloadUrl, headers, cancel, connectTimeoutMs, readTimeoutMs)
            val status = conn.responseCode
            val append: Boolean
            val total: Long
            when (status) {
                206 -> {
                    val cr = parseContentRange(conn.getHeaderField("Content-Range"))
                    if (existing == 0L || cr == null || cr.first != existing) {
                        // Server ignored / mangled our range: start over cleanly.
                        part.delete()
                        return true
                    }
                    append = true
                    total = cr.third.takeIf { it > 0 } ?: conn.contentLengthLong.takeIf { it > 0 }?.let { it + existing } ?: info.sizeBytes
                }
                200 -> {
                    append = false
                    total = conn.contentLengthLong.takeIf { it > 0 } ?: info.sizeBytes
                }
                416 -> {
                    // Range not satisfiable: either already complete, or the remote file changed.
                    val cr = conn.getHeaderField("Content-Range")?.substringAfter('/')?.trim()?.toLongOrNull()
                    if (cr != null && cr == existing) {
                        onProgress(existing, existing)
                        return false
                    }
                    part.delete()
                    return true
                }
                else -> throw httpError(conn, status, Wa2I18n.s.errPackDownloadFailed)
            }
            if (conn.contentType.orEmpty().trim().startsWith("text/html", ignoreCase = true)) {
                throw UpdateException(UpdateErrorCode.CORRUPT_DOWNLOAD, Wa2I18n.s.errServerReturnedWebPagePack)
            }
            if (info.sizeBytes > 0 && total > 0 && total != info.sizeBytes) {
                part.delete()
                throw UpdateException(UpdateErrorCode.CORRUPT_DOWNLOAD, Wa2I18n.s.errSizeMismatch(total, info.sizeBytes))
            }
            stream(conn.inputStream, part, append, if (append) existing else 0L, total, cancel, onProgress)
            return false
        } catch (e: Exception) {
            throw mapError(e, cancel, info.downloadUrl, readTimeoutMs)
        } finally {
            cancel?.detach()
            conn?.disconnect()
        }
    }

    private fun stream(input: InputStream, part: File, append: Boolean, start: Long, total: Long, cancel: CancelToken?, onProgress: (Long, Long) -> Unit) {
        var written = start
        var lastBytes = -1L
        var lastAt = 0L
        input.use { ins ->
            val fos = try {
                FileOutputStream(part, append)
            } catch (e: IOException) {
                throw storageError(e)
            }
            fos.use { out ->
                val buf = ByteArray(BUFFER)
                while (true) {
                    if (cancel?.isCancelled == true) throw cancelled()
                    val n = try {
                        ins.read(buf)
                    } catch (e: SocketTimeoutException) {
                        throw e
                    } catch (e: IOException) {
                        if (cancel?.isCancelled == true) throw cancelled()
                        throw UpdateException(UpdateErrorCode.NETWORK, Wa2I18n.s.errPackConnectionDropped(written, if (total > 0) total.toString() else "?"), e)
                    }
                    if (n < 0) break
                    if (n == 0) continue
                    try {
                        out.write(buf, 0, n)
                    } catch (e: IOException) {
                        throw storageError(e)
                    }
                    written += n
                    if (total > 0 && written > total) {
                        throw UpdateException(UpdateErrorCode.CORRUPT_DOWNLOAD, Wa2I18n.s.errDownloadLarger(written, total))
                    }
                    if (total <= 0 && written > MAX_UNKNOWN_SIZE_BYTES) {
                        throw UpdateException(UpdateErrorCode.CORRUPT_DOWNLOAD, Wa2I18n.s.errDownloadTooLarge(MAX_UNKNOWN_SIZE_BYTES shr 20))
                    }
                    val now = System.currentTimeMillis()
                    if (lastBytes < 0 || now - lastAt >= PROGRESS_INTERVAL_MS || written - lastBytes >= PROGRESS_INTERVAL_BYTES) {
                        onProgress(written, total)
                        lastBytes = written
                        lastAt = now
                    }
                }
                try {
                    out.flush()
                    out.fd.sync()
                } catch (e: IOException) {
                    throw storageError(e)
                }
            }
        }
        if (cancel?.isCancelled == true) throw cancelled()
        if (lastBytes != written) onProgress(written, total)
        if (total > 0 && written != total) {
            throw UpdateException(UpdateErrorCode.NETWORK, Wa2I18n.s.errIncompleteDownloadResumable(written, total))
        }
    }

    private fun verifyAndInstall(
        info: AiPackRemoteInfo,
        part: File,
        dest: File,
        cancel: CancelToken?,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
        sidecarLookup: Boolean,
    ) {
        if (!part.isFile) throw UpdateException(UpdateErrorCode.CORRUPT_DOWNLOAD, Wa2I18n.s.errPackDownloadMissing)
        if (info.sizeBytes > 0 && part.length() != info.sizeBytes) {
            throw UpdateException(UpdateErrorCode.CORRUPT_DOWNLOAD, Wa2I18n.s.errSizeMismatch(part.length(), info.sizeBytes))
        }
        val expectedSha = info.sha256 ?: if (sidecarLookup) fetchSidecar(info.downloadUrl, cancel, connectTimeoutMs, readTimeoutMs) else null
        if (cancel?.isCancelled == true) throw cancelled()
        if (expectedSha != null) {
            val actual = sha256(part)
            if (!actual.equals(expectedSha, ignoreCase = true)) {
                throw UpdateException(UpdateErrorCode.CORRUPT_DOWNLOAD, Wa2I18n.s.errPackSha256Mismatch(actual.take(12), expectedSha.take(12)))
            }
        }
        try {
            OfflineAiPack.open(part).use { pack ->
                require(pack.entryCount > 0) { Wa2I18n.s.errPackEmpty }
                // Touch the first and last block so a truncated tail is caught before install.
                requireNotNull(pack.entry(0)) { Wa2I18n.s.errPackCorruptFirstBlock }
                requireNotNull(pack.entry(pack.entryCount - 1)) { Wa2I18n.s.errPackCorruptLastBlock }
            }
        } catch (e: UpdateException) {
            throw e
        } catch (e: Exception) {
            throw UpdateException(UpdateErrorCode.CORRUPT_DOWNLOAD, Wa2I18n.s.errPackInvalid(e.message ?: e.javaClass.simpleName), e)
        }
        try {
            Files.move(part.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: Exception) {
            dest.delete()
            if (!part.renameTo(dest)) throw UpdateException(UpdateErrorCode.NETWORK, Wa2I18n.s.errCannotSavePack(dest.name), e)
        }
    }

    /** Best-effort `<url>.sha256` sidecar (any failure → null, no checksum). */
    private fun fetchSidecar(url: String, cancel: CancelToken?, connectTimeoutMs: Int, readTimeoutMs: Int): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = openFollowingRedirects("$url${AiPackEndpointConfig.SHA256_SUFFIX}", mapOf("Accept-Encoding" to "identity", "User-Agent" to USER_AGENT), cancel, connectTimeoutMs, readTimeoutMs)
            if (conn.responseCode != 200) null else parseSidecar(readLimited(conn.inputStream, 4096, cancel))
        } catch (e: Exception) {
            if (cancel?.isCancelled == true) throw cancelled()
            null
        } finally {
            cancel?.detach()
            conn?.disconnect()
        }
    }

    /** `bytes start-end/total` → (start, end, total or -1). */
    private fun parseContentRange(v: String?): Triple<Long, Long, Long>? {
        val m = Regex("bytes\\s+(\\d+)-(\\d+)/(\\d+|\\*)", RegexOption.IGNORE_CASE).find(v ?: return null) ?: return null
        return Triple(m.groupValues[1].toLong(), m.groupValues[2].toLong(), m.groupValues[3].toLongOrNull() ?: -1L)
    }

    // ------------------------------------------------------------------------------- http helpers (as AppUpdater)

    private fun openFollowingRedirects(url: String, headers: Map<String, String>, cancel: CancelToken?, connectTimeoutMs: Int, readTimeoutMs: Int): HttpURLConnection {
        var current = try {
            URL(url)
        } catch (e: MalformedURLException) {
            throw UpdateException(UpdateErrorCode.INVALID_ARGUMENT, Wa2I18n.s.errInvalidUrl(url), e)
        }
        requireHttp(current, initial = true)
        var hops = 0
        while (true) {
            if (cancel?.isCancelled == true) throw cancelled()
            val conn = openConnection(current)
            var handedOff = false
            try {
                cancel?.attach { conn.disconnect() }
                conn.requestMethod = "GET"
                conn.instanceFollowRedirects = false
                conn.useCaches = false
                conn.connectTimeout = connectTimeoutMs.coerceAtLeast(1_000)
                conn.readTimeout = readTimeoutMs.coerceAtLeast(1_000)
                headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
                val status = conn.responseCode
                if (status !in REDIRECT_CODES) {
                    handedOff = true
                    return conn
                }
                val location = conn.getHeaderField("Location")?.trim()
                if (location.isNullOrEmpty()) throw UpdateException(UpdateErrorCode.HTTP, Wa2I18n.s.errRedirectMissingLocation(status), httpStatus = status)
                if (++hops > MAX_REDIRECTS) throw UpdateException(UpdateErrorCode.NETWORK, Wa2I18n.s.errTooManyRedirects)
                val next = try {
                    URL(current, location)
                } catch (e: MalformedURLException) {
                    throw UpdateException(UpdateErrorCode.HTTP, Wa2I18n.s.errInvalidRedirect(location), e, status)
                }
                requireHttp(next, initial = false)
                if (current.protocol.equals("https", true) && next.protocol.equals("http", true)) {
                    throw UpdateException(UpdateErrorCode.NETWORK, Wa2I18n.s.errInsecureRedirectHttps)
                }
                current = next
            } finally {
                if (!handedOff) {
                    cancel?.detach()
                    conn.disconnect()
                }
            }
        }
    }

    private fun requireHttp(url: URL, initial: Boolean) {
        val proto = url.protocol.lowercase()
        if (proto != "http" && proto != "https") {
            val msg = if (initial) Wa2I18n.s.errUnsupportedScheme(proto) else Wa2I18n.s.errInsecureRedirectScheme(proto)
            throw UpdateException(if (initial) UpdateErrorCode.INVALID_ARGUMENT else UpdateErrorCode.NETWORK, msg)
        }
        if (url.host.isNullOrEmpty()) throw UpdateException(UpdateErrorCode.INVALID_ARGUMENT, Wa2I18n.s.errUrlNoHost(url.toString()))
    }

    private fun httpError(conn: HttpURLConnection, status: Int, what: String): UpdateException {
        val body = runCatching { conn.errorStream?.use { readLimited(it, 4096, null, truncate = true) } }.getOrNull().orEmpty()
        val rateLimited = status == 429 ||
            (status == 403 && (conn.getHeaderField("X-RateLimit-Remaining")?.trim() == "0" || body.contains("rate limit", ignoreCase = true)))
        val msg = when {
            rateLimited -> Wa2I18n.s.errRateLimited(status)
            status == 404 -> Wa2I18n.s.errPackNotFound404
            else -> "$what HTTP $status" + body.trim().take(160).let { if (it.isNotEmpty()) ": $it" else "" }
        }
        return UpdateException(UpdateErrorCode.HTTP, msg, httpStatus = status)
    }

    private fun mapError(e: Exception, cancel: CancelToken?, url: String, timeoutMs: Int): UpdateException {
        if (cancel?.isCancelled == true) {
            return if (e is UpdateException && e.code == UpdateErrorCode.CANCELLED) e else UpdateException(UpdateErrorCode.CANCELLED, Wa2I18n.s.errCancelled, e)
        }
        if (e is UpdateException) return e
        val host = runCatching { URL(url).host }.getOrDefault("")
        val where = if (host.isNotEmpty()) " ($host)" else ""
        return when (e) {
            is SocketTimeoutException -> UpdateException(UpdateErrorCode.TIMEOUT, Wa2I18n.s.errTimedOutMs(timeoutMs, where), e)
            is UnknownHostException -> UpdateException(UpdateErrorCode.NETWORK, Wa2I18n.s.errUnknownHost(where), e)
            is IOException -> UpdateException(UpdateErrorCode.NETWORK, Wa2I18n.s.errNetworkError(where, e.message ?: e.javaClass.simpleName), e)
            else -> UpdateException(UpdateErrorCode.NETWORK, "${e.javaClass.simpleName}: ${e.message}$where", e)
        }
    }

    private fun cancelled() = UpdateException(UpdateErrorCode.CANCELLED, Wa2I18n.s.errCancelled)

    private fun storageError(e: IOException) =
        UpdateException(UpdateErrorCode.NETWORK, Wa2I18n.s.errStorageWrite(e.message ?: e.javaClass.simpleName), e)

    private fun readLimited(input: InputStream, limit: Int, cancel: CancelToken?, truncate: Boolean = false): String {
        input.use { ins ->
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(16 * 1024)
            while (true) {
                if (cancel?.isCancelled == true) throw cancelled()
                val n = ins.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                if (out.size() > limit) {
                    if (truncate) break
                    throw UpdateException(UpdateErrorCode.INVALID_RESPONSE, Wa2I18n.s.errResponseTooLarge)
                }
            }
            return out.toString(Charsets.UTF_8.name())
        }
    }
}

private const val USER_AGENT = "wa2port-android-aipack"
