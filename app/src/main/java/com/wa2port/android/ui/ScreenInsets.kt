package com.wa2port.android.ui

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.mandatorySystemGestures
import androidx.compose.foundation.layout.absolutePadding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Safe screen insets (dp) computed once in `Wa2App`: [top] = `max(displayCutout.top, 8dp)`,
 * [bottom] = `max(mandatorySystemGestures.bottom, 16dp)`, [left]/[right] = `displayCutout`.
 * `displayCutout` is stable while the system bars are hidden (`systemBars` goes non-zero during the
 * transient swipe reveal and would make the layout jump).
 */
@Immutable
data class ScreenInsets(
    val top: Dp = 0.dp,
    val bottom: Dp = 0.dp,
    val left: Dp = 0.dp,
    val right: Dp = 0.dp,
) {
    companion object {
        const val MIN_TOP_DP = 8f
        const val MIN_BOTTOM_DP = 16f

        /** Applies the [MIN_TOP_DP] / [MIN_BOTTOM_DP] floors to raw cutout / gesture insets (dp). */
        fun of(cutoutTopDp: Float, gestureBottomDp: Float, cutoutLeftDp: Float, cutoutRightDp: Float): ScreenInsets =
            ScreenInsets(
                top = maxOf(cutoutTopDp, MIN_TOP_DP).dp,
                bottom = maxOf(gestureBottomDp, MIN_BOTTOM_DP).dp,
                left = maxOf(cutoutLeftDp, 0f).dp,
                right = maxOf(cutoutRightDp, 0f).dp,
            )
    }
}

/** Pure screen-class rules (R1: chosen by measured constraints, never by the orientation setting). */
object ScreenClass {
    /** Width (dp) below which a landscape window uses the compact (phone-portrait style) panels. */
    const val COMPACT_MAX_WIDTH_DP = 600f

    fun isPortrait(widthDp: Float, heightDp: Float): Boolean = heightDp > widthDp

    /** Compact panels: any portrait window, or a landscape window narrower than 600 dp. */
    fun isCompact(isPortrait: Boolean, widthDp: Float): Boolean = isPortrait || widthDp < COMPACT_MAX_WIDTH_DP
}

/** Floored safe insets of the window ([ScreenInsets]); provided by `Wa2App`. */
val LocalScreenInsets = compositionLocalOf { ScreenInsets() }

/** True when the window is taller than wide (measured constraints, see [ScreenClass.isPortrait]). */
val LocalIsPortrait = compositionLocalOf { false }

/** True for compact panels ([ScreenClass.isCompact]). */
val LocalCompact = compositionLocalOf { false }

/** Reads the live cutout / gesture insets and floors them ([ScreenInsets.of]). */
@Composable
fun rememberScreenInsets(): ScreenInsets {
    val density = LocalDensity.current
    val dir = LocalLayoutDirection.current
    val cutout = WindowInsets.displayCutout
    val gestures = WindowInsets.mandatorySystemGestures
    return with(density) {
        ScreenInsets.of(
            cutoutTopDp = cutout.getTop(this).toDp().value,
            gestureBottomDp = gestures.getBottom(this).toDp().value,
            cutoutLeftDp = cutout.getLeft(this, dir).toDp().value,
            cutoutRightDp = cutout.getRight(this, dir).toDp().value,
        )
    }
}

/**
 * The one safe-area helper, applied exactly once at each leaf content container (screens and
 * overlays):
 * * portrait: `absolutePadding(left, top, right)` from [LocalScreenInsets] + `ime ∪ bottom` (max, never sum);
 * * landscape: `ime` only (0 dp while the keyboard is closed → landscape layout unchanged).
 */
fun Modifier.screenSafePadding(): Modifier = composed {
    val insets = LocalScreenInsets.current
    if (LocalIsPortrait.current) {
        this
            .absolutePadding(left = insets.left, top = insets.top, right = insets.right)
            .windowInsetsPadding(WindowInsets.ime.union(WindowInsets(bottom = insets.bottom)))
    } else {
        this.windowInsetsPadding(WindowInsets.ime)
    }
}
