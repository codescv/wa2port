package com.wa2port.core.engine

import com.wa2port.core.script.WaitState
import com.wa2port.core.text.TextLang

/** One recorded stop of a headless run. */
data class TraceLine(
    val scene: String,
    val lineNo: Int,
    val speakerJp: String,
    val speakerCn: String,
    val jp: String,
    val cn: String,
    val voice: String?,
    val bg: String?,
    val cg: String?,
    val sprites: List<String>,
    val lut: String?,
)

/**
 * Drives a [Wa2Engine] without a UI: advances every message, picks choices with [chooser], and
 * auto-completes movies. Used by the desktop test-suite and handy for debugging on device.
 */
class HeadlessRunner(
    val engine: Wa2Engine,
    private val frameMs: Long = 16,
    private val chooser: (List<ChoiceOption>) -> Int = { 0 },
) {
    val trace = ArrayList<TraceLine>()
    val events = ArrayList<EngineEvent>()
    val choicesSeen = ArrayList<List<String>>()
    val scenesVisited = LinkedHashSet<String>()

    /**
     * Runs until [maxLines] messages were shown, the game ends, the scene leaves [stayIn] (when
     * given), or [maxFrames] elapse.
     */
    fun run(maxLines: Int = Int.MAX_VALUE, maxFrames: Int = 2_000_000, stayIn: String? = null): Int {
        var frames = 0
        var lines = 0
        while (frames < maxFrames) {
            val w = engine.update(frameMs)
            frames++
            scenesVisited += engine.scene
            events += engine.drainEvents()
            if (stayIn != null && !engine.scene.equals(stayIn, true) && engine.scene != "") {
                if (!isEyecatch(engine.scene)) break
            }
            when (w) {
                WaitState.Click -> {
                    if (engine.willAutoAdvance) continue
                    record()
                    lines++
                    if (lines >= maxLines) return lines
                    engine.advance()
                }
                WaitState.Choice -> {
                    choicesSeen += engine.choices.map { engine.plain(it.jp, TextLang.JP) }
                    engine.choose(chooser(engine.choices).coerceIn(0, (engine.choices.size - 1).coerceAtLeast(0)))
                }
                is WaitState.Movie -> engine.movieFinished()
                WaitState.Ended -> break
                else -> {}
            }
        }
        return lines
    }

    private fun isEyecatch(s: String) = s.lowercase().startsWith("catch")

    private fun record() {
        val l = engine.currentLine ?: return
        val st = engine.stage
        trace += TraceLine(
            scene = l.ref.scene,
            lineNo = l.ref.lineNo,
            speakerJp = engine.plain(l.speakerJp, TextLang.JP),
            speakerCn = engine.plain(l.speakerCn, TextLang.CN),
            jp = engine.plain(l.jp, TextLang.JP),
            cn = engine.plain(l.cn, TextLang.CN),
            voice = l.ref.voice?.file,
            bg = st.bg,
            cg = st.cg,
            sprites = st.sprites.map { it.image },
            lut = st.lut,
        )
    }
}
