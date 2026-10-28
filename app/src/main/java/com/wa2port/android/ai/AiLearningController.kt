package com.wa2port.android.ai

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.wa2port.android.data.Settings
import com.wa2port.android.game.GameSession
import com.wa2port.android.game.Overlay
import com.wa2port.android.ui.sceneLabel
import com.wa2port.core.ai.AiConnectionTestResult
import com.wa2port.core.ai.AiErrorCode
import com.wa2port.core.ai.AiException
import com.wa2port.core.ai.AiPersonaSettings
import com.wa2port.core.ai.AiProviderSettings
import com.wa2port.core.ai.AiSampleExplanation
import com.wa2port.core.ai.CacheStats
import com.wa2port.core.ai.CancelToken
import com.wa2port.core.ai.ExplainCache
import com.wa2port.core.ai.ExplainRequest
import com.wa2port.core.ai.FollowUpMessage
import com.wa2port.core.ai.GeminiAiClient
import com.wa2port.core.ai.JpSentence
import com.wa2port.core.ai.OfflineExplainer
import com.wa2port.core.ai.SemanticChunk
import com.wa2port.core.ai.SentenceExplanation
import com.wa2port.core.ai.SpanAlign
import com.wa2port.core.ai.formatContextLine
import com.wa2port.core.engine.DialogueLine
import com.wa2port.core.engine.VoiceRef
import com.wa2port.core.text.TextLang
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Status of the breakdown request. */
sealed interface AiStatus {
    data object Idle : AiStatus

    /**
     * Request in flight. [skeleton] is the local [OfflineExplainer] result (< 1 ms: CK-GAL line,
     * script furigana, coarse chunks) so the sheet is never blank while the model works.
     */
    data class Loading(val skeleton: SentenceExplanation) : AiStatus
    data class Ready(val expl: SentenceExplanation) : AiStatus
    data class Error(val code: AiErrorCode, val message: String) : AiStatus
}

/**
 * State of one open AI breakdown sheet (Compose state; mutated on the main thread only).
 * [returnTo] is the overlay restored when the sheet closes (NONE, BACKLOG, MENU, CONFIG…).
 */
class AiBreakdownState(
    val line: DialogueLine?,
    val sentence: JpSentence,
    val speakerJp: String?,
    val speakerCn: String?,
    val officialCn: String?,
    val contextBefore: List<String>,
    val sceneHint: String,
    val voice: VoiceRef?,
    val returnTo: Overlay,
    val isSample: Boolean = false,
) {
    var status by mutableStateOf<AiStatus>(AiStatus.Idle)
    var focusCharIndex by mutableStateOf<Int?>(null)
    /** Chunk picked from the chunk strip / cards (wins over [focusCharIndex]; needed for unaligned chunks). */
    var selectedChunkId by mutableStateOf<String?>(null)
    /** Bumped when the focus changes from the sentence header, so the sheet scrolls to the active card. */
    var focusFromSentence by mutableIntStateOf(0)
    var thread by mutableStateOf<List<FollowUpMessage>>(emptyList())
    /** Optimistic user bubble while a follow-up is in flight. */
    var pendingQuestion by mutableStateOf<String?>(null)
    var followUpError by mutableStateOf<String?>(null)
    /** Question to put back into the input after a failure. */
    var failedQuestion by mutableStateOf<String?>(null)
    val followUpPending: Boolean get() = pendingQuestion != null

    val explanation: SentenceExplanation? get() = (status as? AiStatus.Ready)?.expl

    /** What the sheet shows: the real breakdown, else the offline skeleton while [AiStatus.Loading]. */
    val shownExplanation: SentenceExplanation? get() = explanation ?: (status as? AiStatus.Loading)?.skeleton

    /** True while only the loading skeleton is available (no real breakdown yet). */
    val isSkeleton: Boolean get() = explanation == null && status is AiStatus.Loading

    /** Chunk highlighted in the header and expanded in the list (0 ms, purely local). */
    val activeChunk: SemanticChunk?
        get() {
            val e = explanation ?: return null
            selectedChunkId?.let { id -> e.chunks.firstOrNull { it.id == id }?.let { return it } }
            return focusCharIndex?.let { SpanAlign.findChunkAtChar(e.chunks, it) }
        }

    /** Request for the current persona. */
    fun request(persona: AiPersonaSettings, force: Boolean): ExplainRequest = ExplainRequest(
        sentence = sentence.text,
        contextBefore = contextBefore,
        speakerJp = speakerJp,
        speakerCn = speakerCn,
        officialCnTranslation = officialCn,
        sceneHint = sceneHint,
        focusCharIndex = focusCharIndex,
        scriptRuby = sentence.scriptRuby,
        persona = persona,
        force = force,
    )
}

/**
 * Owns the AI learning feature for the app (the "GameController" role of the design): opening the
 * breakdown for a line, the explain / follow-up requests (blocking core client on [io], results on
 * Main), the explanation cache, connection tests and the sample / offline previews.
 *
 * At most one explain and one follow-up are in flight; closing or reopening cancels both, and a
 * result for a stale [AiBreakdownState] is dropped (identity check). Every failure becomes an
 * [AiStatus.Error] — only coroutine cancellation propagates.
 */
class AiLearningController(
    cacheDir: File,
    private val settings: () -> Settings,
    private val scope: CoroutineScope,
    clientFactory: (() -> AiProviderSettings) -> GeminiAiClient = { GeminiAiClient(it) },
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val now: () -> Long = System::currentTimeMillis,
) {
    val cache = ExplainCache(cacheDir)
    private val client = clientFactory { settings().ai }
    private val testClientFactory = clientFactory

    var breakdown by mutableStateOf<AiBreakdownState?>(null)
        private set
    val persona: AiPersonaSettings
        get() = settings().let { AiPersonaSettings.resolve(it.aiPersonaId, it.aiCustomPersonaName, it.aiCustomPersonaSoul) }

    /** Selectable personas: the two built-in characters plus the user's custom persona. */
    val personas: List<AiPersonaSettings>
        get() = settings().let {
            listOf(AiPersonaSettings.KAZUSA, AiPersonaSettings.SETSUNA, AiPersonaSettings.custom(it.aiCustomPersonaName, it.aiCustomPersonaSoul))
        }
    val isConfigured: Boolean get() = settings().ai.isConfigured

    var testing by mutableStateOf(false)
        private set
    var testResult by mutableStateOf<AiConnectionTestResult?>(null)
    /** Last loaded cache statistics (refreshed off the main thread via [refreshCacheStats]). */
    var cacheStats by mutableStateOf<CacheStats?>(null)
        private set

    private var explainJob: Job? = null
    private var explainToken: CancelToken? = null
    private var followJob: Job? = null
    private var followToken: CancelToken? = null

    // ------------------------------------------------------------------------------- open / close

    /**
     * Opens the breakdown for [line] ([focusCharIndex] = tapped character, if any). The sheet stacks
     * above the current overlay ([returnTo], restored on close); from the plain game view it sets
     * `Overlay.AI` so the engine pauses. Starts the explain request immediately.
     */
    fun open(session: GameSession, line: DialogueLine, focusCharIndex: Int?, returnTo: Overlay = session.overlay) {
        val decoder = session.engine.decoder
        val sentence = JpSentence.fromRich(line.jp, decoder)
        if (sentence.text.isBlank()) {
            session.toast = "这一行没有日文 No Japanese text"
            return
        }
        val context = runCatching {
            session.contextBefore(line, 3).map { l ->
                formatContextLine(JpSentence.speaker(l.speakerJp, decoder, TextLang.JP), JpSentence.fromRich(l.jp, decoder).text)
            }.filter { it.isNotBlank() }
        }.getOrDefault(emptyList())
        cancelRequests()
        val back = if (returnTo == Overlay.AI) breakdown?.returnTo ?: Overlay.NONE else returnTo
        val st = AiBreakdownState(
            line = line,
            sentence = sentence,
            speakerJp = JpSentence.speaker(line.speakerJp, decoder, TextLang.JP),
            speakerCn = JpSentence.speaker(line.speakerCn, decoder, TextLang.CN),
            officialCn = JpSentence.officialCn(line.cn, decoder),
            contextBefore = context,
            sceneHint = "WHITE ALBUM 2 · ${sceneLabel(session.engine.scene)}",
            voice = line.ref.voice,
            returnTo = back,
        )
        st.focusCharIndex = focusCharIndex?.coerceIn(0, (sentence.text.length - 1).coerceAtLeast(0))
        breakdown = st
        if (back == Overlay.NONE) session.overlay = Overlay.AI
        explain()
    }

    /** Opens the hand-written sample breakdown (示例预览); works without a session (title config). */
    fun openSample(session: GameSession?) {
        val back = breakdown?.returnTo ?: session?.overlay?.takeIf { it != Overlay.AI } ?: Overlay.NONE
        cancelRequests()
        val st = AiBreakdownState(
            line = null,
            sentence = JpSentence.ofText(AiSampleExplanation.SENTENCE),
            speakerJp = AiSampleExplanation.SPEAKER_JP,
            speakerCn = AiSampleExplanation.SPEAKER_CN,
            officialCn = AiSampleExplanation.OFFICIAL_CN,
            contextBefore = AiSampleExplanation.request().contextBefore,
            sceneHint = "WHITE ALBUM 2 · 示例 Sample",
            voice = null,
            returnTo = back,
            isSample = true,
        )
        val expl = AiSampleExplanation.build(now())
        st.status = AiStatus.Ready(expl)
        st.focusCharIndex = AiSampleExplanation.SENTENCE.indexOf('雪')
        breakdown = st
        if (session != null && back == Overlay.NONE) session.overlay = Overlay.AI
        scope.launch {
            val th = withContext(io) { runCatching { cache.thread(expl.id) }.getOrDefault(emptyList()) }
            if (breakdown === st) st.thread = th
        }
    }

    /** Shows the offline phrase split (script furigana + CK-GAL line) for the open line; no network. */
    fun showOffline() {
        val st = breakdown ?: return
        cancelRequests()
        st.followUpError = null
        st.status = AiStatus.Ready(OfflineExplainer.build(st.request(persona, force = false), now()))
    }

    /** Closes the sheet, cancels in-flight requests and restores the overlay it was opened over. */
    fun close(session: GameSession?) {
        cancelRequests()
        val st = breakdown
        breakdown = null
        if (session != null && session.overlay == Overlay.AI) session.overlay = st?.returnTo?.takeIf { it != Overlay.AI } ?: Overlay.NONE
    }

    private fun cancelRequests() {
        explainToken?.cancel()
        explainJob?.cancel()
        explainToken = null
        explainJob = null
        cancelFollowUp()
    }

    /** Cancels an in-flight follow-up; the question goes back into the input (never stuck pending). */
    private fun cancelFollowUp() {
        followToken?.cancel()
        followJob?.cancel()
        followToken = null
        followJob = null
        breakdown?.let { st ->
            st.pendingQuestion?.let { q ->
                st.failedQuestion = q
                st.pendingQuestion = null
            }
        }
    }

    // ------------------------------------------------------------------------------- explain

    /** (Re)requests the breakdown of the open sentence. NOT_CONFIGURED is reported without any I/O. */
    fun explain(force: Boolean = false) {
        val st = breakdown ?: return
        if (st.isSample) {
            st.status = AiStatus.Ready(AiSampleExplanation.build(now()))
            return
        }
        explainToken?.cancel()
        explainJob?.cancel()
        // A follow-up about the old breakdown must not land on the new one.
        if (force) cancelFollowUp()
        val ai = settings().ai
        if (!ai.isConfigured) {
            st.status = AiStatus.Error(AiErrorCode.NOT_CONFIGURED, ai.validationError() ?: "尚未配置 AI 服务")
            return
        }
        if (force) st.thread = emptyList()
        val token = CancelToken()
        explainToken = token
        val req = st.request(persona, force)
        // Progressive loading: the local skeleton (< 1 ms, no I/O) fills the sheet immediately.
        st.status = AiStatus.Loading(OfflineExplainer.build(req, now()))
        // readTimeout is per read, not a total deadline: a watchdog cancels the whole call. One
        // explain may do a token exchange + the call + one nudge retry, hence 2 × timeout + slack
        // (a "thinking" HTTP 400 that triggers the no-thinkingConfig retry comes back immediately).
        val deadlineMs = 2L * ai.timeoutMs + 10_000L
        explainJob = scope.launch {
            var timedOut = false
            val watchdog = launch {
                delay(deadlineMs)
                timedOut = true
                token.cancel()
            }
            val outcome: Result<Pair<SentenceExplanation, List<FollowUpMessage>>> = try {
                withContext(io) {
                    val e = client.explainSentence(req, cache, token)
                    val th = runCatching { cache.thread(e.id) }.getOrDefault(emptyList())
                    Result.success(e to th)
                }
            } catch (c: CancellationException) {
                token.cancel()
                throw c
            } catch (t: Throwable) {
                Result.failure(t)
            } finally {
                watchdog.cancel()
            }
            if (breakdown !== st) return@launch // stale result for a closed / replaced sheet
            outcome.fold(
                onSuccess = { (e, th) ->
                    st.status = AiStatus.Ready(e)
                    st.thread = th
                },
                onFailure = { t ->
                    val ae = t as? AiException
                    st.status = when {
                        timedOut -> AiStatus.Error(AiErrorCode.TIMEOUT, "请求超时 Request timed out after ${deadlineMs / 1000} s")
                        ae?.code == AiErrorCode.CANCELLED -> return@fold
                        ae != null -> AiStatus.Error(ae.code, ae.message ?: ae.code.name)
                        else -> AiStatus.Error(AiErrorCode.NETWORK, "${t.javaClass.simpleName}: ${t.message}")
                    }
                },
            )
        }
    }

    fun regenerate() = explain(force = true)

    /** Tap on a character of the sentence header (0 ms, local). */
    fun selectChar(i: Int) {
        val st = breakdown ?: return
        st.selectedChunkId = null
        st.focusCharIndex = i
        st.focusFromSentence++
    }

    /**
     * Tap on a chunk chip / card. [scrollToCard] (chunk strip in the summary) also asks the detail
     * pane to scroll the matching card into view; tapping the card itself leaves the list alone.
     */
    fun selectChunk(chunk: SemanticChunk, scrollToCard: Boolean = false) {
        val st = breakdown ?: return
        st.selectedChunkId = chunk.id
        st.focusCharIndex = if (chunk.span.isEmpty) null else chunk.span.start
        if (scrollToCard) st.focusFromSentence++
    }

    // ------------------------------------------------------------------------------- follow-up

    /** Asks a follow-up question in the current persona's voice. Failed questions never enter the history. */
    fun askFollowUp(question: String) {
        val st = breakdown ?: return
        val expl = st.explanation ?: return
        val q = question.trim()
        if (q.isEmpty() || st.followUpPending) return
        if (!isConfigured) {
            st.followUpError = "追问需要先配置 AI 服务（设置 → AI 学习）"
            st.failedQuestion = q
            return
        }
        if (expl.model == OfflineExplainer.MODEL) {
            st.followUpError = "离线分段不支持追问，请先生成 AI 解说"
            st.failedQuestion = q
            return
        }
        st.pendingQuestion = q
        st.followUpError = null
        st.failedQuestion = null
        val history = st.thread
        val p = persona
        val token = CancelToken()
        followToken = token
        val deadlineMs = settings().ai.timeoutMs.toLong() + 5_000L
        followJob = scope.launch {
            var timedOut = false
            val watchdog = launch {
                delay(deadlineMs)
                timedOut = true
                token.cancel()
            }
            val outcome: Result<FollowUpMessage> = try {
                withContext(io) { Result.success(client.askFollowUp(expl, history, q, p, cache, token)) }
            } catch (c: CancellationException) {
                token.cancel()
                throw c
            } catch (t: Throwable) {
                Result.failure(t)
            } finally {
                watchdog.cancel()
            }
            if (breakdown !== st) return@launch
            st.pendingQuestion = null
            outcome.fold(
                onSuccess = { answer ->
                    st.thread = history + FollowUpMessage("u" + answer.id, FollowUpMessage.ROLE_USER, q, answer.createdAt) + answer
                },
                onFailure = { t ->
                    val ae = t as? AiException
                    if (ae?.code == AiErrorCode.CANCELLED && !timedOut) return@fold
                    st.followUpError = if (timedOut) "请求超时 Request timed out" else errorText(ae?.code ?: AiErrorCode.NETWORK) + (ae?.message?.let { "：$it" } ?: "")
                    st.failedQuestion = q
                },
            )
        }
    }

    // ------------------------------------------------------------------------------- settings helpers

    /** Round-trips a test prompt with [draft] (unsaved edits allowed), or the current settings when null. */
    fun testConnection(draft: AiProviderSettings? = null) {
        val target = draft ?: settings().ai
        if (testing) return
        testing = true
        testResult = null
        scope.launch {
            val r = try {
                withContext(io) { testClientFactory { target }.testConnection() }
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                AiConnectionTestResult(false, target.model, 0, "${t.javaClass.simpleName}: ${t.message}")
            }
            testResult = r
            testing = false
        }
    }

    /** Reloads [cacheStats] on [io]. */
    fun refreshCacheStats() {
        scope.launch { cacheStats = withContext(io) { runCatching { cache.stats() }.getOrNull() } }
    }

    /** Clears the explanation cache on [io]; [onDone] receives the number of removed files (Main). */
    fun clearCache(onDone: (Int) -> Unit = {}) {
        scope.launch {
            val n = withContext(io) { runCatching { cache.clear() }.getOrDefault(0) }
            cacheStats = withContext(io) { runCatching { cache.stats() }.getOrNull() }
            onDone(n)
        }
    }

    companion object {
        /** zh-CN text for an error code (AI sheet / follow-up errors). */
        fun errorText(code: AiErrorCode): String = when (code) {
            AiErrorCode.NOT_CONFIGURED -> "尚未配置 AI 服务 (Gemini / Vertex AI)"
            AiErrorCode.AUTH -> "密钥无效或未授权"
            AiErrorCode.QUOTA -> "配额不足 / 请求过于频繁"
            AiErrorCode.TIMEOUT -> "请求超时"
            AiErrorCode.NETWORK -> "网络错误：请检查网络或自定义服务器地址"
            AiErrorCode.INVALID_ARGUMENT -> "请求参数无效：检查模型名 / 服务器地址 / 请求头"
            AiErrorCode.NOT_FOUND -> "模型不存在：检查模型名"
            AiErrorCode.SAFETY -> "内容被安全策略拦截"
            AiErrorCode.INVALID_RESPONSE -> "模型返回了无法解析的内容"
            AiErrorCode.CANCELLED -> "已取消"
        }
    }
}
