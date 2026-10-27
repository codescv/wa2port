package com.wa2port.core.update

import com.wa2port.core.ai.CancelToken
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.MalformedURLException
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.net.UnknownServiceException
import java.util.zip.ZipFile

/**
 * Checks GitHub (or a proxy / custom endpoint) for the latest release and downloads its APK.
 * Blocking — call from a background thread (`Dispatchers.IO`). [openConnection] is the test seam.
 */
class AppUpdater(
    private val openConnection: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection },
) {
    companion object {
        const val MAX_REDIRECTS = 5
        val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
        val APK_MAGIC = byteArrayOf(0x50, 0x4B, 0x03, 0x04)
        const val BUFFER = 64 * 1024

        /** Release JSON larger than this is rejected (GitHub's are a few KiB; guards against a pasted APK URL). */
        const val MAX_JSON_BYTES = 2 * 1024 * 1024

        /** Download cap when neither Content-Length nor the expected size is known. */
        const val MAX_UNKNOWN_SIZE_BYTES = 512L * 1024 * 1024
        private const val PROGRESS_INTERVAL_MS = 100L
        private const val PROGRESS_INTERVAL_BYTES = 256L * 1024
    }

    /**
     * GETs the release JSON from [endpoint], parses it and compares with [currentVersion]. The APK
     * asset URL is routed through [endpoint]'s proxy prefix (if any).
     */
    fun checkLatestRelease(
        currentVersion: String,
        endpoint: UpdateEndpointConfig = UpdateEndpointConfig(),
        cancel: CancelToken? = null,
        timeoutMs: Int = 15_000,
    ): UpdateCheckResult {
        endpoint.validationError()?.let { throw UpdateException(UpdateErrorCode.INVALID_ARGUMENT, it) }
        val apiUrl = endpoint.apiUrl()
        val headers = mapOf(
            "Accept" to "application/vnd.github+json",
            "X-GitHub-Api-Version" to "2022-11-28",
            "User-Agent" to userAgent(currentVersion),
        )
        var conn: HttpURLConnection? = null
        try {
            conn = openFollowingRedirects(apiUrl, headers, cancel, timeoutMs, timeoutMs)
            val status = conn.responseCode
            if (status !in 200..299) throw httpError(conn, status, "检查更新失败")
            val text = readLimited(conn.inputStream, MAX_JSON_BYTES, cancel)
            if (cancel?.isCancelled == true) throw cancelled()
            val parsed = ReleaseJson.parse(text)
            val latest = parsed.copy(apkAsset = parsed.apkAsset?.let { it.copy(downloadUrl = endpoint.downloadUrl(it.downloadUrl)) })
            return UpdateCheckResult(
                currentVersion = currentVersion,
                latest = latest,
                updateAvailable = SemanticVersion.isNewerVersion(currentVersion, latest.tagName),
            )
        } catch (e: Exception) {
            throw mapError(e, cancel, hostOf(apiUrl), timeoutMs)
        } finally {
            cancel?.detach()
            conn?.disconnect()
        }
    }

    /**
     * Downloads [url] (following up to [MAX_REDIRECTS] redirects) into `destFile.tmp`, verifies it
     * (ZIP/APK magic `PK\x03\x04`, Content-Length and [expectedSize]) and atomically renames it to
     * [destFile]. A truncated, cancelled or non-APK download never leaves [destFile] behind.
     * [onProgress] is called on the first chunk, then at most every 100 ms / 256 KiB, and once at the end.
     */
    fun downloadApk(
        url: String,
        destFile: File,
        cancelToken: CancelToken? = null,
        connectTimeoutMs: Int = 20_000,
        readTimeoutMs: Int = 60_000,
        expectedSize: Long = -1,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit = { _, _ -> },
    ): File {
        destFile.parentFile?.mkdirs()
        val tmp = File(destFile.path + ".tmp")
        tmp.delete()
        // identity: Android's OkHttp-backed HttpURLConnection would otherwise add gzip transparently and
        // report contentLength = -1, silently disabling the length check.
        val headers = mapOf(
            "Accept" to "application/octet-stream",
            "Accept-Encoding" to "identity",
            "User-Agent" to userAgent(null),
        )
        var conn: HttpURLConnection? = null
        var ok = false
        try {
            conn = openFollowingRedirects(url, headers, cancelToken, connectTimeoutMs, readTimeoutMs)
            val status = conn.responseCode
            if (status != 200) throw httpError(conn, status, "下载失败")
            val contentType = conn.contentType.orEmpty()
            if (contentType.trim().startsWith("text/html", ignoreCase = true)) {
                throw UpdateException(UpdateErrorCode.CORRUPT_DOWNLOAD, "服务器返回了网页而不是 APK Server returned a web page, not an APK")
            }
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: expectedSize
            val written = streamToFile(conn.inputStream, tmp, total, cancelToken, onProgress)
            verifyDownload(tmp, written, total, expectedSize)
            destFile.delete()
            if (!tmp.renameTo(destFile)) throw UpdateException(UpdateErrorCode.NETWORK, "无法保存文件 Cannot save ${destFile.name}")
            ok = true
            return destFile
        } catch (e: Exception) {
            throw mapError(e, cancelToken, hostOf(url), readTimeoutMs)
        } finally {
            cancelToken?.detach()
            conn?.disconnect()
            if (!ok) tmp.delete()
        }
    }

    // ------------------------------------------------------------------ internals

    /**
     * Copies [input] to [tmp] in [BUFFER]-sized chunks, checking [cancel] per chunk and throttling
     * [onProgress]. An I/O failure after some bytes arrived is reported as an incomplete download.
     */
    private fun streamToFile(
        input: InputStream,
        tmp: File,
        total: Long,
        cancel: CancelToken?,
        onProgress: (Long, Long) -> Unit,
    ): Long {
        var written = 0L
        var lastReportBytes = 0L
        var lastReportAt = 0L
        var reported = false
        input.use { ins ->
            val fos = try {
                tmp.outputStream()
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
                        throw UpdateException(
                            UpdateErrorCode.CORRUPT_DOWNLOAD,
                            "下载不完整 Download interrupted (${written}/${if (total > 0) total else "?"}): ${e.message ?: e.javaClass.simpleName}",
                            e,
                        )
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
                        throw UpdateException(UpdateErrorCode.CORRUPT_DOWNLOAD, "文件大小不符 Download larger than expected (${written}/${total})")
                    }
                    if (total <= 0 && written > MAX_UNKNOWN_SIZE_BYTES) {
                        throw UpdateException(UpdateErrorCode.CORRUPT_DOWNLOAD, "文件过大 Download exceeds ${MAX_UNKNOWN_SIZE_BYTES shr 20} MiB")
                    }
                    val now = System.currentTimeMillis()
                    if (!reported || now - lastReportAt >= PROGRESS_INTERVAL_MS || written - lastReportBytes >= PROGRESS_INTERVAL_BYTES) {
                        onProgress(written, total)
                        reported = true
                        lastReportAt = now
                        lastReportBytes = written
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
        // A disconnect() from cancel() can surface as a clean EOF on some stacks.
        if (cancel?.isCancelled == true) throw cancelled()
        if (lastReportBytes != written || !reported) onProgress(written, total)
        return written
    }

    /** Rejects anything that is not a complete ZIP/APK. */
    private fun verifyDownload(tmp: File, written: Long, total: Long, expectedSize: Long) {
        if (total > 0 && written != total) {
            throw UpdateException(UpdateErrorCode.CORRUPT_DOWNLOAD, "下载不完整 Incomplete download (${written}/${total})")
        }
        if (expectedSize > 0 && written != expectedSize) {
            throw UpdateException(UpdateErrorCode.CORRUPT_DOWNLOAD, "文件大小不符 Size mismatch (${written}/${expectedSize})")
        }
        val head = ByteArray(4)
        val n = if (written >= 4) tmp.inputStream().use { readFully(it, head) } else 0
        if (n < 4 || !head.contentEquals(APK_MAGIC)) {
            throw UpdateException(UpdateErrorCode.CORRUPT_DOWNLOAD, "不是有效的 APK 文件 Not a valid APK file")
        }
        // The magic survives truncation; opening the archive validates the end-of-central-directory
        // record, which only a complete ZIP has (matters when the length was unknown).
        val hasManifest = try {
            ZipFile(tmp).use { it.getEntry("AndroidManifest.xml") != null }
        } catch (e: Exception) {
            throw UpdateException(UpdateErrorCode.CORRUPT_DOWNLOAD, "下载不完整或已损坏 Download incomplete or corrupt", e)
        }
        if (!hasManifest) {
            throw UpdateException(UpdateErrorCode.CORRUPT_DOWNLOAD, "不是有效的 APK 文件（缺少 AndroidManifest.xml） Not an APK")
        }
    }

    /**
     * Opens [url] and follows redirects manually (relative and cross-host `Location`s), attaching
     * [cancel] to each hop. Only http(s) is accepted, and an https → http downgrade is refused.
     * Every hop sends exactly [headers] (a fixed set built by the caller); nothing is copied from the
     * previous connection. NOTE: if an `Authorization` header is ever added, it must be dropped
     * whenever the host or port changes. Returns the connection of the final, non-redirect response.
     */
    private fun openFollowingRedirects(
        url: String,
        headers: Map<String, String>,
        cancel: CancelToken?,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
    ): HttpURLConnection {
        var current = try {
            URL(url)
        } catch (e: MalformedURLException) {
            throw UpdateException(UpdateErrorCode.INVALID_ARGUMENT, "地址无效 Invalid URL: $url", e)
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
                if (location.isNullOrEmpty()) {
                    throw UpdateException(UpdateErrorCode.HTTP, "重定向缺少 Location Redirect without Location (HTTP $status)", httpStatus = status)
                }
                if (++hops > MAX_REDIRECTS) throw UpdateException(UpdateErrorCode.NETWORK, "重定向过多 Too many redirects")
                val next = try {
                    URL(current, location)
                } catch (e: MalformedURLException) {
                    throw UpdateException(UpdateErrorCode.HTTP, "重定向地址无效 Invalid redirect: $location", e, status)
                }
                requireHttp(next, initial = false)
                if (current.protocol.equals("https", true) && next.protocol.equals("http", true)) {
                    throw UpdateException(UpdateErrorCode.NETWORK, "不安全的重定向 Insecure redirect (https → http)")
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

    /** Builds the [UpdateException] for a non-success [status] (GitHub rate limit / 404 get friendly text). */
    private fun httpError(conn: HttpURLConnection, status: Int, what: String): UpdateException {
        val body = runCatching { conn.errorStream?.use { readPrefix(it, 4096) } }.getOrNull().orEmpty()
        val rateLimited = status == 429 ||
            (status == 403 && (conn.getHeaderField("X-RateLimit-Remaining")?.trim() == "0" || body.contains("rate limit", ignoreCase = true)))
        val msg = when {
            rateLimited -> "GitHub API 限流 rate limited，请稍后再试或设置代理地址 (HTTP $status)"
            status == 404 -> "没有找到发布版本 No release found (HTTP 404)"
            else -> "$what HTTP $status" + body.trim().take(160).let { if (it.isNotEmpty()) ": $it" else "" }
        }
        return UpdateException(UpdateErrorCode.HTTP, msg, httpStatus = status)
    }

    /** Translates any failure into an [UpdateException] (cancellation wins over the I/O error it causes). */
    private fun mapError(e: Exception, cancel: CancelToken?, host: String, timeoutMs: Int): UpdateException {
        if (cancel?.isCancelled == true) {
            return if (e is UpdateException && e.code == UpdateErrorCode.CANCELLED) e
            else UpdateException(UpdateErrorCode.CANCELLED, "已取消 Cancelled", e)
        }
        if (e is UpdateException) return e
        val where = if (host.isNotEmpty()) " ($host)" else ""
        return when (e) {
            is SocketTimeoutException -> UpdateException(UpdateErrorCode.TIMEOUT, "连接超时 Timed out after $timeoutMs ms$where", e)
            is UnknownServiceException -> UpdateException(UpdateErrorCode.NETWORK, "系统禁止明文 http：请使用 https 地址$where", e)
            is UnknownHostException -> UpdateException(UpdateErrorCode.NETWORK, "无法解析服务器地址 Unknown host$where", e)
            is IOException -> UpdateException(UpdateErrorCode.NETWORK, "网络错误 Network error$where: ${e.message ?: e.javaClass.simpleName}", e)
            is IllegalArgumentException -> UpdateException(UpdateErrorCode.INVALID_ARGUMENT, "参数无效 Invalid argument$where: ${e.message}", e)
            else -> UpdateException(UpdateErrorCode.NETWORK, "${e.javaClass.simpleName}: ${e.message}$where", e)
        }
    }

    private fun cancelled() = UpdateException(UpdateErrorCode.CANCELLED, "已取消 Cancelled")

    private fun storageError(e: IOException) =
        UpdateException(UpdateErrorCode.NETWORK, "存储空间不足/无法写入 Cannot write file: ${e.message ?: e.javaClass.simpleName}", e)

    /** Only http(s) URLs are ever opened (a `file:`/`jar:` Location must not reach [openConnection]). */
    private fun requireHttp(url: URL, initial: Boolean) {
        val proto = url.protocol.lowercase()
        if (proto != "http" && proto != "https") {
            val msg = if (initial) "不支持的协议 Unsupported URL scheme: $proto" else "不安全的重定向 Insecure redirect to $proto:"
            throw UpdateException(if (initial) UpdateErrorCode.INVALID_ARGUMENT else UpdateErrorCode.NETWORK, msg)
        }
        if (url.host.isNullOrEmpty()) throw UpdateException(UpdateErrorCode.INVALID_ARGUMENT, "地址缺少主机名 URL has no host: $url")
    }

    private fun userAgent(version: String?) = "wa2port-android" + (version?.let { "/$it" } ?: "")

    private fun hostOf(url: String): String = runCatching { URL(url).host }.getOrDefault("")

    /** Reads [input] as UTF-8, failing when it exceeds [limit] bytes. */
    private fun readLimited(input: InputStream, limit: Int, cancel: CancelToken?): String {
        input.use { ins ->
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(16 * 1024)
            while (true) {
                if (cancel?.isCancelled == true) throw cancelled()
                val n = ins.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                if (out.size() > limit) throw UpdateException(UpdateErrorCode.INVALID_RESPONSE, "更新信息过大 Response too large")
            }
            return out.toString(Charsets.UTF_8.name())
        }
    }

    /** First [limit] bytes of [input] as UTF-8 (the rest is ignored). */
    private fun readPrefix(input: InputStream, limit: Int): String {
        val buf = ByteArray(limit)
        val n = readFully(input, buf)
        return String(buf, 0, n, Charsets.UTF_8)
    }

    private fun readFully(input: InputStream, buf: ByteArray): Int {
        var off = 0
        while (off < buf.size) {
            val n = input.read(buf, off, buf.size - off)
            if (n < 0) break
            off += n
        }
        return off
    }
}
