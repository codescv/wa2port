package com.wa2port.android.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import com.wa2port.core.engine.Stage
import com.wa2port.core.engine.StageState
import com.wa2port.core.image.AmpLut
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/**
 * Flattens the static part of a [StageState] (background, layers and event CG graded by effect LUT
 * slot [StageState.lut]; sprites graded by [StageState.spriteLut] then [StageState.spriteEffectLut],
 * scaled by `scale/256`, tinted by `bright/128`) into one opaque 1280×720 bitmap. Animated parts (transitions, overlay colour, shake,
 * weather) are drawn live by [StageView] on top of the result.
 *
 * Doing the composition once per stage change keeps per-frame work to 1–3 bitmap draws, and lets the
 * LUT be applied exactly (per-pixel [AmpLut] curves) instead of a ColorMatrix approximation.
 */
object StageComposer {
    /** Maximum number of standing sprites the engine displays. */
    const val MAX_SPRITES = 8

    private fun newPaint() = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

    /**
     * Composes [stage] using images from [cache] (decoded in parallel on IO). Missing images are
     * skipped. Runs the pixel work on [Dispatchers.Default].
     */
    suspend fun compose(stage: StageState, cache: AssetImageCache): Bitmap = coroutineScope {
        val sprites = stage.sprites.sortedBy { it.order }.takeLast(MAX_SPRITES)
        val layers = stage.layers.filter { it.visible && it.alpha > 0 }.sortedWith(compareBy({ it.layer }, { it.slot }))
        // Kick off all decodes concurrently.
        // WA2.exe LUT routing: backgrounds/CG/layers get effect slot 1 only; sprites get the
        // background's B….amp (or time-mode default) and then effect slot 2.
        val bgLut = stage.lut
        val bgJob = async { stage.bg?.let { cache.getGraded(stage.bgDir, it, bgLut) } }
        val spriteJobs = sprites.map { async { cache.getGraded("char", it.image, stage.spriteLut, stage.spriteEffectLut) } }
        val layerJobs = layers.map { async { cache.getGraded(it.dir, it.image, bgLut) } }
        val cgJob = async { stage.cg?.let { cache.getGraded("grp", it, bgLut) } }
        val bg = bgJob.await()
        val spriteBmps = spriteJobs.awaitAll()
        val layerBmps = layerJobs.awaitAll()
        val cg = cgJob.await()
        withContext(Dispatchers.Default) {
            val paint = newPaint()
            val out = Bitmap.createBitmap(Stage.WIDTH, Stage.HEIGHT, Bitmap.Config.ARGB_8888)
            val c = Canvas(out)
            c.drawColor(0xFF000000.toInt())
            bg?.let { drawFull(c, it, paint) }
            sprites.forEachIndexed { i, sp ->
                val b = spriteBmps[i] ?: return@forEachIndexed
                // Bottom-aligned, horizontally centred on the slot x; scale in 1/256 anchored at the feet.
                val s = sp.scale.coerceAtLeast(1) / 256f
                val w = b.width * s
                val h = b.height * s
                val left = sp.centerX - w / 2f
                val top = Stage.HEIGHT - h
                val p = if (sp.bright == 128) paint else Paint(paint).apply {
                    // Brightness: per-channel multiply by bright/128 (128 = neutral).
                    val k = sp.bright.coerceIn(0, 255) / 128f
                    colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setScale(k, k, k, 1f) })
                }
                c.drawBitmap(b, null, RectF(left, top, left + w, top + h), p)
            }
            layers.forEachIndexed { i, l ->
                val b = layerBmps[i] ?: return@forEachIndexed
                val p = Paint(paint).apply { alpha = l.alpha.coerceIn(0, 255) }
                if (l.zoom == 1f) {
                    c.drawBitmap(b, l.x.toFloat(), l.y.toFloat(), p)
                } else {
                    val m = Matrix().apply { setScale(l.zoom, l.zoom); postTranslate(l.x.toFloat(), l.y.toFloat()) }
                    c.drawBitmap(b, m, p)
                }
            }
            cg?.let { drawFull(c, it, paint) }
            out.setHasAlpha(false)
            out
        }
    }

    /** Draws a background/CG: native size at the origin, centred when narrower/shorter than the stage. */
    private fun drawFull(c: Canvas, b: Bitmap, paint: Paint) {
        val x = if (b.width < Stage.WIDTH) (Stage.WIDTH - b.width) / 2f else 0f
        val y = if (b.height < Stage.HEIGHT) (Stage.HEIGHT - b.height) / 2f else 0f
        c.drawBitmap(b, x, y, paint)
    }

    /**
     * Applies [lut] to [bmp] in place (exe semantics, see [AmpLut]): α = 0 pixels are untouched and
     * partially transparent pixels blend the graded colour by alpha. `getPixels` yields straight colours.
     */
    fun applyLut(bmp: Bitmap, lut: AmpLut) {
        val w = bmp.width
        val h = bmp.height
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        for (i in px.indices) px[i] = lut.apply(px[i])
        bmp.setPixels(px, 0, w, 0, 0, w, h)
    }
}
