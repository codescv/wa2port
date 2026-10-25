package com.wa2port.android.render

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.os.SystemClock
import android.util.AttributeSet
import android.util.Log
import android.view.View
import com.wa2port.core.engine.Stage
import com.wa2port.core.engine.StageState
import com.wa2port.core.engine.TransitionKind
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * The WA2 stage: a 1280×720 virtual canvas letterboxed (16:9, black bars) into the view.
 *
 * Feed it engine snapshots with [setStage] (cheap to call every frame — unchanged states are
 * ignored). Each new state is flattened off the UI thread by [StageComposer]; when the
 * [StageState.serial] changed the new picture is revealed with the state's transition:
 * * [TransitionKind.CUT] — instant;
 * * [TransitionKind.FADE] — crossfade over `durationMs`;
 * * [TransitionKind.MASK] — pattern dissolve driven by the grey mask `grp/f0NNN.bmp` (GPU: a
 *   ColorMatrix maps grey → alpha with the `pf0x.psh` threshold ramp, then `DST_IN`).
 *
 * Drawn live on top: overlay colour ([StageState.overlayColor], faded over
 * [StageState.overlayFadeMs]), screen shake ([StageState.shake]) and
 * snow particles ([StageState.weather] != null).
 *
 * Use [screenToStage] / [stageToScreen] for touch mapping and [captureThumbnail] for save slots.
 * Call [release] when discarding the view (the Compose wrapper [StageCanvas] does this).
 */
class StageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    /** Image source; must be set before pictures can be composed (states submitted earlier are kept). */
    var cache: AssetImageCache? = null
        set(value) {
            field = value
            val p = pending
            if (value != null && p != null && composeJob?.isActive != true) {
                pending = null
                startCompose(p)
            }
        }

    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var composeJob: Job? = null
    private var pending: StageState? = null
    private var requested: StageState? = null

    // Displayed pictures.
    private var current: Bitmap? = null
    private var currentState: StageState? = null
    private var previous: Bitmap? = null
    private var mask: Bitmap? = null
    private var transitionKind = TransitionKind.CUT
    private var transitionStart = 0L
    private var transitionMs = 0L

    private val stageRect = RectF()
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val fadePaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val maskPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
    }
    private val maskMatrix = ColorMatrix()
    private val fillPaint = Paint()
    private val snowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
    private val stageBounds = RectF(0f, 0f, Stage.WIDTH.toFloat(), Stage.HEIGHT.toFloat())

    // Overlay colour fade (FB(time, r, g, b)).
    private var overlayFrom = 0
    private var overlayTo = 0
    private var overlayStart = 0L
    private var overlayMs = 0L

    private val snow = SnowField(SNOW_FLAKES)
    private var lastFrameMs = 0L

    /** Rectangle (view pixels) the 1280×720 stage occupies after letterboxing. */
    val stageBoundsOnScreen: RectF get() = RectF(stageRect)

    /** True while a transition animation is running. */
    val isTransitioning: Boolean get() = previous != null

    /** Submits a new engine snapshot. Must be called on the main thread. */
    fun setStage(stage: StageState) {
        if (stage == requested) return
        requested = stage
        if (composeJob?.isActive == true) {
            pending = stage // conflate: compose the latest once the running job finishes
            return
        }
        startCompose(stage)
    }

    private fun startCompose(stage: StageState) {
        val c = cache ?: run { pending = stage; return }
        composeJob = scope.launch {
            try {
                val bmp = StageComposer.compose(stage, c)
                val newMask = if (stage.transition.kind == TransitionKind.MASK) stage.transition.mask?.let { c.get("grp", it) } else null
                present(stage, bmp, newMask)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "stage compose failed", e)
            }
            val next = pending
            pending = null
            if (next != null) startCompose(next)
        }
    }

    private fun present(stage: StageState, bmp: Bitmap, newMask: Bitmap?) {
        val old = currentState
        val serialChanged = old == null || old.serial != stage.serial
        val tr = stage.transition
        val animate = serialChanged && old != null && tr.kind != TransitionKind.CUT && tr.durationMs > 0 &&
            (tr.kind != TransitionKind.MASK || newMask != null)
        if (animate) {
            previous = current // a transition interrupted mid-way restarts from the last full picture
            transitionKind = tr.kind
            transitionMs = tr.durationMs
            transitionStart = SystemClock.uptimeMillis()
            mask = newMask?.also { it.setHasAlpha(true) } // keep blending on for the DST_IN pass
        } else {
            previous = null
            mask = null
        }
        if (old == null || old.overlayColor != stage.overlayColor) {
            val now = SystemClock.uptimeMillis()
            overlayFrom = overlayAt(now)
            overlayTo = stage.overlayColor
            overlayStart = now
            overlayMs = stage.overlayFadeMs
        }
        current = bmp
        currentState = stage
        invalidate()
    }

    /** Skips the running transition (e.g. on tap / skip mode). */
    fun finishTransition() {
        previous = null
        mask = null
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val scale = min(w / Stage.WIDTH.toFloat(), h / Stage.HEIGHT.toFloat())
        val sw = Stage.WIDTH * scale
        val sh = Stage.HEIGHT * scale
        stageRect.set((w - sw) / 2f, (h - sh) / 2f, (w + sw) / 2f, (h + sh) / 2f)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(0xFF000000.toInt())
        if (stageRect.isEmpty) return
        val now = SystemClock.uptimeMillis()
        val dt = if (lastFrameMs == 0L) 16L else (now - lastFrameMs).coerceIn(0L, 100L)
        lastFrameMs = now
        val state = currentState
        var animating = false

        canvas.save()
        canvas.clipRect(stageRect)
        canvas.translate(stageRect.left, stageRect.top)
        val scale = stageRect.width() / Stage.WIDTH
        canvas.scale(scale, scale)
        // Screen shake: deterministic jitter, amplitude = shake value (px on the 1280 stage).
        val shake = state?.shake ?: 0
        if (shake != 0) {
            val amp = min(abs(shake), MAX_SHAKE_PX).toFloat()
            val t = now / 1000.0
            canvas.translate((sin(t * 2 * PI * 23) * amp).toFloat(), (sin(t * 2 * PI * 17 + 1.3) * amp * 0.6f).toFloat())
            animating = true
        }

        val prev = previous
        val cur = current
        if (prev != null && cur != null) {
            val p = ((now - transitionStart).toFloat() / max(1L, transitionMs)).coerceIn(0f, 1f)
            canvas.drawBitmap(prev, null, stageBounds, bitmapPaint)
            val m = mask
            if (transitionKind == TransitionKind.MASK && m != null) {
                val save = canvas.saveLayer(stageBounds, null)
                canvas.drawBitmap(cur, null, stageBounds, bitmapPaint)
                maskPaint.colorFilter = ColorMatrixColorFilter(maskAlphaMatrix(p))
                canvas.drawBitmap(m, null, stageBounds, maskPaint)
                canvas.restoreToCount(save)
            } else {
                fadePaint.alpha = (p * 255).toInt()
                canvas.drawBitmap(cur, null, stageBounds, fadePaint)
            }
            if (p >= 1f) {
                previous = null
                mask = null
            }
            animating = true
        } else if (cur != null) {
            canvas.drawBitmap(cur, null, stageBounds, bitmapPaint)
        }

        if (state != null) {
            val overlay = overlayAt(now)
            if (overlay ushr 24 != 0) {
                fillPaint.color = overlay
                canvas.drawRect(stageBounds, fillPaint)
            }
            if (overlayMs > 0 && now - overlayStart < overlayMs) animating = true
            if (state.weather != null) {
                snow.step(dt)
                snow.draw(canvas, snowPaint)
                animating = true
            }
        }
        canvas.restore()
        if (animating) postInvalidateOnAnimation()
    }

    /** Overlay colour at time [now]: ARGB lerp; fading from/to 0 keeps the other end's RGB. */
    private fun overlayAt(now: Long): Int {
        if (overlayMs <= 0 || now - overlayStart >= overlayMs) return overlayTo
        val p = ((now - overlayStart).toFloat() / overlayMs).coerceIn(0f, 1f)
        val from = if (overlayFrom == 0) overlayTo and 0x00FFFFFF else overlayFrom
        val to = if (overlayTo == 0) overlayFrom and 0x00FFFFFF else overlayTo
        var out = 0
        for (shift in intArrayOf(24, 16, 8, 0)) {
            val a = (from ushr shift) and 0xFF
            val b = (to ushr shift) and 0xFF
            out = out or (((a + (b - a) * p).toInt() and 0xFF) shl shift)
        }
        return out
    }

    /**
     * Grey → alpha ramp for the pattern dissolve at progress [p] (0..1), the `pf0x.psh`
     * `add_x4 r0.a, mask, c0` threshold: alpha = clamp((grey − 1 + p·(1+s)) / s) with softness s.
     * Bright mask pixels are revealed first.
     */
    private fun maskAlphaMatrix(p: Float): ColorMatrix {
        val s = MASK_SOFTNESS
        val offset = 255f * (p * (1f + s) - 1f) / s
        maskMatrix.set(
            floatArrayOf(
                0f, 0f, 0f, 0f, 0f,
                0f, 0f, 0f, 0f, 0f,
                0f, 0f, 0f, 0f, 0f,
                1f / s, 0f, 0f, 0f, offset,
            ),
        )
        return maskMatrix
    }

    /** Maps a point in view coordinates to stage coordinates (0..1280, 0..720), or `null` outside the stage. */
    fun screenToStage(x: Float, y: Float): PointF? {
        if (stageRect.isEmpty || !stageRect.contains(x, y)) return null
        val s = Stage.WIDTH / stageRect.width()
        return PointF((x - stageRect.left) * s, (y - stageRect.top) * s)
    }

    /** Maps stage coordinates to view coordinates. */
    fun stageToScreen(x: Float, y: Float): PointF {
        val s = stageRect.width() / Stage.WIDTH
        return PointF(stageRect.left + x * s, stageRect.top + y * s)
    }

    /**
     * Returns a [width]×[height] copy of the current (fully transitioned) stage picture, including
     * the overlay colour, for save-slot thumbnails. Returns a black bitmap before the first picture.
     */
    fun captureThumbnail(width: Int = THUMB_WIDTH, height: Int = THUMB_HEIGHT): Bitmap {
        val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(0xFF000000.toInt())
        val src = current ?: return out
        c.drawBitmap(src, null, RectF(0f, 0f, width.toFloat(), height.toFloat()), Paint(Paint.FILTER_BITMAP_FLAG))
        overlayTo.takeIf { it ushr 24 != 0 }?.let { c.drawColor(it) }
        return out
    }

    /** Cancels pending compositions and drops the pictures. The view must not be reused afterwards. */
    fun release() {
        scope.cancel()
        composeJob = null
        pending = null
        current = null
        previous = null
        mask = null
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        lastFrameMs = 0L
    }

    private fun abs(v: Int) = if (v < 0) -v else v

    /** Simple snow particle system in stage coordinates. */
    private class SnowField(n: Int) {
        private val x = FloatArray(n)
        private val y = FloatArray(n)
        private val r = FloatArray(n)
        private val vy = FloatArray(n)
        private val phase = FloatArray(n)
        private val rnd = Random(2012)
        private var time = 0f

        init {
            for (i in 0 until n) spawn(i, rnd.nextFloat() * Stage.HEIGHT)
        }

        private fun spawn(i: Int, startY: Float) {
            x[i] = rnd.nextFloat() * Stage.WIDTH
            y[i] = startY
            r[i] = 1.5f + rnd.nextFloat() * 3.5f
            vy[i] = 30f + r[i] * 18f + rnd.nextFloat() * 20f // larger flakes fall faster (parallax)
            phase[i] = rnd.nextFloat() * 6.28f
        }

        fun step(dtMs: Long) {
            val dt = dtMs / 1000f
            time += dt
            for (i in x.indices) {
                y[i] += vy[i] * dt
                x[i] += sin(time * 0.9f + phase[i]) * 12f * dt
                if (y[i] > Stage.HEIGHT + 8) spawn(i, -8f)
            }
        }

        fun draw(c: Canvas, p: Paint) {
            for (i in x.indices) {
                p.alpha = (110 + r[i] * 25).toInt().coerceAtMost(235)
                c.drawCircle(x[i], y[i], r[i], p)
            }
        }
    }

    companion object {
        private const val TAG = "StageView"
        private const val MASK_SOFTNESS = 0.25f
        private const val MAX_SHAKE_PX = 24
        private const val SNOW_FLAKES = 160

        /** Default thumbnail size (16:9). */
        const val THUMB_WIDTH = 320
        const val THUMB_HEIGHT = 180
    }
}
