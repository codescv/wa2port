package com.wa2port.android.bridge

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.wa2port.android.audio.AudioEngine
import com.wa2port.android.render.AssetImageCache
import com.wa2port.android.render.GlyphAtlasText
import com.wa2port.android.render.MoviePlayer
import com.wa2port.android.render.StageCanvas
import com.wa2port.android.render.StageController
import com.wa2port.android.render.rememberStageController
import com.wa2port.core.ai.GlyphHit
import com.wa2port.core.engine.EngineEvent
import com.wa2port.core.engine.StageState
import com.wa2port.core.engine.VoiceRef
import com.wa2port.core.engine.Wa2Engine
import com.wa2port.core.text.RichText
import com.wa2port.core.text.TextLang

/** [TextPainter] over @render's fon.pak/fnt.pak [GlyphAtlasText], with a small layout cache. */
class AtlasTextPainter(val glyphs: GlyphAtlasText) : TextPainter {
    /** Identity key: RichText instances are reused across frames, so identity avoids deep hashing. */
    private class Key(val rich: RichText, val lang: TextLang, val width: Float, val size: Float, val maxChars: Float, val reflow: Boolean) {
        override fun equals(other: Any?) =
            other is Key && other.rich === rich && other.lang == lang && other.width == width && other.size == size &&
                other.maxChars == maxChars && other.reflow == reflow

        override fun hashCode() =
            ((((System.identityHashCode(rich) * 31 + lang.ordinal) * 31 + width.hashCode()) * 31 + size.hashCode()) * 31 +
                maxChars.hashCode()) * 31 + reflow.hashCode()
    }

    private val cache = object : LinkedHashMap<Key, GlyphAtlasText.TextLayout>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, GlyphAtlasText.TextLayout>?) = size > 160
    }

    private fun layout(rich: RichText, lang: TextLang, maxWidth: Float, sizePx: Float, maxChars: Float = 0f, reflow: Boolean = false) =
        cache.getOrPut(Key(rich, lang, maxWidth, sizePx, maxChars, reflow)) { glyphs.layout(rich, lang, maxWidth, sizePx, maxChars, reflow) }

    override fun glyphCount(rich: RichText): Int = glyphs.glyphCount(rich)

    override fun measureHeight(rich: RichText, lang: TextLang, maxWidth: Float, sizePx: Float, maxChars: Float, reflow: Boolean): Float =
        layout(rich, lang, maxWidth, sizePx, maxChars, reflow).height

    override fun draw(
        canvas: Canvas, rich: RichText, lang: TextLang, x: Float, y: Float,
        maxWidth: Float, sizePx: Float, color: Int, visibleGlyphs: Int, maxChars: Float, reflow: Boolean,
    ): Float {
        val l = layout(rich, lang, maxWidth, sizePx, maxChars, reflow)
        glyphs.drawLayout(canvas, l, lang, x, y, color, visibleGlyphs)
        return l.height
    }

    override fun glyphIndexAt(
        rich: RichText, lang: TextLang, maxWidth: Float, sizePx: Float, maxChars: Float, reflow: Boolean,
        localX: Float, localY: Float, visibleGlyphs: Int,
    ): Int {
        val l = layout(rich, lang, maxWidth, sizePx, maxChars, reflow)
        val boxes = l.glyphs.map { GlyphHit.Box(it.index, it.x, it.top, it.advance) }
        return GlyphHit.glyphIndexAt(boxes, sizePx * glyphs.lineSpacing, sizePx, localX, localY, visibleGlyphs)
    }
}

/** [AudioSink] over @render's [AudioEngine]. */
class EngineAudio(private val audio: AudioEngine) : AudioSink {
    override fun handle(event: EngineEvent) = audio.handle(event)
    override fun replayVoice(voice: VoiceRef) = audio.replayVoice(voice)
    override fun setVolumes(bgm: Float, se: Float, voice: Float) = audio.setVolumes(bgm, se, voice)
    override fun pause() = audio.pause()
    override fun resume() = audio.resume()
    override fun release() = audio.release()
    override val isVoicePlaying: Boolean get() = audio.isVoicePlaying
}

/** The real backend: StageCanvas + GlyphAtlasText + AudioEngine + MoviePlayer from `render/`/`audio/`. */
class Wa2RenderBackend(private val context: Context, private val engine: Wa2Engine) : RenderBackend {
    private val cache = AssetImageCache(engine.assets)
    private val glyphs = GlyphAtlasText(engine.assets, engine.decoder)
    override val text: TextPainter = AtlasTextPainter(glyphs)

    private class Handle(val controller: StageController) : StageHandle {
        override fun captureThumbnail(): Bitmap? = controller.captureThumbnail()
    }

    override suspend fun preload() {
        glyphs.preload(TextLang.CN, TextLang.JP)
    }

    @Composable
    override fun rememberStageHandle(): StageHandle {
        val controller = rememberStageController()
        return remember(controller) { Handle(controller) }
    }

    @Composable
    override fun Stage(stage: StageState, handle: StageHandle, modifier: Modifier) {
        StageCanvas(stage, cache, modifier, (handle as Handle).controller)
    }

    override fun createAudio(): AudioSink = EngineAudio(AudioEngine(context, engine.assets))

    @Composable
    override fun Movie(stem: String, onFinished: () -> Unit, modifier: Modifier) {
        MoviePlayer(stem, engine.assets, onFinished, modifier)
    }

    override fun trimMemory() = cache.trim()

    override fun release() = cache.close()
}
