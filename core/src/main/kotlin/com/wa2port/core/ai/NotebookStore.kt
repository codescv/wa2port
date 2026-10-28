package com.wa2port.core.ai

import com.wa2port.core.engine.LineRef
import com.wa2port.core.engine.VoiceRef
import kotlinx.serialization.Serializable
import java.io.File

/**
 * One sentence recorded in `notebook/sentences.jsonl` when the user opens an AI breakdown and it
 * reaches `Ready(expl)` (whether fresh or served from cache / prefetch; plan §3.4 & R2.1 note).
 */
@Serializable
data class NotebookSentenceEntry(
    val explanationId: String,
    val scene: Int = 0,
    val lineNo: Int = 0,
    val voice: String? = null,
    val voiceCharId: Int = 0,
    val sentence: String,
    val speakerJp: String? = null,
    val speakerCn: String? = null,
    val officialCn: String? = null,
    val translation: String = "",
    val model: String = "",
    val createdAt: Long = 0L,
    val vocabCount: Int = 0,
    val grammarCount: Int = 0,
) {
    val voiceRef: VoiceRef? get() = voice?.takeIf { it.isNotBlank() }?.let { VoiceRef(it, voiceCharId) }
}

/** Self-contained vocabulary card persisted in `notebook/stars.json` (survives cache clears). */
@Serializable
data class StarredVocab(
    val lemma: String,
    val reading: String = "",
    val surface: String = lemma,
    val meaning: String = "",
    val partOfSpeech: String = "other",
    val conjugation: String? = null,
    val jlpt: String? = null,
    val pitchAccent: Int? = null,
    val notes: String? = null,
    val exampleSentence: String = "",
    val officialCn: String? = null,
    val speakerJp: String? = null,
    val scene: Int = 0,
    val lineNo: Int = 0,
    val voice: String? = null,
    val voiceCharId: Int = 0,
    val explanationId: String = "",
    val starredAt: Long = 0L,
) {
    val voiceRef: VoiceRef? get() = voice?.takeIf { it.isNotBlank() }?.let { VoiceRef(it, voiceCharId) }
}

/** Self-contained grammar card persisted in `notebook/stars.json` (survives cache clears). */
@Serializable
data class StarredGrammar(
    val pattern: String,
    val explanation: String = "",
    val jlpt: String? = null,
    val exampleSentence: String = "",
    val officialCn: String? = null,
    val speakerJp: String? = null,
    val scene: Int = 0,
    val lineNo: Int = 0,
    val voice: String? = null,
    val voiceCharId: Int = 0,
    val explanationId: String = "",
    val starredAt: Long = 0L,
) {
    val voiceRef: VoiceRef? get() = voice?.takeIf { it.isNotBlank() }?.let { VoiceRef(it, voiceCharId) }
}

/** Root structure of `notebook/stars.json`. */
@Serializable
data class NotebookStars(
    val vocab: List<StarredVocab> = emptyList(),
    val grammar: List<StarredGrammar> = emptyList(),
    val sentences: Set<String> = emptySet(),
)

/** One Q&A exchange paired with its source sentence for the Notebook `追问 Q&A` tab. */
data class NotebookQaEntry(
    val id: String,
    val explanationId: String,
    val sentence: String,
    val speakerJp: String?,
    val officialCn: String?,
    val scene: Int,
    val lineNo: Int,
    val voice: String?,
    val voiceCharId: Int,
    val question: String,
    val answer: String,
    val createdAt: Long,
) {
    val voiceRef: VoiceRef? get() = voice?.takeIf { it.isNotBlank() }?.let { VoiceRef(it, voiceCharId) }
}

/**
 * Independent learner notebook store (`filesDir/notebook/`), completely separate from [ExplainCache]
 * (`filesDir/ai_cache/`) so clearing the AI cache or switching models never deletes the user's
 * study history, self-contained explanation snapshots, follow-up threads, or starred items.
 */
class NotebookStore(val dir: File) {
    private val explanationsDir = File(dir, "explanations")
    private val sentencesFile = File(dir, "sentences.jsonl")
    private val starsFile = File(dir, "stars.json")

    private var sentencesCache: MutableList<NotebookSentenceEntry>? = null
    private var starsCache: NotebookStars? = null

    private fun safe(id: String) = id.filter { it.isLetterOrDigit() || it == '_' || it == '-' }.ifEmpty { "x" }
    private fun explFile(id: String) = File(explanationsDir, "${safe(id)}.json")
    private fun threadFile(id: String) = File(explanationsDir, "${safe(id)}.thread.json")

    @Synchronized
    private fun ensureSentences(): MutableList<NotebookSentenceEntry> {
        sentencesCache?.let { return it }
        val loaded = ArrayList<NotebookSentenceEntry>()
        val seen = HashSet<String>()
        if (sentencesFile.isFile) {
            runCatching {
                sentencesFile.forEachLine(Charsets.UTF_8) { line ->
                    val trimmed = line.trim()
                    if (trimmed.isNotEmpty()) {
                        runCatching { AiJson.decodeFromString(NotebookSentenceEntry.serializer(), trimmed) }
                            .getOrNull()
                            ?.takeIf { it.explanationId.isNotBlank() && seen.add(it.explanationId) }
                            ?.let { loaded += it }
                    }
                }
            }
        }
        sentencesCache = loaded
        return loaded
    }

    @Synchronized
    private fun ensureStars(): NotebookStars {
        starsCache?.let { return it }
        val loaded = if (starsFile.isFile) {
            runCatching { AiJson.decodeFromString(NotebookStars.serializer(), starsFile.readText(Charsets.UTF_8)) }
                .getOrDefault(NotebookStars())
        } else {
            NotebookStars()
        }
        starsCache = loaded
        return loaded
    }

    /** All recorded sentences, newest first (deduplicated by `explanationId`). */
    @Synchronized
    fun sentences(): List<NotebookSentenceEntry> = ensureSentences().toList()

    /** Current starred vocabulary, grammar, and sentence IDs. */
    @Synchronized
    fun stars(): NotebookStars = ensureStars()

    /**
     * Records a user-opened sentence breakdown into `sentences.jsonl` (deduplicated by `expl.id`)
     * and writes a self-contained snapshot to `explanations/<id>.json`.
     * Called whenever an opened line reaches `Ready(expl)`, regardless of `expl.fromCache` (R2.1).
     * Offline fallback breakdowns (`OfflineExplainer.MODEL`) are ignored.
     */
    @Synchronized
    fun recordSentence(
        lineRef: LineRef?,
        expl: SentenceExplanation,
        now: Long = if (expl.createdAt > 0L) expl.createdAt else System.currentTimeMillis(),
    ): NotebookSentenceEntry? {
        if (expl.model == OfflineExplainer.MODEL || expl.id.startsWith("offline_")) return null
        val list = ensureSentences()
        val existingIdx = list.indexOfFirst { it.explanationId == expl.id }
        val existing = if (existingIdx >= 0) list.removeAt(existingIdx) else null
        val sceneNum = lineRef?.scene?.toIntOrNull() ?: existing?.scene ?: 0
        val lineNo = lineRef?.lineNo ?: existing?.lineNo ?: 0
        val voiceFile = lineRef?.voice?.file ?: existing?.voice
        val voiceChar = lineRef?.voice?.charId ?: existing?.voiceCharId ?: 0
        val entry = NotebookSentenceEntry(
            explanationId = expl.id,
            scene = sceneNum,
            lineNo = lineNo,
            voice = voiceFile,
            voiceCharId = voiceChar,
            sentence = expl.sentence,
            speakerJp = expl.speakerJp ?: existing?.speakerJp,
            speakerCn = expl.speakerCn ?: existing?.speakerCn,
            officialCn = expl.officialCnTranslation ?: existing?.officialCn,
            translation = expl.translation,
            model = expl.model,
            createdAt = now,
            vocabCount = expl.vocab.size,
            grammarCount = expl.grammar.size,
        )
        list.add(0, entry)
        writeSentences(list)
        writeAtomic(explFile(expl.id), ExplainJsonCodec.encodeExplanation(expl.copy(fromCache = false)))
        return entry
    }

    /** Saves a self-contained copy of [explanationId]'s follow-up Q&A thread. */
    @Synchronized
    fun saveThread(explanationId: String, thread: List<FollowUpMessage>) {
        if (explanationId.isBlank()) return
        if (thread.isEmpty()) {
            threadFile(explanationId).delete()
            return
        }
        writeAtomic(threadFile(explanationId), ExplainJsonCodec.encodeThread(thread))
    }

    /** Loads the self-contained [SentenceExplanation] snapshot for [explanationId], or null. */
    @Synchronized
    fun loadExplanation(explanationId: String): SentenceExplanation? {
        val f = explFile(explanationId)
        if (!f.isFile) return null
        return runCatching { ExplainJsonCodec.decodeExplanation(f.readText(Charsets.UTF_8)).copy(fromCache = true) }.getOrNull()
    }

    /** Loads the self-contained follow-up thread for [explanationId]. */
    @Synchronized
    fun loadThread(explanationId: String): List<FollowUpMessage> {
        val f = threadFile(explanationId)
        if (!f.isFile) return emptyList()
        return runCatching { ExplainJsonCodec.decodeThread(f.readText(Charsets.UTF_8)) }.getOrDefault(emptyList())
    }

    /**
     * One-time migration helper: if the notebook is empty and `ai_cache/` already has cached
     * breakdowns, imports them into `notebook/` so prior study sessions appear immediately.
     */
    @Synchronized
    fun importFromCacheIfEmpty(cacheDir: File) {
        if (ensureSentences().isNotEmpty() || !cacheDir.isDirectory) return
        val files = cacheDir.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".json") && !it.name.endsWith(".thread.json") }
            ?.sortedBy { it.lastModified() }
            ?: return
        for (f in files) {
            val expl = runCatching { ExplainJsonCodec.decodeExplanation(f.readText(Charsets.UTF_8)) }.getOrNull() ?: continue
            recordSentence(null, expl, expl.createdAt.takeIf { it > 0L } ?: f.lastModified())
            val tf = File(cacheDir, f.nameWithoutExtension + ".thread.json")
            if (tf.isFile) {
                val th = runCatching { ExplainJsonCodec.decodeThread(tf.readText(Charsets.UTF_8)) }.getOrDefault(emptyList())
                if (th.isNotEmpty()) saveThread(expl.id, th)
            }
        }
    }

    // ------------------------------------------------------------------------------- stars (★ / ☆)

    @Synchronized
    fun isSentenceStarred(explanationId: String): Boolean = explanationId in ensureStars().sentences

    @Synchronized
    fun toggleSentenceStar(explanationId: String): Boolean {
        val cur = ensureStars()
        val nowStarred = explanationId !in cur.sentences
        val next = cur.copy(sentences = if (nowStarred) cur.sentences + explanationId else cur.sentences - explanationId)
        saveStars(next)
        return nowStarred
    }

    @Synchronized
    fun isVocabStarred(lemma: String): Boolean = ensureStars().vocab.any { it.lemma == lemma }

    @Synchronized
    fun toggleVocabStar(
        v: VocabEntry,
        expl: SentenceExplanation,
        lineRef: LineRef? = null,
        now: Long = System.currentTimeMillis(),
    ): Boolean {
        val key = v.lemma.ifBlank { v.surface }
        val cur = ensureStars()
        if (cur.vocab.any { it.lemma == key }) {
            saveStars(cur.copy(vocab = cur.vocab.filterNot { it.lemma == key }))
            return false
        }
        val entry = ensureSentences().firstOrNull { it.explanationId == expl.id }
        val item = StarredVocab(
            lemma = key,
            reading = v.lemmaReading,
            surface = v.surface,
            meaning = v.meaning,
            partOfSpeech = v.partOfSpeech,
            conjugation = v.conjugation,
            jlpt = v.jlpt,
            pitchAccent = v.pitchAccent,
            notes = v.notes,
            exampleSentence = expl.sentence,
            officialCn = expl.officialCnTranslation ?: entry?.officialCn,
            speakerJp = expl.speakerJp ?: entry?.speakerJp,
            scene = lineRef?.scene?.toIntOrNull() ?: entry?.scene ?: 0,
            lineNo = lineRef?.lineNo ?: entry?.lineNo ?: 0,
            voice = lineRef?.voice?.file ?: entry?.voice,
            voiceCharId = lineRef?.voice?.charId ?: entry?.voiceCharId ?: 0,
            explanationId = expl.id,
            starredAt = now,
        )
        saveStars(cur.copy(vocab = listOf(item) + cur.vocab))
        return true
    }

    @Synchronized
    fun toggleVocabStar(item: StarredVocab, now: Long = System.currentTimeMillis()): Boolean {
        val cur = ensureStars()
        if (cur.vocab.any { it.lemma == item.lemma }) {
            saveStars(cur.copy(vocab = cur.vocab.filterNot { it.lemma == item.lemma }))
            return false
        }
        saveStars(cur.copy(vocab = listOf(item.copy(starredAt = now)) + cur.vocab))
        return true
    }

    @Synchronized
    fun isGrammarStarred(pattern: String): Boolean = ensureStars().grammar.any { it.pattern == pattern }

    @Synchronized
    fun toggleGrammarStar(
        g: GrammarPoint,
        expl: SentenceExplanation,
        lineRef: LineRef? = null,
        now: Long = System.currentTimeMillis(),
    ): Boolean {
        val cur = ensureStars()
        if (cur.grammar.any { it.pattern == g.pattern }) {
            saveStars(cur.copy(grammar = cur.grammar.filterNot { it.pattern == g.pattern }))
            return false
        }
        val entry = ensureSentences().firstOrNull { it.explanationId == expl.id }
        val item = StarredGrammar(
            pattern = g.pattern,
            explanation = g.explanation,
            jlpt = g.jlpt,
            exampleSentence = expl.sentence,
            officialCn = expl.officialCnTranslation ?: entry?.officialCn,
            speakerJp = expl.speakerJp ?: entry?.speakerJp,
            scene = lineRef?.scene?.toIntOrNull() ?: entry?.scene ?: 0,
            lineNo = lineRef?.lineNo ?: entry?.lineNo ?: 0,
            voice = lineRef?.voice?.file ?: entry?.voice,
            voiceCharId = lineRef?.voice?.charId ?: entry?.voiceCharId ?: 0,
            explanationId = expl.id,
            starredAt = now,
        )
        saveStars(cur.copy(grammar = listOf(item) + cur.grammar))
        return true
    }

    @Synchronized
    fun toggleGrammarStar(item: StarredGrammar, now: Long = System.currentTimeMillis()): Boolean {
        val cur = ensureStars()
        if (cur.grammar.any { it.pattern == item.pattern }) {
            saveStars(cur.copy(grammar = cur.grammar.filterNot { it.pattern == item.pattern }))
            return false
        }
        saveStars(cur.copy(grammar = listOf(item.copy(starredAt = now)) + cur.grammar))
        return true
    }

    // ------------------------------------------------------------------------------- aggregated views & Anki TSV

    /**
     * All vocabulary items across the notebook: starred items first, followed by unstarred vocab
     * extracted from recorded sentence snapshots (deduplicated by `lemma`).
     */
    @Synchronized
    fun allVocab(): List<StarredVocab> {
        val st = ensureStars()
        val out = ArrayList<StarredVocab>(st.vocab)
        val seen = st.vocab.mapTo(HashSet()) { it.lemma }
        for (s in ensureSentences()) {
            val expl = loadExplanation(s.explanationId) ?: continue
            for (v in expl.vocab) {
                val key = v.lemma.ifBlank { v.surface }
                if (key.isBlank() || !seen.add(key)) continue
                out += StarredVocab(
                    lemma = key,
                    reading = v.lemmaReading,
                    surface = v.surface,
                    meaning = v.meaning,
                    partOfSpeech = v.partOfSpeech,
                    conjugation = v.conjugation,
                    jlpt = v.jlpt,
                    pitchAccent = v.pitchAccent,
                    notes = v.notes,
                    exampleSentence = expl.sentence,
                    officialCn = expl.officialCnTranslation ?: s.officialCn,
                    speakerJp = expl.speakerJp ?: s.speakerJp,
                    scene = s.scene,
                    lineNo = s.lineNo,
                    voice = s.voice,
                    voiceCharId = s.voiceCharId,
                    explanationId = s.explanationId,
                    starredAt = s.createdAt,
                )
            }
        }
        return out
    }

    /**
     * All grammar items across the notebook: starred items first, followed by unstarred grammar
     * extracted from recorded sentence snapshots (deduplicated by `pattern`).
     */
    @Synchronized
    fun allGrammar(): List<StarredGrammar> {
        val st = ensureStars()
        val out = ArrayList<StarredGrammar>(st.grammar)
        val seen = st.grammar.mapTo(HashSet()) { it.pattern }
        for (s in ensureSentences()) {
            val expl = loadExplanation(s.explanationId) ?: continue
            for (g in expl.grammar) {
                if (g.pattern.isBlank() || !seen.add(g.pattern)) continue
                out += StarredGrammar(
                    pattern = g.pattern,
                    explanation = g.explanation,
                    jlpt = g.jlpt,
                    exampleSentence = expl.sentence,
                    officialCn = expl.officialCnTranslation ?: s.officialCn,
                    speakerJp = expl.speakerJp ?: s.speakerJp,
                    scene = s.scene,
                    lineNo = s.lineNo,
                    voice = s.voice,
                    voiceCharId = s.voiceCharId,
                    explanationId = s.explanationId,
                    starredAt = s.createdAt,
                )
            }
        }
        return out
    }

    /** All follow-up Q&A pairs across recorded sentences, newest first. */
    @Synchronized
    fun allQa(): List<NotebookQaEntry> {
        val out = ArrayList<NotebookQaEntry>()
        for (s in ensureSentences()) {
            val th = loadThread(s.explanationId)
            var i = 0
            while (i < th.size) {
                val m = th[i]
                if (m.role == FollowUpMessage.ROLE_USER) {
                    val ans = th.getOrNull(i + 1)?.takeIf { it.role == FollowUpMessage.ROLE_MODEL }
                    if (ans != null) {
                        out += NotebookQaEntry(
                            id = "${s.explanationId}_${ans.id}",
                            explanationId = s.explanationId,
                            sentence = s.sentence,
                            speakerJp = s.speakerJp,
                            officialCn = s.officialCn,
                            scene = s.scene,
                            lineNo = s.lineNo,
                            voice = s.voice,
                            voiceCharId = s.voiceCharId,
                            question = m.text,
                            answer = ans.text,
                            createdAt = ans.createdAt,
                        )
                        i += 2
                        continue
                    }
                }
                i++
            }
        }
        return out.sortedByDescending { it.createdAt }
    }

    /**
     * Generates an Anki-compatible TSV string (`表达式\t读音\t词性/JLPT\t释义\t例句\t官方译文\t出处`).
     * When [onlyStarred] is true, exports only starred vocabulary and grammar; otherwise exports all
     * vocabulary and grammar collected in the notebook.
     */
    @Synchronized
    fun exportAnkiTsv(onlyStarred: Boolean = false): String {
        val vocabList = if (onlyStarred) ensureStars().vocab else allVocab()
        val grammarList = if (onlyStarred) ensureStars().grammar else allGrammar()
        val sb = StringBuilder()
        for (v in vocabList) {
            val posJlpt = listOfNotNull(partOfSpeechLabel(v.partOfSpeech), v.jlpt).joinToString(" · ")
            val src = buildSourceTag(v.speakerJp, v.scene, v.lineNo)
            sb.append(
                listOf(
                    cleanTsvField(v.lemma),
                    cleanTsvField(v.reading),
                    cleanTsvField(posJlpt),
                    cleanTsvField(v.meaning),
                    cleanTsvField(v.exampleSentence),
                    cleanTsvField(v.officialCn.orEmpty()),
                    cleanTsvField(src),
                ).joinToString("\t"),
            ).append('\n')
        }
        for (g in grammarList) {
            val posJlpt = listOfNotNull("语法 Grammar", g.jlpt).joinToString(" · ")
            val src = buildSourceTag(g.speakerJp, g.scene, g.lineNo)
            sb.append(
                listOf(
                    cleanTsvField(g.pattern),
                    "",
                    cleanTsvField(posJlpt),
                    cleanTsvField(g.explanation),
                    cleanTsvField(g.exampleSentence),
                    cleanTsvField(g.officialCn.orEmpty()),
                    cleanTsvField(src),
                ).joinToString("\t"),
            ).append('\n')
        }
        return sb.toString()
    }

    private fun buildSourceTag(speakerJp: String?, scene: Int, lineNo: Int): String = buildList {
        add("WA2")
        if (scene > 0) add("S$scene" + if (lineNo > 0) "#$lineNo" else "")
        if (!speakerJp.isNullOrBlank()) add(speakerJp)
    }.joinToString(" · ")

    private fun cleanTsvField(s: String): String =
        s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ').trim()

    private fun saveStars(next: NotebookStars) {
        starsCache = next
        writeAtomic(starsFile, AiJson.encodeToString(NotebookStars.serializer(), next))
    }

    private fun writeSentences(list: List<NotebookSentenceEntry>) {
        val text = list.joinToString("\n") { AiJson.encodeToString(NotebookSentenceEntry.serializer(), it) } +
            if (list.isNotEmpty()) "\n" else ""
        writeAtomic(sentencesFile, text)
    }

    private fun writeAtomic(target: File, text: String) {
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile ?: dir, target.name + ".tmp")
        tmp.writeText(text, Charsets.UTF_8)
        if (!tmp.renameTo(target)) {
            target.delete()
            tmp.renameTo(target)
        }
    }
}
