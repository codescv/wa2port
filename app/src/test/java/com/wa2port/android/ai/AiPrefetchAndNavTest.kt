package com.wa2port.android.ai

import com.wa2port.android.data.Settings
import com.wa2port.android.game.Overlay
import com.wa2port.core.ai.AiPersonaSettings
import com.wa2port.core.ai.AiProviderSettings
import com.wa2port.core.ai.CancelToken
import com.wa2port.core.ai.ExplainCache
import com.wa2port.core.ai.GeminiAiClient
import com.wa2port.core.ai.HttpTransport
import com.wa2port.core.ai.JpSentence
import com.wa2port.core.engine.DialogueLine
import com.wa2port.core.engine.LineRef
import com.wa2port.core.text.BilingualTextDecoder
import com.wa2port.core.text.RichText
import com.wa2port.core.text.TextItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.charset.Charset
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class AiPrefetchAndNavTest {
    @TempDir
    lateinit var tempDir: File

    private val decoder = BilingualTextDecoder()

    private fun sjis(s: String): IntArray {
        val b = s.toByteArray(Charset.forName("MS932"))
        val out = ArrayList<Int>()
        var i = 0
        while (i < b.size) {
            val lead = b[i].toInt() and 0xFF
            if (lead in 0x81..0x9F || lead in 0xE0..0xFC) {
                out += (lead shl 8) or (b[i + 1].toInt() and 0xFF)
                i += 2
            } else {
                out += lead
                i++
            }
        }
        return out.toIntArray()
    }

    private fun gb2312(s: String): IntArray {
        val b = s.toByteArray(Charset.forName("GBK"))
        val out = ArrayList<Int>()
        var i = 0
        while (i < b.size) {
            val lead = b[i].toInt() and 0xFF
            if (lead >= 0x81 && i + 1 < b.size) {
                out += (lead shl 8) or (b[i + 1].toInt() and 0xFF)
                i += 2
            } else {
                out += lead
                i++
            }
        }
        return out.toIntArray()
    }

    private fun okExplainResponse(surface: String): HttpTransport.Response {
        val modelJson = """{"translation":"译:$surface","ruby":[],"chunks":[{"surface":"$surface","reading":"","textbookForm":"$surface","role":"r","meaning":"m","vocab":[]}],"grammar":[]}"""
        val escaped = modelJson.replace("\\", "\\\\").replace("\"", "\\\"")
        return HttpTransport.Response(200, """{"candidates":[{"content":{"parts":[{"text":"$escaped"}]},"finishReason":"STOP"}]}""")
    }

    @Test
    fun buildRequestProducesIdenticalCacheKeyForPrefetchAndOpen() {
        val richJp = RichText(
            listOf(
                TextItem.Run(sjis("「")),
                TextItem.Ruby(sjis("本気"), sjis("マジ")),
                TextItem.Run(sjis("で言ってるのか？」")),
            ),
        )
        val richCn = RichText(listOf(TextItem.Run(gb2312("「你是认真的吗？」"))))
        val speakerJp = RichText(listOf(TextItem.Run(sjis("春希"))))
        val speakerCn = RichText(listOf(TextItem.Run(gb2312("春希"))))
        val line = DialogueLine(
            ref = LineRef("1002", token = 10, lineNo = 5),
            jp = richJp,
            cn = richCn,
            speakerJp = speakerJp,
            speakerCn = speakerCn,
            read = false,
        )
        val ctxLine = DialogueLine(
            ref = LineRef("1002", token = 9, lineNo = 4),
            jp = RichText(listOf(TextItem.Run(sjis("雪が降ってきた。")))),
            cn = null,
            speakerJp = null,
            speakerCn = null,
            read = true,
        )

        val prefetchReq = AiLearningController.buildRequest(
            decoder = decoder,
            scene = "1002",
            line = line,
            contextLines = listOf(ctxLine),
            focusCharIndex = null,
            persona = AiPersonaSettings.KAZUSA,
            forceRefresh = false,
        )
        val openReq = AiLearningController.buildRequest(
            decoder = decoder,
            scene = "1002",
            line = line,
            contextLines = listOf(ctxLine),
            focusCharIndex = 2,
            persona = AiPersonaSettings.SETSUNA,
            forceRefresh = false,
        )
        val model = AiProviderSettings.DEFAULT_MODEL
        assertEquals(
            ExplainCache.key(prefetchReq, model),
            ExplainCache.key(openReq, model),
            "Prefetch request and tap-to-analyze open request must share the exact same cache key",
        )
    }

    @Test
    fun singleFlightJoinsInFlightPrefetchWithoutDuplicateHttpCall() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val seenBodies = Collections.synchronizedList(mutableListOf<String>())
        val transport = object : HttpTransport {
            override fun post(url: String, headers: Map<String, String>, body: ByteArray, timeoutMs: Int, cancel: CancelToken?): HttpTransport.Response {
                seenBodies += body.toString(Charsets.UTF_8)
                started.countDown()
                release.await(5, TimeUnit.SECONDS)
                return okExplainResponse("雪だね")
            }
        }
        val settings = Settings(
            ai = AiProviderSettings(geminiApiKey = "test-key"),
            aiPrefetchEnabled = true,
        )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val ai = AiLearningController(
                cacheDir = tempDir,
                settings = { settings },
                scope = scope,
                clientFactory = { s -> GeminiAiClient(s, transport) },
                io = Dispatchers.IO,
                prefetchDebounceMs = 0L,
            )
            val sentence = JpSentence.ofText("雪だね")
            val st = AiBreakdownState(
                line = null,
                sentence = sentence,
                speakerJp = "雪菜",
                speakerCn = "雪菜",
                officialCn = "下雪了呢",
                contextBefore = emptyList(),
                sceneHint = "WHITE ALBUM 2",
                voice = null,
                returnTo = Overlay.NONE,
            )
            val req = st.request(ai.persona, force = false)

            // 1. Start background prefetch and wait until its HTTP request is in flight.
            ai.enqueuePrefetch(req)
            assertTrue(started.await(3, TimeUnit.SECONDS), "Prefetch should start HTTP call")
            assertEquals(1, ai.inFlight.size)

            // 2. User opens AI while prefetch is still in flight -> explain() must join inFlight!
            ai.breakdown = st
            ai.explain(force = false)

            // 3. Release HTTP response and wait for completion.
            release.countDown()
            val deadline = System.currentTimeMillis() + 3_000L
            while (st.status !is AiStatus.Ready && System.currentTimeMillis() < deadline) {
                Thread.sleep(15)
            }

            assertTrue(st.status is AiStatus.Ready, "Breakdown should reach Ready: ${st.status}")
            assertEquals(1, seenBodies.size, "Single-flight must send only 1 HTTP request")
            assertEquals(1, ai.prefetchedCount)
            assertEquals(1, ai.prefetchHitCount)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun latestWinsQueueLetsActiveRequestFinishAndDropsMiddleQueuedLine() {
        val firstStarted = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val thirdDone = CountDownLatch(1)
        val seenSentences = Collections.synchronizedList(mutableListOf<String>())
        val transport = object : HttpTransport {
            override fun post(url: String, headers: Map<String, String>, body: ByteArray, timeoutMs: Int, cancel: CancelToken?): HttpTransport.Response {
                val text = body.toString(Charsets.UTF_8)
                val which = when {
                    text.contains("第一句") -> "第一句"
                    text.contains("第二句") -> "第二句"
                    else -> "第三句"
                }
                seenSentences += which
                if (which == "第一句") {
                    firstStarted.countDown()
                    releaseFirst.await(5, TimeUnit.SECONDS)
                }
                val res = okExplainResponse(which)
                if (which == "第三句") thirdDone.countDown()
                return res
            }
        }
        val settings = Settings(
            ai = AiProviderSettings(geminiApiKey = "test-key"),
            aiPrefetchEnabled = true,
        )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val ai = AiLearningController(
                cacheDir = tempDir,
                settings = { settings },
                scope = scope,
                clientFactory = { s -> GeminiAiClient(s, transport) },
                io = Dispatchers.IO,
                prefetchDebounceMs = 40L,
            )
            fun reqFor(s: String) = AiBreakdownState(
                line = null, sentence = JpSentence.ofText(s), speakerJp = null, speakerCn = null,
                officialCn = null, contextBefore = emptyList(), sceneHint = "WA2", voice = null, returnTo = Overlay.NONE,
            ).request(ai.persona, force = false)

            val req1 = reqFor("第一句")
            val req2 = reqFor("第二句")
            val req3 = reqFor("第三句")

            // Start req1 and wait until it is actively running over HTTP.
            ai.enqueuePrefetch(req1)
            assertTrue(firstStarted.await(3, TimeUnit.SECONDS))

            // While req1 is active, enqueue req2 and then req3 (latest-wins replaces req2 with req3).
            ai.enqueuePrefetch(req2)
            Thread.sleep(10)
            ai.enqueuePrefetch(req3)

            // Let req1 finish into cache; req3 should run next while req2 was dropped.
            releaseFirst.countDown()
            assertTrue(thirdDone.await(3, TimeUnit.SECONDS))

            val model = settings.ai.model
            val deadline = System.currentTimeMillis() + 2_000L
            while (ai.cache.get(ExplainCache.key(req3, model)) == null && System.currentTimeMillis() < deadline) {
                Thread.sleep(15)
            }

            assertEquals(listOf("第一句", "第三句"), seenSentences)
            assertNotNull(ai.cache.get(ExplainCache.key(req1, model)), "Active request 1 must finish into cache")
            assertNull(ai.cache.get(ExplainCache.key(req2, model)), "Superseded queued request 2 must be dropped")
            assertNotNull(ai.cache.get(ExplainCache.key(req3, model)), "Latest queued request 3 must be cached")
        } finally {
            scope.cancel()
        }
    }
}
