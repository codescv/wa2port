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
}
