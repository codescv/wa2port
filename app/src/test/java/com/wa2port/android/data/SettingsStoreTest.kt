package com.wa2port.android.data

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class SettingsStoreTest {
    @TempDir
    lateinit var dir: File

    @Test
    fun agentFeedbackEmailDefaultsToEmptyAndPersistsLocally() {
        val store = SettingsStore(File(dir, "settings.json"))
        val initial = store.load()
        assertEquals("", initial.agentFeedbackEmail)

        store.save(initial.copy(agentFeedbackEmail = " \u200Bagent@example.com\u00A0 "))
        val reloaded = store.load()
        assertEquals("agent@example.com", reloaded.agentFeedbackEmail)
    }

    @Test
    fun orientationPersists() {
        val store = SettingsStore(File(dir, "settings.json"))
        assertEquals(OrientationMode.LANDSCAPE, store.load().orientation)
        store.save(store.load().copy(orientation = OrientationMode.PORTRAIT))
        assertEquals(OrientationMode.PORTRAIT, SettingsStore(File(dir, "settings.json")).load().orientation)
        store.save(store.load().copy(orientation = OrientationMode.AUTO))
        assertEquals(OrientationMode.AUTO, store.load().orientation)
    }
}
