package com.wa2port.core.update

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import com.wa2port.core.ai.CancelToken
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
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.random.Random

class AppUpdaterTest {

    // ================================================================== SemanticVersion

    @Test
    fun parsesVersions() {
        assertEquals(SemanticVersion(1, 2, 0), SemanticVersion.parse("v1.2.0"))
        assertEquals(SemanticVersion(1, 2, 0), SemanticVersion.parse(" V1.2.0 "))
        assertEquals(SemanticVersion(1, 2, 0), SemanticVersion.parse("1.2"))
        assertEquals(SemanticVersion(1, 2, 0, "beta1"), SemanticVersion.parse("1.2.0-beta1"))
        assertEquals(SemanticVersion(1, 2, 0, "rc.1"), SemanticVersion.parse("v1.2.0-rc.1+build.7"))
        assertEquals(SemanticVersion(1, 2, 3), SemanticVersion.parse("1.2.3+sha.abc"))
        assertEquals("1.2.0-beta.2", SemanticVersion.parse("v1.2.0-beta.2").toString())
    }

    @Test
    fun rejectsGarbageVersions() {
        val bad = listOf(
            "", "v", "1", "1.2.0-", "1.2.0.1", "release-1.2.0", "latest", "1.x.0", "1.2.0-beta..1",
            "99999999999.0.0", "1.99999999999.0", "1.2.99999999999", null,
        )
        bad.forEach { assertNull(SemanticVersion.parse(it), "should not parse: $it") }
    }

    @Test
    fun comparesVersions() {
        val ordered = listOf(
            "v1.1.0", "1.2.0-alpha", "1.2.0-alpha.1", "1.2.0-alpha.beta", "1.2.0-beta", "1.2.0-beta.2",
            "1.2.0-beta.10", "1.2.0-beta1", "1.2.0-rc.1", "v1.2.0", "1.2.1", "1.9.0", "v1.10.0", "2.0.0",
        ).map { SemanticVersion.parse(it)!! }
        for (i in ordered.indices) for (j in ordered.indices) {
            assertEquals(i.compareTo(j).coerceIn(-1, 1), ordered[i].compareTo(ordered[j]).coerceIn(-1, 1), "${ordered[i]} vs ${ordered[j]}")
        }
        assertEquals(0, SemanticVersion.parse("1.2")!!.compareTo(SemanticVersion.parse("v1.2.0")!!))
        assertEquals(SemanticVersion.parse("1.2"), SemanticVersion.parse("v1.2.0"))
    }

    @Test
    fun isNewerVersion() {
        assertTrue(SemanticVersion.isNewerVersion("1.1.0", "v1.2.0"))
        assertTrue(SemanticVersion.isNewerVersion("1.2.0", "v1.10.0"))
        assertTrue(SemanticVersion.isNewerVersion("1.2.0-beta1", "1.2.0"))
        assertFalse(SemanticVersion.isNewerVersion("1.2.0", "v1.2.0"))
        assertFalse(SemanticVersion.isNewerVersion("1.2.0", "1.2.0-rc.1"))
        assertFalse(SemanticVersion.isNewerVersion("1.10.0", "v1.9.9"))
        assertFalse(SemanticVersion.isNewerVersion("1.2.0", "nightly"))
        assertFalse(SemanticVersion.isNewerVersion("garbage", "v9.0.0"))
    }

    // ================================================================== ReleaseJson

    private fun asset(name: String) = ReleaseAsset(name, "https://x/$name", 1, "application/vnd.android.package-archive")

    @Test
    fun pickApkPriority() {
        val generic = asset("app-release.apk")
        val named = asset("wa2port-1.3.0-release.apk")
        val other = asset("debug-build.APK")
        val src = asset("Source.zip")
        assertEquals(named, ReleaseJson.pickApk(listOf(src, generic, other, named)))
        assertEquals(generic, ReleaseJson.pickApk(listOf(src, other, generic)))
        assertEquals(other, ReleaseJson.pickApk(listOf(src, other)))
        assertEquals("WA2PORT-v1.3.0-RELEASE.apk", ReleaseJson.pickApk(listOf(generic, asset("WA2PORT-v1.3.0-RELEASE.apk")))!!.name)
        assertEquals(named, ReleaseJson.pickApk(listOf(named, asset("wa2port-1.3.0-x86-release.apk"))))
        assertNull(ReleaseJson.pickApk(listOf(src)))
        assertNull(ReleaseJson.pickApk(emptyList()))
    }

    @Test
    fun parsesGithubPayload() {
        val r = ReleaseJson.parse(githubJson("v1.3.0", "https://github.com/codescv/wa2port/releases/download/v1.3.0/wa2port-1.3.0-release.apk", 12345))
        assertEquals("v1.3.0", r.tagName)
        assertEquals("1.3.0", r.version)
        assertEquals("WA2 Port v1.3.0", r.title)
        assertEquals("- 新功能\n- Fixes", r.body)
        assertEquals("2026-10-27T08:00:00Z", r.publishedAt)
        assertEquals("https://github.com/codescv/wa2port/releases/tag/v1.3.0", r.htmlUrl)
        assertFalse(r.isPreRelease)
        val apk = r.apkAsset
        assertNotNull(apk)
        apk!!
        assertEquals("wa2port-1.3.0-release.apk", apk.name)
        assertEquals(12345L, apk.sizeBytes)
        assertEquals("application/vnd.android.package-archive", apk.contentType)
    }

    @Test
    fun parsesSparsePayload() {
        val r = ReleaseJson.parse("""{"tag_name":"1.4.0","name":null,"body":null,"prerelease":true,"extra":{"x":1}}""")
        assertEquals("1.4.0", r.version)
        assertEquals("1.4.0", r.title)
        assertEquals("", r.body)
        assertNull(r.publishedAt)
        assertNull(r.apkAsset)
        assertTrue(r.isPreRelease)
        assertEquals(UpdateEndpointConfig.DEFAULT_RELEASE_HTML_URL, r.htmlUrl)
        val noSize = ReleaseJson.parse("""{"tag_name":"v2","assets":[{"name":"a.apk","browser_download_url":"https://h/a.apk"},{"bogus":1}]}""")
        assertEquals(-1L, noSize.apkAsset!!.sizeBytes)
    }

    @Test
    fun rejectsInvalidPayload() {
        listOf("", "<html>rate limited</html>", "[]", """{"name":"x"}""", """{"tag_name":"  "}""").forEach {
            val e = assertThrows(UpdateException::class.java) { ReleaseJson.parse(it) }
            assertEquals(UpdateErrorCode.INVALID_RESPONSE, e.code, it)
        }
    }

    // ================================================================== UpdateEndpointConfig

    @Test
    fun endpointModes() {
        val gh = "https://github.com/codescv/wa2port/releases/download/v1.3.0/a.apk"
        UpdateEndpointConfig("  ").let {
            assertEquals(UpdateEndpointConfig.DEFAULT_RELEASE_API_URL, it.apiUrl())
            assertEquals(gh, it.downloadUrl(gh))
            assertNull(it.validationError())
        }
        UpdateEndpointConfig(" https://ghproxy.net/ ").let {
            assertTrue(it.isProxyPrefix)
            assertEquals("https://ghproxy.net/https://api.github.com/repos/codescv/wa2port/releases/latest", it.apiUrl())
            assertEquals("https://ghproxy.net/$gh", it.downloadUrl(gh))
            assertEquals("https://ghproxy.net/$gh", it.downloadUrl("https://ghproxy.net/$gh"))
        }
        UpdateEndpointConfig("http://127.0.0.1:8765/releases/latest").let {
            assertFalse(it.isProxyPrefix)
            assertEquals("http://127.0.0.1:8765/releases/latest", it.apiUrl())
            assertEquals(gh, it.downloadUrl(gh))
            assertNull(it.validationError())
        }
        assertNotNull(UpdateEndpointConfig("ghproxy.net/").validationError())
        assertNotNull(UpdateEndpointConfig("ftp://x/").validationError())
        assertNotNull(UpdateEndpointConfig("https://").validationError())
        assertNotNull(UpdateEndpointConfig("https://a b/").validationError())
    }

    // ================================================================== HttpServer end-to-end

    @TempDir
    lateinit var tmp: File

    private lateinit var a: HttpServer
    private lateinit var b: HttpServer
    private val seenA = Collections.synchronizedList(mutableListOf<Pair<String, Map<String, String>>>())
    private val seenB = Collections.synchronizedList(mutableListOf<Pair<String, Map<String, String>>>())
    private val releaseLatch = CountDownLatch(1)

    private val portA get() = a.address.port
    private val portB get() = b.address.port
    private val baseA get() = "http://127.0.0.1:$portA"

    /** A tiny but real APK-shaped ZIP (AndroidManifest.xml + ~600 KiB incompressible payload). */
    private val apk: ByteArray by lazy {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z ->
            z.putNextEntry(ZipEntry("AndroidManifest.xml")); z.write("<manifest/>".toByteArray()); z.closeEntry()
            z.putNextEntry(ZipEntry("classes.dex")); z.write(Random(42).nextBytes(600 * 1024)); z.closeEntry()
        }
        bos.toByteArray()
    }

    @BeforeEach
    fun startServers() {
        a = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        b = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        a.executor = Executors.newCachedThreadPool()
        b.executor = Executors.newCachedThreadPool()

        a.createContext("/") { ex -> record(seenA, ex); send(ex, 404, "{\"message\":\"Not Found\"}".toByteArray()) }
        a.createContext("/releases/latest") { ex ->
            record(seenA, ex)
            send(ex, 200, githubJson("v1.3.0", "$baseA/dl/start", apk.size.toLong()).toByteArray(), "application/json; charset=utf-8")
        }
        a.createContext("/proxy/") { ex ->
            record(seenA, ex)
            send(ex, 200, githubJson("v1.2.0", "https://github.com/codescv/wa2port/releases/download/v1.2.0/app-release.apk", 1).toByteArray())
        }
        a.createContext("/ratelimit") { ex ->
            record(seenA, ex)
            ex.responseHeaders.add("X-RateLimit-Remaining", "0")
            send(ex, 403, "{\"message\":\"API rate limit exceeded\"}".toByteArray())
        }
        a.createContext("/toomany") { ex -> record(seenA, ex); send(ex, 429, ByteArray(0)) }
        a.createContext("/huge") { ex -> record(seenA, ex); send(ex, 200, ByteArray(3 * 1024 * 1024) { 'a'.code.toByte() }) }
        // 302 (relative) → 302 (absolute, cross-host) → server B.
        a.createContext("/dl/start") { ex -> record(seenA, ex); redirect(ex, 302, "/dl/hop2") }
        a.createContext("/dl/hop2") { ex -> record(seenA, ex); redirect(ex, 302, "http://localhost:$portB/objects/x.apk") }
        a.createContext("/loop/") { ex ->
            record(seenA, ex)
            val n = ex.requestURI.path.substringAfterLast('/').toInt()
            redirect(ex, 302, "/loop/${n + 1}")
        }
        a.createContext("/to-file") { ex -> record(seenA, ex); redirect(ex, 302, "file:///etc/passwd") }
        a.createContext("/no-location") { ex -> record(seenA, ex); send(ex, 302, ByteArray(0)) }
        a.createContext("/blank-location") { ex -> record(seenA, ex); redirect(ex, 301, "  ") }
        a.createContext("/html-type") { ex -> record(seenA, ex); send(ex, 200, "<html>login</html>".toByteArray(), "text/html; charset=utf-8") }
        a.createContext("/html-body") { ex -> record(seenA, ex); send(ex, 200, "<!DOCTYPE html><html>nope</html>".toByteArray()) }
        a.createContext("/truncated-chunked") { ex ->
            record(seenA, ex)
            ex.responseHeaders.add("Content-Type", "application/octet-stream")
            ex.sendResponseHeaders(200, 0) // chunked: no Content-Length, stream ends cleanly
            ex.responseBody.use { it.write(apk, 0, apk.size / 2) }
        }
        a.createContext("/slow") { ex ->
            record(seenA, ex)
            ex.responseHeaders.add("Content-Type", "application/octet-stream")
            ex.sendResponseHeaders(200, apk.size.toLong())
            try {
                ex.responseBody.use { out ->
                    var off = 0
                    while (off < apk.size) {
                        val n = minOf(16 * 1024, apk.size - off)
                        out.write(apk, off, n); out.flush()
                        off += n
                        Thread.sleep(40)
                    }
                }
            } catch (_: IOException) {
                // client went away (cancelled)
            }
        }
        a.createContext("/stall") { ex ->
            record(seenA, ex)
            releaseLatch.await(10, TimeUnit.SECONDS)
            runCatching { send(ex, 200, apk) }
        }
        b.createContext("/objects/") { ex -> record(seenB, ex); send(ex, 200, apk, "application/vnd.android.package-archive") }
        a.start()
        b.start()
        startRawServer()
    }

    /**
     * Raw socket server for a fixed-length truncation: promises `Content-Length: 1000`, sends 500
     * bytes and drops the connection (JDK HttpServer would keep the socket open instead).
     */
    private lateinit var raw: ServerSocket

    private fun startRawServer() {
        raw = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        Thread {
            while (!raw.isClosed) {
                val sock = runCatching { raw.accept() }.getOrNull() ?: break
                runCatching {
                    sock.use { s ->
                        val ins = s.getInputStream()
                        // Consume the request head (up to the blank line).
                        var last4 = 0
                        while (last4 != 0x0D0A0D0A) {
                            val c = ins.read()
                            if (c < 0) break
                            last4 = (last4 shl 8) or c
                        }
                        val out = s.getOutputStream()
                        out.write("HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\nContent-Length: 1000\r\n\r\n".toByteArray())
                        out.write(apk, 0, 500)
                        out.flush()
                    }
                }
            }
        }.apply { isDaemon = true; start() }
    }

    @AfterEach
    fun stopServers() {
        releaseLatch.countDown()
        a.stop(0)
        b.stop(0)
        runCatching { raw.close() }
    }

    private fun record(list: MutableList<Pair<String, Map<String, String>>>, ex: HttpExchange) {
        list += ex.requestURI.toString() to ex.requestHeaders.entries.associate { (k, v) -> k.lowercase() to v.joinToString(",") }
    }

    private fun send(ex: HttpExchange, status: Int, body: ByteArray, type: String = "application/octet-stream") {
        ex.responseHeaders.add("Content-Type", type)
        ex.sendResponseHeaders(status, if (body.isEmpty()) -1 else body.size.toLong())
        ex.responseBody.use { if (body.isNotEmpty()) it.write(body) }
    }

    private fun redirect(ex: HttpExchange, status: Int, location: String) {
        ex.responseHeaders.add("Location", location)
        ex.sendResponseHeaders(status, -1)
        ex.close()
    }

    private fun dest() = File(tmp, "updates/wa2port-1.3.0.apk")

    private fun assertNoLeftovers(d: File = dest()) {
        assertFalse(d.exists(), "apk must not exist")
        assertFalse(File(d.path + ".tmp").exists(), "tmp must not exist")
    }

    private fun downloadFails(path: String, expected: UpdateErrorCode, expectedSize: Long = -1): UpdateException {
        val e = assertThrows(UpdateException::class.java) {
            AppUpdater().downloadApk("$baseA$path", dest(), expectedSize = expectedSize)
        }
        assertEquals(expected, e.code, "$path → ${e.message}")
        assertNoLeftovers()
        return e
    }

    @Test
    fun checkLatestReleaseCustomEndpoint() {
        val r = AppUpdater().checkLatestRelease("1.2.0", UpdateEndpointConfig("$baseA/releases/latest"))
        assertTrue(r.updateAvailable)
        assertEquals("1.2.0", r.currentVersion)
        assertEquals("1.3.0", r.latest.version)
        assertEquals("$baseA/dl/start", r.latest.apkAsset!!.downloadUrl) // custom endpoint → verbatim
        val h = seenA.single().second
        assertEquals("application/vnd.github+json", h["accept"])
        assertEquals("2022-11-28", h["x-github-api-version"])
        assertEquals("wa2port-android/1.2.0", h["user-agent"])

        assertFalse(AppUpdater().checkLatestRelease("1.3.0", UpdateEndpointConfig("$baseA/releases/latest")).updateAvailable)
        assertFalse(AppUpdater().checkLatestRelease("1.10.0", UpdateEndpointConfig("$baseA/releases/latest")).updateAvailable)
    }

    @Test
    fun checkLatestReleaseThroughProxyPrefix() {
        val r = AppUpdater().checkLatestRelease("1.1.0", UpdateEndpointConfig("$baseA/proxy/"))
        assertEquals("/proxy/https://api.github.com/repos/codescv/wa2port/releases/latest", seenA.single().first)
        assertTrue(r.updateAvailable)
        assertEquals(
            "$baseA/proxy/https://github.com/codescv/wa2port/releases/download/v1.2.0/app-release.apk",
            r.latest.apkAsset!!.downloadUrl,
        )
    }

    @Test
    fun checkLatestReleaseErrors() {
        fun fails(url: String) = assertThrows(UpdateException::class.java) {
            AppUpdater().checkLatestRelease("1.2.0", UpdateEndpointConfig(url))
        }
        fails("$baseA/missing").let {
            assertEquals(UpdateErrorCode.HTTP, it.code); assertEquals(404, it.httpStatus)
            assertTrue(it.message!!.contains("No release found"))
        }
        fails("$baseA/ratelimit").let { assertEquals(403, it.httpStatus); assertTrue(it.message!!.contains("限流")) }
        fails("$baseA/toomany").let { assertEquals(429, it.httpStatus); assertTrue(it.message!!.contains("限流")) }
        assertEquals(UpdateErrorCode.INVALID_RESPONSE, fails("$baseA/huge").code)
        assertEquals(UpdateErrorCode.INVALID_RESPONSE, fails("$baseA/html-type").code)
        assertEquals(UpdateErrorCode.INVALID_ARGUMENT, fails("ftp://127.0.0.1/x").code)
        val pre = CancelToken().also { it.cancel() }
        val e = assertThrows(UpdateException::class.java) {
            AppUpdater().checkLatestRelease("1.2.0", UpdateEndpointConfig("$baseA/releases/latest"), pre)
        }
        assertEquals(UpdateErrorCode.CANCELLED, e.code)
    }

    @Test
    fun downloadFollowsRelativeAndCrossHostRedirects() {
        val progress = Collections.synchronizedList(mutableListOf<Pair<Long, Long>>())
        val out = AppUpdater().downloadApk("$baseA/dl/start", dest(), expectedSize = apk.size.toLong()) { d, t -> progress += d to t }
        assertEquals(dest(), out)
        assertArrayEquals(apk, out.readBytes())
        assertFalse(File(out.path + ".tmp").exists())
        assertEquals(listOf("/dl/start", "/dl/hop2"), seenA.map { it.first })
        val (path, headers) = seenB.single()
        assertEquals("/objects/x.apk", path)
        assertEquals("application/octet-stream", headers["accept"])
        assertEquals("identity", headers["accept-encoding"])
        assertNull(headers["authorization"])
        // Progress: first chunk reported, monotonic, final == total.
        assertTrue(progress.size >= 2, "progress=$progress")
        assertTrue(progress.zipWithNext().all { (x, y) -> y.first >= x.first })
        assertTrue(progress.all { it.second == apk.size.toLong() })
        assertEquals(apk.size.toLong(), progress.last().first)
    }

    @Test
    fun downloadReplacesExistingFile() {
        dest().parentFile.mkdirs()
        dest().writeText("old")
        File(dest().path + ".tmp").writeText("stale")
        AppUpdater().downloadApk("http://localhost:$portB/objects/y.apk", dest())
        assertArrayEquals(apk, dest().readBytes())
        assertFalse(File(dest().path + ".tmp").exists())
    }

    @Test
    fun redirectGuards() {
        downloadFails("/loop/0", UpdateErrorCode.NETWORK).let { assertTrue(it.message!!.contains("Too many redirects")) }
        assertEquals(1 + AppUpdater.MAX_REDIRECTS, seenA.size) // initial + 5 followed hops, 6th refused
        downloadFails("/to-file", UpdateErrorCode.NETWORK).let { assertTrue(it.message!!.contains("不安全的重定向")) }
        downloadFails("/no-location", UpdateErrorCode.HTTP)
        downloadFails("/blank-location", UpdateErrorCode.HTTP)
        downloadFails("/missing", UpdateErrorCode.HTTP).let { assertEquals(404, it.httpStatus) }
    }

    @Test
    fun rejectsNonApkPayloads() {
        downloadFails("/html-type", UpdateErrorCode.CORRUPT_DOWNLOAD).let { assertTrue(it.message!!.contains("网页")) }
        downloadFails("/html-body", UpdateErrorCode.CORRUPT_DOWNLOAD).let { assertTrue(it.message!!.contains("APK")) }
    }

    @Test
    fun rejectsTruncatedDownloads() {
        // Fixed Content-Length 1000, only 500 bytes then the connection drops.
        val e = assertThrows(UpdateException::class.java) {
            AppUpdater().downloadApk("http://127.0.0.1:${raw.localPort}/truncated-fixed", dest(), readTimeoutMs = 5_000)
        }
        assertEquals(UpdateErrorCode.CORRUPT_DOWNLOAD, e.code, e.message)
        assertTrue(e.message!!.contains("下载不完整"), e.message)
        assertNoLeftovers()
        // Chunked (no length): starts with PK\x03\x04 but has no central directory.
        downloadFails("/truncated-chunked", UpdateErrorCode.CORRUPT_DOWNLOAD).let { assertTrue(it.message!!.contains("下载不完整或已损坏")) }
        // Complete transfer but the release JSON promised a different size.
        downloadFails("/dl/start", UpdateErrorCode.CORRUPT_DOWNLOAD, expectedSize = apk.size + 1L)
    }

    @Test
    fun cancelMidDownload() {
        val token = CancelToken()
        val firstChunk = CountDownLatch(1)
        var lastProgress = 0L
        val canceller = Thread {
            firstChunk.await(10, TimeUnit.SECONDS)
            token.cancel()
        }.apply { start() }
        val e = assertThrows(UpdateException::class.java) {
            AppUpdater().downloadApk("$baseA/slow", dest(), token) { d, _ -> lastProgress = d; firstChunk.countDown() }
        }
        canceller.join(5_000)
        assertEquals(UpdateErrorCode.CANCELLED, e.code)
        assertTrue(lastProgress in 1 until apk.size, "progress=$lastProgress")
        assertNoLeftovers()
    }

    @Test
    fun cancelWhileWaitingForHeaders() {
        val token = CancelToken()
        val t = Thread { Thread.sleep(300); token.cancel() }.apply { start() }
        val started = System.currentTimeMillis()
        val e = assertThrows(UpdateException::class.java) { AppUpdater().downloadApk("$baseA/stall", dest(), token) }
        t.join()
        assertEquals(UpdateErrorCode.CANCELLED, e.code)
        assertTrue(System.currentTimeMillis() - started < 5_000)
        assertNoLeftovers()
    }

    @Test
    fun cancelBeforeAttach() {
        val token = CancelToken().also { it.cancel() }
        val e = assertThrows(UpdateException::class.java) { AppUpdater().downloadApk("$baseA/dl/start", dest(), token) }
        assertEquals(UpdateErrorCode.CANCELLED, e.code)
        assertTrue(seenA.isEmpty())
        assertNoLeftovers()
        // attach() itself throwing IOException("cancelled") must also map to CANCELLED.
        val lateToken = CancelToken()
        val e2 = assertThrows(UpdateException::class.java) {
            AppUpdater { url -> lateToken.cancel(); url.openConnection() as java.net.HttpURLConnection }
                .downloadApk("$baseA/dl/start", dest(), lateToken)
        }
        assertEquals(UpdateErrorCode.CANCELLED, e2.code)
        assertNoLeftovers()
    }

    @Test
    fun unsupportedSchemeAndUnknownHost() {
        val e = assertThrows(UpdateException::class.java) { AppUpdater().downloadApk("file:///etc/passwd", dest()) }
        assertEquals(UpdateErrorCode.INVALID_ARGUMENT, e.code)
        val e2 = assertThrows(UpdateException::class.java) {
            AppUpdater().downloadApk("http://nonexistent.invalid/x.apk", dest(), connectTimeoutMs = 2_000)
        }
        assertTrue(e2.code == UpdateErrorCode.NETWORK || e2.code == UpdateErrorCode.TIMEOUT, e2.message)
        assertNoLeftovers()
    }

    @Test
    fun endToEndCheckThenDownload() {
        val updater = AppUpdater()
        val r = updater.checkLatestRelease("1.2.0", UpdateEndpointConfig("$baseA/releases/latest"))
        assertTrue(r.updateAvailable)
        val asset = r.latest.apkAsset!!
        val f = updater.downloadApk(asset.downloadUrl, File(tmp, "updates/wa2port-${r.latest.version}.apk"), expectedSize = asset.sizeBytes)
        assertArrayEquals(apk, f.readBytes())
    }

    /** Real-shaped GitHub "latest release" payload; the main APK sits behind a lower-priority decoy. */
    private fun githubJson(tag: String, apkUrl: String, size: Long): String {
        val name = apkUrl.substringAfterLast('/').takeIf { it.endsWith(".apk") } ?: "wa2port-${tag.removePrefix("v")}-release.apk"
        val decoy = if (name.equals("app-release.apk", true)) "app-debug.apk" else "app-release.apk"
        return """
        {
          "url": "https://api.github.com/repos/codescv/wa2port/releases/1",
          "html_url": "https://github.com/codescv/wa2port/releases/tag/$tag",
          "id": 1, "tag_name": "$tag", "target_commitish": "main",
          "name": "WA2 Port $tag", "draft": false, "prerelease": false,
          "created_at": "2026-10-27T07:00:00Z", "published_at": "2026-10-27T08:00:00Z",
          "author": {"login": "codescv", "id": 7},
          "assets": [
            {"name": "$decoy", "browser_download_url": "https://example.invalid/$decoy",
             "size": 1, "content_type": "application/vnd.android.package-archive", "state": "uploaded"},
            {"name": "$name",
             "browser_download_url": "$apkUrl", "size": $size,
             "content_type": "application/vnd.android.package-archive", "download_count": 3},
            {"name": "SHA256SUMS", "browser_download_url": "https://example.invalid/SHA256SUMS", "size": 64, "content_type": "text/plain"}
          ],
          "tarball_url": null,
          "body": "- 新功能\n- Fixes"
        }
        """.trimIndent()
    }
}
