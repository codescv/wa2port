package com.wa2port.android.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class SettingsStoreTest {
    @TempDir
    lateinit var dir: File

    private val file get() = File(dir, "settings.json")

    /** `portraitDefaultMigrated` as written on disk (null = key absent). */
    private fun migratedOnDisk(): Boolean? =
        Json.parseToJsonElement(file.readText()).jsonObject["portraitDefaultMigrated"]?.jsonPrimitive?.boolean

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
        assertEquals(OrientationMode.PORTRAIT, store.load().orientation)
        store.save(store.load().copy(orientation = OrientationMode.LANDSCAPE))
        assertEquals(OrientationMode.LANDSCAPE, SettingsStore(File(dir, "settings.json")).load().orientation)
        store.save(store.load().copy(orientation = OrientationMode.AUTO))
        assertEquals(OrientationMode.AUTO, store.load().orientation)
        store.save(store.load().copy(orientation = OrientationMode.PORTRAIT))
        assertEquals(OrientationMode.PORTRAIT, store.load().orientation)
    }

    @Test
    fun defaultsArePortraitAndMigrated() {
        assertEquals(OrientationMode.PORTRAIT, Settings().orientation)
        assertTrue(Settings().portraitDefaultMigrated)
    }

    @Test
    fun missingFileLoadsPortrait() {
        assertFalse(file.exists())
        val loaded = SettingsStore(file).load()
        assertEquals(OrientationMode.PORTRAIT, loaded.orientation)
        assertTrue(loaded.portraitDefaultMigrated)
    }

    @Test
    fun corruptFileFallsBackToPortraitDefaults() {
        file.writeText("{ not json")
        assertEquals(Settings(), SettingsStore(file).load())
    }

    @Test
    fun preV148LegacyFileLoadsPortraitAndPersistsMarker() {
        file.writeText("""{ "textSpeed": 60 }""")
        val loaded = SettingsStore(file).load()
        assertEquals(OrientationMode.PORTRAIT, loaded.orientation)
        assertEquals(60, loaded.textSpeed)
        assertTrue(loaded.portraitDefaultMigrated)
        assertEquals(true, migratedOnDisk())
        val reloaded = SettingsStore(file).load()
        assertEquals(OrientationMode.PORTRAIT, reloaded.orientation)
        assertEquals(60, reloaded.textSpeed)
    }

    @Test
    fun v148ToV1412LandscapeMigratesOnceThenExplicitLandscapeSticks() {
        file.writeText("""{ "textSpeed": 60, "orientation": "LANDSCAPE", "agentFeedbackEmail": "user@example.com" }""")
        val store = SettingsStore(file)
        val loaded = store.load()
        assertEquals(OrientationMode.PORTRAIT, loaded.orientation)
        assertEquals(60, loaded.textSpeed)
        assertEquals("user@example.com", loaded.agentFeedbackEmail)
        assertTrue(loaded.portraitDefaultMigrated)
        assertEquals(true, migratedOnDisk())
        assertEquals(OrientationMode.PORTRAIT, SettingsStore(file).load().orientation)

        // The user now explicitly picks 横屏: it must survive restarts (no second migration).
        store.save(store.load().copy(orientation = OrientationMode.LANDSCAPE))
        val restarted = SettingsStore(file).load()
        assertEquals(OrientationMode.LANDSCAPE, restarted.orientation)
        assertEquals(60, restarted.textSpeed)
        assertEquals("user@example.com", restarted.agentFeedbackEmail)
        assertEquals(OrientationMode.LANDSCAPE, SettingsStore(file).load().orientation)
    }

    @Test
    fun v148ToV1412AutoIsKept() {
        file.writeText("""{ "orientation": "AUTO" }""")
        val loaded = SettingsStore(file).load()
        assertEquals(OrientationMode.AUTO, loaded.orientation)
        assertTrue(loaded.portraitDefaultMigrated)
        assertEquals(true, migratedOnDisk())
        assertEquals(OrientationMode.AUTO, SettingsStore(file).load().orientation)
    }

    @Test
    fun v148ToV1412PortraitIsKept() {
        file.writeText("""{ "orientation": "PORTRAIT" }""")
        assertEquals(OrientationMode.PORTRAIT, SettingsStore(file).load().orientation)
        assertEquals(true, migratedOnDisk())
    }

    @Test
    fun explicitLandscapeAfterMigrationReloadsAsLandscape() {
        val store = SettingsStore(file)
        store.save(store.load().copy(orientation = OrientationMode.LANDSCAPE))
        assertEquals(true, migratedOnDisk())
        assertEquals(OrientationMode.LANDSCAPE, SettingsStore(file).load().orientation)
        assertEquals(OrientationMode.LANDSCAPE, SettingsStore(file).load().orientation)

        file.writeText("""{ "orientation": "LANDSCAPE", "portraitDefaultMigrated": true }""")
        assertEquals(OrientationMode.LANDSCAPE, SettingsStore(file).load().orientation)
    }

    @Test
    fun explicitFalseMarkerStillMigrates() {
        file.writeText("""{ "orientation": "LANDSCAPE", "portraitDefaultMigrated": false }""")
        assertEquals(OrientationMode.PORTRAIT, SettingsStore(file).load().orientation)
        assertEquals(true, migratedOnDisk())
    }
}
