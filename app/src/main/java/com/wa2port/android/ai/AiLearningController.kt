package com.wa2port.android.ai

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import com.wa2port.android.data.Settings
import com.wa2port.android.game.GameSession
import com.wa2port.android.game.Overlay
import com.wa2port.android.ui.sceneLabel
import com.wa2port.core.ai.AiConnectionTestResult
import com.wa2port.core.ai.AiEndpoints
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
import com.wa2port.core.ai.GrammarPoint
import com.wa2port.core.ai.JpSentence
import com.wa2port.core.ai.NotebookSentenceEntry
import com.wa2port.core.ai.NotebookStore
import com.wa2port.core.ai.OfflineExplainer
import com.wa2port.core.ai.SemanticChunk
import com.wa2port.core.ai.SentenceExplanation
import com.wa2port.core.ai.SpanAlign
import com.wa2port.core.ai.StarredGrammar
import com.wa2port.core.ai.StarredVocab
import com.wa2port.core.ai.VocabEntry
import com.wa2port.core.ai.formatContextLine
import com.wa2port.core.engine.DialogueLine
import com.wa2port.core.engine.LineRef
import com.wa2port.core.engine.VoiceRef
import com.wa2port.core.text.BilingualTextDecoder
import com.wa2port.core.text.TextLang
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

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
    val returnToNotebook: Boolean = false,
) {
    /** Backlog reference of the open line (null for the hand-written sample). */
    val cursorRef: LineRef? = line?.ref
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
 * Main), single-flight deduplication + study-burst prefetching, in-sheet `◀ / ▶` line stepping,
 * the explanation cache, connection tests and the sample / offline previews.
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
    private val prefetchDebounceMs: Long = PREFETCH_DEBOUNCE_MS,
) {
    val cache = ExplainCache(cacheDir)
    /** Independent learner notebook in `filesDir/notebook/` (never wiped by [clearCache]). */
    val notebook = NotebookStore(
        if (cacheDir.name == "ai_cache" && cacheDir.parentFile != null) File(cacheDir.parentFile, "notebook") else File(cacheDir, "notebook"),
    )
    private val client = clientFactory { settings().ai }
    private val testClientFactory = clientFactory

    var breakdown by mutableStateOf<AiBreakdownState?>(null)
        internal set
    /** True while the full-screen `📒 学习记录 Notebook` overlay is open. */
    var notebookOpen by mutableStateOf(false)
    /** Bumped on every notebook / star mutation so Compose observers re-read [notebook]. */
    var notebookRevision by mutableIntStateOf(0)
        private set

    init {
        scope.launch {
            withContext(io) { runCatching { notebook.importFromCacheIfEmpty(cacheDir) } }
            notebookRevision++
        }
    }
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

    // --- Single-flight & study-burst prefetch (#2)
    /** Active `explainSentence` calls keyed by [ExplainCache.key], joined by both prefetch and [explain]. */
    val inFlight = ConcurrentHashMap<String, Deferred<SentenceExplanation>>()
    /** Remaining unread lines in the current study burst (reset to [STUDY_BURST_LINES] on manual [open]). */
    var remainingBurstLines by mutableIntStateOf(0)
        internal set
    /** Session counter: background prefetches started in this session. */
    var prefetchedCount by mutableIntStateOf(0)
        private set
    /** Session counter: manual opens that joined or hit a prefetched explanation. */
    var prefetchHitCount by mutableIntStateOf(0)
        private set
    /** Set by [openNext] when stepping past the latest backlog line so the next script line re-opens AI. */
    var pendingReopenOnNextLine: Boolean = false

    private val prefetchedKeys: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val hitCountedKeys: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private var dailyEpochDay: Long = -1L
    private var dailyCount: Int = 0

    private var activeSession: GameSession? = null
    private var observedSession: GameSession? = null
    private var sessionObserverJob: Job? = null
    private var lastOpenedRef: LineRef? = null
    private var lastSeenLineRef: LineRef? = null

    private var explainJob: Job? = null
    private var explainToken: CancelToken? = null
    private var followJob: Job? = null
    private var followToken: CancelToken? = null
    private var queuedPrefetchJob: Job? = null
    private var queuedPrefetchToken: CancelToken? = null
    private var activePrefetchJob: Job? = null

    // ------------------------------------------------------------------------------- request builder

    /**
     * Pure request builder shared by [open] and background prefetch so `ExplainCache.key(prefetchReq)`
     * and `ExplainCache.key(openReq)` are guaranteed identical for the same [line] and model.
     */
    fun buildRequest(
        session: GameSession,
        line: DialogueLine,
        focusCharIndex: Int? = null,
        persona: AiPersonaSettings = this.persona,
        forceRefresh: Boolean = false,
    ): ExplainRequest = Companion.buildRequest(
        decoder = session.engine.decoder,
        scene = session.engine.scene,
        line = line,
        contextLines = runCatching { session.contextBefore(line, 3) }.getOrDefault(emptyList()),
        focusCharIndex = focusCharIndex,
        persona = persona,
        forceRefresh = forceRefresh,
    )

    // ------------------------------------------------------------------------------- open / close

    /**
     * Opens the breakdown for [line] ([focusCharIndex] = tapped character, if any). The sheet stacks
     * above the current overlay ([returnTo], restored on close); from the plain game view it sets
     * `Overlay.AI` so the engine pauses. Resets the 10-line study-burst window and starts [explain].
     */
    fun open(session: GameSession, line: DialogueLine, focusCharIndex: Int?, returnTo: Overlay = session.overlay) {
        val decoder = session.engine.decoder
        val sentence = JpSentence.fromRich(line.jp, decoder)
        if (sentence.text.isBlank()) {
            session.toast = "这一行没有日文 No Japanese text"
            return
        }
        val req = buildRequest(session, line, focusCharIndex, persona, forceRefresh = false)
        cancelRequests()
        activeSession = session
        ensureSessionObserver(session)
        lastOpenedRef = line.ref
        lastSeenLineRef = line.ref
        remainingBurstLines = STUDY_BURST_LINES
        val back = if (returnTo == Overlay.AI) breakdown?.returnTo ?: Overlay.NONE else returnTo
        val st = AiBreakdownState(
            line = line,
            sentence = sentence,
            speakerJp = req.speakerJp,
            speakerCn = req.speakerCn,
            officialCn = req.officialCnTranslation,
            contextBefore = req.contextBefore,
            sceneHint = req.sceneHint ?: "WHITE ALBUM 2 · ${sceneLabel(session.engine.scene)}",
            voice = line.ref.voice,
            returnTo = back,
        )
        st.focusCharIndex = req.focusCharIndex
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
    fun close(session: GameSession? = activeSession) {
        cancelRequests()
        val st = breakdown
        breakdown = null
        if (st?.returnToNotebook == true) {
            notebookOpen = true
            return
        }
        if (session != null && session.overlay == Overlay.AI) session.overlay = st?.returnTo?.takeIf { it != Overlay.AI } ?: Overlay.NONE
    }

    // ------------------------------------------------------------------------------- notebook & stars (#9)

    /** Opens the full-screen `📒 学习记录 Notebook` overlay. */
    fun openNotebook(session: GameSession? = activeSession) {
        if (session != null) activeSession = session
        notebookRevision++
        notebookOpen = true
    }

    /** Closes the `📒 学习记录 Notebook` overlay. */
    fun closeNotebook(session: GameSession? = activeSession) {
        notebookOpen = false
        val s = session ?: activeSession
        if (s != null && breakdown == null && s.overlay == Overlay.AI) {
            s.overlay = Overlay.NONE
        }
    }

    /**
     * Opens a previously recorded sentence from [notebook] directly into the AI breakdown sheet
     * using its persisted [SentenceExplanation] and Q&A thread (no network needed).
     */
    fun openFromNotebook(entry: NotebookSentenceEntry, session: GameSession? = activeSession): Boolean {
        val expl = notebook.loadExplanation(entry.explanationId) ?: cache.get(entry.explanationId) ?: return false
        val s = session ?: activeSession
        val back = breakdown?.returnTo ?: s?.overlay?.takeIf { it != Overlay.AI } ?: Overlay.NONE
        cancelRequests()
        val st = AiBreakdownState(
            line = null,
            sentence = JpSentence.ofText(expl.sentence),
            speakerJp = expl.speakerJp ?: entry.speakerJp,
            speakerCn = expl.speakerCn ?: entry.speakerCn,
            officialCn = expl.officialCnTranslation ?: entry.officialCn,
            contextBefore = emptyList(),
            sceneHint = if (entry.scene > 0) "WHITE ALBUM 2 · ${sceneLabel(entry.scene.toString())}" else "WHITE ALBUM 2 · 笔记本",
            voice = entry.voiceRef,
            returnTo = back,
            returnToNotebook = true,
        )
        st.status = AiStatus.Ready(expl)
        st.thread = notebook.loadThread(entry.explanationId).ifEmpty { runCatching { cache.thread(entry.explanationId) }.getOrDefault(emptyList()) }
        notebookOpen = false
        breakdown = st
        if (s != null && back == Overlay.NONE) s.overlay = Overlay.AI
        return true
    }

    /** Replays [voiceRef] through the active [GameSession]'s audio player when available. */
    fun replayVoice(voiceRef: VoiceRef?, session: GameSession? = activeSession): Boolean {
        val v = voiceRef ?: return false
        val s = session ?: activeSession ?: return false
        s.audio.replayVoice(v)
        return true
    }

    private fun ensureRecordedForStar(st: AiBreakdownState?, expl: SentenceExplanation?) {
        if (expl == null || expl.model == OfflineExplainer.MODEL) return
        if (notebook.sentences().none { it.explanationId == expl.id }) {
            runCatching { notebook.recordSentence(st?.cursorRef, expl, now()) }
        }
    }

    fun isSentenceStarred(sentenceId: String): Boolean {
        @Suppress("UNUSED_EXPRESSION")
        notebookRevision
        return notebook.isSentenceStarred(sentenceId)
    }

    fun toggleSentenceStar(sentenceId: String? = breakdown?.explanation?.id): Boolean {
        val id = sentenceId ?: return false
        val st = breakdown
        if (st?.explanation?.id == id) ensureRecordedForStar(st, st.explanation)
        val res = notebook.toggleSentenceStar(id)
        notebookRevision++
        return res
    }

    fun isVocabStarred(vocab: VocabEntry): Boolean {
        @Suppress("UNUSED_EXPRESSION")
        notebookRevision
        return notebook.isVocabStarred(vocab.lemma.ifBlank { vocab.surface })
    }

    fun toggleVocabStar(vocab: VocabEntry, expl: SentenceExplanation? = breakdown?.explanation): Boolean {
        val e = expl ?: return false
        val st = breakdown
        if (st?.explanation?.id == e.id) ensureRecordedForStar(st, e)
        val res = notebook.toggleVocabStar(vocab, e, st?.cursorRef, now())
        notebookRevision++
        return res
    }

    fun toggleVocabStar(item: StarredVocab): Boolean {
        val res = notebook.toggleVocabStar(item, now())
        notebookRevision++
        return res
    }

    fun isGrammarStarred(grammar: GrammarPoint): Boolean {
        @Suppress("UNUSED_EXPRESSION")
        notebookRevision
        return notebook.isGrammarStarred(grammar.pattern)
    }

    fun toggleGrammarStar(grammar: GrammarPoint, expl: SentenceExplanation? = breakdown?.explanation): Boolean {
        val e = expl ?: return false
        val st = breakdown
        if (st?.explanation?.id == e.id) ensureRecordedForStar(st, e)
        val res = notebook.toggleGrammarStar(grammar, e, st?.cursorRef, now())
        notebookRevision++
        return res
    }

    fun toggleGrammarStar(item: StarredGrammar): Boolean {
        val res = notebook.toggleGrammarStar(item, now())
        notebookRevision++
        return res
    }

    // ------------------------------------------------------------------------------- ◀ / ▶ line navigation (A4)

    /** True when the open breakdown can step backward in [session]'s backlog. */
    fun canPrev(session: GameSession? = activeSession): Boolean {
        val s = session ?: return false
        val st = breakdown ?: return false
        if (st.isSample) return false
        val ref = st.cursorRef ?: return false
        return s.backlog().lastIndexOf(ref) > 0
    }

    /** True when the open breakdown can step forward in [session]'s backlog or advance to the next script line. */
    fun canNext(session: GameSession? = activeSession): Boolean {
        val s = session ?: return false
        val st = breakdown ?: return false
        if (st.isSample) return false
        val ref = st.cursorRef ?: return false
        val bl = s.backlog()
        val idx = bl.lastIndexOf(ref)
        if (idx in 0 until bl.lastIndex) return true
        return s.line != null && s.choices.isEmpty() && s.movie == null && !s.ended
    }

    /** Steps to the previous line in [session]'s backlog and opens its breakdown. */
    fun openPrev(session: GameSession? = activeSession): Boolean {
        val s = session ?: return false
        val st = breakdown ?: return false
        if (st.isSample) return false
        val ref = st.cursorRef ?: return false
        val bl = s.backlog()
        val idx = bl.lastIndexOf(ref)
        if (idx <= 0) return false
        val prevLine = runCatching { s.resolve(bl[idx - 1]) }.getOrNull() ?: return false
        open(s, prevLine, focusCharIndex = null, returnTo = st.returnTo)
        return true
    }

    /**
     * Steps to the next line in [session]'s backlog, or — when already at the latest backlog entry —
     * sets [pendingReopenOnNextLine], closes the sheet, advances the script via [onAdvanceToNextLine]
     * (default: completes any reveal and calls [GameSession.onTap]), and re-opens AI on the new line.
     */
    fun openNext(session: GameSession? = activeSession, onAdvanceToNextLine: (() -> Unit)? = null): Boolean {
        val s = session ?: return false
        val st = breakdown ?: return false
        if (st.isSample) return false
        val ref = st.cursorRef ?: return false
        val bl = s.backlog()
        val idx = bl.lastIndexOf(ref)
        if (idx in 0 until bl.lastIndex) {
            val nextLine = runCatching { s.resolve(bl[idx + 1]) }.getOrNull() ?: return false
            open(s, nextLine, focusCharIndex = null, returnTo = st.returnTo)
            return true
        }
        if (s.line == null || s.choices.isEmpty().not() || s.movie != null || s.ended) return false
        val prevRef = s.line?.ref
        pendingReopenOnNextLine = true
        lastOpenedRef = prevRef
        cancelRequests()
        breakdown = null
        s.overlay = Overlay.NONE
        s.windowHidden = false
        s.skipToggle = false
        s.autoMode = false
        if (onAdvanceToNextLine != null) {
            onAdvanceToNextLine()
        } else {
            var guard = 0
            while (!s.revealDone && guard++ < 16) s.onTap()
            s.onTap()
        }
        // Fallback frame watcher in case the host screen does not call onLineChanged directly.
        scope.launch {
            var ticks = 0
            while (pendingReopenOnNextLine && ticks++ < 120) {
                if (s.choices.isNotEmpty() || s.movie != null || s.ended) {
                    pendingReopenOnNextLine = false
                    break
                }
                val cur = s.line
                if (cur != null && cur.ref != prevRef) {
                    onLineChanged(s, cur)
                    break
                }
                delay(16)
            }
            pendingReopenOnNextLine = false
        }
        return true
    }

    // ------------------------------------------------------------------------------- prefetch & line hook (#2)

    private fun ensureSessionObserver(session: GameSession) {
        if (observedSession === session && sessionObserverJob?.isActive == true) return
        observedSession = session
        sessionObserverJob?.cancel()
        sessionObserverJob = scope.launch {
            snapshotFlow { session.line }.collectLatest { line ->
                onLineChanged(session, line)
            }
        }
    }

    /**
     * Called when the game's current dialogue line changes:
     * 1. Fulfils [pendingReopenOnNextLine] (`▶` on the latest line) by completing the typewriter and
     *    re-opening the AI sheet on the new line (cancelled if choices / movie / end intervened).
     * 2. Within the 10-line study-burst window after a manual [open], schedules a debounced (600 ms)
     *    size-1 latest-wins background prefetch for unread lines when `aiPrefetchEnabled` is on.
     */
    fun onLineChanged(session: GameSession, line: DialogueLine?) {
        activeSession = session
        if (pendingReopenOnNextLine) {
            if (session.choices.isNotEmpty() || session.movie != null || session.ended || line == null) {
                pendingReopenOnNextLine = false
            } else if (line.ref != lastOpenedRef) {
                pendingReopenOnNextLine = false
                var guard = 0
                while (!session.revealDone && guard++ < 16) session.onTap()
                open(session, line, focusCharIndex = null, returnTo = Overlay.NONE)
                return
            }
        }
        if (line == null) return
        if (line.ref == lastSeenLineRef) return
        lastSeenLineRef = line.ref

        if (remainingBurstLines <= 0) return
        remainingBurstLines--

        val s = settings()
        if (!s.aiPrefetchEnabled || !s.ai.isConfigured || !s.ai.cacheEnabled) return
        if (session.skipping || session.autoMode || session.inReplayMode || session.movie != null || session.ended) return
        if (line.read) return
        if (dailyPrefetchedCount() >= s.aiPrefetchDailyLimit) return

        val req = buildRequest(session, line, focusCharIndex = null, persona = persona, forceRefresh = false)
        if (req.sentence.isBlank()) return
        enqueuePrefetch(req) {
            !session.skipping && !session.autoMode && !session.inReplayMode && session.movie == null && !session.ended
        }
    }

    /**
     * Enqueues [req] into the size-1 latest-wins prefetch queue:
     * - Any previously queued (debouncing or waiting) item is cancelled and replaced immediately.
     * - The 1 currently running HTTP request ([activePrefetchJob], if any) is allowed to finish into
     *   [cache] so already-generated tokens are never wasted.
     */
    fun enqueuePrefetch(req: ExplainRequest, stillValid: () -> Boolean = { true }) {
        queuedPrefetchToken?.cancel()
        queuedPrefetchJob?.cancel()
        val qToken = CancelToken()
        queuedPrefetchToken = qToken
        queuedPrefetchJob = scope.launch {
            if (prefetchDebounceMs > 0) delay(prefetchDebounceMs)
            // Wait for the single active prefetch (if any) to finish into cache before starting the latest queued item.
            activePrefetchJob?.join()
            if (qToken.isCancelled || !stillValid()) return@launch
            val s = settings()
            if (!s.aiPrefetchEnabled || !s.ai.isConfigured || !s.ai.cacheEnabled) return@launch
            if (dailyPrefetchedCount() >= s.aiPrefetchDailyLimit) return@launch
            val key = ExplainCache.key(req, AiEndpoints.sanitizeModel(s.ai.model))
            if (inFlight.containsKey(key)) return@launch
            val cached = withContext(io) { runCatching { cache.get(key) }.getOrNull() }
            if (cached != null || qToken.isCancelled || !stillValid()) return@launch

            val activeToken = CancelToken()
            prefetchedKeys += key
            incrementPrefetchedCount()
            activePrefetchJob = scope.launch {
                val (def, _) = acquireOrStartExplain(key, req, useSingleFlight = true, token = activeToken)
                runCatching { def.await() }
            }
        }
    }

    private fun dailyPrefetchedCount(): Int {
        val day = now() / 86_400_000L
        if (day != dailyEpochDay) {
            dailyEpochDay = day
            dailyCount = 0
        }
        return dailyCount
    }

    private fun incrementPrefetchedCount() {
        val day = now() / 86_400_000L
        if (day != dailyEpochDay) {
            dailyEpochDay = day
            dailyCount = 0
        }
        dailyCount++
        prefetchedCount++
    }

    private fun recordPrefetchHitIfAny(key: String, joinedExisting: Boolean) {
        if ((joinedExisting || key in prefetchedKeys) && hitCountedKeys.add(key)) {
            prefetchHitCount++
        }
    }

    /**
     * Single-flight coordinator: returns an existing active [Deferred] in [inFlight] for [key] if
     * present (`joined = true`), or starts a new call on [io] and registers it in [inFlight]
     * (`joined = false`).
     */
    private fun acquireOrStartExplain(
        key: String,
        req: ExplainRequest,
        useSingleFlight: Boolean,
        token: CancelToken,
    ): Pair<Deferred<SentenceExplanation>, Boolean> {
        if (!useSingleFlight) {
            return scope.async(io) { client.explainSentence(req, cache, token) } to false
        }
        synchronized(inFlight) {
            inFlight[key]?.takeIf { it.isActive }?.let { return it to true }
            lateinit var def: Deferred<SentenceExplanation>
            def = scope.async(io) {
                try {
                    client.explainSentence(req, cache, token)
                } finally {
                    synchronized(inFlight) { inFlight.remove(key, def) }
                }
            }
            inFlight[key] = def
            return def to false
        }
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
        val key = ExplainCache.key(req, AiEndpoints.sanitizeModel(ai.model))
        val useSingleFlight = !force && ai.cacheEnabled
        // Progressive loading: the local skeleton (< 1 ms, no I/O) fills the sheet immediately.
        st.status = AiStatus.Loading(OfflineExplainer.build(req, now()))
        // readTimeout is per read, not a total deadline: a watchdog cancels the whole call. One
        // explain may do a token exchange + the call + one nudge retry, hence 2 × timeout + slack
        // (a "thinking" HTTP 400 that triggers the no-thinkingConfig retry comes back immediately).
        val deadlineMs = 2L * ai.timeoutMs + 10_000L
        val (def, joined) = acquireOrStartExplain(key, req, useSingleFlight, token)
        if (useSingleFlight) recordPrefetchHitIfAny(key, joined)
        explainJob = scope.launch {
            var timedOut = false
            val watchdog = launch {
                delay(deadlineMs)
                timedOut = true
                token.cancel()
                if (!joined) def.cancel()
            }
            val outcome: Result<Pair<SentenceExplanation, List<FollowUpMessage>>> = try {
                withContext(io) {
                    val e = def.await()
                    runCatching { notebook.recordSentence(st.cursorRef, e, now()) }
                    val th = runCatching { cache.thread(e.id) }.getOrDefault(emptyList())
                        .ifEmpty { runCatching { notebook.loadThread(e.id) }.getOrDefault(emptyList()) }
                    if (th.isNotEmpty()) runCatching { notebook.saveThread(e.id, th) }
                    Result.success(e to th)
                }
            } catch (c: CancellationException) {
                if (!joined) token.cancel()
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
                    notebookRevision++
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
                    val newThread = history + FollowUpMessage("u" + answer.id, FollowUpMessage.ROLE_USER, q, answer.createdAt) + answer
                    st.thread = newThread
                    scope.launch {
                        withContext(io) {
                            runCatching {
                                ensureRecordedForStar(st, expl)
                                notebook.saveThread(expl.id, newThread)
                            }
                        }
                        notebookRevision++
                    }
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
        /** Number of unread lines after a manual [open] during which background prefetch is active. */
        const val STUDY_BURST_LINES = 10

        /** Default debounce before a newly displayed line enters the background prefetch slot. */
        const val PREFETCH_DEBOUNCE_MS = 600L

        /**
         * Pure request builder shared by [open] and background prefetch so their [ExplainCache.key]
         * values are guaranteed identical for the same [line] and model.
         */
        fun buildRequest(
            decoder: BilingualTextDecoder,
            scene: String,
            line: DialogueLine,
            contextLines: List<DialogueLine>,
            focusCharIndex: Int?,
            persona: AiPersonaSettings,
            forceRefresh: Boolean = false,
        ): ExplainRequest {
            val sentence = JpSentence.fromRich(line.jp, decoder)
            val context = contextLines.map { l ->
                formatContextLine(JpSentence.speaker(l.speakerJp, decoder, TextLang.JP), JpSentence.fromRich(l.jp, decoder).text)
            }.filter { it.isNotBlank() }
            val clampedFocus = focusCharIndex?.takeIf { sentence.text.isNotEmpty() }?.coerceIn(0, sentence.text.length - 1)
            return ExplainRequest(
                sentence = sentence.text,
                contextBefore = context,
                speakerJp = JpSentence.speaker(line.speakerJp, decoder, TextLang.JP),
                speakerCn = JpSentence.speaker(line.speakerCn, decoder, TextLang.CN),
                officialCnTranslation = JpSentence.officialCn(line.cn, decoder),
                sceneHint = "WHITE ALBUM 2 · ${sceneLabel(scene)}",
                focusCharIndex = clampedFocus,
                scriptRuby = sentence.scriptRuby,
                persona = persona,
                force = forceRefresh,
            )
        }

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
