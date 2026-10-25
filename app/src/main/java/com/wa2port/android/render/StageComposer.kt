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
 * Flattens the static part of a [StageState] (background → sprites (graded by
 * [StageState.spriteLut], scaled by `scale/256`, tinted by `bright/128`) → bitmap layers → event CG →
 * `.AMP` LUT) into one opaque 1280×720 bitmap. Animated parts (transitions, overlay colour, shake,
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
        val bgJob = async { stage.bg?.let { cache.get(stage.bgDir, it) } }
        val spriteLut = stage.spriteLut
        val spriteJobs = sprites.map {
            async { if (spriteLut != null) cache.getGraded("char", it.image, spriteLut) else cache.get("char", it.image) }
        }
        val layerJobs = layers.map { async { cache.get(it.dir, it.image) } }
        val cgJob = async { stage.cg?.let { cache.get("grp", it) } }
        val lutJob = async(Dispatchers.IO) { stage.lut?.let { cache.lut(it) } }
        val bg = bgJob.await()
        val spriteBmps = spriteJobs.awaitAll()
        val layerBmps = layerJobs.awaitAll()
        val cg = cgJob.await()
        val lut = lutJob.await()
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
            lut?.let { applyLut(out, it) }
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

    /** Applies [lut] to every (opaque) pixel of [bmp] in place. */
    fun applyLut(bmp: Bitmap, lut: AmpLut) {
        val w = bmp.width
        val h = bmp.height
        // Fold master + channel curves into three 256-entry tables (same maths as AmpLut.apply).
        val rT = IntArray(256)
        val gT = IntArray(256)
        val bT = IntArray(256)
        val m = lut.master
        for (i in 0 until 256) {
            val v = m?.get(i) ?: i
            rT[i] = lut.red[v] shl 16
            gT[i] = lut.green[v] shl 8
            bT[i] = lut.blue[v]
        }
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        val mono = lut.monochrome
        for (i in px.indices) {
            val p = px[i]
            var r = (p ushr 16) and 0xFF
            var g = (p ushr 8) and 0xFF
            var b = p and 0xFF
            if (mono) {
                val y = (r * 77 + g * 150 + b * 29) shr 8
                r = y; g = y; b = y
            }
            px[i] = (p and 0xFF000000.toInt()) or rT[r] or gT[g] or bT[b]
        }
        bmp.setPixels(px, 0, w, 0, 0, w, h)
    }
}
