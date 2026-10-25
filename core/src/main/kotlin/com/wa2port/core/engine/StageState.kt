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
    /** Scale in 1/256 units (256 = 100 %). */
    val scale: Int = 256,
    /** Brightness/tint per channel (128 = neutral). */
    val bright: Int = 128,
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

/**
 * How the renderer should animate the change to the current stage picture.
 * For [TransitionKind.MASK], pixels switch where `maskLevel ≤ progress·255` (or `≥` when [reverse]);
 * the mask may be mirrored with [flipX]/[flipY] (`WA2.exe` transition renderer 0x40ba5a).
 */
@Serializable
data class Transition(
    val kind: TransitionKind,
    val durationMs: Long,
    val mask: String? = null,
    val reverse: Boolean = false,
    val flipX: Boolean = false,
    val flipY: Boolean = false,
)

/** On-screen date card (`Calender(y, m, d, …)`). */
@Serializable
data class CalendarCard(val year: Int, val month: Int, val day: Int)

/** Everything the renderer needs to draw the 1280×720 stage. Immutable snapshot. */
@Serializable
data class StageState(
    /** Background image name (in [bgDir] archive: `bak` for `B%04d%1d%1d.tga`), null = black. */
    val bg: String? = null,
    val bgDir: String = "bak",
    /**
     * Sprite LUT A: the background's `B{X}{Y}{T}.amp` if present, else the time-mode default
     * (`evening`/`night`/`indoor`); grades standing sprites only, never the background.
     */
    val spriteLut: String? = null,
    /** Event CG (`v%06d.tga` / `h%06d.tga` in `grp`), drawn above sprites; null = none. */
    val cg: String? = null,
    val sprites: List<SpriteState> = emptyList(),
    val layers: List<BmpLayer> = emptyList(),
    /** Effect LUT slot 1 (`SetEffctMode(name, 0|1)`): grades backgrounds, CG and layers (e.g. `sepia.AMP`). */
    val lut: String? = null,
    /** Effect LUT slot 2 (`SetEffctMode(name, 0|2)`): grades standing sprites after [spriteLut]. */
    val spriteEffectLut: String? = null,
    val chroma: Int = 0,
    val timeMode: Int = 0,
    /** Weather effect id (`SetWeather`: 4 = snow), null = none. */
    val weather: Int? = null,
    /** Legacy flash overlay `ARGB` (unused by the engine now; always 0). */
    val overlayColor: Int = 0,
    val overlayFadeMs: Long = 0,
    /**
     * Stage brightness `0xRRGGBB` from `FB(time, r, g, b)` (`WA2.exe` 0x401de0 / 0x43b290), per channel
     * v: v < 128 multiplies by 2v/255, v ≥ 128 adds (2v−256)/255 — 0 = black, 128 = neutral, 255 = white.
     * Applies to the stage picture only, never to the message window.
     */
    val tint: Int = NEUTRAL_TINT,
    /** Duration over which the renderer tweens from the previous [tint] to this one. */
    val tintFadeMs: Long = 0,
    /** `F(t1, t2, r, g, b)` flash: tween to [flashColor] over [flashInMs], then back over [flashOutMs]. */
    val flashSerial: Int = 0,
    val flashColor: Int = NEUTRAL_TINT,
    val flashInMs: Long = 0,
    val flashOutMs: Long = 0,
    val shake: Int = 0,
    val transition: Transition = Transition(TransitionKind.CUT, 0),
    /** Increments whenever the picture changes (renderers crossfade from the previous serial). */
    val serial: Int = 0,
    val windowVisible: Boolean = false,
    val novelMode: Boolean = false,
    val calendar: CalendarCard? = null,
)

/** `FB(…, 128, 128, 128)`: neutral screen colour (no tint). */
const val NEUTRAL_TINT = 0x808080

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
