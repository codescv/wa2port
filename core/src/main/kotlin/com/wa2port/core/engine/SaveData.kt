package com.wa2port.core.engine

import com.wa2port.core.script.VmSnapshot
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.Base64
import java.util.BitSet

/** How long-press / SKIP behaves with respect to the persistent 既読 (read-line) bitsets. */
@Serializable
enum class SkipMode { OFF, READ_ONLY, ALL }

/** Persistent per-scene "already read" bitsets indexed by the message line number. */
class ReadTracker {
    private val sets = HashMap<String, BitSet>()

    fun isRead(scene: String, line: Int): Boolean = line >= 0 && sets[scene.lowercase()]?.get(line) == true

    fun markRead(scene: String, line: Int) {
        if (line >= 0) sets.getOrPut(scene.lowercase()) { BitSet() }.set(line)
    }

    fun readCount(scene: String): Int = sets[scene.lowercase()]?.cardinality() ?: 0

    fun totalRead(): Int = sets.values.sumOf { it.cardinality() }

    /** Base64 of `BitSet.toByteArray()` per scene. */
    fun export(): Map<String, String> = sets.mapValues { Base64.getEncoder().encodeToString(it.value.toByteArray()) }

    fun import(data: Map<String, String>) {
        sets.clear()
        data.forEach { (k, v) -> sets[k.lowercase()] = BitSet.valueOf(Base64.getDecoder().decode(v)) }
    }
}

/** Number of global flags in `WA2.exe` (`0x521930`, `Sys.sav` offset `0x268480`: 1024 `i32`s). */
const val GFLAG_COUNT = 1024

/** Global, save-independent data (`Sys.sav` equivalent). */
@Serializable
data class SystemDataJson(
    val gflags: List<Int> = emptyList(),
    val read: Map<String, String> = emptyMap(),
    val cgFlags: List<String> = emptyList(),
    val bgmFlags: List<Int> = emptyList(),
)

class SystemData {
    val gflags = IntArray(GFLAG_COUNT)
    val read = ReadTracker()
    val cgFlags = LinkedHashSet<String>()
    val bgmFlags = LinkedHashSet<Int>()

    fun toJson(): String = JSON.encodeToString(
        SystemDataJson(gflags.toList(), read.export(), cgFlags.toList(), bgmFlags.toList())
    )

    fun loadJson(text: String) {
        val d = JSON.decodeFromString<SystemDataJson>(text)
        gflags.fill(0)
        d.gflags.forEachIndexed { i, v -> if (i < gflags.size) gflags[i] = v }
        read.import(d.read)
        cgFlags.clear(); cgFlags.addAll(d.cgFlags)
        bgmFlags.clear(); bgmFlags.addAll(d.bgmFlags)
        // Sync legacy bgmFlags into gflags[100 + id]
        for (id in bgmFlags) {
            if (id in 1..199 && (100 + id) in gflags.indices) gflags[100 + id] = 1
        }
    }
}

/** Audio state that must be re-established after loading a save. */
@Serializable
data class AudioState(
    val bgmTrack: Int = 0,
    val bgmVolume: Int = 255,
    /** Looping ambient SE per channel: channel → (name, volume). */
    val loops: Map<Int, Pair<String, Int>> = emptyMap(),
)

/** One save slot (JSON). [thumbnail] is filled in by the host (PNG/JPEG bytes, Base64). */
@Serializable
data class SaveData(
    val version: Int = 1,
    val timestamp: Long,
    val scene: String,
    /** Preview text of the current line (JP / CN plain). */
    val previewJp: String = "",
    val previewCn: String = "",
    val vm: VmSnapshot,
    val stage: StageState,
    val audio: AudioState,
    val message: LineRef? = null,
    val voiceScene: Int = 0,
    val speakerToken: Int = -1,
    val pendingChoices: List<Int> = emptyList(),
    val backlog: List<LineRef> = emptyList(),
    val thumbnail: String? = null,
) {
    fun toJson(): String = JSON.encodeToString(this)

    companion object {
        fun fromJson(text: String): SaveData = JSON.decodeFromString(text)
    }
}

internal val JSON = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    allowStructuredMapKeys = true
}
