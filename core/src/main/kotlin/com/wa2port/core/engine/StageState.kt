package com.wa2port.core.engine

import kotlinx.serialization.Serializable

/** Logical stage size of the PC game (all coordinates are in this space). */
object Stage {
    const val WIDTH = 1280
    const val HEIGHT = 720

    /** `WA2.exe` .rdata 0x4be0bc: sprite position index → x offset (px from stage centre). */
    val POSITION_X = intArrayOf(-288, 0, 288, -384, 384, -480, 480, -480, -160, 160, 480)

    /**
     * Sprite-slot prefix table (`WA2.exe` .rdata 0x4a2904; ids 6..9 are unused duds).
     * `char\{prefix}{number:06d}.tga`.
     */
    val CHAR_PREFIX: Map<Int, String> = mapOf(
        0 to "har", 1 to "kaz", 2 to "set", 3 to "koh", 4 to "izu", 5 to "mar",
        10 to "tak", 11 to "ioo", 12 to "chi", 13 to "pap", 14 to "mam", 15 to "oto", 16 to "you",
        17 to "tan", 18 to "shi", 19 to "tom", 20 to "sat", 21 to "hon", 22 to "nak", 23 to "say",
        24 to "aco", 25 to "mih", 26 to "mhh", 27 to "ueh", 28 to "yos", 29 to "tan", 30 to "ham",
        31 to "mat", 32 to "kiz", 33 to "suz", 34 to "saw", 35 to "miy", 36 to "yan", 37 to "mas",
    )

    /** Script timing values are in 60 Hz frames. */
    const val FRAME_MS = 1000.0 / 60.0

    fun framesToMs(frames: Int): Long = (frames.coerceAtLeast(0) * FRAME_MS).toLong()
}

/** One standing character sprite. */
@Serializable
data class SpriteState(
    val charId: Int,
    val prefix: String,
    val number: Int,
    val position: Int,
    /** File name inside `char.pak` (e.g. `kaz001101.tga`). */
    val image: String,
    /** Draw order (allocation order; later = on top). */
    val order: Int,
) {
    /** Centre x on the 1280-wide stage. */
    val centerX: Int get() = Stage.WIDTH / 2 + Stage.POSITION_X.getOrElse(position) { 0 }
}

/** A script-loaded bitmap layer (`LoadBmp` / `SetBmp*`). */
@Serializable
data class BmpLayer(
    val slot: Int,
    val image: String,
    val dir: String = "grp",
    val layer: Int = 0,
    val visible: Boolean = true,
    val alpha: Int = 255,
    val x: Int = 0,
    val y: Int = 0,
    val zoom: Float = 1f,
    val params: Map<Int, Int> = emptyMap(),
)

@Serializable
enum class TransitionKind { CUT, FADE, MASK }

/** How the renderer should animate the change to the current stage picture. */
@Serializable
data class Transition(val kind: TransitionKind, val durationMs: Long, val mask: String? = null)

/** On-screen date card (`Calender(y, m, d, …)`). */
@Serializable
data class CalendarCard(val year: Int, val month: Int, val day: Int)

/** Everything the renderer needs to draw the 1280×720 stage. Immutable snapshot. */
@Serializable
data class StageState(
    /** Background image name (in [bgDir] archive: `bak` for `B%04d%1d%1d.tga`), null = black. */
    val bg: String? = null,
    val bgDir: String = "bak",
    /** Event CG (`v%06d.tga` / `h%06d.tga` in `grp`), drawn above sprites; null = none. */
    val cg: String? = null,
    val sprites: List<SpriteState> = emptyList(),
    val layers: List<BmpLayer> = emptyList(),
    /** `.AMP` colour-grading LUT name in `grp` (e.g. `sepia.AMP`), null = none. */
    val lut: String? = null,
    val chroma: Int = 0,
    val timeMode: Int = 0,
    /** Weather effect id (`SetWeather`: 4 = snow), null = none. */
    val weather: Int? = null,
    /** Flash/fade colour overlay `ARGB` (`FB`), 0 = none. */
    val overlayColor: Int = 0,
    /** Duration over which the renderer should fade to [overlayColor] (`FB(time, r, g, b)`). */
    val overlayFadeMs: Long = 0,
    val shake: Int = 0,
    val transition: Transition = Transition(TransitionKind.CUT, 0),
    /** Increments whenever the picture changes (renderers crossfade from the previous serial). */
    val serial: Int = 0,
    val windowVisible: Boolean = false,
    val novelMode: Boolean = false,
    val calendar: CalendarCard? = null,
)

/** A voice clip reference. */
@Serializable
data class VoiceRef(val file: String, val charId: Int)

/** One dialogue/narration line as shown in the message window or backlog. */
@Serializable
data class LineRef(
    val scene: String,
    val token: Int,
    val lineNo: Int,
    val speakerToken: Int = -1,
    val voice: VoiceRef? = null,
)

/** Engine → host side effects (audio, movies, UI cues). Drained by the host every frame. */
sealed class EngineEvent {
    /** BGM `BGM_%03d`: [intro] played once then [loop] repeats (single-file tracks: intro = null). */
    data class PlayBgm(val track: Int, val intro: String?, val loop: String, val fadeMs: Long, val volume: Int) : EngineEvent()
    data class StopBgm(val fadeMs: Long) : EngineEvent()
    data class PlaySe(val channel: Int, val name: String, val loop: Boolean, val volume: Int, val fadeMs: Long) : EngineEvent()
    data class StopSe(val channel: Int, val fadeMs: Long) : EngineEvent()
    data class SeVolume(val channel: Int, val volume: Int, val fadeMs: Long) : EngineEvent()
    data class PlayVoice(val voice: VoiceRef) : EngineEvent()
    data object StopVoice : EngineEvent()
    /** Movie `mvNN` → play `mvNN0.pak` (HD ASF) or a user-supplied `mvNN.mp4`. */
    data class PlayMovie(val stem: String) : EngineEvent()
    data class SceneChanged(val scene: String) : EngineEvent()
    data object GameEnded : EngineEvent()
}

/** SE channel ids used for one-shot `SE()` calls (ambient channels are 0..3). */
const val SE_ONESHOT_CHANNEL = 8
