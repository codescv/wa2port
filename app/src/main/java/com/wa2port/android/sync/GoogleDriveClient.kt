package com.wa2port.android.sync

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayOutputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.net.HttpURLConnection
import java.net.ProtocolException
import java.net.SocketTimeoutException
import java.net.URL
import java.util.UUID
import java.util.concurrent.ThreadLocalRandom

data class DriveFile(
    val id: String,
    val name: String,
    val mimeType: String,
    val md5Checksum: String? = null,
    val modifiedTimeMs: Long = 0L,
    val size: Long = 0L,
    val parents: List<String> = emptyList(),
) {
    val isFolder: Boolean get() = mimeType == DRIVE_FOLDER_MIME
}

class DriveApiException(
    val status: Int,
    val reason: String?,
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)

class DriveFileNotFoundException(message: String) : FileNotFoundException(message)

/** Abstraction over Google Drive REST API v3 operations used by [CloudSyncEngine]. */
interface DriveApi {
    suspend fun findOrCreateFolder(name: String, parentId: String? = null): DriveFile
    suspend fun listChildren(folderId: String): List<DriveFile>
    suspend fun downloadFile(fileId: String): ByteArray
    suspend fun uploadFile(
        parentId: String,
        name: String,
        mimeType: String,
        content: ByteArray,
        existingFileId: String? = null,
        modifiedTimeMs: Long? = null,
    ): DriveFile

    suspend fun deleteFile(fileId: String)
}

/**
 * Pure-JVM `HttpURLConnection` client for Google Drive REST API v3.
 *
 * - Single HTTP 401 refresh retry via [DriveTokenProvider.accessToken].
 * - Exponential backoff on 429 / 5xx / Drive rate-limit 403 / SocketTimeoutException.
 * - Uses `setFixedLengthStreamingMode` on every request body and `Accept-Encoding: identity` on downloads.
 * - Supports multipart/related upload (<= 4 MiB) and resumable upload (> 4 MiB).
 */
class HttpGoogleDriveClient(
    private val tokenProvider: DriveTokenProvider,
    val apiBase: String = DEFAULT_DRIVE_API_BASE,
    val uploadBase: String = DEFAULT_DRIVE_UPLOAD_BASE,
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 30_000,
    private val uploadReadTimeoutMs: Int = 60_000,
    private val baseBackoffMs: Long = 500L,
    private val maxJitterMs: Long = 250L,
    private val multipartThresholdBytes: Int = DEFAULT_MULTIPART_THRESHOLD_BYTES,
) : DriveApi {

    companion object {
        const val MAX_ATTEMPTS = 5
        const val DEFAULT_MULTIPART_THRESHOLD_BYTES = 4 * 1024 * 1024
        const val FILE_FIELDS = "id,name,mimeType,md5Checksum,modifiedTime,size,parents,trashed"
        const val LIST_FIELDS = "nextPageToken,files($FILE_FIELDS)"
        private val RATE_LIMIT_REASONS = setOf("userRateLimitExceeded", "rateLimitExceeded")
        private val RETRYABLE_STATUSES = setOf(429, 500, 502, 503, 504)
    }

    override suspend fun findOrCreateFolder(name: String, parentId: String?): DriveFile {
        val escapedName = escapeDriveQueryLiteral(name)
        val parentFilter = if (parentId != null) {
            "'${escapeDriveQueryLiteral(parentId)}' in parents"
        } else {
            "'root' in parents"
        }
        val query = "name = '$escapedName' and $parentFilter and mimeType = '$DRIVE_FOLDER_MIME' and trashed = false"
        val listJson = executeJson(
            method = "GET",
            url = buildUrl(
                apiBase,
                listOf("files"),
                listOf(
                    "q" to query,
                    "spaces" to "drive",
                    "orderBy" to "createdTime asc",
                    "pageSize" to "10",
                    "fields" to "files($FILE_FIELDS)",
                ),
            ),
            bodyBytes = null,
            contentType = null,
        )
        val files = listJson["files"]?.jsonArray.orEmpty()
        for (item in files) {
            val parsed = parseDriveFile(item.jsonObject)
            if (parsed != null && parsed.isFolder) {
                return parsed
            }
        }

        val createFields = LinkedHashMap<String, kotlinx.serialization.json.JsonElement>().apply {
            put("name", JsonPrimitive(name))
            put("mimeType", JsonPrimitive(DRIVE_FOLDER_MIME))
            if (parentId != null) {
                put("parents", JsonArray(listOf(JsonPrimitive(parentId))))
            }
        }
        val createBody = JsonObject(createFields).toString().toByteArray(Charsets.UTF_8)
        val createdJson = executeJson(
            method = "POST",
            url = buildUrl(apiBase, listOf("files"), listOf("fields" to FILE_FIELDS)),
            bodyBytes = createBody,
            contentType = "application/json; charset=UTF-8",
        )
        return parseDriveFile(createdJson)
            ?: throw IOException("Google Drive returned invalid folder metadata for '$name'")
    }

    override suspend fun listChildren(folderId: String): List<DriveFile> {
        val escapedParent = escapeDriveQueryLiteral(folderId)
        val query = "'$escapedParent' in parents and trashed = false"
        val results = ArrayList<DriveFile>()
        var pageToken: String? = null
        while (true) {
            val params = buildList {
                add("q" to query)
                add("spaces" to "drive")
                add("pageSize" to "1000")
                add("fields" to LIST_FIELDS)
                if (!pageToken.isNullOrBlank()) {
                    add("pageToken" to pageToken!!)
                }
            }
            val resp = executeJson(
                method = "GET",
                url = buildUrl(apiBase, listOf("files"), params),
                bodyBytes = null,
                contentType = null,
            )
            val arr = resp["files"]?.jsonArray.orEmpty()
            for (el in arr) {
                parseDriveFile(el.jsonObject)?.let { results.add(it) }
            }
            val next = resp.str("nextPageToken")
            if (next.isNullOrBlank()) break
            pageToken = next
        }
        return results
    }

    override suspend fun downloadFile(fileId: String): ByteArray {
        val url = buildUrl(apiBase, listOf("files", fileId), listOf("alt" to "media"))
        return executeBytes(
            method = "GET",
            url = url,
            bodyBytes = null,
            contentType = null,
            readTimeoutOverrideMs = readTimeoutMs,
        ).body
    }

    override suspend fun uploadFile(
        parentId: String,
        name: String,
        mimeType: String,
        content: ByteArray,
        existingFileId: String?,
        modifiedTimeMs: Long?,
    ): DriveFile {
        return if (content.size <= multipartThresholdBytes) {
            uploadMultipart(parentId, name, mimeType, content, existingFileId, modifiedTimeMs)
        } else {
            uploadResumable(parentId, name, mimeType, content, existingFileId, modifiedTimeMs)
        }
    }

    override suspend fun deleteFile(fileId: String) {
        val url = buildUrl(apiBase, listOf("files", fileId), listOf("fields" to "id,trashed"))
        val trashBody = """{"trashed":true}""".toByteArray(Charsets.UTF_8)
        try {
            executeBytes(
                method = "PATCH",
                url = url,
                bodyBytes = trashBody,
                contentType = "application/json; charset=UTF-8",
                readTimeoutOverrideMs = readTimeoutMs,
            )
        } catch (_: DriveFileNotFoundException) {
            // Idempotent: already deleted/trashed remotely.
        }
    }

    private suspend fun uploadMultipart(
        parentId: String,
        name: String,
        mimeType: String,
        content: ByteArray,
        existingFileId: String?,
        modifiedTimeMs: Long?,
    ): DriveFile {
        val boundary = "wa2port_sync_${UUID.randomUUID().toString().replace("-", "")}"
        val metaJson = buildUploadMetadataJson(name, parentId, existingFileId, modifiedTimeMs)
        val bodyStream = ByteArrayOutputStream(metaJson.length + content.size + 256)
        bodyStream.write("--$boundary\r\n".toByteArray(Charsets.US_ASCII))
        bodyStream.write("Content-Type: application/json; charset=UTF-8\r\n\r\n".toByteArray(Charsets.US_ASCII))
        bodyStream.write(metaJson.toByteArray(Charsets.UTF_8))
        bodyStream.write("\r\n--$boundary\r\n".toByteArray(Charsets.US_ASCII))
        bodyStream.write("Content-Type: $mimeType\r\n\r\n".toByteArray(Charsets.US_ASCII))
        bodyStream.write(content)
        bodyStream.write("\r\n--$boundary--\r\n".toByteArray(Charsets.US_ASCII))
        val bodyBytes = bodyStream.toByteArray()

        val method = if (existingFileId == null) "POST" else "PATCH"
        val segments = if (existingFileId == null) listOf("files") else listOf("files", existingFileId)
        val url = buildUrl(
            uploadBase,
            segments,
            listOf("uploadType" to "multipart", "fields" to FILE_FIELDS),
        )
        val resp = executeJson(
            method = method,
            url = url,
            bodyBytes = bodyBytes,
            contentType = "multipart/related; boundary=$boundary",
            readTimeoutOverrideMs = uploadReadTimeoutMs,
        )
        return parseDriveFile(resp)
            ?: throw IOException("Google Drive multipart upload returned invalid metadata for '$name'")
    }

    private suspend fun uploadResumable(
        parentId: String,
        name: String,
        mimeType: String,
        content: ByteArray,
        existingFileId: String?,
        modifiedTimeMs: Long?,
    ): DriveFile {
        val metaBytes = buildUploadMetadataJson(name, parentId, existingFileId, modifiedTimeMs)
            .toByteArray(Charsets.UTF_8)
        val method = if (existingFileId == null) "POST" else "PATCH"
        val segments = if (existingFileId == null) listOf("files") else listOf("files", existingFileId)
        val initUrl = buildUrl(
            uploadBase,
            segments,
            listOf("uploadType" to "resumable", "fields" to FILE_FIELDS),
        )
        val initOutcome = executeBytes(
            method = method,
            url = initUrl,
            bodyBytes = metaBytes,
            contentType = "application/json; charset=UTF-8",
            extraHeaders = listOf(
                "X-Upload-Content-Type" to mimeType,
                "X-Upload-Content-Length" to content.size.toString(),
            ),
            readTimeoutOverrideMs = uploadReadTimeoutMs,
        )
        val sessionUri = findHeaderIgnoreCase(initOutcome.headers, "Location")
            ?: throw IOException("Google Drive resumable upload missing Location header")

        val putOutcome = executeBytes(
            method = "PUT",
            url = sessionUri,
            bodyBytes = content,
            contentType = mimeType,
            includeAuthHeader = false,
            readTimeoutOverrideMs = uploadReadTimeoutMs,
        )
        val raw = putOutcome.body.toString(Charsets.UTF_8)
        val obj = runCatching { syncJson.parseToJsonElement(raw).jsonObject }.getOrNull()
            ?: throw IOException("Google Drive resumable upload returned invalid JSON")
        return parseDriveFile(obj)
            ?: throw IOException("Google Drive resumable upload returned invalid metadata for '$name'")
    }

    private fun buildUploadMetadataJson(
        name: String,
        parentId: String,
        existingFileId: String?,
        modifiedTimeMs: Long?,
    ): String {
        val fields = LinkedHashMap<String, kotlinx.serialization.json.JsonElement>()
        fields["name"] = JsonPrimitive(name)
        if (existingFileId == null) {
            fields["parents"] = JsonArray(listOf(JsonPrimitive(parentId)))
        }
        if (modifiedTimeMs != null && modifiedTimeMs > 0L) {
            fields["modifiedTime"] = JsonPrimitive(formatRfc3339(modifiedTimeMs))
        }
        return JsonObject(fields).toString()
    }

    private data class RawHttpOutcome(
        val status: Int,
        val body: ByteArray,
        val headers: Map<String?, List<String>>,
    )

    private suspend fun executeJson(
        method: String,
        url: String,
        bodyBytes: ByteArray?,
        contentType: String?,
        readTimeoutOverrideMs: Int = readTimeoutMs,
    ): JsonObject {
        val outcome = executeBytes(
            method = method,
            url = url,
            bodyBytes = bodyBytes,
            contentType = contentType,
            readTimeoutOverrideMs = readTimeoutOverrideMs,
        )
        val raw = outcome.body.toString(Charsets.UTF_8)
        if (raw.isBlank()) return JsonObject(emptyMap())
        return runCatching { syncJson.parseToJsonElement(raw).jsonObject }.getOrNull()
            ?: throw IOException("Malformed JSON from Google Drive")
    }

    private suspend fun executeBytes(
        method: String,
        url: String,
        bodyBytes: ByteArray?,
        contentType: String?,
        extraHeaders: List<Pair<String, String>> = emptyList(),
        includeAuthHeader: Boolean = true,
        readTimeoutOverrideMs: Int = readTimeoutMs,
    ): RawHttpOutcome = withContext(Dispatchers.IO) {
        var retried401 = false
        var staleToken: String? = null
        var attempt = 0

        while (true) {
            val token = if (includeAuthHeader) tokenProvider.accessToken(staleToken) else null
            staleToken = null
            val outcome = try {
                performSingleCall(
                    method = method,
                    url = url,
                    bearerToken = token,
                    bodyBytes = bodyBytes,
                    contentType = contentType,
                    extraHeaders = extraHeaders,
                    readTimeoutOverrideMs = readTimeoutOverrideMs,
                )
            } catch (timeout: SocketTimeoutException) {
                if (attempt + 1 >= MAX_ATTEMPTS) throw timeout
                delay(computeBackoffMs(attempt))
                attempt++
                continue
            }

            if (outcome.status == 401 && includeAuthHeader) {
                if (!retried401) {
                    retried401 = true
                    staleToken = token
                    continue
                }
                throw GDriveAuthException(
                    message = "Google Drive sign-in expired — sign in again",
                    needsInteraction = true,
                )
            }

            if (outcome.status in 200..299) {
                return@withContext outcome
            }

            val rawErr = outcome.body.toString(Charsets.UTF_8)
            val parsedErr = parseDriveError(outcome.status, rawErr)
            if (shouldRetry(outcome.status, parsedErr.reason) && attempt + 1 < MAX_ATTEMPTS) {
                delay(computeBackoffMs(attempt))
                attempt++
                continue
            }
            throw mapDriveException(outcome.status, parsedErr)
        }
        @Suppress("UNREACHABLE_CODE")
        error("Unreachable")
    }

    private fun performSingleCall(
        method: String,
        url: String,
        bearerToken: String?,
        bodyBytes: ByteArray?,
        contentType: String?,
        extraHeaders: List<Pair<String, String>>,
        readTimeoutOverrideMs: Int,
    ): RawHttpOutcome {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = false
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutOverrideMs
            setMethod(this, method)
            if (bearerToken != null) {
                setRequestProperty("Authorization", "Bearer $bearerToken")
            }
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Accept-Encoding", "identity")
            for ((hk, hv) in extraHeaders) {
                setRequestProperty(hk, hv)
            }
            if (bodyBytes != null) {
                doOutput = true
                if (contentType != null) {
                    setRequestProperty("Content-Type", contentType)
                }
                setFixedLengthStreamingMode(bodyBytes.size)
            }
        }
        try {
            if (bodyBytes != null) {
                conn.outputStream.use { out ->
                    if (bodyBytes.isNotEmpty()) out.write(bodyBytes)
                }
            }
            val status = conn.responseCode
            val stream = if (status in 200..299) {
                runCatching { conn.inputStream }.getOrNull()
            } else {
                conn.errorStream ?: runCatching { conn.inputStream }.getOrNull()
            }
            val bytes = stream?.use { it.readBytes() } ?: ByteArray(0)
            return RawHttpOutcome(status, bytes, conn.headerFields ?: emptyMap())
        } finally {
            conn.disconnect()
        }
    }

    private fun buildUrl(
        base: String,
        pathSegments: List<String>,
        queryParams: List<Pair<String, String>>,
    ): String {
        val sb = StringBuilder(base.trimEnd('/'))
        for (seg in pathSegments) {
            sb.append('/').append(urlEncode(seg))
        }
        if (queryParams.isNotEmpty()) {
            sb.append('?')
            queryParams.forEachIndexed { idx, (k, v) ->
                if (idx > 0) sb.append('&')
                sb.append(urlEncode(k)).append('=').append(urlEncode(v))
            }
        }
        return sb.toString()
    }

    private fun computeBackoffMs(attempt: Int): Long {
        val exp = baseBackoffMs * (1L shl attempt.coerceIn(0, 6))
        val jitter = if (maxJitterMs > 0L) ThreadLocalRandom.current().nextLong(0L, maxJitterMs + 1L) else 0L
        return exp + jitter
    }

    private fun shouldRetry(status: Int, reason: String?): Boolean {
        if (status in RETRYABLE_STATUSES) return true
        if (status == 403 && reason != null && reason in RATE_LIMIT_REASONS) return true
        return false
    }

    private data class ParsedDriveError(val reason: String?, val message: String)

    private fun parseDriveError(status: Int, rawBody: String): ParsedDriveError {
        if (rawBody.isBlank()) return ParsedDriveError(null, "HTTP $status")
        val root = runCatching { syncJson.parseToJsonElement(rawBody).jsonObject }.getOrNull()
            ?: return ParsedDriveError(null, rawBody.take(200))
        val errObj = runCatching { root["error"]?.jsonObject }.getOrNull()
        if (errObj == null) {
            val simpleMsg = root.str("error_description") ?: root.str("error") ?: "HTTP $status"
            return ParsedDriveError(null, simpleMsg)
        }
        val msg = errObj.str("message") ?: "HTTP $status"
        val firstErr = runCatching { errObj["errors"]?.jsonArray?.firstOrNull()?.jsonObject }.getOrNull()
        val reason = firstErr?.str("reason") ?: errObj.str("status")
        return ParsedDriveError(reason = reason, message = msg)
    }

    private fun mapDriveException(status: Int, err: ParsedDriveError): IOException {
        if (status == 404) {
            return DriveFileNotFoundException("Google Drive item not found: ${redactSecrets(err.message)}")
        }
        if (status == 403 && err.reason == "storageQuotaExceeded") {
            return DriveApiException(status = 403, reason = err.reason, message = "Google Drive storage is full")
        }
        val reasonPrefix = err.reason?.takeIf { it.isNotBlank() }?.let { "$it " } ?: ""
        return DriveApiException(
            status = status,
            reason = err.reason,
            message = "Drive: $reasonPrefix${redactSecrets(err.message)}".trim(),
        )
    }

    private fun findHeaderIgnoreCase(headers: Map<String?, List<String>>, name: String): String? {
        for ((k, v) in headers) {
            if (k != null && k.equals(name, ignoreCase = true)) {
                return v.firstOrNull()
            }
        }
        return null
    }

    private fun setMethod(conn: HttpURLConnection, method: String) {
        if (method == "PATCH") {
            try {
                conn.requestMethod = "PATCH"
            } catch (_: ProtocolException) {
                conn.requestMethod = "POST"
                conn.setRequestProperty("X-HTTP-Method-Override", "PATCH")
            }
        } else {
            conn.requestMethod = method
        }
    }

    internal fun parseDriveFile(obj: JsonObject): DriveFile? {
        if (obj["trashed"]?.jsonPrimitive?.booleanOrNull == true) return null
        val id = obj.str("id")?.takeIf { it.isNotEmpty() } ?: return null
        val name = obj.str("name") ?: ""
        val mimeType = obj.str("mimeType") ?: "application/octet-stream"
        val md5 = obj.str("md5Checksum")?.takeIf { it.isNotBlank() }
        val modifiedTimeMs = parseRfc3339Ms(obj.str("modifiedTime"))
        val size = obj.long("size", 0L).coerceAtLeast(0L)
        val parents = runCatching {
            obj["parents"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()
        }.getOrDefault(emptyList())
        return DriveFile(
            id = id,
            name = name,
            mimeType = mimeType,
            md5Checksum = md5,
            modifiedTimeMs = modifiedTimeMs,
            size = size,
            parents = parents,
        )
    }
}
