package com.wa2port.core.ai

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import com.wa2port.core.update.UpdateErrorCode
import com.wa2port.core.update.UpdateException
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.util.Collections
import java.util.concurrent.Executors

class AiPackDownloaderTest {
    @TempDir
    lateinit var dir: File

    private lateinit var server: HttpServer
    private lateinit var base: String
    private lateinit var packBytes: ByteArray
    private val ranges: MutableList<String?> = Collections.synchronizedList(ArrayList())

    /** Per-test behaviour knobs. */
    @Volatile private var honorRange = true
    @Volatile private var dropAfter = -1
    @Volatile private var serveBytes: ByteArray? = null
    @Volatile private var apiStatus = 200
    @Volatile private var digest: String? = null
    @Volatile private var sidecar: String? = null

    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    private fun buildPack(n: Int): ByteArray {
        val entries = (0 until n).map { i ->
            val s = "「テスト文その${i}です」"
            PackEntry(OfflineAiPack.hash64(s), s.length, "测试$i 号句子的翻译内容比较长一些以便压缩", chunks = listOf(PackChunk(1, s.length - 1, SpanAlignment.EXACT, "", 0, "てすと$i", "", null, "整句", "测试 $i")))
        }
        val f = File(dir, "src.wa2ai")
        OfflineAiPackWriter.write(f, AiPackMeta("v1", "p", "m", 1, 1, n, n), entries, entries.indices.map { OfflineAiPackWriter.TokenRef("1001", it, it) })
        return f.readBytes().also { f.delete() }
    }

    @BeforeEach
    fun start() {
        packBytes = buildPack(300)
        server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.executor = Executors.newCachedThreadPool()
        base = "http://127.0.0.1:${server.address.port}"
        server.createContext("/api") { ex ->
            if (apiStatus != 200) {
                ex.responseHeaders.add("X-RateLimit-Remaining", "0")
                send(ex, apiStatus, """{"message":"API rate limit exceeded"}""".toByteArray())
                return@createContext
            }
            val d = digest?.let { ""","digest":"sha256:$it"""" } ?: ""
            val json = """{"tag_name":"ai-pack-v1","assets":[{"name":"other.bin","browser_download_url":"$base/x","size":1},{"name":"${OfflineAiPack.FILE_NAME}","browser_download_url":"$base/download/${OfflineAiPack.FILE_NAME}","size":${packBytes.size}$d,"updated_at":"2026-11-06T00:00:00Z"}]}"""
            send(ex, 200, json.toByteArray())
        }
        // github.com/…/download → 302 → objects CDN (cross-path redirect chain).
        server.createContext("/download/") { ex ->
            if (ex.requestURI.path.endsWith(".sha256")) {
                val s = sidecar
                if (s == null) send(ex, 404, ByteArray(0)) else send(ex, 200, s.toByteArray())
                return@createContext
            }
            ex.responseHeaders.add("Location", "/cdn/hop1")
            send(ex, 302, ByteArray(0))
        }
        server.createContext("/cdn/hop1") { ex ->
            ex.responseHeaders.add("Location", "$base/cdn/file")
            send(ex, 307, ByteArray(0))
        }
        server.createContext("/cdn/file") { ex ->
            val body = serveBytes ?: packBytes
            val range = ex.requestHeaders.getFirst("Range")
            ranges += range
            val start = range?.let { Regex("bytes=(\\d+)-").find(it)?.groupValues?.get(1)?.toLong() } ?: 0L
            ex.responseHeaders.add("Content-Type", "application/octet-stream")
            if (range != null && honorRange && start > 0) {
                if (start >= body.size) {
                    ex.responseHeaders.add("Content-Range", "bytes */${body.size}")
                    send(ex, 416, ByteArray(0))
                    return@createContext
                }
                ex.responseHeaders.add("Content-Range", "bytes $start-${body.size - 1}/${body.size}")
                send(ex, 206, body.copyOfRange(start.toInt(), body.size))
            } else {
                send(ex, 200, body)
            }
        }
        server.createContext("/evil") { ex ->
            ex.responseHeaders.add("Location", "file:///etc/passwd")
            send(ex, 302, ByteArray(0))
        }
        server.start()
    }

    private fun send(ex: HttpExchange, status: Int, body: ByteArray) {
        val drop = dropAfter
        if (drop in 1 until body.size && status in 200..206) {
            ex.sendResponseHeaders(status, body.size.toLong())
            ex.responseBody.write(body, 0, drop)
            ex.responseBody.flush()
            ex.close() // short body → client sees premature EOF
            return
        }
        ex.sendResponseHeaders(status, if (body.isEmpty()) -1 else body.size.toLong())
        if (body.isNotEmpty()) ex.responseBody.write(body)
        ex.close()
    }

    @AfterEach
    fun stop() = server.stop(0)

    private fun endpoint() = AiPackEndpointConfig(apiUrl = "$base/api", assetUrl = "$base/download/${OfflineAiPack.FILE_NAME}")

    @Test
    fun endpointDefaultsAreFixedReleaseTag() {
        val e = AiPackEndpointConfig()
        assertEquals("https://api.github.com/repos/codescv/wa2port/releases/tags/ai-pack-v1", e.resolvedApiUrl())
        assertEquals("https://github.com/codescv/wa2port/releases/download/ai-pack-v1/wa2-ai-pack-v1.wa2ai", e.resolvedAssetUrl())
        val p = AiPackEndpointConfig.fromUpdateUrl("https://ghproxy.net/")
        assertEquals("https://ghproxy.net/https://github.com/codescv/wa2port/releases/download/ai-pack-v1/wa2-ai-pack-v1.wa2ai", p.resolvedAssetUrl())
        assertEquals("", AiPackEndpointConfig.fromUpdateUrl("https://example.com/latest.json").proxyPrefix)
    }

    @Test
    fun fetchInfoParsesReleaseAssetWithDigest() {
        digest = sha(packBytes)
        val info = AiPackDownloader().fetchInfo(endpoint())
        assertFalse(info.fromFallback)
        assertEquals(packBytes.size.toLong(), info.sizeBytes)
        assertEquals(sha(packBytes), info.sha256)
        assertTrue(info.downloadUrl.endsWith("/download/${OfflineAiPack.FILE_NAME}"))
    }

    @Test
    fun rateLimitedApiFallsBackToDirectAssetAndStillDownloads() {
        apiStatus = 403
        val d = AiPackDownloader()
        val info = d.fetchInfo(endpoint())
        assertTrue(info.fromFallback)
        assertEquals(-1, info.sizeBytes)
        assertTrue(info.apiError!!.contains("rate limited"), info.apiError)
        val dest = File(dir, "ai_pack/${OfflineAiPack.FILE_NAME}")
        d.download(info, dest)
        assertArrayEquals(packBytes, dest.readBytes())
    }

    @Test
    fun downloadFollowsRedirectsVerifiesShaAndInstallsAtomically() {
        digest = sha(packBytes)
        val d = AiPackDownloader()
        val dest = File(dir, "ai_pack/${OfflineAiPack.FILE_NAME}")
        val progress = ArrayList<Pair<Long, Long>>()
        d.download(d.fetchInfo(endpoint()), dest) { a, b -> progress += a to b }
        assertArrayEquals(packBytes, dest.readBytes())
        assertFalse(d.partFile(dest).exists())
        assertEquals(packBytes.size.toLong() to packBytes.size.toLong(), progress.last())
        OfflineAiPack.open(dest).use { assertEquals(300, it.entryCount) }
        assertNull(ranges.single(), "fresh download sends no Range")
    }

    @Test
    fun resumesPartialDownloadWithRange206() {
        digest = sha(packBytes)
        val d = AiPackDownloader()
        val dest = File(dir, OfflineAiPack.FILE_NAME)
        d.partFile(dest).writeBytes(packBytes.copyOf(1000))
        val progress = ArrayList<Long>()
        d.download(d.fetchInfo(endpoint()), dest) { a, _ -> progress += a }
        assertEquals("bytes=1000-", ranges.single())
        assertArrayEquals(packBytes, dest.readBytes())
        assertTrue(progress.first() > 1000, "progress includes resumed bytes")
    }

    @Test
    fun serverIgnoringRangeOverwritesCleanly() {
        honorRange = false
        val d = AiPackDownloader()
        val dest = File(dir, OfflineAiPack.FILE_NAME)
        d.partFile(dest).writeBytes(ByteArray(500) { 9 })
        d.download(d.fetchInfo(endpoint()), dest)
        assertArrayEquals(packBytes, dest.readBytes())
    }

    @Test
    fun completePartIsVerifiedWithoutRedownload() {
        digest = sha(packBytes)
        val d = AiPackDownloader()
        val dest = File(dir, OfflineAiPack.FILE_NAME)
        d.partFile(dest).writeBytes(packBytes)
        d.download(d.fetchInfo(endpoint()), dest)
        assertTrue(ranges.isEmpty())
        assertArrayEquals(packBytes, dest.readBytes())
    }

    @Test
    fun shaMismatchDeletesPartAndKeepsOldPack() {
        digest = "0".repeat(64)
        val d = AiPackDownloader()
        val dest = File(dir, OfflineAiPack.FILE_NAME)
        dest.writeBytes(byteArrayOf(1, 2, 3))
        val e = assertThrows(UpdateException::class.java) { d.download(d.fetchInfo(endpoint()), dest) }
        assertEquals(UpdateErrorCode.CORRUPT_DOWNLOAD, e.code)
        assertFalse(d.partFile(dest).exists())
        assertArrayEquals(byteArrayOf(1, 2, 3), dest.readBytes(), "old file untouched")
    }

    @Test
    fun sidecarChecksumIsUsedWhenNoDigest() {
        apiStatus = 403
        sidecar = "${"f".repeat(64)}  ${OfflineAiPack.FILE_NAME}\n"
        val d = AiPackDownloader()
        val dest = File(dir, OfflineAiPack.FILE_NAME)
        val e = assertThrows(UpdateException::class.java) { d.download(d.fetchInfo(endpoint()), dest) }
        assertEquals(UpdateErrorCode.CORRUPT_DOWNLOAD, e.code)
        sidecar = "${sha(packBytes)}  ${OfflineAiPack.FILE_NAME}\n"
        d.download(d.fetchInfo(endpoint()), dest)
        assertArrayEquals(packBytes, dest.readBytes())
    }

    @Test
    fun droppedConnectionKeepsResumablePart() {
        dropAfter = 2000
        val d = AiPackDownloader()
        val dest = File(dir, OfflineAiPack.FILE_NAME)
        val info = d.fetchInfo(endpoint()).also { dropAfter = 2000 }
        val e = assertThrows(UpdateException::class.java) { d.download(info, dest, readTimeoutMs = 2_000) }
        assertTrue(e.code == UpdateErrorCode.NETWORK || e.code == UpdateErrorCode.TIMEOUT, e.code.name + ": " + e.message)
        assertEquals(2000L, d.partFile(dest).length())
        dropAfter = -1
        d.download(info, dest)
        assertEquals("bytes=2000-", ranges.last())
        assertArrayEquals(packBytes, dest.readBytes())
    }

    @Test
    fun cancelKeepsPartAndReportsCancelled() {
        val d = AiPackDownloader()
        val dest = File(dir, OfflineAiPack.FILE_NAME)
        val token = CancelToken()
        token.cancel()
        val e = assertThrows(UpdateException::class.java) { d.download(d.fetchInfo(endpoint()), dest, token) }
        assertEquals(UpdateErrorCode.CANCELLED, e.code)
        assertFalse(dest.exists())
    }

    @Test
    fun nonPackPayloadIsRejected() {
        serveBytes = ByteArray(5000) { (it % 251).toByte() }
        apiStatus = 403
        val d = AiPackDownloader()
        val dest = File(dir, OfflineAiPack.FILE_NAME)
        val e = assertThrows(UpdateException::class.java) { d.download(d.fetchInfo(endpoint()), dest) }
        assertEquals(UpdateErrorCode.CORRUPT_DOWNLOAD, e.code)
        assertFalse(d.partFile(dest).exists())
        assertFalse(dest.exists())
    }

    @Test
    fun redirectToNonHttpSchemeIsRefused() {
        val d = AiPackDownloader()
        val info = AiPackRemoteInfo("t", OfflineAiPack.FILE_NAME, "$base/evil", -1, null, null)
        val e = assertThrows(UpdateException::class.java) { d.download(info, File(dir, "x.wa2ai")) }
        assertTrue(e.message!!.contains("Insecure redirect"), e.message)
    }

    @Test
    fun parsesReleaseAndSidecarHelpers() {
        val info = AiPackDownloader.parseRelease("""{"tag_name":"ai-pack-v1","assets":[{"name":"wa2-ai-pack-v1.wa2ai","browser_download_url":"https://x/y","size":12,"digest":"sha256:${"A".repeat(64)}"}]}""", OfflineAiPack.FILE_NAME)
        assertNotNull(info)
        assertEquals("a".repeat(64), info!!.sha256)
        assertNull(AiPackDownloader.parseRelease("""{"tag_name":"t","assets":[]}""", OfflineAiPack.FILE_NAME))
        assertEquals("b".repeat(64), AiPackDownloader.parseSidecar("${"B".repeat(64)} *file"))
        assertNull(AiPackDownloader.parseSidecar("not a hash"))
    }
}
