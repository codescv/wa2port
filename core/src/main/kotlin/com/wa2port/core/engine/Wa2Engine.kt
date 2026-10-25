package com.wa2port.core.engine

import java.util.Locale
import com.wa2port.core.archive.Wa2Assets
import com.wa2port.core.script.CallOutcome
import com.wa2port.core.script.FuncSpec
import com.wa2port.core.script.LscrProgram
import com.wa2port.core.script.LscrVm
import com.wa2port.core.script.ScenarioStore
import com.wa2port.core.script.Scene
import com.wa2port.core.script.Value
import com.wa2port.core.script.VmHost
import com.wa2port.core.script.WaitState
import com.wa2port.core.text.BilingualTextDecoder
import com.wa2port.core.text.RichText
import com.wa2port.core.text.TextLang

/** A line ready for display: both languages plus speaker and voice. */
data class DialogueLine(
    val ref: LineRef,
    val jp: RichText,
    val cn: RichText?,
    val speakerJp: RichText?,
    val speakerCn: RichText?,
    val read: Boolean,
)

/** One choice option in both languages. */
data class ChoiceOption(val token: Int, val jp: RichText, val cn: RichText?)

/**
 * The WA2 engine: owns the LSCR VM and turns engine calls into [StageState], dialogue, choices and
 * [EngineEvent]s. Not thread-safe: drive it from one game-loop thread.
 *
 * Typical host loop: `update(dtMs)` every frame → draw [stage] + [currentLine] / [choices] →
 * play [drainEvents]; on tap `advance()`, on choice `choose(i)`, after a movie `movieFinished()`.
 */
class Wa2Engine(
    val assets: Wa2Assets,
    val store: ScenarioStore = ScenarioStore(assets),
    val system: SystemData = SystemData(),
    val decoder: BilingualTextDecoder = BilingualTextDecoder(),
) : VmHost {
    val vm = LscrVm(this, store.functions)

    override var nowMs: Long = 0
        private set

    var stage = StageState()
        private set

    /** The line currently in the message window (null when the window is cleared). */
    var currentLine: DialogueLine? = null
        private set

    var choices: List<ChoiceOption> = emptyList()
        private set

    val backlog = ArrayDeque<LineRef>()
    var skipMode: SkipMode = SkipMode.OFF
    /** When true, skipping also passes unread lines (the "skip-all" setting). */
    var skipUnread: Boolean = false

    private val events = ArrayList<EngineEvent>()
    private var voiceScene = 0
    private var speakerToken = -1
    private var pendingVoice: VoiceRef? = null
    private val pendingChoiceTokens = ArrayList<Int>()
    private var timerBase = 0L
    private var clickLatch = false
    private var audio = AudioState()
    private var spriteOrder = 0

    /** Diagnostics: engine opcodes that hit the generic no-op path. */
    val unhandledOps = HashMap<Int, Int>()
    val missingAssets = LinkedHashSet<String>()
    val logLines = ArrayList<String>()

    init {
        store.globalVars.forEachIndexed { i, g -> if (i < vm.globals.size) vm.globals[i] = Value.I(g.init) }
    }

    val wait: WaitState? get() = vm.wait
    val scene: String get() = vm.scriptName
    val isWaitingForClick: Boolean get() = vm.wait == WaitState.Click
    val isEnded: Boolean get() = vm.isEnded
    val skipping: Boolean get() = skipMode != SkipMode.OFF

    /** True when the current message will be skipped automatically on the next [update]. */
    val willAutoAdvance: Boolean get() = vm.wait == WaitState.Click && skipping && canSkipCurrent()

    fun currentScene(): Scene? = store.scene(scene)

    // ------------------------------------------------------------------------------- host API

    /** Starts a new game at [sceneName] (`1001` = IC prologue, `2001` = CC). */
    fun start(sceneName: String): Boolean {
        stage = StageState()
        currentLine = null
        choices = emptyList()
        backlog.clear()
        speakerToken = -1
        audio = AudioState()
        store.globalVars.forEachIndexed { i, g -> if (i < vm.globals.size) vm.globals[i] = Value.I(g.init) }
        vm.locals.clear()
        val ok = vm.start(sceneName)
        if (ok) events += EngineEvent.SceneChanged(sceneName)
        return ok
    }

    /** Advances the clock by [deltaMs] and runs the VM until it blocks. */
    fun update(deltaMs: Long): WaitState? {
        nowMs += deltaMs.coerceAtLeast(0)
        when (val w = vm.wait) {
            WaitState.Frame -> { vm.resume(); clickLatchFrame() }
            is WaitState.Until -> if (nowMs >= w.untilMs || (skipping && w.skippable)) vm.resume()
            WaitState.Click -> if (skipping && canSkipCurrent()) advanceInternal()
            else -> {}
        }
        if (vm.wait == null) {
            val before = scene
            vm.run()
            if (scene != before) events += EngineEvent.SceneChanged(scene)
            if (vm.isEnded) events += EngineEvent.GameEnded
        }
        return vm.wait
    }

    private fun clickLatchFrame() {
        // GetClick() sees a click for exactly one frame.
        clickLatch = false
    }

    /** Player tap: completes the current wait (message, timed wait). */
    fun advance() {
        clickLatch = true
        when (val w = vm.wait) {
            WaitState.Click -> advanceInternal()
            is WaitState.Until -> if (w.skippable) vm.resume()
            else -> {}
        }
    }

    private fun advanceInternal() {
        currentLine?.let { system.read.markRead(it.ref.scene, it.ref.lineNo) }
        vm.resume()
    }

    private fun canSkipCurrent(): Boolean {
        val l = currentLine ?: return true
        return skipMode == SkipMode.ALL || skipUnread || system.read.isRead(l.ref.scene, l.ref.lineNo)
    }

    fun choose(index: Int) {
        if (vm.wait != WaitState.Choice) return
        choices = emptyList()
        vm.resume(Value.I(index))
    }

    fun movieFinished() {
        if (vm.wait is WaitState.Movie) vm.resume()
    }

    fun drainEvents(): List<EngineEvent> {
        val out = events.toList()
        events.clear()
        return out
    }

    /** Resolves a backlog/line reference to displayable text. */
    fun resolve(ref: LineRef): DialogueLine {
        val sc = store.scene(ref.scene)
        return DialogueLine(
            ref = ref,
            jp = sc?.jp?.rich(ref.token) ?: RichText.EMPTY,
            cn = sc?.cn?.rich(ref.token),
            speakerJp = if (ref.speakerToken >= 0) sc?.jp?.rich(ref.speakerToken) else null,
            speakerCn = if (ref.speakerToken >= 0) sc?.cn?.rich(ref.speakerToken) else null,
            read = system.read.isRead(ref.scene, ref.lineNo),
        )
    }

    fun plain(text: RichText?, lang: TextLang, ruby: Boolean = false): String =
        if (text == null) "" else decoder.toPlain(text, lang, ruby)

    // ------------------------------------------------------------------------------- save/load

    fun save(thumbnailBase64: String? = null): SaveData {
        val line = currentLine
        return SaveData(
            timestamp = System.currentTimeMillis(),
            scene = scene,
            previewJp = line?.let { plain(it.jp, TextLang.JP) } ?: "",
            previewCn = line?.let { plain(it.cn, TextLang.CN) } ?: "",
            vm = vm.snapshot(),
            stage = stage,
            audio = audio,
            message = line?.ref,
            voiceScene = voiceScene,
            speakerToken = speakerToken,
            pendingChoices = pendingChoiceTokens.toList(),
            backlog = backlog.toList().takeLast(BACKLOG_SAVE),
            thumbnail = thumbnailBase64,
        )
    }

    fun load(data: SaveData) {
        vm.restore(data.vm)
        stage = data.stage.copy(transition = Transition(TransitionKind.FADE, 300), serial = data.stage.serial + 1)
        audio = data.audio
        voiceScene = data.voiceScene
        speakerToken = data.speakerToken
        pendingChoiceTokens.clear(); pendingChoiceTokens.addAll(data.pendingChoices)
        currentLine = data.message?.let { resolve(it) }
        choices = emptyList()
        backlog.clear(); backlog.addAll(data.backlog)
        events += EngineEvent.SceneChanged(scene)
        events += EngineEvent.StopVoice
        if (audio.bgmTrack > 0) bgmEvent(audio.bgmTrack, 0, audio.bgmVolume)?.let { events += it } else events += EngineEvent.StopBgm(0)
        for (ch in 0 until 4) events += EngineEvent.StopSe(ch, 0)
        audio.loops.forEach { (ch, p) -> events += EngineEvent.PlaySe(ch, p.first, true, p.second, 0) }
    }

    // ------------------------------------------------------------------------------- VmHost

    override fun loadProgram(name: String): LscrProgram? = store.scene(name)?.program

    override fun tokenString(script: String, token: Int): String {
        val sc = store.scene(script) ?: return ""
        return decoder.toPlain(sc.jp.rich(token), TextLang.JP)
    }

    override fun log(msg: String) {
        if (logLines.size < 2000) logLines += "[$scene] $msg"
    }

    private fun textArg(v: Value?): String = when (v) {
        is Value.Text -> tokenString(scene, v.token)
        is Value.Str -> v.s
        is Value.I -> v.v.toString()
        else -> ""
    }

    private fun int(args: List<Value>, i: Int, def: Int = 0): Int =
        args.getOrNull(i)?.let { LscrVm.toInt(it) } ?: def

    private fun ret(v: Int = 0) = CallOutcome.Ret(Value.I(v))

    override fun call(vm: LscrVm, op: Int, spec: FuncSpec?, args: List<Value>, resume: Value?): CallOutcome {
        when (op) {
            // ---- messages
            130, 131, 133 -> { // SetMessage / SetMessageE / SetMessage2 (text, lineNo, flag)
                val tok = (args.getOrNull(0) as? Value.Text)?.token ?: -1
                val lineNo = int(args, 1, -1)
                val ref = LineRef(scene, tok, lineNo, speakerToken, pendingVoice)
                pendingVoice?.let { events += EngineEvent.PlayVoice(it) }
                pendingVoice = null
                currentLine = resolve(ref)
                backlog.addLast(ref)
                while (backlog.size > BACKLOG_MAX) backlog.removeFirst()
                setStage { it.copy(windowVisible = true) }
                return ret()
            }
            132, 134 -> { // EndMessage / WaitMessage2
                if (resume != null) return ret()
                // Even while skipping we block here: update() auto-advances one line per frame so the
                // player sees the text fly by (~60 lines/s) and can stop mid-scene.
                return CallOutcome.Block(WaitState.Click)
            }
            135, 136 -> return ret() // K / SetDemoMode
            210, 211, 212, 213 -> return ret() // S (scroll) / Z (zoom) / R (rotate) / WSZ: camera effects
            233, 234 -> return ret() // NB / NBR: novel-mode page box
            144, 223 -> { // WN / WN2: speaker name token, 0 = protagonist/none, -1 = narration
                speakerToken = (args.getOrNull(0) as? Value.Text)?.token ?: -1
                return ret()
            }
            145, 224 -> { speakerToken = (args.getOrNull(0) as? Value.Text)?.token ?: -1; return ret() }
            142 -> return ret() // W (window params)
            143 -> { // WR: window reset/hide
                currentLine = null
                speakerToken = -1
                setStage { it.copy(windowVisible = false) }
                return ret()
            }
            220 -> { setStage { it.copy(novelMode = int(args, 0) != 0) }; return ret() }

            // ---- voice
            137 -> { // VI(?, scene)
                voiceScene = int(args, 1).takeIf { it > 0 } ?: voiceScene
                return ret()
            }
            138 -> { // VV(char, 256, 0, 0, line)
                val ch = int(args, 0)
                val line = int(args, 4)
                pendingVoice = voiceRef(voiceScene, line, ch)
                return ret()
            }
            139 -> { // VX(char, line, scene = -1 → current, vol, ?, ?)
                val ch = int(args, 0)
                val line = int(args, 1)
                val sc = int(args, 2).takeIf { it > 0 } ?: voiceScene
                pendingVoice = voiceRef(sc, line, ch)
                return ret()
            }
            235 -> return ret() // VXV(vol, time, ?): voice volume ramp
            140, 141, 215 -> { // VW / VS / VA
                if (op == 141) events += EngineEvent.StopVoice
                return ret()
            }

            // ---- pictures
            146, 147, 148, 149, 225, 226, 227, 228 -> {
                if (resume != null) return ret()
                picture(op, args)
                val wait = int(args, 0) != -1 && (op !in 225..228 || int(args, 10, 1) != 0)
                return if (!wait) ret() else effectWait(int(args, 3, 30))
            }
            150, 231 -> { // SetShake(mode, amp, frames, curve, mask) / SetShake2(..., wait=1)
                if (resume != null) { setStage { it.copy(shake = 0) }; return ret() }
                val amp = int(args, 1).let { if (it != 0) it else int(args, 0).let { a -> if (a != 0) a else 8 } }
                val frames = int(args, 2)
                val wait = op == 150 || int(args, 5, 1) != 0
                setStage { it.copy(shake = amp) }
                if (frames <= 0 || !wait) return ret()
                val out = effectWait(frames)
                if (out is CallOutcome.Ret) setStage { it.copy(shake = 0) }
                return out
            }
            151, 214 -> { setStage { it.copy(shake = 0) }; return ret() }
            153 -> { // FB(time, r, g, b): stage colour multiplier v/128 (defaults time=-1, 128,128,128)
                val t = int(args, 0)
                val r = int(args, 1, 128).coerceIn(0, 255)
                val g = int(args, 2, 128).coerceIn(0, 255)
                val b = int(args, 3, 128).coerceIn(0, 255)
                if (resume != null) return ret()
                val ms = if (skipping || t <= 0) 0 else Stage.framesToMs(t)
                setStage { it.copy(tint = (r shl 16) or (g shl 8) or b, tintFadeMs = ms) }
                return effectWait(t)
            }
            152 -> { // F(t1, t2, r, g, b): flash to rgb over t1 frames, back to 128 over t2 (blocking)
                if (skipping || resume != null) return ret()
                val r = int(args, 2, 128).coerceIn(0, 255)
                val g = int(args, 3, 128).coerceIn(0, 255)
                val b = int(args, 4, 128).coerceIn(0, 255)
                setStage {
                    it.copy(
                        flashSerial = it.flashSerial + 1, flashColor = (r shl 16) or (g shl 8) or b,
                        flashInMs = Stage.framesToMs(int(args, 0)), flashOutMs = Stage.framesToMs(int(args, 1)),
                    )
                }
                return effectWait(int(args, 0) + int(args, 1))
            }
            154, 216 -> { // C / CS: blocking crossfade of `fade` frames (arg5, default 15)
                if (resume != null) return ret()
                showSprite(op, args)
                return effectWait(int(args, 5, 15))
            }
            155 -> return showSprite(op, args) // CW: asynchronous (the renderer fades it while the script runs)
            156, 157, 218 -> { // CR(char, mode, fade=15) blocking / CRW(char) asynchronous
                if (resume != null) return ret()
                val ch = int(args, 0)
                val fade = if (op == 156) int(args, 2, 15) else 15
                val tr = if (skipping || fade <= 0) Transition(TransitionKind.CUT, 0) else Transition(TransitionKind.FADE, Stage.framesToMs(fade))
                setStage(tr) { s -> s.copy(sprites = s.sprites.filter { it.charId != ch }) }
                return if (op == 156) effectWait(fade) else ret()
            }
            217 -> return ret() // CM (move): unused in the corpus
            170 -> { setStage { it.copy(timeMode = int(args, 0)) }; return ret() }
            171 -> { setStage { it.copy(chroma = int(args, 0)) }; return ret() }
            172 -> { // SetEffctMode(lutName, which): 0 = both slots, 1 = backgrounds/CG, 2 = sprites
                val name = textArg(args.getOrNull(0)).trim().takeIf { it.isNotEmpty() }
                if (name != null && !assets.exists("grp", name)) missingAssets += "grp/$name"
                val which = int(args, 1)
                setStage {
                    when (which) {
                        1 -> it.copy(lut = name)
                        2 -> it.copy(spriteEffectLut = name)
                        else -> it.copy(lut = name, spriteEffectLut = name)
                    }
                }
                return ret()
            }
            173, 229 -> { setStage { it.copy(weather = int(args, 0)) }; return ret() }
            174, 230 -> return ret()
            175 -> { setStage { it.copy(weather = null) }; return ret() }
            176, 177 -> return loadBmp(op, args)
            180 -> { val slot = int(args, 0); setStage { s -> s.copy(layers = s.layers.filter { it.slot != slot }) }; return ret() }
            183 -> { layer(int(args, 0)) { it.copy(visible = int(args, 1) != 0) }; return ret() }
            184 -> { layer(int(args, 0)) { it.copy(layer = int(args, 1)) }; return ret() }
            185 -> { // SetBmpParam(slot, param, value, ?)
                val slot = int(args, 0)
                val p = int(args, 1)
                val v = int(args, 2)
                layer(slot) { l -> if (p == 1) l.copy(alpha = v.coerceIn(0, 255), params = l.params + (p to v)) else l.copy(params = l.params + (p to v)) }
                return ret()
            }
            187 -> { layer(int(args, 0)) { it.copy(alpha = int(args, 1).coerceIn(0, 255)) }; return ret() }
            188, 189 -> { layer(int(args, 0)) { it.copy(x = int(args, 1), y = int(args, 2)) }; return ret() }
            190, 191 -> { layer(int(args, 0)) { it.copy(zoom = (LscrVm.toNum(args.getOrElse(args.size - 1) { Value.I(100) }) / 100.0).toFloat().coerceIn(0.01f, 100f)) }; return ret() }
            178, 179, 181, 182, 186, 192 -> return ret()
            203 -> { setStage { it.copy(calendar = CalendarCard(int(args, 0), int(args, 1), int(args, 2))) }; return ret() }

            // ---- audio
            158, 232 -> { // M(track, fade, loop, vol)
                val track = int(args, 0)
                val fade = int(args, 1)
                val vol = int(args, 3, 255)
                if (track <= 0) {
                    audio = audio.copy(bgmTrack = 0)
                    events += EngineEvent.StopBgm(Stage.framesToMs(fade))
                } else {
                    audio = audio.copy(bgmTrack = track, bgmVolume = vol)
                    bgmEvent(track, fade, vol)?.let { events += it }
                }
                return ret()
            }
            159 -> {
                audio = audio.copy(bgmTrack = 0)
                events += EngineEvent.StopBgm(Stage.framesToMs(int(args, 0)))
                return ret()
            }
            160, 161, 162, 163 -> return ret()
            164 -> { // SE(id, vol)
                seName(int(args, 0))?.let { events += EngineEvent.PlaySe(SE_ONESHOT_CHANNEL, it, false, int(args, 1, 255), 0) }
                return ret()
            }
            165 -> { // SEP(ch, id, fade, loop, vol, ?)
                val ch = int(args, 0)
                val loop = int(args, 3) != 0
                val vol = int(args, 4, 255)
                val name = seName(int(args, 1))
                if (name != null) {
                    events += EngineEvent.PlaySe(ch, name, loop, vol, Stage.framesToMs(int(args, 2)))
                    audio = audio.copy(loops = if (loop) audio.loops + (ch to (name to vol)) else audio.loops - ch)
                }
                return ret()
            }
            166 -> { // SES(ch, fade)
                val ch = int(args, 0)
                events += EngineEvent.StopSe(ch, Stage.framesToMs(int(args, 1)))
                audio = audio.copy(loops = audio.loops - ch)
                return ret()
            }
            167 -> { events += EngineEvent.SeVolume(int(args, 0), int(args, 1), Stage.framesToMs(int(args, 2))); return ret() }
            168, 169 -> return ret()

            // ---- movie
            193 -> {
                if (resume != null) return ret()
                val stem = textArg(args.getOrNull(0)).trim()
                if (stem.isEmpty() || skipping) return ret()
                events += EngineEvent.PlayMovie(stem)
                return CallOutcome.Block(WaitState.Movie(stem))
            }

            // ---- timing
            194, 236 -> { // Wait(frames, ?, ?)
                if (resume != null) return ret()
                val ms = Stage.framesToMs(int(args, 0))
                if (ms <= 0 || skipping) return ret()
                return CallOutcome.Block(WaitState.Until(nowMs + ms))
            }
            195 -> { timerBase = nowMs; return ret() }
            196 -> {
                if (resume != null) return ret()
                val target = timerBase + int(args, 0)
                if (nowMs >= target || skipping) return ret()
                return CallOutcome.Block(WaitState.Until(target))
            }
            204 -> return ret((nowMs - timerBase).toInt())
            205, 238 -> return ret(if (skipping) 1 else 0)
            206 -> return ret(if (clickLatch) 1 else 0)
            207 -> { // runEX: yield one frame
                if (resume != null) return ret()
                return CallOutcome.Block(WaitState.Frame)
            }
            237 -> { // IfSkip: the engine stops unread skipping before choices
                if (skipMode != SkipMode.OFF && !skipUnread && skipMode != SkipMode.ALL) skipMode = SkipMode.OFF
                return ret(if (skipping) 1 else 0)
            }
            219 -> { skipMode = SkipMode.OFF; return ret() }

            // ---- flags
            197 -> { vm.end(); return CallOutcome.Block(WaitState.Ended) }
            198 -> return ret(system.gflags.getOrElse(int(args, 0)) { 0 })
            199 -> { val i = int(args, 0); if (i in system.gflags.indices) system.gflags[i] = int(args, 1); return ret() }
            201, 202 -> { // V_Flag / H_Flag(X, Y, flag=1): mark CG stem X*10+Y as seen for the gallery
                val name = "%c%06d".format(Locale.ROOT, if (op == 201) 'v' else 'h', int(args, 0) * 10 + int(args, 1))
                if (int(args, 2, 1) != 0) system.cgFlags += name else system.cgFlags -= name
                return ret()
            }
            200, 128, 129 -> return ret()
            221, 222 -> return ret(0)

            // ---- choices
            208 -> { // SetSelectMess(text, …)
                (args.getOrNull(0) as? Value.Text)?.let { pendingChoiceTokens += it.token }
                return ret()
            }
            209 -> { // SetSelect(id) → 0-based index
                if (resume != null) {
                    pendingChoiceTokens.clear()
                    choices = emptyList()
                    return CallOutcome.Ret(Value.I(LscrVm.toInt(resume)))
                }
                val sc = currentScene()
                choices = pendingChoiceTokens.map { t -> ChoiceOption(t, sc?.jp?.rich(t) ?: RichText.EMPTY, sc?.cn?.rich(t)) }
                if (skipMode == SkipMode.READ_ONLY) skipMode = SkipMode.OFF
                return CallOutcome.Block(WaitState.Choice)
            }
        }
        unhandledOps.merge(op, 1, Int::plus)
        return ret()
    }

    // ------------------------------------------------------------------------------- helpers

    /**
     * `WA2.exe` "yielding" handlers (B/BC/V/H, C, CR, F/FB, SetShake …) start their effect and keep
     * the script waiting until it has finished, so the renderer draws every intermediate frame
     * (pose changes, entrances, crossfades). Returns the wait for [frames] (none while skipping).
     */
    private fun effectWait(frames: Int): CallOutcome {
        val ms = Stage.framesToMs(frames)
        if (ms <= 0 || skipping) return ret()
        return CallOutcome.Block(WaitState.Until(nowMs + ms))
    }

    private fun setStage(transition: Transition? = null, f: (StageState) -> StageState) {
        val next = f(stage)
        stage = if (transition != null) next.copy(transition = transition, serial = stage.serial + 1) else next
    }

    private fun layer(slot: Int, f: (BmpLayer) -> BmpLayer) {
        setStage { s -> s.copy(layers = s.layers.map { if (it.slot == slot) f(it) else it }) }
    }

    private fun voiceRef(sceneId: Int, line: Int, ch: Int): VoiceRef? {
        val name = "%04d_%04d_%02d.OGG".format(Locale.ROOT, sceneId, line, ch)
        if (assets.audio("VOICE", name) == null) {
            missingAssets += "voice/$name"
            return null
        }
        return VoiceRef(name, ch)
    }

    private fun seName(id: Int): String? {
        for (ext in listOf("WAV", "OGG")) {
            val n = "SE_%04d.%s".format(Locale.ROOT, id, ext)
            if (assets.audio("SE", n) != null) return n
        }
        missingAssets += "se/SE_%04d".format(Locale.ROOT, id)
        return null
    }

    private fun bgmEvent(track: Int, fadeFrames: Int, vol: Int): EngineEvent.PlayBgm? {
        val a = "BGM_%03d_A.OGG".format(Locale.ROOT, track)
        val b = "BGM_%03d_B.OGG".format(Locale.ROOT, track)
        val single = "BGM_%03d.OGG".format(Locale.ROOT, track)
        val fade = Stage.framesToMs(fadeFrames)
        return when {
            assets.audio("BGM", b) != null -> EngineEvent.PlayBgm(track, if (assets.audio("BGM", a) != null) a else null, b, fade, vol)
            assets.audio("BGM", single) != null -> EngineEvent.PlayBgm(track, null, single, fade, vol)
            else -> { missingAssets += "bgm/$single"; null }
        }
    }

    /**
     * Background file for `B(…, X, Y, …)`: `B{X:04d}{Y:1d}{T:1d}.tga` with T = current `SetTimeMode`
     * (X = 0 → the solid screens `B000000..B000003`).
     */
    fun backgroundName(x: Int, y: Int, time: Int = stage.timeMode): String? {
        // Exact time-of-day variant first, then the closest earlier one (2009: b103303 → b103302),
        // then any variant of the same picture, then Y = 0.
        val t0 = time.coerceIn(0, 9)
        val order = (t0 downTo 0) + ((t0 + 1)..9)
        for (t in order) {
            val n = "B%04d%d%d.tga".format(Locale.ROOT, x, y, t)
            if (assets.exists("bak", n)) return n
        }
        return "B%04d00.tga".format(Locale.ROOT, x).takeIf { assets.exists("bak", it) }
    }

    /** Event CG file for `V/H(…, X, Y, …)`: X is a 5-digit stem → `v%05d%1d.tga` (10010,0 → v100100). */
    fun eventCgName(prefix: Char, x: Int, y: Int): String? {
        val candidates = listOf("%c%05d%d.tga".format(Locale.ROOT, prefix, x, y), "%c%05d0.tga".format(Locale.ROOT, prefix, x), "%c%06d.tga".format(Locale.ROOT, prefix, x))
        return candidates.firstOrNull { assets.exists("grp", it) }
    }

    /**
     * Transition for the B/BC/V primitive (`WA2.exe` 0x4171dd): −1 instant; 1 and 11..19 crossfade
     * (11..14 zoom and 16..19 slide variants are approximated by a crossfade); ≥128 mask dissolve with
     * `f0{mode & 0x7F:03d}.bmp`, flag bits 0x100 reverse / 0x800 flip-X / 0x1000 flip-Y (missing mask →
     * crossfade); anything else is an engine error that switches instantly.
     */
    private fun transitionFor(mode: Int, frames: Int): Transition {
        val ms = Stage.framesToMs(frames)
        if (ms <= 0 || skipping || mode == -1) return Transition(TransitionKind.CUT, 0)
        if (mode >= 128) {
            val mask = "f0%03d.bmp".format(Locale.ROOT, mode and 0x7F)
            if (assets.exists("grp", mask)) {
                return Transition(
                    TransitionKind.MASK, ms, mask,
                    reverse = mode and 0x100 != 0, flipX = mode and 0x800 != 0, flipY = mode and 0x1000 != 0,
                )
            }
            return Transition(TransitionKind.FADE, ms)
        }
        return when (mode) {
            1, in 11..19 -> Transition(TransitionKind.FADE, ms)
            else -> Transition(TransitionKind.CUT, 0)
        }
    }

    private fun picture(op: Int, args: List<Value>) {
        val mode = int(args, 0)
        val x = int(args, 1)
        val y = int(args, 2)
        val frames = int(args, 3)
        val tr = transitionFor(mode, frames)
        when (op) {
            146, 225, 147, 226 -> {
                // B / B2: background, clears sprites + CG. BC / BC2: background change keeping the
                // standing characters (corpus: every BC stem exists in bak.pak, never only in grp).
                // X = −2 keeps the current picture and only runs the transition.
                val keepChars = op == 147 || op == 226
                if (x < 0) {
                    setStage(tr) { it }
                } else {
                    val name = backgroundName(x, y)
                    if (name == null) missingAssets += "bak/B%04d%d%d".format(Locale.ROOT, x, y, stage.timeMode)
                    val amp = name?.let { it.dropLast(4) + ".amp" }?.takeIf { assets.exists("grp", it) }
                        ?: com.wa2port.core.image.AmpLut.timeModeDefault(stage.timeMode)?.takeIf { assets.exists("grp", it) }
                    setStage(tr) {
                        it.copy(
                            bg = name, bgDir = "bak", spriteLut = amp, cg = null,
                            sprites = if (keepChars) it.sprites else emptyList(), layers = emptyList(),
                        )
                    }
                }
            }
            else -> { // V / V2 / H / H2: event CG (grp), clears standing sprites
                val prefix = if (op == 149 || op == 228) 'h' else 'v'
                val name = if (x > 0) eventCgName(prefix, x, y) else null
                if (x > 0 && name == null) missingAssets += "grp/%c%05d%d".format(Locale.ROOT, prefix, x, y)
                // Displaying a CG marks it as seen for the CG gallery (WA2.exe 0x416929 → cgSeen[stem]).
                if (name != null) system.cgFlags += name.substringBefore('.').lowercase()
                setStage(tr) { it.copy(cg = name, sprites = emptyList()) }
            }
        }
    }

    /**
     * `C(char, num, pos, 0, 0, fade=15, scale=256, bright=128)` / `CW(char, num, pos, 0, scale, bright)`:
     * shows `char\{prefix}{num:06d}.tga` at position index `pos` (corpus: values 0..10, 1 = centre);
     * `num == 4000` hides the character.
     */
    private fun showSprite(op: Int, args: List<Value>): CallOutcome {
        val ch = int(args, 0)
        val number = int(args, 1)
        val pos = int(args, 2)
        val isC = op != 155
        val fade = if (isC) int(args, 5, 15) else 15
        val scale = int(args, if (isC) 6 else 4, 256)
        val bright = int(args, if (isC) 7 else 5, 128)
        val prefix = Stage.CHAR_PREFIX[ch]
        val tr = if (skipping || fade <= 0) Transition(TransitionKind.CUT, 0) else Transition(TransitionKind.FADE, Stage.framesToMs(fade))
        if (prefix == null || number == 4000) { // 4000 = hide sentinel
            setStage(tr) { s -> s.copy(sprites = s.sprites.filter { it.charId != ch }) }
            return ret()
        }
        val image = "%s%06d.tga".format(Locale.ROOT, prefix, number)
        if (!assets.exists("char", image)) missingAssets += "char/$image"
        val existing = stage.sprites.firstOrNull { it.charId == ch }
        val sp = SpriteState(ch, prefix, number, pos, image, existing?.order ?: ++spriteOrder, scale, bright)
        setStage(tr) { s ->
            val list = s.sprites.filter { it.charId != ch } + sp
            s.copy(sprites = list.sortedBy { it.order }.take(8))
        }
        return ret()
    }

    private fun loadBmp(op: Int, args: List<Value>): CallOutcome {
        val slot = int(args, 0)
        val name = textArg(args.getOrNull(1)).trim()
        val layerNo = int(args, 2)
        // LoadBmp(slot, name, layer, ?, name2, ?, dir, ?) / LoadBmpAnime(slot, name, ?, name2, ?, dir, ?)
        val dir = textArg(args.getOrNull(if (op == 177) 5 else 6)).trim().ifEmpty { "grp" }
        if (name.isEmpty()) return ret()
        if (!assets.exists(dir, name)) missingAssets += "$dir/$name"
        setStage(Transition(TransitionKind.FADE, 300)) { s ->
            s.copy(layers = s.layers.filter { it.slot != slot } + BmpLayer(slot, name, dir, layerNo))
        }
        return ret()
    }

    companion object {
        const val BACKLOG_MAX = 500
        const val BACKLOG_SAVE = 200
    }
}
