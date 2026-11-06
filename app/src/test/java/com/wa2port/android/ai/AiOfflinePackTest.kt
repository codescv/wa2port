package com.wa2port.android.ai

import com.wa2port.android.data.Settings
import com.wa2port.android.game.Overlay
import com.wa2port.core.ai.AiErrorCode
import com.wa2port.core.ai.AiPackDownloader
import com.wa2port.core.ai.AiPackEndpointConfig
import com.wa2port.core.ai.AiPackMeta
import com.wa2port.core.ai.AiProviderSettings
import com.wa2port.core.ai.CancelToken
import com.wa2port.core.ai.ExplainCache
import com.wa2port.core.ai.ExplainJsonCodec
import com.wa2port.core.ai.ExplainRequest
import com.wa2port.core.ai.ExplanationMeta
import com.wa2port.core.ai.GeminiAiClient
import com.wa2port.core.ai.HttpTransport
import com.wa2port.core.ai.JpSentence
import com.wa2port.core.ai.OfflineAiPack
import com.wa2port.core.ai.OfflineAiPackWriter
import com.wa2port.core.ai.PackEntry
import com.wa2port.core.ai.RawChunk
import com.wa2port.core.ai.RawExplanation
import com.wa2port.core.ai.RawRuby
import com.wa2port.core.ai.RawVocab
import com.wa2port.core.engine.DialogueLine
import com.wa2port.core.engine.LineRef
import com.wa2port.core.text.RichText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger

/** Offline AI pack: zero-key lookup in [AiLearningController] + [AiPackController] state machine. */
class AiOfflinePackTest {
    @TempDir
    lateinit var tempDir: File

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val httpCalls = AtomicInteger()

    @AfterEach
    fun tearDown() {
        scope.cancel()
    }

    /** In-memory HTTP: `routes(url)` → (status, headers, body). */
    private class FakeConn(u: URL, private val routes: (URL) -> Triple<Int, Map<String, String>, ByteArray>) : HttpURLConnection(u) {
        private val resp by lazy { routes(url) }
        override fun setRequestProperty(key: String?, value: String?) {}
        override fun connect() {}
        override fun disconnect() {}
        override fun usingProxy(): Boolean = false
        override fun getResponseCode(): Int = resp.first
        override fun getHeaderField(name: String?): String? = resp.second[name]
        override fun getInputStream(): InputStream = ByteArrayInputStream(resp.third)
        override fun getErrorStream(): InputStream = ByteArrayInputStream(resp.third)
        override fun getContentLengthLong(): Long = resp.third.size.toLong()
        override fun getContentType(): String = "application/octet-stream"
    }

    private val sentence = "雪が降ってきた"
    private val ref = LineRef("1001", token = 42, lineNo = 7)

    private fun buildPackFile(file: File, translation: String = "下雪了") {
        val req = ExplainRequest(sentence)
        val raw = RawExplanation(
            translation = translation, literalTranslation = null, officialTranslationNote = null, register = null, tone = null,
            ruby = listOf(RawRuby("雪", "ゆき"), RawRuby("降", "ふ")),
            chunks = listOf(
                RawChunk("雪が", "ゆきが", "雪が", null, "主语", "雪", null, listOf(RawVocab("雪", "雪", "ゆき", emptyList(), "noun", "雪", null, "N5", null, null))),
                RawChunk("降ってきた", "ふってきた", "降ってきた", null, "谓语", "下起来了", null, emptyList()),
            ),
            grammar = emptyList(),
        )
        val e = PackEntry.fromExplanation(ExplainJsonCodec.toSentenceExplanation(raw, req, ExplanationMeta("x", "m", now = 0)), req)
        file.parentFile?.mkdirs()
        OfflineAiPackWriter.write(file, AiPackMeta("v1", "p", "flash", 1, 1, 1, 1), listOf(e), listOf(OfflineAiPackWriter.TokenRef(ref.scene, ref.token, 0)))
    }

    private val failingTransport = object : HttpTransport {
        override fun post(url: String, headers: Map<String, String>, body: ByteArray, timeoutMs: Int, cancel: CancelToken?): HttpTransport.Response {
            httpCalls.incrementAndGet()
            return HttpTransport.Response(500, """{"error":{"message":"should not be called"}}""")
        }
    }

    private fun controller(settings: Settings): AiLearningController = AiLearningController(
        cacheDir = File(tempDir, "ai_cache"),
        settings = { settings },
        scope = scope,
        clientFactory = { s -> GeminiAiClient(s, failingTransport) },
        io = Dispatchers.IO,
        prefetchDebounceMs = 0L,
    )

    private fun state(): AiBreakdownState = AiBreakdownState(
        line = DialogueLine(ref, RichText.EMPTY, null, null, null, read = false),
        sentence = JpSentence.ofText(sentence),
        speakerJp = "雪菜",
        speakerCn = "雪菜",
        officialCn = "下雪了",
        contextBefore = emptyList(),
        sceneHint = "WA2",
        voice = null,
        returnTo = Overlay.NONE,
    )

    private fun waitFor(timeoutMs: Long = 5_000, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!cond() && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertTrue(cond(), "condition not reached in $timeoutMs ms")
    }

    private fun installPack(ai: AiLearningController): OfflineAiPack {
        val f = File(tempDir, "pack/${OfflineAiPack.FILE_NAME}")
        buildPackFile(f)
        return OfflineAiPack.open(f).also { ai.installOfflinePack(it) }
    }

    @Test
    fun explainUsesOfflinePackWithoutApiKeyAndRecordsNotebook() {
        val ai = controller(Settings(ai = AiProviderSettings()))
        assertFalse(ai.isConfigured)
        installPack(ai)
        val st = state()
        ai.breakdown = st
        ai.explain()
        waitFor { st.status is AiStatus.Ready }
        val e = (st.status as AiStatus.Ready).expl
        assertTrue(st.isFromOfflinePack)
        assertEquals("下雪了", e.translation)
        assertEquals(listOf("雪が", "降ってきた"), e.chunks.map { it.surface })
        assertEquals("雪菜", e.speakerJp)
        assertEquals(0, httpCalls.get())
        waitFor { ai.notebook.sentences().size == 1 }
        assertEquals(e.id, ai.notebook.sentences().first().explanationId)
        assertEquals(1001, ai.notebook.sentences().first().scene)

        // ↻ without API key: keep the pack breakdown, show a non-destructive notice.
        ai.regenerate()
        assertTrue(st.status is AiStatus.Ready)
        assertEquals(AiLearningController.PACK_REGENERATE_NOTICE, st.notice)

        // Follow-up without API key: friendly message, no request.
        ai.askFollowUp("为什么用てくる？")
        assertEquals(AiLearningController.PACK_FOLLOW_UP_NOTICE, st.followUpError)
        assertEquals(0, httpCalls.get())

        // Star works on pack breakdowns.
        assertTrue(ai.toggleSentenceStar(e.id))
        assertTrue(ai.isSentenceStarred(e.id))
    }

    @Test
    fun noPackNoKeyReportsNotConfigured() {
        val ai = controller(Settings(ai = AiProviderSettings()))
        val st = state()
        ai.breakdown = st
        ai.explain()
        waitFor { st.status is AiStatus.Error }
        assertEquals(AiErrorCode.NOT_CONFIGURED, (st.status as AiStatus.Error).code)
        // Hot-plug a pack (zero restart): the same sentence now resolves offline.
        installPack(ai)
        ai.explain()
        waitFor { st.status is AiStatus.Ready }
        assertTrue(st.isFromOfflinePack)
        // Removing the pack falls back immediately.
        ai.installOfflinePack(null)
        ai.explain()
        waitFor { st.status is AiStatus.Error }
    }

    @Test
    fun cachedOnlineRegenerationWinsOverPack() {
        val settings = Settings(ai = AiProviderSettings(geminiApiKey = "k"))
        val ai = controller(settings)
        installPack(ai)
        val st = state()
        val req = st.request(ai.persona, force = false)
        val key = ExplainCache.key(req, settings.ai.model)
        val online = OfflineAiPack.open(File(tempDir, "pack/${OfflineAiPack.FILE_NAME}")).use { it.lookup(null, null, req)!! }
            .copy(id = key, translation = "在线重新生成的译文", model = settings.ai.model, fromCache = false)
        ai.cache.put(online)
        ai.breakdown = st
        ai.explain()
        waitFor { st.status is AiStatus.Ready }
        assertEquals("在线重新生成的译文", (st.status as AiStatus.Ready).expl.translation)
        assertFalse(st.isFromOfflinePack)
        assertEquals(0, httpCalls.get())
    }

    @Test
    fun configuredUsesPackBeforeNetworkAndPrefetchSkipsCoveredLines() {
        val settings = Settings(ai = AiProviderSettings(geminiApiKey = "k"), aiPrefetchEnabled = true)
        val ai = controller(settings)
        installPack(ai)
        val st = state()
        ai.breakdown = st
        ai.explain()
        waitFor { st.status is AiStatus.Ready }
        assertTrue(st.isFromOfflinePack)
        assertEquals(0, httpCalls.get())

        assertTrue(ai.offlinePackCovers(ref.scene, ref.token, sentence))
        ai.enqueuePrefetch(st.request(ai.persona, force = false))
        Thread.sleep(150)
        assertEquals(0, httpCalls.get(), "covered lines never use online prefetch tokens")
        assertEquals(0, ai.prefetchedCount)

        // Not covered → online is attempted (fails with our 500 transport, proving the order).
        val other = AiBreakdownState(
            line = null, sentence = JpSentence.ofText("全然違う文"), speakerJp = null, speakerCn = null, officialCn = null,
            contextBefore = emptyList(), sceneHint = "WA2", voice = null, returnTo = Overlay.NONE,
        )
        ai.breakdown = other
        ai.explain()
        waitFor { other.status is AiStatus.Error }
        assertTrue(httpCalls.get() >= 1)
    }

    // ------------------------------------------------------------------ AiPackController

    private val endpoint = AiPackEndpointConfig(apiUrl = "https://api.test/api", assetUrl = "https://cdn.test/file")

    /** Downloader backed by [FakeConn]: release JSON at /api, the pack at /file. */
    private fun fakeDownloader(packFile: File, status: Int = 200, digest: () -> String? = { null }) = AiPackDownloader { u ->
        FakeConn(u) { url ->
            when (url.path) {
                "/api" -> {
                    val d = digest()?.let { ",\"digest\":\"sha256:$it\"" } ?: ""
                    Triple(200, emptyMap(), """{"tag_name":"ai-pack-v1","assets":[{"name":"${OfflineAiPack.FILE_NAME}","browser_download_url":"https://cdn.test/file","size":${packFile.length()}$d}]}""".toByteArray())
                }
                "/file" -> if (status == 200) Triple(200, emptyMap(), packFile.readBytes()) else Triple(status, emptyMap(), ByteArray(0))
                else -> Triple(404, emptyMap(), ByteArray(0))
            }
        }
    }

    private fun sha(f: File) = MessageDigest.getInstance("SHA-256").digest(f.readBytes()).joinToString("") { "%02x".format(it) }

    @Test
    fun packControllerDownloadInstallCheckUpdateDelete() {
        val src = File(tempDir, "remote/${OfflineAiPack.FILE_NAME}").also { buildPackFile(it) }
        var remoteDigest: String? = sha(src)
        val downloader = fakeDownloader(src) { remoteDigest }
        val plugged = ArrayList<OfflineAiPack?>()
        val packDir = File(tempDir, "ai_pack")
        val c = AiPackController(packDir, scope, downloader, Dispatchers.IO, { endpoint }) { plugged += it }
        waitFor { c.state is AiPackState.NotInstalled }
        c.download()
        assertTrue(c.state is AiPackState.Downloading || c.state is AiPackState.Installed)
        waitFor { c.state is AiPackState.Installed }
        val inst = c.state as AiPackState.Installed
        assertEquals(1, inst.stats.occurrenceCount)
        assertTrue(c.packFile.isFile)
        assertNotNull(plugged.last())
        assertTrue(c.isInstalled)

        c.checkUpdate()
        waitFor { (c.state as? AiPackState.Installed)?.checked == true }
        assertNull((c.state as AiPackState.Installed).updateInfo, "same digest → up to date")

        remoteDigest = "0".repeat(64)
        c.checkUpdate()
        waitFor { (c.state as? AiPackState.Installed)?.updateInfo != null }

        c.delete()
        assertTrue(c.state is AiPackState.NotInstalled)
        assertNull(plugged.last())
        waitFor { !c.packFile.exists() }

        // Reopen from disk on a new controller (app restart) → Installed again after re-download.
        remoteDigest = sha(src)
        c.download()
        waitFor { c.state is AiPackState.Installed }
        val c2 = AiPackController(packDir, scope, downloader, Dispatchers.IO, { endpoint })
        waitFor { c2.state is AiPackState.Installed }
        c2.close()
        c.close()
    }

    @Test
    fun packControllerErrorAndCancelTransitions() {
        val src = File(tempDir, "remote/${OfflineAiPack.FILE_NAME}").also { buildPackFile(it) }
        val c = AiPackController(File(tempDir, "ai_pack"), scope, fakeDownloader(src, status = 500), Dispatchers.IO, { endpoint })
        c.download()
        waitFor { c.state is AiPackState.Error }
        assertTrue((c.state as AiPackState.Error).message.contains("500"), (c.state as AiPackState.Error).message)
        assertNull((c.state as AiPackState.Error).previousInstalled)
        c.cancel()
        assertTrue(c.state is AiPackState.Error, "cancel outside a job keeps the state")
        c.download()
        c.cancel()
        assertTrue(c.state is AiPackState.NotInstalled)
        assertFalse(c.busy)
    }

    @Test
    fun learningControllerHotPlugsDownloadedPack() {
        val src = File(tempDir, "remote/${OfflineAiPack.FILE_NAME}").also { buildPackFile(it) }
        val ai = AiLearningController(
            cacheDir = File(tempDir, "ai_cache"),
            settings = { Settings(ai = AiProviderSettings()) },
            scope = scope,
            clientFactory = { s -> GeminiAiClient(s, failingTransport) },
            io = Dispatchers.IO,
            packDownloader = fakeDownloader(src),
            packEndpoint = { endpoint },
        )
        assertEquals(File(tempDir, "ai_pack"), ai.packs.packDir)
        assertNull(ai.offlinePack)
        ai.packs.download()
        waitFor { ai.packs.state is AiPackState.Installed }
        assertNotNull(ai.offlinePack)
        val st = state()
        ai.breakdown = st
        ai.explain()
        waitFor { st.status is AiStatus.Ready }
        assertTrue(st.isFromOfflinePack)
        ai.packs.delete()
        assertNull(ai.offlinePack)
    }
}
