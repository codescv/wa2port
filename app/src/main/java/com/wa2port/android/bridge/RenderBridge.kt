package com.wa2port.android.bridge

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.wa2port.core.engine.EngineEvent
import com.wa2port.core.engine.StageState
import com.wa2port.core.engine.VoiceRef
import com.wa2port.core.text.Reflow
import com.wa2port.core.text.RichText
import com.wa2port.core.text.TextLang

/*
 * Thin seam between the UI shell (this package's users) and the renderer/audio packages owned by
 * @render (`com.wa2port.android.render` / `.audio`). The UI only talks to these interfaces;
 * [RenderBackends.create] picks the implementation.
 */

/** Draws engine [RichText] (CN via the fon.pak atlas, JP with ruby) onto a native canvas. */
interface TextPainter {
    /** Number of revealable glyphs (ruby readings excluded). */
    fun glyphCount(rich: RichText): Int

    /**
     * Height in px of [rich] wrapped to [maxWidth] at [sizePx]. [maxChars] > 0 also caps full-width
     * characters per line; [reflow] controls how script `\n` breaks are handled (see GlyphAtlasText.layout).
     */
    fun measureHeight(rich: RichText, lang: TextLang, maxWidth: Float, sizePx: Float, maxChars: Float = 0f, reflow: Reflow = Reflow.KEEP_ALL): Float

    /**
     * Height in px from the text box top to the bottom of the line containing the last revealed
     * glyph among the first [visibleGlyphs] glyphs (0 when [visibleGlyphs] <= 0; full
     * [measureHeight] when all glyphs are visible).
     */
    fun measureRevealedHeight(
        rich: RichText,
        lang: TextLang,
        maxWidth: Float,
        sizePx: Float,
        visibleGlyphs: Int,
        maxChars: Float = 0f,
        reflow: Reflow = Reflow.KEEP_ALL,
    ): Float = if (visibleGlyphs <= 0) 0f else measureHeight(rich, lang, maxWidth, sizePx, maxChars, reflow)

    /** Draws the first [visibleGlyphs] glyphs with the top-left at ([x], [y]); returns the drawn height. */
    fun draw(
        canvas: Canvas,
        rich: RichText,
        lang: TextLang,
        x: Float,
        y: Float,
        maxWidth: Float,
        sizePx: Float,
        color: Int,
        visibleGlyphs: Int = Int.MAX_VALUE,
        maxChars: Float = 0f,
        reflow: Reflow = Reflow.KEEP_ALL,
    ): Float

    /**
     * Index (in [RichText.baseCodes] order) of the glyph under ([localX], [localY]) relative to the
     * text box top-left, laid out with the same arguments as [draw]; −1 when none (or unsupported).
     * Glyphs with index ≥ [visibleGlyphs] never hit. See `com.wa2port.core.ai.GlyphHit`.
     */
    fun glyphIndexAt(
        rich: RichText,
        lang: TextLang,
        maxWidth: Float,
        sizePx: Float,
        maxChars: Float,
        reflow: Reflow,
        localX: Float,
        localY: Float,
        visibleGlyphs: Int = Int.MAX_VALUE,
    ): Int = -1
}

/** Engine audio output. */
interface AudioSink {
    fun handle(event: EngineEvent)
    fun replayVoice(voice: VoiceRef)

    /** Volumes 0..1. */
    fun setVolumes(bgm: Float, se: Float, voice: Float)
    fun pause()
    fun resume()
    fun release()
    val isVoicePlaying: Boolean
}

/** Per-stage handle that outlives recompositions (thumbnail capture for save slots). */
interface StageHandle {
    fun captureThumbnail(): Bitmap?
}

/** Factory for the stage composable, text painter, audio and movie player. */
interface RenderBackend {
    val text: TextPainter

    @Composable
    fun rememberStageHandle(): StageHandle

    /** Draws the 1280×720 stage filling [modifier]'s (already 16:9) bounds. */
    @Composable
    fun Stage(stage: StageState, handle: StageHandle, modifier: Modifier)

    fun createAudio(): AudioSink

    /** Full-screen movie; must call [onFinished] exactly once (end, skip, or unavailable). */
    @Composable
    fun Movie(stem: String, onFinished: () -> Unit, modifier: Modifier)

    /** Warms up expensive resources (font atlases) off the UI thread. */
    suspend fun preload() {}

    /** Called on memory pressure. */
    fun trimMemory() {}

    fun release()
}
