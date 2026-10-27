package com.wa2port.core.ai

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.security.MessageDigest

/** Entry count and on-disk size of an [ExplainCache]. */
data class CacheStats(val entries: Int, val bytes: Long)

/**
 * Persistent explanation cache: `<dir>/<id>.json` (the [SentenceExplanation]) plus
 * `<dir>/<id>.thread.json` (follow-up Q&A), fronted by an in-memory LRU of [MEMORY_ENTRIES].
 * All methods are synchronized (explain and follow-up may hit the same id concurrently on IO) and
 * do disk I/O — never call them on the main thread. Corrupt files are deleted on read.
 */
class ExplainCache(private val dir: File) {
    private val memory = object : LinkedHashMap<String, SentenceExplanation>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, SentenceExplanation>?): Boolean = size > MEMORY_ENTRIES
    }

    private fun file(id: String) = File(dir, "${safe(id)}.json")
    private fun threadFile(id: String) = File(dir, "${safe(id)}.thread.json")
    private fun safe(id: String) = id.filter { it.isLetterOrDigit() || it == '_' || it == '-' }.ifEmpty { "x" }

    /** Cached explanation (marked `fromCache = true`) or null. */
    @Synchronized
    fun get(id: String): SentenceExplanation? {
        memory[id]?.let { return it.copy(fromCache = true) }
        val f = file(id)
        if (!f.isFile) return null
        val e = runCatching { ExplainJsonCodec.decodeExplanation(f.readText(Charsets.UTF_8)) }.getOrNull()
        if (e == null || e.id != id) {
            f.delete()
            return null
        }
        memory[id] = e
        return e.copy(fromCache = true)
    }

    /** Stores [expl] (atomic tmp + rename). */
    @Synchronized
    fun put(expl: SentenceExplanation) {
        val stored = expl.copy(fromCache = false)
        memory[expl.id] = stored
        writeAtomic(file(expl.id), ExplainJsonCodec.encodeExplanation(stored))
    }

    /** Follow-up thread of [id], oldest first. */
    @Synchronized
    fun thread(id: String): List<FollowUpMessage> {
        val f = threadFile(id)
        if (!f.isFile) return emptyList()
        return runCatching { ExplainJsonCodec.decodeThread(f.readText(Charsets.UTF_8)) }.getOrElse {
            f.delete()
            emptyList()
        }
    }

    @Synchronized
    fun appendThread(id: String, msg: FollowUpMessage) {
        writeAtomic(threadFile(id), ExplainJsonCodec.encodeThread(thread(id) + msg))
    }

    /** Drops the follow-up thread of [id] (e.g. after regenerating the breakdown it was based on). */
    @Synchronized
    fun clearThread(id: String) {
        threadFile(id).delete()
    }

    /** Removes every cache file; returns the number of files removed. */
    @Synchronized
    fun clear(): Int {
        memory.clear()
        return dir.listFiles()?.count { it.isFile && it.name.endsWith(".json") && it.delete() } ?: 0
    }

    @Synchronized
    fun stats(): CacheStats {
        val files = dir.listFiles()?.filter { it.isFile && it.name.endsWith(".json") } ?: emptyList()
        return CacheStats(files.count { !it.name.endsWith(".thread.json") }, files.sumOf { it.length() })
    }

    private fun writeAtomic(target: File, text: String) {
        dir.mkdirs()
        val tmp = File(dir, target.name + ".tmp")
        tmp.writeText(text, Charsets.UTF_8)
        if (!tmp.renameTo(target)) {
            target.delete()
            tmp.renameTo(target)
        }
    }

    companion object {
        const val MEMORY_ENTRIES = 32

        /**
         * `ex_` + sha256 of the canonical JSON array
         * `[promptVersion, model, "zh-CN", speakerJp, sentence, officialCn, scriptRuby]`. The CK-GAL line
         * and script furigana are part of the key: WA2 repeats short lines (うん / …… / え？) with
         * different official translations, and `<R本気|マジ>` vs plain 本気 share the same plain text.
         */
        fun key(
            sentence: String,
            speakerJp: String?,
            model: String,
            promptVersion: String = PROMPT_VERSION,
            officialCn: String? = null,
            scriptRuby: List<ScriptRuby> = emptyList(),
        ): String {
            val ruby = scriptRuby.joinToString("|") { "${it.span.start}-${it.span.end}=${it.reading}" }
            val canonical = JsonArray(
                listOf(promptVersion, model, TARGET_LANGUAGE, speakerJp?.trim().orEmpty(), sentence, officialCn?.trim().orEmpty(), ruby).map { JsonPrimitive(it) },
            ).toString()
            return "ex_" + sha256Hex(canonical)
        }

        /** Cache key of [req] under [model] (see the other overload). */
        fun key(req: ExplainRequest, model: String): String =
            key(req.sentence, req.speakerJp, model, PROMPT_VERSION, req.officialCnTranslation, req.scriptRuby)

        fun sha256Hex(s: String): String =
            MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}
