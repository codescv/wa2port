package com.wa2port.core.engine

import com.wa2port.core.script.VmSnapshot
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
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
        fun fromJson(text: String): SaveData {
            val root = JSON.parseToJsonElement(text)
            val normalized = if (root is JsonObject) normalizeCrossPlatformSaveJson(root) else root
            return JSON.decodeFromJsonElement(serializer(), normalized)
        }
    }
}

/**
 * Tolerant reader adapter that accepts both Android (kotlinx.serialization) and iOS (`core/engine.cc`)
 * `SaveData` JSON payloads without changing either platform's writer format (keeping MD5s stable).
 */
internal fun normalizeCrossPlatformSaveJson(root: JsonObject): JsonObject {
    val out = LinkedHashMap<String, JsonElement>(root)
    val thumb = out["thumbnail"]
    if (thumb is JsonPrimitive && thumb.isString && thumb.content.isEmpty()) {
        out["thumbnail"] = JsonNull
    }
    (out["vm"] as? JsonObject)?.let { out["vm"] = normalizeVmJson(it) }
    (out["stage"] as? JsonObject)?.let { out["stage"] = normalizeStageJson(it) }
    (out["audio"] as? JsonObject)?.let { out["audio"] = normalizeAudioJson(it) }
    return JsonObject(out)
}

private fun normalizeVmJson(vm: JsonObject): JsonObject {
    val out = LinkedHashMap<String, JsonElement>(vm)

    // switchStack: Android `[{"end":N}]` vs iOS `[N]`.
    (out["switchStack"] as? JsonArray)?.let { arr ->
        val normalized = arr.map { el ->
            if (el is JsonPrimitive) {
                JsonObject(mapOf("end" to JsonPrimitive(el.intOrNull ?: 0)))
            } else {
                el
            }
        }
        out["switchStack"] = JsonArray(normalized)
    }

    // globals: Value array (Android `{"type":...}` vs iOS `{"k":...}`).
    (out["globals"] as? JsonArray)?.let { arr ->
        out["globals"] = JsonArray(arr.map { normalizeValueJson(it) })
    }

    // locals: Android `{"a":{"e":Value}}` vs iOS `[{"a":a,"e":e,"v":Value}]`.
    when (val locals = out["locals"]) {
        is JsonArray -> {
            val nested = LinkedHashMap<String, LinkedHashMap<String, JsonElement>>()
            for (item in locals) {
                val obj = item as? JsonObject ?: continue
                val arrKey = (obj["a"] as? JsonPrimitive)?.contentOrNull ?: continue
                val elemKey = (obj["e"] as? JsonPrimitive)?.contentOrNull ?: continue
                val valueEl = obj["v"] ?: continue
                nested.getOrPut(arrKey) { LinkedHashMap() }[elemKey] = normalizeValueJson(valueEl)
            }
            out["locals"] = JsonObject(nested.mapValues { JsonObject(it.value) })
        }
        is JsonObject -> {
            val normalized = LinkedHashMap<String, JsonElement>(locals.size)
            for ((arrKey, elemMapEl) in locals) {
                val elemMap = elemMapEl as? JsonObject
                if (elemMap != null) {
                    normalized[arrKey] = JsonObject(elemMap.mapValues { normalizeValueJson(it.value) })
                } else {
                    normalized[arrKey] = elemMapEl
                }
            }
            out["locals"] = JsonObject(normalized)
        }
        else -> Unit
    }

    // wait: Android requires `"wait"` (WaitState?); iOS omits `"wait"` and writes `"ended": bool`.
    if ("wait" !in out) {
        val ended = (out["ended"] as? JsonPrimitive)?.booleanOrNull == true
        out["wait"] = if (ended) {
            JsonObject(mapOf("type" to JsonPrimitive("com.wa2port.core.script.WaitState.Ended")))
        } else {
            JsonNull
        }
    }

    return JsonObject(out)
}

private fun normalizeValueJson(el: JsonElement): JsonElement {
    val obj = el as? JsonObject ?: return el
    if ("type" in obj) return obj
    val kind = (obj["k"] as? JsonPrimitive)?.contentOrNull ?: return obj
    val intVal = (obj["v"] as? JsonPrimitive)?.intOrNull ?: 0
    return when (kind) {
        "i" -> JsonObject(
            mapOf(
                "type" to JsonPrimitive("i"),
                "v" to JsonPrimitive(intVal),
            ),
        )
        "f" -> {
            val floatVal = when {
                "b" in obj -> {
                    val bits = (obj["b"] as? JsonPrimitive)?.longOrNull?.toInt() ?: 0
                    val f = Float.fromBits(bits)
                    if (f.isFinite()) f else 0f
                }
                else -> (obj["v"] as? JsonPrimitive)?.contentOrNull?.toFloatOrNull()?.takeIf { it.isFinite() } ?: 0f
            }
            JsonObject(
                mapOf(
                    "type" to JsonPrimitive("f"),
                    "v" to JsonPrimitive(floatVal),
                ),
            )
        }
        "t" -> JsonObject(
            mapOf(
                "type" to JsonPrimitive("t"),
                "token" to JsonPrimitive((obj["token"] as? JsonPrimitive)?.intOrNull ?: intVal),
            ),
        )
        "s" -> JsonObject(
            mapOf(
                "type" to JsonPrimitive("s"),
                "s" to JsonPrimitive((obj["s"] as? JsonPrimitive)?.contentOrNull ?: ""),
            ),
        )
        "g" -> JsonObject(
            mapOf(
                "type" to JsonPrimitive("g"),
                "index" to JsonPrimitive((obj["index"] as? JsonPrimitive)?.intOrNull ?: intVal),
            ),
        )
        "la" -> JsonObject(
            mapOf(
                "type" to JsonPrimitive("la"),
                "index" to JsonPrimitive((obj["index"] as? JsonPrimitive)?.intOrNull ?: intVal),
            ),
        )
        "lr" -> JsonObject(
            mapOf(
                "type" to JsonPrimitive("lr"),
                "index" to JsonPrimitive((obj["index"] as? JsonPrimitive)?.intOrNull ?: intVal),
                "elem" to JsonPrimitive(
                    (obj["elem"] as? JsonPrimitive)?.intOrNull
                        ?: (obj["e"] as? JsonPrimitive)?.intOrNull
                        ?: 0,
                ),
            ),
        )
        else -> JsonObject(
            mapOf(
                "type" to JsonPrimitive("i"),
                "v" to JsonPrimitive(intVal),
            ),
        )
    }
}

private fun normalizeAudioJson(audio: JsonObject): JsonObject {
    val loops = audio["loops"] as? JsonArray ?: return audio
    val out = LinkedHashMap<String, JsonElement>(audio)
    val map = LinkedHashMap<String, JsonElement>()
    for (item in loops) {
        val obj = item as? JsonObject ?: continue
        val ch = (obj["ch"] as? JsonPrimitive)?.contentOrNull ?: continue
        val name = (obj["name"] as? JsonPrimitive)?.contentOrNull ?: ""
        val vol = (obj["vol"] as? JsonPrimitive)?.intOrNull ?: 255
        map[ch] = JsonObject(
            mapOf(
                "first" to JsonPrimitive(name),
                "second" to JsonPrimitive(vol),
            ),
        )
    }
    out["loops"] = JsonObject(map)
    return JsonObject(out)
}

private fun normalizeStageJson(stage: JsonObject): JsonObject {
    val out = LinkedHashMap<String, JsonElement>(stage)
    for (nullableStrKey in listOf("bg", "spriteLut", "cg", "lut", "spriteEffectLut")) {
        val v = out[nullableStrKey]
        if (v is JsonPrimitive && v.isString && v.content.isEmpty()) {
            out[nullableStrKey] = JsonNull
        }
    }
    val weather = out["weather"]
    if (weather is JsonPrimitive && (weather.intOrNull ?: 0) < 0) {
        out["weather"] = JsonNull
    }
    (out["layers"] as? JsonArray)?.let { layers ->
        val normalizedLayers = layers.map { layerEl ->
            val layerObj = layerEl as? JsonObject ?: return@map layerEl
            val params = layerObj["params"] as? JsonArray ?: return@map layerEl
            val paramMap = LinkedHashMap<String, JsonElement>()
            for (pairEl in params) {
                val pair = pairEl as? JsonArray ?: continue
                if (pair.size >= 2) {
                    val k = (pair[0] as? JsonPrimitive)?.contentOrNull ?: continue
                    val v = (pair[1] as? JsonPrimitive)?.intOrNull ?: 0
                    paramMap[k] = JsonPrimitive(v)
                }
            }
            val copy = LinkedHashMap<String, JsonElement>(layerObj)
            copy["params"] = JsonObject(paramMap)
            JsonObject(copy)
        }
        out["layers"] = JsonArray(normalizedLayers)
    }
    return JsonObject(out)
}

internal val JSON = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    allowStructuredMapKeys = true
}
