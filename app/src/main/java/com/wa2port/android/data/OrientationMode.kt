package com.wa2port.android.data

import android.content.pm.ActivityInfo
import kotlinx.serialization.Serializable

/**
 * Screen orientation preference (`Settings.orientation`). Only sets the Activity's requested
 * orientation: the layout itself is always chosen from the measured window constraints
 * (`maxHeight > maxWidth` ⇒ portrait), because large screens / multi-window may ignore the request.
 */
@Serializable
enum class OrientationMode(val label: String) {
    LANDSCAPE("横屏"),
    PORTRAIT("竖屏"),
    AUTO("自动"),
    ;

    /** Title pill: flips to the orientation opposite to what is on screen now (AUTO included). */
    fun toggle(currentlyPortrait: Boolean): OrientationMode = if (currentlyPortrait) LANDSCAPE else PORTRAIT

    /** `ActivityInfo.screenOrientation` value requested for this mode. */
    val activityOrientation: Int
        get() = activityOrientationFor(this)

    companion object {
        /**
         * LANDSCAPE → `SCREEN_ORIENTATION_SENSOR_LANDSCAPE` (6, = the manifest default), PORTRAIT →
         * `SCREEN_ORIENTATION_PORTRAIT` (1), AUTO → `SCREEN_ORIENTATION_FULL_USER` (13, honours the
         * system rotation lock).
         */
        fun activityOrientationFor(mode: OrientationMode): Int = when (mode) {
            LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            AUTO -> ActivityInfo.SCREEN_ORIENTATION_FULL_USER
        }
    }
}
