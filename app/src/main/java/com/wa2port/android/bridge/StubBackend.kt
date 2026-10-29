package com.wa2port.android.bridge

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.LruCache
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import com.wa2port.core.engine.EngineEvent
import com.wa2port.core.engine.StageState
import com.wa2port.core.engine.VoiceRef
import com.wa2port.core.text.BilingualTextDecoder
import com.wa2port.core.text.Reflow
import com.wa2port.core.text.RichText
import com.wa2port.core.text.TextItem
import com.wa2port.core.text.TextLang
import kotlinx.coroutines.delay
import java.util.Locale

/**
 * Text painter using Android's font stack via [BilingualTextDecoder] (Unicode). Used until/unless
 * the fon.pak atlas painter is available; CN quality depends on the recovered cell table.
 */
class FallbackTextPainter(private val decoder: BilingualTextDecoder) : TextPainter {
    private val paints = HashMap<TextLang, TextPaint>()
    private val layouts = LruCache<String, StaticLayout>(96)

    private fun paint(lang: TextLang, sizePx: Float, color: Int): TextPaint {
        val p = paints.getOrPut(lang) {
            TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                textLocale = if (lang == TextLang.CN) Locale.SIMPLIFIED_CHINESE else Locale.JAPANESE
            }
        }
        p.textSize = sizePx
        p.color = color
        p.setShadowLayer(sizePx * 0.12f, 0f, sizePx * 0.06f, 0xD0000000.toInt())
        return p
    }

    override fun glyphCount(rich: RichText): Int = rich.baseCodes().size

    /** Plain string of the first [limit] glyphs (ruby dropped, `\n`/`\k` handled per [reflow]). */
    private fun plain(rich: RichText, lang: TextLang, limit: Int, reflow: Reflow = Reflow.KEEP_ALL): String {
        val sb = StringBuilder()
        var n = 0
        fun add(code: Int): Boolean {
            if (n >= limit) return false
            n++
            // Stray control bytes (CK-GAL typos) count as glyphs, like the atlas painter, but draw nothing.
            if (code >= 0x20) sb.append(decoder.char(code, lang))
            return true
        }
        loop@ for (item in rich.items) {
            when (item) {
                is TextItem.Run -> for (c in item.codes) if (!add(c)) break@loop
                is TextItem.Ruby -> for (c in item.base) if (!add(c)) break@loop
                TextItem.NewLine -> if (n < limit && reflow != Reflow.JOIN_ALL) sb.append('\n')
                TextItem.PageBreak -> if (n < limit) sb.append('\n')
                is TextItem.Wait -> {}
            }
        }
        return sb.toString().trimEnd('\n')
    }

    private fun layout(text: String, lang: TextLang, width: Int, sizePx: Float, color: Int): StaticLayout {
        val key = "${lang.ordinal}|$width|$sizePx|$color|$text"
        layouts.get(key)?.let { return it }
        val l = StaticLayout.Builder.obtain(text, 0, text.length, paint(lang, sizePx, color), width.coerceAtLeast(1))
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(0f, 1.15f)
            .setIncludePad(false)
            .build()
        layouts.put(key, l)
        return l
    }

    private fun width(maxWidth: Float, sizePx: Float, maxChars: Float): Int =
        (if (maxChars > 0f) minOf(maxWidth, maxChars * sizePx * 1.02f) else maxWidth).toInt()

    override fun measureHeight(rich: RichText, lang: TextLang, maxWidth: Float, sizePx: Float, maxChars: Float, reflow: Reflow): Float {
        val text = plain(rich, lang, Int.MAX_VALUE, reflow)
        if (text.isEmpty()) return 0f
        return layout(text, lang, width(maxWidth, sizePx, maxChars), sizePx, Color.WHITE).height.toFloat()
    }

    override fun measureRevealedHeight(
        rich: RichText, lang: TextLang, maxWidth: Float, sizePx: Float,
        visibleGlyphs: Int, maxChars: Float, reflow: Reflow,
    ): Float {
        if (visibleGlyphs <= 0) return 0f
        val text = plain(rich, lang, visibleGlyphs, reflow)
        if (text.isEmpty()) return 0f
        return layout(text, lang, width(maxWidth, sizePx, maxChars), sizePx, Color.WHITE).height.toFloat()
    }

    override fun draw(
        canvas: Canvas, rich: RichText, lang: TextLang, x: Float, y: Float,
        maxWidth: Float, sizePx: Float, color: Int, visibleGlyphs: Int, maxChars: Float, reflow: Reflow,
    ): Float {
        // Lay out the full text (stable line breaks while revealing), then clip to the visible prefix.
        val full = plain(rich, lang, Int.MAX_VALUE, reflow)
        if (full.isEmpty()) return 0f
        val w = width(maxWidth, sizePx, maxChars)
        val fullLayout = layout(full, lang, w, sizePx, color)
        val text = if (visibleGlyphs >= Int.MAX_VALUE / 2) full else plain(rich, lang, visibleGlyphs, reflow)
        if (text.isNotEmpty()) {
            val l = if (text == full) fullLayout else layout(text, lang, w, sizePx, color)
            canvas.save()
            canvas.translate(x, y)
            l.draw(canvas)
            canvas.restore()
        }
        return fullLayout.height.toFloat()
    }
}

/** Audio sink that plays nothing (used until the AudioEngine is wired). */
class SilentAudio : AudioSink {
    override fun handle(event: EngineEvent) {}
    override fun replayVoice(voice: VoiceRef) {}
    override fun setVolumes(bgm: Float, se: Float, voice: Float) {}
    override fun pause() {}
    override fun resume() {}
    override fun release() {}
    override val isVoicePlaying: Boolean get() = false
}

/** Placeholder backend: draws stage diagnostics instead of pictures. */
class StubRenderBackend(decoder: BilingualTextDecoder) : RenderBackend {
    override val text: TextPainter = FallbackTextPainter(decoder)

    private class Handle : StageHandle {
        var last: StageState? = null
        override fun captureThumbnail(): Bitmap {
            val bmp = Bitmap.createBitmap(320, 180, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            c.drawColor(0xFF203040.toInt())
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 18f }
            c.drawText(last?.bg ?: "—", 10f, 30f, p)
            c.drawText(last?.cg ?: "", 10f, 55f, p)
            return bmp
        }
    }

    @Composable
    override fun rememberStageHandle(): StageHandle = remember { Handle() }

    @Composable
    override fun Stage(stage: StageState, handle: StageHandle, modifier: Modifier) {
        (handle as? Handle)?.last = stage
        Canvas(modifier.background(androidx.compose.ui.graphics.Color(0xFF101820))) {
            drawIntoCanvas { c ->
                val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF8090A0.toInt(); textSize = size.height / 30f }
                var y = p.textSize * 1.5f
                listOf(
                    "bg: ${stage.bg ?: "-"}  cg: ${stage.cg ?: "-"}",
                    "sprites: ${stage.sprites.joinToString { it.image }}",
                    "layers: ${stage.layers.joinToString { it.image }}  lut: ${stage.lut ?: "-"}",
                ).forEach { c.nativeCanvas.drawText(it, p.textSize, y, p); y += p.textSize * 1.4f }
            }
        }
    }

    override fun createAudio(): AudioSink = SilentAudio()

    @Composable
    override fun Movie(stem: String, onFinished: () -> Unit, modifier: Modifier) {
        LaunchedEffect(stem) {
            delay(1500)
            onFinished()
        }
        Box(modifier.background(androidx.compose.ui.graphics.Color.Black).clickable { onFinished() }, contentAlignment = Alignment.Center) {
            Text("影片 $stem 不可用 / ムービー再生不可", color = androidx.compose.ui.graphics.Color.Gray)
        }
    }

    override fun release() {}
}
