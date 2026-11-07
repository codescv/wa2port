package com.wa2port.android.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.unit.Velocity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Pull-down-to-dismiss rules ([PullDismissLogic]) and state machine ([PullDismissState]). */
class PullDismissLogicTest {
    private val d = 2.625f // PKX110-class density (px per dp)
    private val eps = 1e-3f

    // ------------------------------------------------------------------ pure rules

    @Test
    fun distanceThresholdIsMin120dpOr22PercentAndNeverZero() {
        assertEquals(120f * d, PullDismissLogic.distanceThresholdPx(850f * d, d), eps)
        assertEquals(88f * d, PullDismissLogic.distanceThresholdPx(400f * d, d), eps)
        // Unknown height (before onSizeChanged): 120 dp, never 0.
        assertEquals(120f * d, PullDismissLogic.distanceThresholdPx(0f, d), eps)
        assertEquals(120f * d, PullDismissLogic.distanceThresholdPx(-5f, d), eps)
    }

    @Test
    fun dismissNeedsDistanceOrVelocityWithMinimumTravel() {
        val h = 850f * d
        assertFalse(PullDismissLogic.shouldDismiss(0f, 9999f * d, 0f, d))
        assertFalse(PullDismissLogic.shouldDismiss(0f, 9999f * d, h, d))
        assertFalse(PullDismissLogic.shouldDismiss(1f, 0f, 0f, d), "zero-height trap: 1 px never dismisses")
        assertFalse(PullDismissLogic.shouldDismiss(119f * d, 0f, h, d))
        assertTrue(PullDismissLogic.shouldDismiss(120f * d, 0f, h, d))
        // Velocity rule: ≥ 1000 dp/s, but only after ≥ 24 dp of travel.
        assertFalse(PullDismissLogic.shouldDismiss(23f * d, 5000f * d, h, d))
        assertTrue(PullDismissLogic.shouldDismiss(24f * d, 1000f * d, h, d))
        assertFalse(PullDismissLogic.shouldDismiss(24f * d, 999f * d, h, d))
        // An upward release never counts as a dismiss fling.
        assertFalse(PullDismissLogic.shouldDismiss(60f * d, -5000f * d, h, d))
    }

    @Test
    fun scrimFadesMonotonicallyFromOneToZero() {
        val h = 800f
        assertEquals(1f, PullDismissLogic.scrimAlpha(0f, h))
        assertEquals(1f, PullDismissLogic.scrimAlpha(300f, 0f), "unknown height keeps the scrim opaque")
        assertEquals(0f, PullDismissLogic.scrimAlpha(h, h))
        assertEquals(0f, PullDismissLogic.scrimAlpha(2 * h, h))
        var prev = 1.01f
        for (o in 0..800 step 50) {
            val a = PullDismissLogic.scrimAlpha(o.toFloat(), h)
            assertTrue(a < prev && a in 0f..1f, "o=$o a=$a")
            prev = a
        }
    }

    @Test
    fun preAndPostScrollTables() {
        // preScroll: only while displaced, only user drags.
        assertEquals(0f, PullDismissLogic.preScroll(0f, 30f, userInput = true))
        assertEquals(0f, PullDismissLogic.preScroll(0f, -30f, userInput = true))
        assertEquals(0f, PullDismissLogic.preScroll(50f, 30f, userInput = false))
        assertEquals(0f, PullDismissLogic.preScroll(50f, -30f, userInput = false))
        assertEquals(30f, PullDismissLogic.preScroll(50f, 30f, userInput = true))
        assertEquals(-30f, PullDismissLogic.preScroll(50f, -30f, userInput = true))
        assertEquals(-50f, PullDismissLogic.preScroll(50f, -80f, userInput = true), "back-drag clamps at −offset")
        // postScroll: a user drag downwards the child could not consume.
        assertEquals(40f, PullDismissLogic.postScroll(40f, userInput = true))
        assertEquals(0f, PullDismissLogic.postScroll(-40f, userInput = true))
        assertEquals(0f, PullDismissLogic.postScroll(40f, userInput = false))
        assertEquals(0f, PullDismissLogic.postScroll(0f, userInput = true))
    }

    // ------------------------------------------------------------------ state machine

    /** Test rig: Unconfined scope (launches run eagerly), recorded animations, switchable keyboard. */
    private class Rig(
        val d: Float,
        sheetDp: Float = 800f,
        val suspendAnimations: Boolean = false,
        val frames: List<Float>? = null,
    ) {
        var ime = false
        var imeHides = 0
        var dismisses = 0
        val runs = mutableListOf<Triple<Float, Float, Boolean>>()
        val state = PullDismissState(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            density = d,
            onDismiss = { dismisses++ },
            isImeVisible = { ime },
            onHideIme = { imeHides++ },
            animator = { from, to, _, dismiss, onFrame ->
                runs += Triple(from, to, dismiss)
                frames?.forEach(onFrame)
                if (suspendAnimations) awaitCancellation()
                onFrame(to)
            },
        ).also { it.sheetHeightPx = sheetDp * d }

        /** One list drag step of [dy] px: pre-scroll, the child (consuming [childConsumes]), post-scroll. */
        fun scroll(dy: Float, childConsumes: Float = 0f, source: NestedScrollSource = NestedScrollSource.UserInput): Float {
            val pre = state.connection.onPreScroll(Offset(0f, dy), source).y
            val left = dy - pre - childConsumes
            val post = state.connection.onPostScroll(Offset(0f, childConsumes), Offset(0f, left), source).y
            return pre + post
        }

        fun fling(vy: Float): Velocity = runBlocking { state.connection.onPreFling(Velocity(0f, vy)) }
    }

    @Test
    fun pullFromTheListTopMovesTheSheetOneToOne() {
        val r = Rig(d)
        assertEquals(30f * d, r.scroll(30f * d), eps)
        assertEquals(30f * d, r.state.offsetPx, eps)
        // While displaced, further downward drags go to the sheet first (list frozen).
        assertEquals(10f * d, r.state.connection.onPreScroll(Offset(0f, 10f * d), NestedScrollSource.UserInput).y, eps)
        assertEquals(40f * d, r.state.offsetPx, eps)
        // At rest, a list that can still scroll keeps the drag (nothing reaches the sheet).
        val rest = Rig(d)
        assertEquals(0f, rest.scroll(-20f * d, childConsumes = -20f * d), eps)
        assertEquals(0f, rest.scroll(20f * d, childConsumes = 20f * d), eps)
        assertEquals(0f, rest.state.offsetPx)
    }

    @Test
    fun dragBackUpReturnsTheSheetHomeBeforeTheListScrolls() {
        val r = Rig(d)
        r.scroll(50f * d)
        val pre = r.state.connection.onPreScroll(Offset(0f, -80f * d), NestedScrollSource.UserInput).y
        assertEquals(-50f * d, pre, eps)
        assertEquals(0f, r.state.offsetPx, eps)
        // Now at rest: upward drags belong to the list again.
        assertEquals(0f, r.state.connection.onPreScroll(Offset(0f, -10f * d), NestedScrollSource.UserInput).y)
        // Releasing at 0 does nothing (no animation, no dismiss).
        assertEquals(Velocity.Zero, r.fling(-3000f * d))
        assertEquals(0, r.dismisses)
        assertTrue(r.runs.isEmpty())
    }

    @Test
    fun flingMomentumNeverPullsOrDismisses() {
        val r = Rig(d)
        // An upward fling's momentum reaching the list top arrives as SideEffect: ignored.
        assertEquals(0f, r.scroll(400f * d, source = NestedScrollSource.SideEffect), eps)
        assertEquals(0f, r.state.offsetPx)
        assertEquals(Velocity.Zero, r.fling(8000f * d))
        assertEquals(0, r.dismisses)
        // SideEffect never moves a displaced sheet either.
        r.scroll(30f * d)
        assertEquals(0f, r.scroll(100f * d, source = NestedScrollSource.SideEffect), eps)
        assertEquals(30f * d, r.state.offsetPx, eps)
    }

    @Test
    fun fastFlickPastMinimumTravelDismissesAndSwallowsTheFling() {
        val r = Rig(d)
        r.scroll(30f * d)
        val v = Velocity(0f, 1200f * d)
        assertEquals(v, r.fling(v.y))
        assertEquals(1, r.dismisses)
        assertTrue(r.state.dismissing)
        assertEquals(Triple(30f * d, 800f * d, true), r.runs.single())
        assertEquals(800f * d, r.state.offsetPx, eps)
        // Further input during / after the slide-out is ignored; a second dismiss is a no-op.
        assertEquals(0f, r.scroll(50f * d), eps)
        r.state.dismiss()
        assertEquals(1, r.dismisses)
    }

    @Test
    fun distanceThresholdDismissesOnSlowRelease() {
        val r = Rig(d)
        r.scroll(130f * d)
        r.fling(0f)
        assertEquals(1, r.dismisses)
        // Unknown height: slides out by 120 dp past the current offset.
        val u = Rig(d, sheetDp = 0f)
        u.scroll(121f * d)
        u.fling(0f)
        assertEquals(1, u.dismisses)
        assertEquals(241f * d, u.runs.single().second, eps)
    }

    @Test
    fun shortSlowPullSnapsBackAndNeverGoesNegative() {
        val r = Rig(d, frames = listOf(20f * d, -5f * d, 3f))
        r.scroll(60f * d)
        assertEquals(Velocity(0f, 300f * d), r.fling(300f * d), "a displaced release swallows the fling")
        assertEquals(0, r.dismisses)
        assertFalse(r.state.dismissing)
        assertEquals(Triple(60f * d, 0f, false), r.runs.single())
        assertEquals(0f, r.state.offsetPx)
        // Zero-height trap: a 1 px pull with an unknown height snaps back.
        val z = Rig(d, sheetDp = 0f)
        z.scroll(1f)
        z.fling(0f)
        assertEquals(0, z.dismisses)
        assertEquals(0f, z.state.offsetPx)
    }

    @Test
    fun negativeSpringFramesAreClampedAtRest() {
        val seen = mutableListOf<Float>()
        val r = Rig(d, frames = listOf(-12f, -1f), suspendAnimations = true)
        r.scroll(40f * d)
        r.fling(0f)
        seen += r.state.offsetPx
        assertTrue(seen.all { it >= 0f }, "offset never above 0 (into the cutout): $seen")
        assertEquals(0f, r.state.offsetPx)
    }

    @Test
    fun cancelledSlideOutNeverDismisses() {
        val r = Rig(d, suspendAnimations = true)
        r.scroll(200f * d)
        r.fling(0f)
        assertTrue(r.state.dismissing)
        assertTrue(r.state.isAnimating)
        r.state.reset() // e.g. ◀ / ▶ switched the sentence, or the overlay was disposed
        assertEquals(0, r.dismisses)
        assertFalse(r.state.dismissing)
        assertFalse(r.state.isAnimating)
        assertEquals(0f, r.state.offsetPx)
    }

    @Test
    fun newGestureGrabsASettlingSheet() {
        val r = Rig(d, suspendAnimations = true)
        r.scroll(60f * d)
        r.fling(0f)
        assertTrue(r.state.isAnimating)
        // Grabbing again mid-spring cancels it and continues from the current offset.
        r.scroll(10f * d)
        assertFalse(r.state.isAnimating)
        assertEquals(70f * d, r.state.offsetPx, eps)
    }

    @Test
    fun pointerCancelWithoutFlingSettlesViaIdleNet() {
        val r = Rig(d)
        r.scroll(40f * d)
        // The list was swapped / the pointer cancelled: no onPreFling ever arrives.
        r.state.pointerDown = true
        r.state.settleIfIdle()
        assertEquals(40f * d, r.state.offsetPx, eps, "a pressed pointer still owns the sheet")
        r.state.pointerDown = false
        r.state.handleDragging = true
        r.state.settleIfIdle()
        assertEquals(40f * d, r.state.offsetPx, eps, "a handle drag still owns the sheet")
        r.state.handleDragging = false
        r.state.settleIfIdle()
        assertEquals(0f, r.state.offsetPx)
        assertEquals(0, r.dismisses)
        assertFalse(r.state.gestureActive)
    }

    @Test
    fun handleDragPullsOneToOneAndReleasesWithVelocity() {
        val r = Rig(d)
        r.state.onHandleDragStarted()
        assertEquals(50f * d, r.state.dragBy(50f * d), eps)
        assertEquals(-50f * d, r.state.dragBy(-80f * d), eps, "never above rest")
        r.state.dragBy(30f * d)
        r.state.onHandleDragStopped(1500f * d)
        assertFalse(r.state.handleDragging)
        assertEquals(1, r.dismisses)
    }

    @Test
    fun keyboardLatchedGestureHidesTheKeyboardOnceAndNeverMovesOrDismisses() {
        val r = Rig(d)
        r.ime = true
        r.scroll(10f * d)
        assertEquals(0, r.imeHides)
        r.scroll(20f * d) // 30 dp ≥ 24 dp
        assertEquals(1, r.imeHides)
        // The keyboard is gone mid-stroke: the rest of THIS gesture still can't move the sheet.
        r.ime = false
        r.scroll(100f * d)
        r.scroll(100f * d)
        assertEquals(1, r.imeHides, "hidden at most once per gesture")
        assertEquals(0f, r.state.offsetPx)
        assertEquals(Velocity.Zero, r.fling(5000f * d))
        assertEquals(0, r.dismisses)
        // The next gesture pulls normally.
        r.scroll(130f * d)
        assertEquals(130f * d, r.state.offsetPx, eps)
        r.fling(0f)
        assertEquals(1, r.dismisses)
    }

    @Test
    fun keyboardLatchIsClearedOnIdleWhenTheFlingNeverComes() {
        val r = Rig(d)
        r.ime = true
        r.scroll(10f * d) // below 24 dp: latched, keyboard still up
        assertTrue(r.state.gestureActive)
        assertTrue(r.state.imeCapture)
        // Crossfade swapped the list / pointer cancelled before onPreFling.
        r.state.settleIfIdle()
        assertFalse(r.state.gestureActive)
        assertFalse(r.state.imeCapture)
        r.ime = false
        r.scroll(20f * d)
        assertEquals(20f * d, r.state.offsetPx, eps)
    }

    @Test
    fun resetReturnsToRestAndDensityUpdatesThresholds() {
        val r = Rig(d)
        r.scroll(50f * d)
        r.state.handleDragging = true
        r.state.reset()
        assertEquals(0f, r.state.offsetPx)
        assertFalse(r.state.handleDragging)
        assertFalse(r.state.gestureActive)
        // Multi-window density change: thresholds follow the new density without recreating the state.
        r.state.density = 2f
        r.scroll(110f * 2f)
        r.fling(0f)
        assertEquals(0, r.dismisses, "110 dp < 120 dp at the new density")
        r.scroll(121f * 2f)
        r.fling(0f)
        assertEquals(1, r.dismisses)
    }
}
