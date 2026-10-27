package com.wa2port.core.ai

import com.sun.net.httpserver.HttpServer
import com.wa2port.core.TestAssets
import com.wa2port.core.engine.HeadlessRunner
import com.wa2port.core.engine.Wa2Engine
import com.wa2port.core.script.ScenarioStore
import com.wa2port.core.text.TextLang
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.net.InetSocketAddress
import java.util.Collections

/**
 * End-to-end QA verification suite for the WA2 × Kotori AI learning feature:
 * - Real WA2 scene `1002` (Introductory Chapter) dialogue extraction (`JpSentence`, `officialCn`, `speaker`, `contextBefore`).
 * - Local `HttpServer` reverse-proxy (`customBaseUrl`) end-to-end verification for `testConnection`,
 *   `explainSentence`, and multi-turn `askFollowUp` across Gemini API and Vertex AI proxy modes.
 * - Full verification of `RubyText` token concatenation, 0ms character-to-chunk hit mapping,
 *   📘 课本标准 vs 🔥 口语演变 (`spokenEvolution` + `formalityLevel`), vocabulary & grammar resolution,
 *   and `ExplainCache` persistence.
 */
class Wa2KotoriQaVerificationTest {
    @TempDir
    lateinit var tempDir: File

    private lateinit var server: HttpServer
    private val capturedRequests = Collections.synchronizedList(mutableListOf<CapturedHttp>())
    private val queuedResponses = ArrayDeque<Pair<Int, String>>()

    data class CapturedHttp(
        val method: String,
        val path: String,
        val query: String?,
        val headers: Map<String, String>,
        val body: String,
    )

    @BeforeEach
    fun setUpServer() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex ->
            val body = ex.requestBody.readBytes().toString(Charsets.UTF_8)
            val headers = ex.requestHeaders.entries.associate { (k, v) -> k.lowercase() to v.joinToString(",") }
            capturedRequests += CapturedHttp(
                method = ex.requestMethod,
                path = ex.requestURI.path,
                query = ex.requestURI.rawQuery,
                headers = headers,
                body = body,
            )
            val (status, payload) = synchronized(queuedResponses) {
                queuedResponses.removeFirstOrNull()
            } ?: (200 to FakeTransport.geminiBody("OK"))
            val bytes = payload.toByteArray(Charsets.UTF_8)
            ex.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
            ex.sendResponseHeaders(status, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        server.executor = java.util.concurrent.Executors.newCachedThreadPool()
        server.start()
    }

    @AfterEach
    fun tearDownServer() {
        server.stop(0)
    }

    @Test
    fun `scene 1002 real dialogue end-to-end over customBaseUrl mock proxy`() {
        val assets = TestAssets.require()
        val engine = Wa2Engine(assets, ScenarioStore(assets))
        assertTrue(engine.start("1002"))
        val runner = HeadlessRunner(engine)
        runner.run(maxLines = 5, stayIn = "1002")
        // Export a SaveData snapshot in 1002 (with 5 lines in backlog) for emulator QA if scratch dir exists.
        val scratchSave = File("/usr/local/google/home/chii/.gemini/jetski/brain/0c1a99d9-7045-43c4-9b6a-69981467eaf2/scratch/save_1002.json")
        if (scratchSave.parentFile?.isDirectory == true) {
            scratchSave.writeText(engine.save().toJson())
        }

        val backlogRefs = engine.backlog.toList()
        assertEquals(5, backlogRefs.size)
        val lines = backlogRefs.map { engine.resolve(it) }
        lines.forEachIndexed { idx, l ->
            val jp = JpSentence.fromRich(l.jp, engine.decoder)
            val cn = JpSentence.officialCn(l.cn, engine.decoder)
            val spJp = JpSentence.speaker(l.speakerJp, engine.decoder, TextLang.JP)
            val spCn = JpSentence.speaker(l.speakerCn, engine.decoder, TextLang.CN)
            println("1002[$idx] speaker=$spJp/$spCn jp=${jp.text} cn=$cn voice=${l.ref.voice}")
            assertTrue(jp.text.isNotBlank())
            assertNotNull(cn)
            assertEquals(l.jp.baseCodes().size + 1, jp.glyphCharStart.size)
            assertEquals(jp.text.length, jp.glyphCharStart.last())
        }

        // Pick line 0 of 1002: 武也's iconic opening line with colloquial contractions.
        val line0 = lines[0]
        val jp0 = JpSentence.fromRich(line0.jp, engine.decoder)
        val cn0 = JpSentence.officialCn(line0.cn, engine.decoder)
        val speakerJp0 = JpSentence.speaker(line0.speakerJp, engine.decoder, TextLang.JP)
        val speakerCn0 = JpSentence.speaker(line0.speakerCn, engine.decoder, TextLang.CN)
        assertEquals("武也", speakerJp0)
        assertTrue(jp0.text.contains("ちょっと待てって"))
        assertTrue(jp0.text.contains("行っちまう"))

        val explainJson = """
        {
          "translation": "「等、等等啊！连你也要走掉吗！？」",
          "literalTranslation": "「等一下、我说等一下啊！连你也要走掉了吗！？」",
          "officialTranslationNote": "CK-GAL 译为「连你也要走吗！？」，原文「行っちまう（行ってしまう）」带有强烈的遗憾与焦急感（全都走光了、彻底离开）。",
          "register": "rough",
          "tone": "焦急挽留、男性友人粗犷口吻",
          "ruby": [
            {"base": "「ちょっ、ちょっと"},
            {"base": "待", "ruby": "ま"},
            {"base": "てって！お"},
            {"base": "前", "ruby": "まえ"},
            {"base": "まで"},
            {"base": "行", "ruby": "い"},
            {"base": "っちまうの！？」"}
          ],
          "chunks": [
            {
              "surface": "ちょっ、ちょっと",
              "reading": "ちょっ、ちょっと",
              "textbookForm": "ちょっと",
              "spokenEvolution": {
                "kind": "口吃重复",
                "derivation": "ちょっと → ちょっ、ちょっと（慌张打断对方时的急促重复）",
                "formality": 0.25
              },
              "role": "感叹/副词",
              "meaning": "等、等一下",
              "nuance": "急促打断想要离开的社团成员",
              "vocab": [
                {
                  "surface": "ちょっと",
                  "lemma": "ちょっと",
                  "lemmaReading": "ちょっと",
                  "lemmaRuby": [{"base": "ちょっと"}],
                  "partOfSpeech": "adverb",
                  "meaning": "稍微；等一下",
                  "jlpt": "N5",
                  "pitchAccent": 0
                }
              ]
            },
            {
              "surface": "待てって",
              "reading": "まてって",
              "textbookForm": "待ってください / 待てと言っている",
              "spokenEvolution": {
                "kind": "命令形+終助詞って",
                "derivation": "待つ（命令形 待て）+ と（引用）+ 言っている → 待てって",
                "formality": 0.15
              },
              "role": "谓语（祈使）",
              "meaning": "叫你等等啊！",
              "nuance": "句尾「って」表示不耐烦地重申或强调诉求",
              "vocab": [
                {
                  "surface": "待て",
                  "lemma": "待つ",
                  "lemmaReading": "まつ",
                  "lemmaRuby": [{"base": "待", "ruby": "ま"}, {"base": "つ"}],
                  "partOfSpeech": "verb-godan",
                  "meaning": "等待",
                  "conjugation": "五段动词「待つ」命令形",
                  "jlpt": "N5",
                  "pitchAccent": 1
                }
              ]
            },
            {
              "surface": "お前まで",
              "reading": "おまえまで",
              "textbookForm": "あなたまで",
              "role": "主语+副助词",
              "meaning": "连你也……",
              "nuance": "「まで」表极端举例，暗示其他人已经走了",
              "vocab": [
                {
                  "surface": "お前",
                  "lemma": "お前",
                  "lemmaReading": "おまえ",
                  "lemmaRuby": [{"base": "お"}, {"base": "前", "ruby": "まえ"}],
                  "partOfSpeech": "pronoun",
                  "meaning": "你（男性对平辈或熟人的随性称呼）",
                  "jlpt": "N3",
                  "pitchAccent": 0
                }
              ]
            },
            {
              "surface": "行っちまうの",
              "reading": "いっちまうの",
              "textbookForm": "行ってしまうのですか",
              "spokenEvolution": {
                "kind": "音便縮約",
                "derivation": "行ってしまう → 行っちまう（てしまう → ちまう）+ の（句尾升调疑问）",
                "formality": 0.1
              },
              "role": "核心谓语",
              "meaning": "也要一走了之吗！？",
              "nuance": "「ちまう」是「てしまう」的典型男性口语缩约，表达彻底完成与懊恼",
              "vocab": [
                {
                  "surface": "行っちまう",
                  "lemma": "行く",
                  "lemmaReading": "いく",
                  "lemmaRuby": [{"base": "行", "ruby": "い"}, {"base": "く"}],
                  "partOfSpeech": "verb-godan",
                  "meaning": "去；走掉",
                  "conjugation": "行く → 行って + しまう → 行っちまう（口语缩约）",
                  "jlpt": "N5",
                  "pitchAccent": 0
                }
              ]
            }
          ],
          "grammar": [
            {
              "pattern": "〜てしまう → 〜ちまう / 〜ちゃう",
              "chunkSurfaces": ["行っちまうの"],
              "explanation": "补助动词「てしまう」表动作彻底完成或遗憾心情；口语中「てしまう」常缩约为「ちゃう」（通用）或「ちまう」（偏男性/粗犷）。",
              "jlpt": "N4"
            },
            {
              "pattern": "命令形 + って",
              "chunkSurfaces": ["待てって"],
              "explanation": "终助词「って」接在命令形或普通形后，相当于「〜と言っているだろう」，表示「我都叫你……了！」的焦急语气。",
              "jlpt": "N3"
            }
          ]
        }
        """.trimIndent()

        val proxyBase = "http://127.0.0.1:${server.address.port}/custom-proxy/v1beta/"
        val settings = AiProviderSettings(
            providerType = AiProviderSettings.PROVIDER_GEMINI_API,
            geminiApiKey = "proxy-secret-key",
            customBaseUrl = proxyBase,
            proxyAuthStyle = AiProviderSettings.AUTH_STYLE_BEARER,
            extraHeaders = "X-Kotori-Client: wa2port-qa\nX-Auth-Echo: {apiKey}",
            model = "gemini-3.8-flash",
        )
        val cache = ExplainCache(tempDir)
        val client = GeminiAiClient({ settings })

        queuedResponses.addLast(200 to FakeTransport.geminiBody("OK", modelVersion = "gemini-3.8-flash"))
        queuedResponses.addLast(200 to FakeTransport.geminiBody(explainJson, modelVersion = "gemini-3.8-flash"))
        queuedResponses.addLast(200 to FakeTransport.geminiBody("「**行っちまう**」是「行ってしまう」的音便缩约；换成礼貌敬体可以说是 `行ってしまうのですか` 或 `行かれてしまうのですか` 哦。"))

        // 1. testConnection via customBaseUrl
        val conn = client.testConnection()
        assertTrue(conn.ok, conn.message)
        assertEquals("gemini-3.8-flash", conn.model)
        assertEquals("OK", conn.message)

        // 2. explainSentence on real 1002 line via customBaseUrl
        val focusIdx = jp0.text.indexOf('行')
        assertTrue(focusIdx >= 0)
        val req = ExplainRequest(
            sentence = jp0.text,
            contextBefore = emptyList(),
            speakerJp = speakerJp0,
            speakerCn = speakerCn0,
            officialCnTranslation = cn0,
            sceneHint = "WHITE ALBUM 2 · 序章 IC · 1002",
            focusCharIndex = focusIdx,
            scriptRuby = jp0.scriptRuby,
            persona = AiPersonaSettings.KOTORI,
        )
        val expl = client.explainSentence(req, cache)
        assertFalse(expl.fromCache)
        assertEquals(jp0.text, expl.sentence)
        assertEquals(jp0.text, expl.ruby.joinToString("") { it.base })
        assertEquals(RegisterTag.ROUGH, expl.register)
        assertEquals(4, expl.chunks.size)
        assertTrue(expl.chunks.all { it.alignment == SpanAlignment.EXACT })

        // Verify 📘 课本标准 vs 🔥 口语演变 on chunks
        val waitChunk = expl.chunks.first { it.surface == "待てって" }
        assertTrue(waitChunk.isEvolved)
        assertEquals("待ってください / 待てと言っている", waitChunk.textbookForm)
        assertEquals(2, waitChunk.spokenEvolution!!.formalityLevel)

        val goChunk = expl.chunks.first { it.surface == "行っちまうの" }
        assertTrue(goChunk.isEvolved)
        assertEquals("行ってしまうのですか", goChunk.textbookForm)
        assertEquals(1, goChunk.spokenEvolution!!.formalityLevel)

        // Verify 0ms character-to-chunk lookup across the sentence
        val activeAtFocus = SpanAlign.findChunkAtChar(expl.chunks, focusIdx)
        assertNotNull(activeAtFocus)
        assertEquals(goChunk.id, activeAtFocus!!.id)
        val goVocab = SpanAlign.findVocabForChunk(goChunk, expl.vocab)
        assertEquals(1, goVocab.size)
        assertEquals("行く", goVocab.single().lemma)
        val goGrammar = SpanAlign.grammarForChunk(goChunk, expl.grammar)
        assertEquals(1, goGrammar.size)
        assertTrue(goGrammar.single().pattern.contains("ちまう"))

        // Second explain call hits cache without network I/O
        val cachedExpl = client.explainSentence(req, cache)
        assertTrue(cachedExpl.fromCache)
        assertEquals(2, capturedRequests.size)

        // 3. askFollowUp via customBaseUrl
        val followUp = client.askFollowUp(
            expl = expl,
            history = emptyList(),
            question = "换成敬体怎么说？",
            persona = AiPersonaSettings.KAZUSA,
            cache = cache,
        )
        assertTrue(followUp.text.contains("行ってしまうのですか"))
        assertEquals(2, cache.thread(expl.id).size)

        // Verify all 3 HTTP requests hit the customBaseUrl reverse proxy with Bearer & extra headers
        assertEquals(3, capturedRequests.size)
        capturedRequests.forEach { r ->
            assertEquals("POST", r.method)
            assertEquals("/custom-proxy/v1beta/models/gemini-3.8-flash:generateContent", r.path)
            assertEquals("Bearer proxy-secret-key", r.headers["authorization"])
            assertEquals("wa2port-qa", r.headers["x-kotori-client"])
            assertEquals("proxy-secret-key", r.headers["x-auth-echo"])
        }
    }
}
