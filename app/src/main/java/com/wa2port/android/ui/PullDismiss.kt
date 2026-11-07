package com.wa2port.android.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Pure pull-down-to-dismiss rules (px; `+y` = finger moving down = towards dismissal).
 *
 * * A sheet dismisses when released at `offset ≥ min(120 dp, 0.22 · sheetHeight)` (120 dp while the
 *   height is still unknown, never 0), or at `offset ≥ 24 dp` with a downward release velocity
 *   `≥ 1000 dp/s`; otherwise it springs back to 0.
 * * Nested scroll: only `UserInput` drags ever move the sheet — fling momentum (`SideEffect`) that
 *   reaches the list top can never start a pull. While displaced, every user drag goes to the sheet
 *   first (down keeps pulling 1:1 with the list frozen, up brings the sheet home before the list
 *   scrolls); at rest, a downward drag the child could not consume (list at its top) starts a pull.
 */
object PullDismissLogic {
    const val MAX_DISTANCE_DP = 120f
    const val DISTANCE_FRAC = 0.22f
    const val VELOCITY_DP_S = 1000f
    const val MIN_TRAVEL_FOR_VELOCITY_DP = 24f

    /** Downward pull (keyboard open) that hides the keyboard instead of moving the sheet. */
    const val IME_HIDE_PULL_DP = 24f

    fun distanceThresholdPx(sheetHeightPx: Float, density: Float): Float =
        if (sheetHeightPx <= 0f) MAX_DISTANCE_DP * density else minOf(MAX_DISTANCE_DP * density, DISTANCE_FRAC * sheetHeightPx)

    fun shouldDismiss(offsetPx: Float, velocityPxS: Float, sheetHeightPx: Float, density: Float): Boolean =
        offsetPx > 0f && (
            offsetPx >= distanceThresholdPx(sheetHeightPx, density) ||
                (offsetPx >= MIN_TRAVEL_FOR_VELOCITY_DP * density && velocityPxS >= VELOCITY_DP_S * density)
            )

    /** Scrim alpha: 1 at rest, fading linearly to 0 as the sheet slides one full height down. */
    fun scrimAlpha(offsetPx: Float, sheetHeightPx: Float): Float =
        if (sheetHeightPx <= 0f) 1f else (1f - offsetPx / sheetHeightPx).coerceIn(0f, 1f)

    /** Pull taken BEFORE the child scrolls: only while displaced, only user drags. */
    fun preScroll(offsetPx: Float, availableY: Float, userInput: Boolean): Float = when {
        !userInput || offsetPx <= 0f -> 0f
        availableY < 0f -> maxOf(availableY, -offsetPx)
        else -> availableY
    }

    /** Pull taken AFTER the child could not scroll (at its top): a user drag downwards. */
    fun postScroll(availableY: Float, userInput: Boolean): Float = if (userInput && availableY > 0f) availableY else 0f

    // ------------------------------------------------------------------ entrance (slide up from the bottom)

    /** Bottom-to-top entrance duration (ms). */
    const val ENTER_DURATION_MS = 260

    /** Material 3 "Emphasized Decelerate": fast start, long gentle landing. */
    val EnterEasing: Easing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)

    /**
     * Entrance progress (0 = parked one full height below, 1 = at rest) of a sheet on its first
     * frame: 0 when it should slide in, 1 when it appears in place (e.g. `下一句 ▶` re-opening the
     * sheet on the next script line, which is a continuation and not a new opening).
     */
    fun initialEnterProgress(slideIn: Boolean): Float = if (slideIn) 0f else 1f

    /** Eased entrance progress [elapsedMs] after the mount ([ENTER_DURATION_MS], [EnterEasing]). */
    fun enterProgressAt(elapsedMs: Long): Float {
        val t = (elapsedMs.toFloat() / ENTER_DURATION_MS).coerceIn(0f, 1f)
        return if (t >= 1f) 1f else EnterEasing.transform(t)
    }

    /** Entrance offset (px, + = down): one full [heightPx] at progress 0, 0 at progress 1. */
    fun entranceOffsetPx(enterProgress: Float, heightPx: Float): Float =
        (1f - enterProgress.coerceIn(0f, 1f)) * heightPx.coerceAtLeast(0f)

    /**
     * Total downward translation of the sheet: the entrance offset plus the pull [pullOffsetPx]
     * ([PullDismissState.offsetPx]). Additive, so a pull or a slide-out that starts mid-entrance
     * composes with it and neither can leave the sheet stuck displaced. [heightPx] is the sheet's
     * own laid-out height (read in the draw phase, so it is exact on the very first frame —
     * no frame-0 flash at `y = 0` before `onSizeChanged` reports the size).
     */
    fun sheetTranslationPx(enterProgress: Float, heightPx: Float, pullOffsetPx: Float): Float =
        entranceOffsetPx(enterProgress, heightPx) + pullOffsetPx.coerceAtLeast(0f)

    /**
     * Backdrop scrim alpha of an entering / pulled sheet: tracks the sheet's position
     * ([scrimAlpha] of [sheetTranslationPx]) — 0 on the first entrance frame, 1 at rest, fading as it
     * is pulled. With an unknown height (≤ 0) it follows [enterProgress] alone.
     */
    fun overlayScrimAlpha(enterProgress: Float, heightPx: Float, pullOffsetPx: Float): Float =
        if (heightPx <= 0f) enterProgress.coerceIn(0f, 1f) else scrimAlpha(sheetTranslationPx(enterProgress, heightPx, pullOffsetPx), heightPx)

    /**
     * Whether the entrance should (keep) running: always, except during a slide-out ([dismissing]),
     * which freezes it so the sheet only ever moves down while leaving. A [PullDismissState.reset]
     * (dismissing → false) resumes a frozen entrance, so it can never stay part-way up.
     */
    fun entranceRuns(dismissing: Boolean, enterProgress: Float): Boolean = !dismissing && enterProgress < 1f
}

/** Runs one sheet animation from [from] to [to], reporting every frame to [onFrame]. */
fun interface PullDismissAnimator {
    /** [dismiss]: slide-out tween; otherwise the no-bounce settle spring starting at [initialVelocity]. */
    suspend fun run(from: Float, to: Float, initialVelocity: Float, dismiss: Boolean, onFrame: (Float) -> Unit)
}

/** 200 ms `FastOutLinearIn` slide-out / no-bounce `StiffnessMediumLow` settle spring. */
internal val DefaultPullDismissAnimator = PullDismissAnimator { from, to, v, dismiss, onFrame ->
    if (dismiss) {
        animate(from, to, animationSpec = tween(durationMillis = 200, easing = FastOutLinearInEasing)) { x, _ -> onFrame(x) }
    } else {
        animate(from, to, initialVelocity = v, animationSpec = spring(Spring.DampingRatioNoBouncy, Spring.StiffnessMediumLow)) { x, _ -> onFrame(x) }
    }
}

/**
 * Pull-down-to-dismiss state of one sheet ([PullDismissLogic] rules). [offsetPx] (≥ 0) is the sheet's
 * downward translation; feed it from [connection] (nested scroll of the sheet's lists, via
 * [pullDismissSheet]) and [dragBy] / [release] (drag handle, via [pullDismissHandle]).
 *
 * Keyboard: a gesture that starts while the IME is visible is latched for its whole duration — it
 * hides the keyboard once after [PullDismissLogic.IME_HIDE_PULL_DP] and can neither move nor dismiss
 * the sheet; the next gesture can. Anti-stuck: [settleIfIdle] (passive pointer-up watcher, list
 * scroll end) springs a displaced sheet home and clears a latched gesture when nothing else will.
 */
@Stable
class PullDismissState internal constructor(
    private val scope: CoroutineScope,
    density: Float,
    private val onDismiss: () -> Unit,
    private val isImeVisible: () -> Boolean,
    private val onHideIme: () -> Unit,
    private val animator: PullDismissAnimator = DefaultPullDismissAnimator,
) {
    /** px per dp; refreshed on every composition so multi-window density changes keep thresholds right. */
    var density: Float by mutableFloatStateOf(density)
        internal set

    var offsetPx: Float by mutableFloatStateOf(0f)
        private set

    /** Measured sheet height (px; 0 = unknown → 120 dp distance threshold). */
    var sheetHeightPx: Float by mutableFloatStateOf(0f)
        internal set

    /** True from the start of the slide-out until the state is [reset]. */
    var dismissing: Boolean by mutableStateOf(false)
        private set

    internal var handleDragging = false
    internal var pointerDown = false

    internal var gestureActive = false
        private set
    internal var imeCapture = false
        private set
    private var imeHidden = false
    private var imePull = 0f
    private var settleJob: Job? = null
    private var idleJob: Job? = null

    /** True while a settle spring or the slide-out is running. */
    val isAnimating: Boolean get() = settleJob?.isActive == true

    private fun beginGesture() {
        if (gestureActive) return
        gestureActive = true
        imeCapture = isImeVisible()
        imeHidden = false
        imePull = 0f
        settleJob?.cancel()
    }

    private fun endGesture() {
        gestureActive = false
        imeCapture = false
        imeHidden = false
        imePull = 0f
    }

    /** Applies a pull of [dy] px (+ = down); returns the part consumed by the sheet. */
    internal fun applyPull(dy: Float): Float {
        if (dismissing || dy == 0f) return 0f
        beginGesture()
        if (imeCapture) {
            if (!imeHidden && dy > 0f) {
                imePull += dy
                if (imePull >= PullDismissLogic.IME_HIDE_PULL_DP * density) {
                    imeHidden = true
                    onHideIme()
                }
            }
            return 0f
        }
        val next = (offsetPx + dy).coerceAtLeast(0f)
        val used = next - offsetPx
        offsetPx = next
        return used
    }

    /** Drag-handle delta (px, + = down). */
    fun dragBy(dy: Float): Float = applyPull(dy)

    /**
     * Gesture end with downward velocity [velocityY] (px/s): dismiss past the thresholds, otherwise
     * spring home. A keyboard-latched gesture never dismisses (it only springs home if displaced).
     */
    fun release(velocityY: Float) {
        val captured = imeCapture
        endGesture()
        if (dismissing || offsetPx <= 0f) return
        settleJob?.cancel()
        if (!captured && PullDismissLogic.shouldDismiss(offsetPx, velocityY, sheetHeightPx, density)) {
            dismiss()
        } else {
            val from = offsetPx
            settleJob = scope.launch {
                animator.run(from, 0f, velocityY.coerceAtLeast(0f), dismiss = false) { x -> offsetPx = x.coerceAtLeast(0f) }
                offsetPx = 0f
            }
        }
    }

    /** Slides the sheet out (200 ms), then calls `onDismiss` — only if the slide-out completes. */
    fun dismiss() {
        if (dismissing) return
        dismissing = true
        endGesture()
        settleJob?.cancel()
        val from = offsetPx
        val target = maxOf(from, if (sheetHeightPx > 0f) sheetHeightPx else from + PullDismissLogic.MAX_DISTANCE_DP * density)
        settleJob = scope.launch {
            animator.run(from, target, 0f, dismiss = true) { x -> offsetPx = x }
            onDismiss()
        }
    }

    /** Back to rest at once (new sentence, rotation overshoot, disposal); a running slide-out never dismisses. */
    fun reset() {
        settleJob?.cancel()
        settleJob = null
        idleJob?.cancel()
        idleJob = null
        offsetPx = 0f
        dismissing = false
        handleDragging = false
        endGesture()
    }

    /**
     * Anti-stuck net, called when every pointer is up / a list stopped scrolling: when no handle drag,
     * pointer, animation or slide-out owns the sheet, springs a displaced sheet home ([release] with
     * 0 velocity) or clears a latched (e.g. keyboard-captured) gesture whose fling never came.
     */
    fun settleIfIdle() {
        if (dismissing || handleDragging || pointerDown || settleJob?.isActive == true) return
        if (offsetPx > 0f) release(0f) else if (gestureActive) endGesture()
    }

    /** [settleIfIdle] after 3 frames, so `onPreFling` / `onDragStopped` decide first with the real velocity. */
    internal fun scheduleSettle() {
        idleJob?.cancel()
        idleJob = scope.launch {
            delay(SETTLE_DELAY_MS)
            settleIfIdle()
        }
    }

    internal fun onHandleDragStarted() {
        handleDragging = true
        settleJob?.takeIf { !dismissing }?.cancel()
    }

    internal fun onHandleDragStopped(velocityY: Float) {
        handleDragging = false
        release(velocityY)
    }

    /** Nested-scroll hook for the sheet's lists (installed by [pullDismissSheet]). */
    val connection: NestedScrollConnection = object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            if (source != NestedScrollSource.UserInput || dismissing || offsetPx <= 0f) return Offset.Zero
            val want = PullDismissLogic.preScroll(offsetPx, available.y, userInput = true)
            return if (want == 0f) Offset.Zero else Offset(0f, applyPull(want))
        }

        override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
            if (source != NestedScrollSource.UserInput || dismissing) return Offset.Zero
            val want = PullDismissLogic.postScroll(available.y, userInput = true)
            return if (want == 0f) Offset.Zero else Offset(0f, applyPull(want))
        }

        override suspend fun onPreFling(available: Velocity): Velocity {
            val displaced = offsetPx > 0f
            if (!displaced && !gestureActive) return Velocity.Zero
            release(available.y)
            // Swallow the fling while the sheet settles / slides out so the list never scrolls under it.
            return if (displaced) available else Velocity.Zero
        }
    }

    internal companion object {
        const val SETTLE_DELAY_MS = 48L
    }
}

/** A [PullDismissState] calling [onDismiss] after the slide-out; hides the keyboard on a keyboard-latched pull. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun rememberPullDismissState(onDismiss: () -> Unit): PullDismissState {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current.density
    val currentOnDismiss by rememberUpdatedState(onDismiss)
    val imeVisible by rememberUpdatedState(WindowInsets.isImeVisible)
    val keyboard by rememberUpdatedState(LocalSoftwareKeyboardController.current)
    val focus by rememberUpdatedState(LocalFocusManager.current)
    val state = remember(scope) {
        PullDismissState(
            scope = scope,
            density = density,
            onDismiss = { currentOnDismiss() },
            isImeVisible = { imeVisible },
            onHideIme = {
                keyboard?.hide()
                focus.clearFocus()
            },
        )
    }
    SideEffect { state.density = density }
    DisposableEffect(state) { onDispose { state.reset() } }
    return state
}

/**
 * Bottom-to-top entrance of a pull-to-dismiss sheet: [progress] runs 0 → 1 over
 * [PullDismissLogic.ENTER_DURATION_MS] with [PullDismissLogic.EnterEasing]. Feed it to
 * [PullDismissLogic.sheetTranslationPx] / [PullDismissLogic.overlayScrimAlpha] and read [progress]
 * in the draw phase only (`graphicsLayer` / `drawBehind`): no recomposition per frame.
 */
@Stable
class SheetEntrance internal constructor(initialProgress: Float) {
    internal val anim = Animatable(initialProgress)

    /** 0 = parked one full height below the container, 1 = at rest. */
    val progress: Float get() = anim.value
}

/**
 * A [SheetEntrance] scoped to the caller's composition lifetime: `remember {}` with no key, so
 * swapping the sheet's content (`◀ 上一句` / `下一句 ▶` / `↻` replace the state without unmounting),
 * recomposition and live rotation (same composition slot) never replay it — only a fresh mount
 * does. [slideIn] is read on the mount only (false = appear at rest). The entrance freezes while
 * [pull] slides out (the sheet only moves down while leaving) and resumes if the slide-out is
 * [PullDismissState.reset], so it never stays part-way up. A pull during the entrance just adds to
 * it ([PullDismissLogic.sheetTranslationPx]).
 */
@Composable
fun rememberSheetEntrance(pull: PullDismissState, slideIn: Boolean = true): SheetEntrance {
    val entrance = remember { SheetEntrance(PullDismissLogic.initialEnterProgress(slideIn)) }
    val dismissing = pull.dismissing
    // A key change cancels the running animateTo (value kept), so the slide-out freezes it.
    LaunchedEffect(entrance, dismissing) {
        if (PullDismissLogic.entranceRuns(dismissing, entrance.progress)) {
            entrance.anim.animateTo(1f, tween(PullDismissLogic.ENTER_DURATION_MS, easing = PullDismissLogic.EnterEasing))
        }
    }
    return entrance
}

/**
 * The sheet container: measures [PullDismissState.sheetHeightPx], hosts the nested-scroll
 * [PullDismissState.connection] (must precede any `verticalScroll` / lazy list in the chain) and a
 * passive Final-pass pointer watcher — it never consumes anything (scrollables below keep their
 * touch-slop detection) and only calls [PullDismissState.settleIfIdle] after every pointer is up or
 * cancelled, so a list swap or a cancelled scroll can never leave the sheet displaced.
 */
fun Modifier.pullDismissSheet(state: PullDismissState): Modifier = this
    .onSizeChanged { state.sheetHeightPx = it.height.toFloat() }
    .nestedScroll(state.connection)
    .pointerInput(state) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Final)
            state.pointerDown = true
            try {
                do {
                    val anyPressed = awaitPointerEvent(PointerEventPass.Final).changes.any { it.pressed }
                } while (anyPressed)
            } finally {
                state.pointerDown = false
                state.scheduleSettle()
            }
        }
    }

/**
 * Drag zone (handle + header): drags the sheet 1:1 whatever the lists' scroll positions. Claims the
 * pointer only after vertical touch slop, so child clicks and horizontal chip rows keep working.
 */
fun Modifier.pullDismissHandle(state: PullDismissState): Modifier = composed {
    val draggableState = rememberDraggableState { state.dragBy(it) }
    DisposableEffect(state) { onDispose { state.handleDragging = false } }
    draggable(
        state = draggableState,
        orientation = Orientation.Vertical,
        enabled = !state.dismissing,
        onDragStarted = { state.onHandleDragStarted() },
        onDragStopped = { v -> state.onHandleDragStopped(v) },
    )
}

/** 36 × 4 dp grab pill centred in a 14 dp tall strip (top of a pull-to-dismiss sheet). */
@Composable
fun DragHandle(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(14.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.size(width = 36.dp, height = 4.dp).clip(RoundedCornerShape(2.dp)).background(Wa2Colors.AccentDim.copy(alpha = 0.7f)))
    }
}
