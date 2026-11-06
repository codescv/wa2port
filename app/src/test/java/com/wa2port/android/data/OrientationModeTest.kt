package com.wa2port.android.data

import android.content.pm.ActivityInfo
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** [OrientationMode]: Activity orientation mapping, title-pill toggle and persistence (spec §1.1). */
class OrientationModeTest {
    @TempDir
    lateinit var dir: File

    @Test
    fun activityOrientationMapping() {
        assertEquals(6, OrientationMode.LANDSCAPE.activityOrientation)
        assertEquals(1, OrientationMode.PORTRAIT.activityOrientation)
        assertEquals(13, OrientationMode.AUTO.activityOrientation)
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE, OrientationMode.activityOrientationFor(OrientationMode.LANDSCAPE))
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, OrientationMode.activityOrientationFor(OrientationMode.PORTRAIT))
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_FULL_USER, OrientationMode.activityOrientationFor(OrientationMode.AUTO))
    }

    @Test
    fun toggleFlipsWhatIsOnScreen() {
        for (m in OrientationMode.entries) {
            assertEquals(OrientationMode.LANDSCAPE, m.toggle(currentlyPortrait = true), "$m")
            assertEquals(OrientationMode.PORTRAIT, m.toggle(currentlyPortrait = false), "$m")
        }
        assertEquals(listOf("横屏", "竖屏", "自动"), OrientationMode.entries.map { it.label })
    }

    @Test
    fun defaultIsLandscapeAndRoundTrips() {
        assertEquals(OrientationMode.LANDSCAPE, Settings().orientation)
        val store = SettingsStore(File(dir, "settings.json"))
        for (m in OrientationMode.entries) {
            store.save(store.load().copy(orientation = m))
            assertEquals(m, store.load().orientation)
        }
    }

    @Test
    fun legacySettingsLoadAsLandscape() {
        val file = File(dir, "settings.json")
        file.writeText("""{"textSpeed":60,"lang":"JP","textScale":130}""")
        val loaded = SettingsStore(file).load()
        assertEquals(OrientationMode.LANDSCAPE, loaded.orientation)
        assertEquals(60, loaded.textSpeed)
        assertEquals(DisplayLang.JP, loaded.lang)
        assertEquals(130, loaded.textScale)
    }
}
