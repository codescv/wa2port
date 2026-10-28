package com.wa2port.android.game

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.wa2port.android.bridge.AudioSink
import com.wa2port.android.bridge.RenderBackend
import com.wa2port.android.bridge.StageHandle
import com.wa2port.android.data.SaveStore
import com.wa2port.android.data.Settings
import com.wa2port.core.engine.ChoiceOption
import com.wa2port.core.engine.DialogueLine
import com.wa2port.core.engine.EngineEvent
import com.wa2port.core.engine.LineRef
import com.wa2port.core.engine.SaveData
import com.wa2port.core.engine.SkipMode
import com.wa2port.core.engine.StageState
import com.wa2port.core.engine.Wa2Engine
import com.wa2port.core.text.TextLang
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Overlays drawn above the game (the engine is paused while one is open). AI = the AI breakdown sheet. */
enum class Overlay { NONE, MENU, BACKLOG, SAVE, LOAD, CONFIG, AI }

/**
 * A short-lived 「撤销 Undo」 offer shown at the top of the game screen after a load / quick save.
 * [token] distinguishes successive offers (the screen expires each one after [UNDO_WINDOW_MS]).
 */
class UndoAction(val message: String, val token: Long, val perform: suspend () -> Unit)

/**
 * Drives one play session: runs [Wa2Engine] from the frame loop ([tick]), owns typewriter/novel-page
 * state, AUTO/SKIP, overlays, quick save/load, and exposes everything as Compose state.
 * Must only be used from the main thread (the engine is single-threaded).
 *
 * [ioScope] runs background save writes (autosave); it should outlive the session (e.g. the
 * ViewModel scope) so an autosave started right before leaving the game still completes.
 */
class GameSession(
    val engine: Wa2Engine,
    val backend: RenderBackend,
    private val saves: SaveStore,
    private val systemFile: File,
    private val ioScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
    private val settings: () -> Settings,
) {
    val audio: AudioSink = backend.createAudio()
    var stageHandle: StageHandle? = null

    var stage by mutableStateOf(engine.stage)
        private set
    var line by mutableStateOf<DialogueLine?>(null)
        private set
    var choices by mutableStateOf<List<ChoiceOption>>(emptyList())
        private set

    /** NOVEL mode page: lines accumulated since the last page break (last = current line). */
    var page by mutableStateOf<List<DialogueLine>>(emptyList())
        private set

    var plan by mutableStateOf<RevealPlan?>(null)
        private set
    var segment by mutableIntStateOf(0)
        private set
    var visibleJp by mutableIntStateOf(0)
        private set
    var visibleCn by mutableIntStateOf(0)
        private set
    var segmentComplete by mutableStateOf(true)
        private set

    var autoMode by mutableStateOf(false)
    var skipToggle by mutableStateOf(false)
    var skipHeld by mutableStateOf(false)
        private set
    var windowHidden by mutableStateOf(false)
    var overlay by mutableStateOf(Overlay.NONE)
    var movie by mutableStateOf<String?>(null)
        private set
    var ended by mutableStateOf(false)
        private set
    var toast by mutableStateOf<String?>(null)
    /** Pending 「撤销 Undo」 offer (after an in-session load or a quick save), or null. */
    var undoAction by mutableStateOf<UndoAction?>(null)
    var paused = false

    private var lastLine: DialogueLine? = null
    /** Scene of the last autosave; a different [Wa2Engine.scene] in [sync] triggers a new one. */
    private var lastAutoSavedScene: String? = null
    private var undoSeq = 0L
    private var segProgressMs = 0f
    private var autoWaitMs = 0f
    private var pageBreakPending = false
    private var weSetSkip = false
    private var skipSuppressed = false

    val revealDone: Boolean get() = plan == null || (segmentComplete && segment >= (plan?.segments ?: 1) - 1)
    val skipping: Boolean get() = (skipHeld || skipToggle) && !skipSuppressed
    /** True while running a Scene Replay (`回想モード`, `WA2.exe` `ds:0x4be048 != 0`). */
    val inReplayMode: Boolean get() = engine.replayMode != 0

    // ------------------------------------------------------------------------- lifecycle

    fun startNew(scene: String): Boolean {
        resetUi()
        val ok = engine.start(scene)
        sync()
        return ok
    }

    fun startReplay(slotNumber: Int, fallbackScript: String? = null): Boolean {
        resetUi()
        val ok = engine.startReplay(slotNumber, fallbackScript)
        sync()
        return ok
    }

    fun loadData(data: SaveData) {
        resetUi()
        engine.load(data)
        // The loaded state is already on disk: no scene-change autosave right after a load.
        lastAutoSavedScene = engine.scene
        sync()
        completeLine()
    }

    private fun resetUi() {
        lastLine = null
        line = null
        page = emptyList()
        plan = null
        choices = emptyList()
        autoMode = false
        skipToggle = false
        windowHidden = false
        overlay = Overlay.NONE
        movie = null
        ended = false
        pageBreakPending = false
        undoAction = null
        engine.skipMode = SkipMode.OFF
        weSetSkip = false
    }

    fun dispose() {
        engine.replayMode = 0
        persistSystemBlocking()
        audio.release()
    }

    // ------------------------------------------------------------------------- frame loop

    /** One frame: input-derived modes → engine.update → events → UI state → typewriter. */
    fun tick(dtMs: Long) {
        if (paused || overlay != Overlay.NONE || movie != null || ended) return
        val s = settings()
        applySkip(s)
        runAuto(s, dtMs)
        engine.update(dtMs)
        if (weSetSkip && engine.skipMode == SkipMode.OFF) {
            // The engine cancelled skipping (choice / unread line before a choice / script request).
            weSetSkip = false
            skipToggle = false
            skipSuppressed = skipHeld
        }
        dispatchEvents()
        sync()
        advanceReveal(s, dtMs)
        // Read-only skip stops on an unread line: release the SKIP toggle so the button reflects it.
        if (skipping && !s.skipAll && engine.isWaitingForClick && line?.read == false) {
            skipToggle = false
            if (skipHeld) skipSuppressed = true
            applySkip(s)
        }
    }

    private fun applySkip(s: Settings) {
        if (skipping) {
            engine.skipMode = if (s.skipAll) SkipMode.ALL else SkipMode.READ_ONLY
            weSetSkip = true
        } else if (weSetSkip) {
            engine.skipMode = SkipMode.OFF
            weSetSkip = false
        }
    }

    private fun runAuto(s: Settings, dtMs: Long) {
        val ready = autoMode && !skipping && choices.isEmpty() && segmentComplete && !audio.isVoicePlaying &&
            (engine.isWaitingForClick || (plan != null && !revealDone))
        if (!ready) {
            autoWaitMs = 0f
            return
        }
        autoWaitMs += dtMs
        if (autoWaitMs >= s.autoDelayMs) {
            autoWaitMs = 0f
            proceed()
        }
    }

    private fun dispatchEvents() {
        for (e in engine.drainEvents()) {
            when (e) {
                is EngineEvent.PlayMovie -> movie = e.stem
                EngineEvent.GameEnded -> ended = true
                is EngineEvent.PlayVoice -> if (!skipping) audio.handle(e)
                else -> audio.handle(e)
            }
        }
    }

    private fun sync() {
        val st = engine.stage
        if (st != stage) stage = st
        val cl = engine.currentLine
        if (cl !== lastLine) {
            lastLine = cl
            onNewLine(cl)
        }
        val ch = engine.choices
        if (ch != choices) choices = ch
        // Autosave on every scene change (never in Scene Replay; needs a line so it is resumable).
        if (line != null && !inReplayMode && engine.scene != lastAutoSavedScene) {
            lastAutoSavedScene = engine.scene
            autoSave(captureThumb = true)
        }
    }

    private fun onNewLine(cl: DialogueLine?) {
        line = cl
        if (cl == null) {
            plan = null
            page = emptyList()
            pageBreakPending = false
            return
        }
        val p = RevealPlan(cl.jp, cl.cn)
        plan = p
        segment = 0
        segProgressMs = 0f
        segmentComplete = false
        visibleJp = 0
        visibleCn = 0
        page = if (pageBreakPending || page.isEmpty() || page.size >= MAX_PAGE_LINES) listOf(cl) else page + cl
        pageBreakPending = p.endsWithPageBreak
        if (skipping || settings().instantText) completeLine()
    }

    private fun advanceReveal(s: Settings, dtMs: Long) {
        val p = plan ?: return
        if (segmentComplete) return
        if (s.instantText || skipping) {
            completeSegment()
            return
        }
        segProgressMs += dtMs
        val dur = p.durationMs(segment, s.lang.showJp, s.lang.showCn, s.textSpeed)
        val frac = if (dur <= 0f) 1f else segProgressMs / dur
        visibleJp = p.visible(TextLang.JP, segment, frac)
        visibleCn = p.visible(TextLang.CN, segment, frac)
        if (frac >= 1f) completeSegment()
    }

    private fun completeSegment() {
        val p = plan ?: return
        segmentComplete = true
        visibleJp = p.visible(TextLang.JP, segment, 1f)
        visibleCn = p.visible(TextLang.CN, segment, 1f)
    }

    private fun completeLine() {
        val p = plan ?: return
        segment = p.segments - 1
        completeSegment()
    }

    // ------------------------------------------------------------------------- input

    /** Tap on the stage interior: restore window / stop skip / finish reveal / next segment / advance. */
    fun onTap() {
        when {
            overlay != Overlay.NONE -> {} // taps never advance the script under an overlay
            windowHidden -> windowHidden = false
            skipToggle -> skipToggle = false
            autoMode -> autoMode = false
            choices.isNotEmpty() -> {}
            else -> proceed()
        }
    }

    /** Finish the reveal, continue after `\k`, or advance the engine. */
    private fun proceed() {
        val p = plan
        when {
            p != null && !segmentComplete -> completeSegment()
            p != null && segment < p.segments - 1 -> {
                segment++
                segProgressMs = 0f
                segmentComplete = false
                if (settings().instantText) completeSegment()
            }
            else -> engine.advance()
        }
    }

    fun holdSkip(held: Boolean) {
        skipHeld = held
        if (!held) skipSuppressed = false
    }

    /** ADV 🔊 重听: replays the current line's voice without advancing. False when it has none. */
    fun replayCurrentVoice(): Boolean {
        val v = line?.ref?.voice ?: return false
        audio.replayVoice(v)
        return true
    }

    fun toggleSkip() {
        skipToggle = !skipToggle
        if (skipToggle) {
            skipSuppressed = false
            autoMode = false
        }
    }

    fun toggleAuto() {
        autoMode = !autoMode
        if (autoMode) skipToggle = false
    }

    fun choose(index: Int) {
        engine.choose(index)
        sync()
    }

    fun movieFinished() {
        if (movie == null) return
        movie = null
        engine.movieFinished()
    }

    /** Called by NOVEL mode when the page no longer fits: start a fresh page with the current line. */
    fun trimPage() {
        if (page.size > 1) page = listOf(page.last())
    }

    fun backlog(): List<LineRef> = engine.backlog.toList()

    fun resolve(ref: LineRef): DialogueLine = engine.resolve(ref)

    /**
     * Up to [n] backlog lines before [line] (oldest first), for AI context. The current line is the
     * last backlog entry, so lines opened from the backlog get their own preceding lines.
     */
    fun contextBefore(line: DialogueLine, n: Int = 3): List<DialogueLine> {
        val bl = engine.backlog
        val idx = bl.lastIndexOf(line.ref)
        val end = if (idx >= 0) idx else bl.size
        return bl.subList(maxOf(0, end - n), end).map(::resolve)
    }

    // ------------------------------------------------------------------------- save / load

    suspend fun saveTo(slot: Int, thumbnailB64: String? = null): Boolean {
        if (inReplayMode) return false
        val thumb = thumbnailB64 ?: SaveStore.encodeThumbnail(stageHandle?.captureThumbnail())
        val data = engine.save(thumb)
        val system = engine.system.toJson()
        return runCatching {
            saves.write(slot, data)
            writeSystem(system)
            true
        }.getOrDefault(false)
    }

    /**
     * Loads [slot] in-session. Unless this load *is* the undo ([isUndo]), the current state is first
     * written to [SaveStore.UNDO_SLOT] and a 「撤销 Undo」 offer ([undoAction]) brings it back via
     * [undoLoad] — which passes `isUndo = true`, so undoing never overwrites `undo.json` itself.
     */
    suspend fun loadFrom(slot: Int, isUndo: Boolean = false, undoMessage: String = "已读取存档 Loaded"): Boolean {
        if (inReplayMode) return false
        val data = saves.read(slot) ?: return false
        val backedUp = !isUndo && line != null && runCatching {
            saves.write(SaveStore.UNDO_SLOT, engine.save(null))
            true
        }.getOrDefault(false)
        loadData(data)
        if (backedUp) offerUndo(undoMessage) { undoLoad() }
        return true
    }

    /** Goes back to the state replaced by the last in-session load (`undo.json`). */
    suspend fun undoLoad(): Boolean {
        val ok = loadFrom(SaveStore.UNDO_SLOT, isUndo = true)
        toast = if (ok) "已撤销读档 Load undone" else "无法撤销 Nothing to undo"
        return ok
    }

    suspend fun quickSave() {
        if (inReplayMode) { toast = "回想模式下无法存档 Save disabled in replay"; return }
        // SaveStore backs the previous quick.json up to quick.prev.json before writing.
        if (saveTo(SaveStore.QUICK_SLOT)) {
            offerUndo("已快速存档 Quick saved") {
                saves.restoreQuickPrev()
                toast = "已撤销快速存档 Quick save undone"
            }
        } else {
            toast = "存档失败 Save failed"
        }
    }

    suspend fun quickLoad() {
        if (inReplayMode) { toast = "回想模式下无法读档 Load disabled in replay"; return }
        if (!loadFrom(SaveStore.QUICK_SLOT, undoMessage = "已快速读档 Quick loaded")) toast = "没有快速存档 No quick save"
        else if (undoAction == null) toast = "已快速读档 Quick loaded"
    }

    private fun offerUndo(message: String, perform: suspend () -> Unit) {
        toast = null
        val token = ++undoSeq
        undoAction = UndoAction(message, token) {
            // One shot: clear first so a double tap cannot run it twice.
            if (undoAction?.token == token) {
                undoAction = null
                perform()
            }
        }
    }

    /**
     * Writes the current state to [SaveStore.AUTO_SLOT] (`auto.json`) in the background. No-op in
     * Scene Replay, before the first line, while a movie plays and after the game ended. [captureThumb]
     * = false skips the stage capture (e.g. from `ON_PAUSE`, where the surface may already be going
     * away). Never called from the engine-failure path, so a broken state can never become 「继续」.
     */
    fun autoSave(captureThumb: Boolean = true): Job? {
        if (inReplayMode || line == null || ended || movie != null) return null
        val bitmap = if (captureThumb) runCatching { stageHandle?.captureThumbnail() }.getOrNull() else null
        val data = runCatching { engine.save(null) }.getOrNull() ?: return null
        return ioScope.launch {
            runCatching {
                val thumb = bitmap?.let { withContext(Dispatchers.Default) { SaveStore.encodeThumbnail(it) } }
                saves.write(SaveStore.AUTO_SLOT, if (thumb != null) data.copy(thumbnail = thumb) else data)
            }
        }
    }

    suspend fun persistSystem() = writeSystem(engine.system.toJson())

    private fun persistSystemBlocking() {
        runCatching { systemFile.writeText(engine.system.toJson()) }
    }

    private suspend fun writeSystem(json: String) = withContext(Dispatchers.IO) {
        runCatching {
            val tmp = File(systemFile.parentFile, systemFile.name + ".tmp")
            tmp.writeText(json)
            tmp.renameTo(systemFile)
        }
    }

    companion object {
        const val MAX_PAGE_LINES = 24
        /** How long a 「撤销 Undo」 offer stays on screen. */
        const val UNDO_WINDOW_MS = 4000L
    }
}
