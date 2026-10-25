package com.wa2port.android.game

import android.os.SystemClock
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/** Callbacks for the stage gesture layer. */
class StageGestureHandler(
    val onTap: () -> Unit,
    val onLongPressStart: () -> Unit,
    val onLongPressEnd: () -> Unit,
    val onSwipeUp: () -> Unit,
    val onTwoFingerTap: () -> Unit,
)

/**
 * Stage touch handling (§7): tap; long-press ≥ [LONG_PRESS_MS] held = fast skip until release;
 * swipe up (only when starting away from the screen edges, so it never fights system gestures);
 * two-finger tap. [isNearEdge] receives the down position in this node's coordinates.
 */
fun Modifier.stageGestures(handler: StageGestureHandler, isNearEdge: (Offset) -> Boolean): Modifier =
    pointerInput(handler) {
        val swipeDistance = 64.dp.toPx()
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val t0 = SystemClock.uptimeMillis()
            val start = down.position
            val edge = isNearEdge(start)
            var maxPointers = 1
            var moved = false
            var longPress = false
            var swiped = false
            while (true) {
                val waitingForLongPress = !longPress && !moved && maxPointers == 1
                val event: PointerEvent? = if (waitingForLongPress) {
                    val remaining = LONG_PRESS_MS - (SystemClock.uptimeMillis() - t0)
                    if (remaining <= 0) null else withTimeoutOrNull(remaining) { awaitPointerEvent() }
                } else {
                    awaitPointerEvent()
                }
                if (event == null) {
                    longPress = true
                    handler.onLongPressStart()
                    continue
                }
                val pressed = event.changes.count { it.pressed }
                if (pressed > maxPointers) maxPointers = pressed
                val primary = event.changes.firstOrNull { it.id == down.id }
                if (primary != null && primary.pressed) {
                    val d = primary.position - start
                    if (d.getDistance() > viewConfiguration.touchSlop) moved = true
                    if (!edge && !longPress && !swiped && maxPointers == 1 && -d.y > swipeDistance && abs(d.y) > abs(d.x) * 1.5f) {
                        swiped = true
                        handler.onSwipeUp()
                    }
                }
                event.changes.forEach { it.consume() }
                if (pressed == 0) break
            }
            val duration = SystemClock.uptimeMillis() - t0
            when {
                longPress -> handler.onLongPressEnd()
                swiped -> {}
                maxPointers >= 2 && duration < TWO_FINGER_TAP_MS -> handler.onTwoFingerTap()
                maxPointers == 1 && !moved -> handler.onTap()
            }
        }
    }

const val LONG_PRESS_MS = 350L
private const val TWO_FINGER_TAP_MS = 450L
