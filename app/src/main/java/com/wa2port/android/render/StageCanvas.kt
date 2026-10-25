package com.wa2port.android.render

import android.graphics.Bitmap
import android.graphics.PointF
import android.graphics.RectF
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.wa2port.core.engine.StageState

/**
 * Handle to the [StageView] hosted by a [StageCanvas], usable from outside the composable
 * (save-slot thumbnails, touch mapping). All methods must be called on the main thread and return
 * `null` while no view is attached.
 */
class StageController {
    internal var view: StageView? = null

    /** Thumbnail of the current stage picture (default 320×180). */
    fun captureThumbnail(width: Int = StageView.THUMB_WIDTH, height: Int = StageView.THUMB_HEIGHT): Bitmap? =
        view?.captureThumbnail(width, height)

    /** View-local pixel → stage coordinates (1280×720 space), `null` outside the letterboxed stage. */
    fun screenToStage(x: Float, y: Float): PointF? = view?.screenToStage(x, y)

    /** Stage coordinates → view-local pixels. */
    fun stageToScreen(x: Float, y: Float): PointF? = view?.stageToScreen(x, y)

    /** Letterboxed stage rectangle in view-local pixels. */
    val stageBounds: RectF? get() = view?.stageBoundsOnScreen

    /** True while a transition is animating. */
    val isTransitioning: Boolean get() = view?.isTransitioning == true

    /** Completes the running transition immediately. */
    fun finishTransition() {
        view?.finishTransition()
    }
}

/** Remembers a [StageController] for [StageCanvas]. */
@Composable
fun rememberStageController(): StageController = remember { StageController() }

/**
 * Composable wrapper around [StageView]: draws [stage] letterboxed (16:9) inside [modifier]'s bounds.
 * Recomposing with a new [StageState] submits it to the view; the view is released when it leaves
 * the composition.
 */
@Composable
fun StageCanvas(
    stage: StageState,
    cache: AssetImageCache,
    modifier: Modifier = Modifier,
    controller: StageController = rememberStageController(),
) {
    AndroidView(
        factory = { ctx ->
            StageView(ctx).also {
                it.cache = cache
                controller.view = it
            }
        },
        modifier = modifier,
        update = { v ->
            if (v.cache !== cache) v.cache = cache
            controller.view = v
            v.setStage(stage)
        },
        onRelease = { v ->
            if (controller.view === v) controller.view = null
            v.release()
        },
    )
}
