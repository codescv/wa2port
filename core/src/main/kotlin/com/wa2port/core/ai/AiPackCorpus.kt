package com.wa2port.core.ai

import com.wa2port.core.archive.Wa2Assets
import com.wa2port.core.script.FuncSpec
import com.wa2port.core.script.ScenarioStore
import com.wa2port.core.text.BilingualTextDecoder
import com.wa2port.core.text.TextLang
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/*
 * Offline AI pack corpus pipeline (Method 3A): static extraction of every playable dialogue /
 * narration line from the `.bnr` programs, deduplication into generation units, the compact
 * Flash JSONL schema parser and the strict per-item validator. Used by [AiPackTool] and tests.
 */

/** One playable line: `SetMessage*(text, lineNo, …)` in [scene] with text token [token]. */
data class CorpusOccurrence(
    val scene: String,
    val token: Int,
    /** Global story-order index (scene order, then bytecode order). */
    val order: Int,
    val lineNo: Int,
    val speakerToken: Int,
    val speakerJp: String?,
    val speakerCn: String?,
    val jp: String,
    val cn: String?,
    val scriptRuby: List<ScriptRuby>,
    /** Up to [AiPackCorpus.CONTEXT_LINES] previous lines of the same scene (`話者: 台詞`). */
    val context: List<String>,
) {
    val lexical: Boolean get() = AiPackCorpus.isLexical(jp)

    /** The request the app builds for this line ([com.wa2port.core.engine.LineRef] fields aside). */
    fun request(): ExplainRequest = ExplainRequest(
        sentence = jp,
        contextBefore = context,
        speakerJp = speakerJp,
        speakerCn = speakerCn,
        officialCnTranslation = cn,
        scriptRuby = scriptRuby,
    )
}

/** One generation unit = one distinct plain Japanese sentence. */
data class CorpusUnit(
    val id: String,
    val jp: String,
    /** Canonical (most frequent) CK-GAL line, passed to the model and scoping `nt`. */
    val cn: String?,
    val speakerJp: String?,
    val scriptRuby: List<ScriptRuby>,
    val context: List<String>,
    val occurrences: Int,
    val cnVariants: Int,
    val lexical: Boolean,
)

/** Static scan statistics of one extraction (audit of the token streams). */
data class CorpusAudit(
    val scenes: Int,
    val tokens: Int,
    val messageCalls: Int,
    val nonLiteralMessageCalls: Int,
    val messageTokens: Int,
    val speakerOnlyTokens: Int,
    val choiceTokens: Int,
    val otherTextTokens: Int,
    /** Tokens with kana/kanji never referenced by any call (should be 0 or dead text). */
    val unreferencedJapaneseTokens: List<Pair<String, Int>>,
    /** Non-line text arguments with kana/kanji (`scene\ttoken\ttext`), e.g. CG / BGM titles. */
    val otherJapaneseTokens: List<String> = emptyList(),
)

data class CorpusExtraction(val occurrences: List<CorpusOccurrence>, val audit: CorpusAudit) {
    /** Distinct-sentence units, ids `u00000…` in first-appearance (story) order. */
    fun units(): List<CorpusUnit> {
        val groups = LinkedHashMap<String, MutableList<CorpusOccurrence>>()
        for (o in occurrences) groups.getOrPut(o.jp) { ArrayList() } += o
        var n = 0
        return groups.map { (jp, occ) ->
            val key = { o: CorpusOccurrence -> OfflineAiPack.cnKey(o.cn.orEmpty()) }
            val cnCounts = occ.groupingBy(key).eachCount()
            val best = cnCounts.entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { occ.indexOfFirst { o -> key(o) == it.key } }).first().key
            val canon = occ.first { key(it) == best }
            CorpusUnit(
                id = "u%05d".format(n++),
                jp = jp,
                cn = canon.cn,
                speakerJp = canon.speakerJp,
                scriptRuby = canon.scriptRuby,
                context = canon.context,
                occurrences = occ.size,
                cnVariants = cnCounts.size,
                lexical = AiPackCorpus.isLexical(jp),
            )
        }
    }
}

object AiPackCorpus {
    const val CONTEXT_LINES = 2
    const val CONTEXT_MAX_CHARS = 80

    /** `SetMessage` / `SetMessageE` / `SetMessage2`. */
    val MESSAGE_OPS = setOf(130, 131, 133)
    val SPEAKER_OPS = setOf(144, 223, 145, 224)
    const val WINDOW_RESET_OP = 143
    const val CHOICE_OP = 208

    /** A line with at least one letter / digit (kana, kanji, Latin…); else it is a pure pause / punctuation line. */
    fun isLexical(s: String): Boolean = s.codePoints().anyMatch { Character.isLetterOrDigit(it) || JapaneseText.isKanji(it) }

    private const val UNKNOWN = -1
    private const val INT_LIT = -2

    private class SceneScan {
        val messages = ArrayList<IntArray>() // token, lineNo, speakerToken
        var calls = 0
        var nonLiteral = 0
        val speakerTokens = HashSet<Int>()
        val choiceTokens = HashSet<Int>()
        val otherTokens = HashSet<Int>()
    }

    /**
     * Linear static scan of an LSCR program mirroring [com.wa2port.core.script.LscrVm]'s
     * statement evaluation (constant pushes, operator arities, `argc` from the function table) so
     * every literal text argument of every call is attributed to its opcode.
     */
    private fun scan(words: IntArray, headerBytes: Int, functions: Map<Int, FuncSpec>): SceneScan {
        val r = SceneScan()
        val stack = ArrayList<Int>()
        var speaker = -1
        var i = headerBytes / 4
        fun pop(): Int = if (stack.isEmpty()) UNKNOWN else stack.removeAt(stack.size - 1)
        while (i < words.size) {
            when (words[i]) {
                5 -> { stack += INT_LIT; i += 3 }
                3 -> { stack += words.getOrElse(i + 1) { 0 }.coerceAtLeast(0); i += 2 }
                1, 2 -> { stack += UNKNOWN; i += 2 }
                6 -> {
                    val op = words.getOrElse(i + 1) { 30 }
                    when (op) {
                        30 -> stack.clear()
                        23, 24, 25, 26 -> { pop(); stack += UNKNOWN }
                        else -> { pop(); pop(); stack += UNKNOWN }
                    }
                    i += 2
                }
                4 -> {
                    val op = words.getOrElse(i + 1) { -1 }
                    val spec = functions[op]
                    val argc = spec?.argc ?: stack.size
                    val take = minOf(argc, stack.size)
                    val args = ArrayList<Int>(take)
                    for (k in stack.size - take until stack.size) args += stack[k]
                    repeat(take) { stack.removeAt(stack.size - 1) }
                    val a0 = args.getOrElse(0) { UNKNOWN }
                    when {
                        op in MESSAGE_OPS -> {
                            r.calls++
                            if (a0 >= 0) r.messages += intArrayOf(a0, -1, speaker) else r.nonLiteral++
                        }
                        op in SPEAKER_OPS -> { speaker = if (a0 >= 0) a0 else -1; if (a0 >= 0) r.speakerTokens += a0 }
                        op == WINDOW_RESET_OP -> speaker = -1
                        op == CHOICE_OP -> if (a0 >= 0) r.choiceTokens += a0
                        else -> args.filter { it >= 0 }.forEach { r.otherTokens += it }
                    }
                    stack += UNKNOWN
                    i += 2
                }
                0 -> {
                    when (words.getOrElse(i + 1) { -1 }) {
                        13, 14, 15, 16 -> pop()
                        else -> stack.clear()
                    }
                    i += com.wa2port.core.script.LscrProgram.controlLength(words, i)
                }
                else -> i += 1
            }
        }
        return r
    }

    /**
     * Extracts every playable dialogue / narration line of every scene (`9999` replay dispatcher
     * and Omake included — replay targets are ordinary scenes). Lines are keyed by `(scene, token)`
     * exactly like [com.wa2port.core.engine.LineRef]; speaker-name tokens are not lines.
     */
    fun extract(assets: Wa2Assets, decoder: BilingualTextDecoder = BilingualTextDecoder()): CorpusExtraction {
        val store = ScenarioStore(assets)
        val out = ArrayList<CorpusOccurrence>()
        var tokens = 0
        var calls = 0
        var nonLiteral = 0
        var speakerOnly = 0
        var choice = 0
        var other = 0
        val unreferenced = ArrayList<Pair<String, Int>>()
        val otherJp = ArrayList<String>()
        var order = 0
        for (name in store.sceneNames) {
            val sc = store.scene(name) ?: continue
            val s = scan(sc.program.words, sc.program.headerBytes, store.functions)
            calls += s.calls
            nonLiteral += s.nonLiteral
            tokens += sc.jp.size
            val seen = HashSet<Int>()
            val msgTokens = s.messages.map { it[0] }.toSet()
            speakerOnly += s.speakerTokens.count { it !in msgTokens }
            choice += s.choiceTokens.size
            other += s.otherTokens.count { it !in msgTokens && it !in s.speakerTokens && it !in s.choiceTokens }
            val history = ArrayList<String>()
            for (m in s.messages) {
                val tok = m[0]
                if (!seen.add(tok)) continue
                val sentence = JpSentence.fromRich(sc.jp.rich(tok), decoder)
                if (sentence.text.isBlank()) continue
                val spTok = m[2]
                val spJp = if (spTok >= 0) JpSentence.speaker(sc.jp.rich(spTok), decoder, TextLang.JP) else null
                val spCn = if (spTok >= 0) sc.cn?.let { JpSentence.speaker(it.rich(spTok), decoder, TextLang.CN) } else null
                out += CorpusOccurrence(
                    scene = name,
                    token = tok,
                    order = order++,
                    lineNo = m[1],
                    speakerToken = spTok,
                    speakerJp = spJp,
                    speakerCn = spCn,
                    jp = sentence.text,
                    cn = JpSentence.officialCn(sc.cn?.rich(tok), decoder),
                    scriptRuby = sentence.scriptRuby,
                    context = history.takeLast(CONTEXT_LINES),
                )
                history += formatContextLine(spJp, sentence.text).take(CONTEXT_MAX_CHARS)
            }
            for (t in s.otherTokens.sorted()) {
                if (t in msgTokens || t in s.speakerTokens || t in s.choiceTokens) continue
                val text = decoder.toPlain(sc.jp.rich(t), TextLang.JP)
                if (text.codePoints().anyMatch { JapaneseText.isKanji(it) || JapaneseText.isKana(it) }) otherJp += "$name\t$t\t$text"
            }
            for (t in 0 until sc.jp.size) {
                if (t in msgTokens || t in s.speakerTokens || t in s.choiceTokens || t in s.otherTokens) continue
                val text = decoder.toPlain(sc.jp.rich(t), TextLang.JP)
                if (text.codePoints().anyMatch { JapaneseText.isKanji(it) || JapaneseText.isKana(it) }) unreferenced += name to t
            }
        }
        val audit = CorpusAudit(
            scenes = store.sceneNames.size,
            tokens = tokens,
            messageCalls = calls,
            nonLiteralMessageCalls = nonLiteral,
            messageTokens = out.size,
            speakerOnlyTokens = speakerOnly,
            choiceTokens = choice,
            otherTextTokens = other,
            unreferencedJapaneseTokens = unreferenced,
            otherJapaneseTokens = otherJp,
        )
        return CorpusExtraction(out, audit)
    }

    // --------------------------------------------------------------------------------- JSONL helpers

    fun scriptRubyJson(list: List<ScriptRuby>): JsonArray = buildJsonArray {
        list.forEach { add(buildJsonArray { add(JsonPrimitive(it.span.start)); add(JsonPrimitive(it.span.end)); add(JsonPrimitive(it.reading)) }) }
    }

    fun parseScriptRuby(el: JsonElement?): List<ScriptRuby> = (el as? JsonArray)?.mapNotNull { e ->
        val a = e as? JsonArray ?: return@mapNotNull null
        val s = a.getOrNull(0)?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
        val t = a.getOrNull(1)?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
        val r = a.getOrNull(2)?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
        ScriptRuby(CharSpan(s, t), r)
    } ?: emptyList()

    /** `unique.jsonl` / batch line of [u] (the Flash input). */
    fun unitJson(u: CorpusUnit): JsonObject = buildJsonObject {
        put("i", u.id)
        put("jp", u.jp)
        u.speakerJp?.let { put("sp", it) }
        u.cn?.let { put("cn", it) }
        if (u.cnVariants > 1) put("cnv", u.cnVariants)
        if (u.context.isNotEmpty()) put("ctx", JsonArray(u.context.map { JsonPrimitive(it) }))
        if (u.scriptRuby.isNotEmpty()) put("srb", scriptRubyJson(u.scriptRuby))
        put("n", u.occurrences)
        if (!u.lexical) put("auto", true)
    }

    fun parseUnit(o: JsonObject): CorpusUnit = CorpusUnit(
        id = o.str("i") ?: error("unit without i"),
        jp = o.str("jp") ?: error("unit without jp"),
        cn = o.str("cn"),
        speakerJp = o.str("sp"),
        scriptRuby = parseScriptRuby(o["srb"]),
        context = (o["ctx"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList(),
        occurrences = (o["n"] as? JsonPrimitive)?.intOrNull ?: 1,
        cnVariants = (o["cnv"] as? JsonPrimitive)?.intOrNull ?: 1,
        lexical = (o["auto"] as? JsonPrimitive)?.contentOrNull != "true",
    )

    fun unitRequest(u: CorpusUnit): ExplainRequest = ExplainRequest(
        sentence = u.jp,
        contextBefore = u.context,
        speakerJp = u.speakerJp,
        officialCnTranslation = u.cn,
        scriptRuby = u.scriptRuby,
    )

    private fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull

    // --------------------------------------------------------------------------------- compact schema

    private fun JsonElement?.s(): String? = (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull
    private fun JsonElement?.sOrEmpty(): String = s().orEmpty()
    private fun JsonElement?.blankToNull(): String? = s()?.trim()?.takeIf { it.isNotEmpty() }

    private fun parseRubyList(el: JsonElement?): List<RawRuby> = (el as? JsonArray)?.mapNotNull { e ->
        when (e) {
            is JsonArray -> RawRuby(e.getOrNull(0).sOrEmpty(), e.getOrNull(1).blankToNull())
            is JsonObject -> RawRuby((e["base"] ?: e["b"]).sOrEmpty(), (e["ruby"] ?: e["r"]).blankToNull())
            is JsonPrimitive -> e.contentOrNull?.let { t ->
                // "漢字|かんじ" shorthand.
                val bar = t.indexOf('|')
                if (bar > 0) RawRuby(t.substring(0, bar), t.substring(bar + 1).ifBlank { null }) else RawRuby(t, null)
            }
        }
    }?.filter { it.base.isNotEmpty() } ?: emptyList()

    private fun parseVocab(el: JsonElement): RawVocab? = when (el) {
        is JsonArray -> {
            val surface = el.getOrNull(0).sOrEmpty()
            RawVocab(
                surface = surface,
                lemma = el.getOrNull(1).sOrEmpty().ifBlank { surface },
                lemmaReading = el.getOrNull(2).sOrEmpty(),
                lemmaRuby = emptyList(),
                partOfSpeech = ExplainJsonCodec.normPartOfSpeech(el.getOrNull(3).s()),
                meaning = el.getOrNull(4).sOrEmpty(),
                conjugation = el.getOrNull(5).blankToNull(),
                jlpt = el.getOrNull(6).blankToNull()?.uppercase()?.takeIf { it in JLPT_LEVELS },
                pitchAccent = null,
                notes = el.getOrNull(7).blankToNull(),
            )
        }
        is JsonObject -> {
            val surface = el["surface"].sOrEmpty()
            RawVocab(
                surface = surface,
                lemma = el["lemma"].sOrEmpty().ifBlank { surface },
                lemmaReading = (el["lemmaReading"] ?: el["reading"]).sOrEmpty(),
                lemmaRuby = emptyList(),
                partOfSpeech = ExplainJsonCodec.normPartOfSpeech((el["partOfSpeech"] ?: el["pos"]).s()),
                meaning = el["meaning"].sOrEmpty(),
                conjugation = el["conjugation"].blankToNull(),
                jlpt = el["jlpt"].blankToNull()?.uppercase()?.takeIf { it in JLPT_LEVELS },
                pitchAccent = el["pitchAccent"].s()?.toFloatOrNull(),
                notes = el["notes"].blankToNull(),
            )
        }
        else -> null
    }

    private fun parseEvo(el: JsonElement?): Pair<String?, RawSpokenEvolution?> = when (el) {
        is JsonArray -> {
            // [textbookForm, kind, derivation, formality?]
            val tb = el.getOrNull(0).blankToNull()
            val kind = el.getOrNull(1).sOrEmpty()
            val der = el.getOrNull(2).sOrEmpty()
            val f = el.getOrNull(3).s()?.toFloatOrNull()
            tb to if (kind.isBlank() && der.isBlank()) null else RawSpokenEvolution(kind, der, f)
        }
        is JsonObject -> {
            val tb = el["textbookForm"].blankToNull()
            val kind = el["kind"].sOrEmpty()
            val der = el["derivation"].sOrEmpty()
            tb to if (kind.isBlank() && der.isBlank()) null else RawSpokenEvolution(kind, der, el["formality"].s()?.toFloatOrNull())
        }
        else -> null to null
    }

    private fun parseChunk(el: JsonElement): RawChunk? = when (el) {
        is JsonArray -> {
            // [surface, reading, evoOrNull, role, meaning, vocab[], nuance?]
            val surface = el.getOrNull(0).sOrEmpty()
            val (tb, evo) = parseEvo(el.getOrNull(2))
            RawChunk(
                surface = surface,
                reading = el.getOrNull(1).sOrEmpty(),
                textbookForm = tb ?: surface,
                spokenEvolution = evo,
                role = el.getOrNull(3).sOrEmpty(),
                meaning = el.getOrNull(4).sOrEmpty(),
                nuance = el.getOrNull(6).blankToNull(),
                vocab = (el.getOrNull(5) as? JsonArray)?.mapNotNull { parseVocab(it) } ?: emptyList(),
            )
        }
        is JsonObject -> {
            val surface = el["surface"].sOrEmpty()
            val (tbEvo, evo) = parseEvo(el["spokenEvolution"] ?: el["evo"])
            RawChunk(
                surface = surface,
                reading = el["reading"].sOrEmpty(),
                textbookForm = el["textbookForm"].blankToNull() ?: tbEvo ?: surface,
                spokenEvolution = evo,
                role = el["role"].sOrEmpty(),
                meaning = el["meaning"].sOrEmpty(),
                nuance = el["nuance"].blankToNull(),
                vocab = (el["vocab"] as? JsonArray)?.mapNotNull { parseVocab(it) } ?: emptyList(),
            )
        }
        else -> null
    }

    private fun parseGrammar(el: JsonElement): RawGrammar? = when (el) {
        is JsonArray -> {
            val surfaces = when (val s = el.getOrNull(1)) {
                is JsonArray -> s.mapNotNull { it.s() }
                is JsonPrimitive -> listOfNotNull(s.contentOrNull)
                else -> emptyList()
            }
            RawGrammar(el.getOrNull(0).sOrEmpty(), surfaces, el.getOrNull(2).sOrEmpty(), el.getOrNull(3).blankToNull()?.uppercase()?.takeIf { it in JLPT_LEVELS })
        }
        is JsonObject -> RawGrammar(
            el["pattern"].sOrEmpty(),
            (el["chunkSurfaces"] as? JsonArray)?.mapNotNull { it.s() } ?: emptyList(),
            (el["explanation"] ?: el["expl"]).sOrEmpty(),
            el["jlpt"].blankToNull()?.uppercase()?.takeIf { it in JLPT_LEVELS },
        )
        else -> null
    }

    /**
     * Parses one Flash output object: the compact schema (`i,tr,lt,nt,rg,tn,rb,ch,gr`) or the full
     * [RawExplanation] schema (`translation, ruby, chunks, grammar…`).
     */
    fun parseOutput(o: JsonObject): RawExplanation {
        if (o.containsKey("translation")) return ExplainJsonCodec.parseRawExplanation(o)
        return RawExplanation(
            translation = o["tr"].sOrEmpty().trim(),
            literalTranslation = o["lt"].blankToNull(),
            officialTranslationNote = o["nt"].blankToNull(),
            register = RegisterTag.parse(o["rg"].s()),
            tone = o["tn"].blankToNull(),
            ruby = parseRubyList(o["rb"]),
            chunks = (o["ch"] as? JsonArray)?.mapNotNull { parseChunk(it) } ?: emptyList(),
            grammar = (o["gr"] as? JsonArray)?.mapNotNull { parseGrammar(it) } ?: emptyList(),
        )
    }

    // --------------------------------------------------------------------------------- validation

    private val PLACEHOLDER = Regex(
        "^(todo|tbd|n/?a|null|none|undefined|placeholder|translation|译文|翻译|中文翻译|同上|略|无|xxx+|\\.+|…+|-+|\\?+)$",
        RegexOption.IGNORE_CASE,
    )
    private val PLACEHOLDER_INSIDE = Regex("todo|placeholder|lorem ipsum|<[a-z_]+>|\\{\\{|中文翻译在此|此处填写", RegexOption.IGNORE_CASE)
    const val MIN_LEAK_LEN = 12
    const val MIN_CHUNK_COVERAGE = 0.85f

    private fun hanCount(s: String) = s.codePoints().filter { JapaneseText.isKanji(it) && it != 0x3005 && it != 0x3006 }.count().toInt()
    private fun kanaCount(s: String) = s.codePoints().filter { JapaneseText.isKana(it) && it != 0x30FC }.count().toInt()

    /** Result of validating one output item. */
    data class ItemResult(val id: String, val errors: List<String>, val warnings: List<String>, val entry: PackEntry?, val explanation: SentenceExplanation?)

    private fun isPlaceholder(s: String?): Boolean {
        val t = s?.trim() ?: return false
        return t.isEmpty() || PLACEHOLDER.matches(t) || PLACEHOLDER_INSIDE.containsMatchIn(t)
    }

    /** Every model-written text field of [raw]. */
    private fun proseFields(raw: RawExplanation): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        out += "tr" to raw.translation
        raw.literalTranslation?.let { out += "lt" to it }
        raw.officialTranslationNote?.let { out += "nt" to it }
        raw.tone?.let { out += "tn" to it }
        raw.chunks.forEachIndexed { ci, c ->
            out += "ch[$ci].role" to c.role
            out += "ch[$ci].meaning" to c.meaning
            c.nuance?.let { out += "ch[$ci].nuance" to it }
            if (c.textbookForm != c.surface) out += "ch[$ci].textbookForm" to c.textbookForm
            c.spokenEvolution?.let { out += "ch[$ci].evo" to (it.kind + " " + it.derivation) }
            c.vocab.forEachIndexed { vi, v ->
                out += "ch[$ci].v[$vi].meaning" to v.meaning
                v.notes?.let { out += "ch[$ci].v[$vi].notes" to it }
                v.conjugation?.let { out += "ch[$ci].v[$vi].conj" to it }
            }
        }
        raw.grammar.forEachIndexed { gi, g -> out += "gr[$gi].pattern" to g.pattern; out += "gr[$gi].expl" to g.explanation }
        return out
    }

    /** Same ruby (ruby-bearing spans + readings) and concatenation invariant. */
    private fun rubyKey(t: List<RubyToken>) = t.filter { it.ruby != null }.map { Triple(it.span.start, it.span.end, it.ruby) }

    /**
     * Strictly validates one Flash output [o] for [unit]: required Chinese translation, no
     * placeholders, verbatim-aligned chunks covering the sentence, readings on every kanji,
     * clean-room (no verbatim script / CK-GAL line inside prose), and a lossless pack round trip.
     */
    fun validateItem(unit: CorpusUnit, o: JsonObject, now: Long = 0L): ItemResult {
        val errors = ArrayList<String>()
        val warnings = ArrayList<String>()
        val raw = try {
            parseOutput(o)
        } catch (e: Exception) {
            return ItemResult(unit.id, listOf("unparseable item: ${e.message}"), emptyList(), null, null)
        }
        val jp = unit.jp
        // Translation.
        val tr = raw.translation.trim()
        if (tr.isEmpty()) errors += "tr is blank (Simplified Chinese translation required)"
        else if (isPlaceholder(tr)) errors += "tr looks like a placeholder: \"$tr\""
        else {
            if (hanCount(tr) == 0 && kanaCount(jp) + hanCount(jp) > 0) errors += "tr has no Chinese characters: \"$tr\""
            if (kanaCount(tr) > 2 && kanaCount(tr) > hanCount(tr)) errors += "tr looks Japanese, not Chinese: \"$tr\""
            if (tr == jp) errors += "tr just copies the Japanese sentence"
        }
        for ((k, v) in proseFields(raw)) {
            if (k != "tr" && isPlaceholder(v) && v.isNotBlank()) errors += "$k looks like a placeholder: \"$v\""
            if (jp.length >= MIN_LEAK_LEN && v.contains(jp)) errors += "$k quotes the whole Japanese line verbatim (clean-room): remove the quote"
            val cn = unit.cn
            if (cn != null && cn.length >= MIN_LEAK_LEN && v.contains(cn)) errors += "$k quotes the official CK-GAL line verbatim (clean-room): paraphrase instead"
        }
        if (o.containsKey("rg") && raw.register == null && !o["rg"].s().isNullOrBlank()) warnings += "rg \"${o["rg"].s()}\" is not a valid register (ignored)"

        // Chunks.
        if (raw.chunks.isEmpty()) errors += "ch is empty: chunks must cover the sentence"
        raw.chunks.forEachIndexed { ci, c ->
            if (c.surface.isBlank()) errors += "ch[$ci] has a blank surface"
            if (c.meaning.isBlank()) errors += "ch[$ci] \"${c.surface}\" has a blank meaning"
            if (c.role.isBlank()) warnings += "ch[$ci] \"${c.surface}\" has a blank role"
        }
        val req = unitRequest(unit)
        val meta = ExplanationMeta(id = "validate", model = "validate", now = now, promptVersion = PROMPT_VERSION)
        val full = try {
            ExplainJsonCodec.toSentenceExplanation(raw, req, meta)
        } catch (e: Exception) {
            errors += "assembly failed: ${e.javaClass.simpleName}: ${e.message}"
            return ItemResult(unit.id, errors, warnings, null, null)
        }
        full.chunks.forEachIndexed { ci, c ->
            if (c.alignment == SpanAlignment.UNALIGNED) errors += "ch[$ci] surface \"${c.surface}\" is not a verbatim substring of the sentence (in order)"
        }
        val coverage = SpanAlign.coverage(jp, full.chunks.filter { it.alignment != SpanAlignment.UNALIGNED }.map { it.span })
        if (raw.chunks.isNotEmpty() && coverage < MIN_CHUNK_COVERAGE) {
            errors += "chunks cover only ${(coverage * 100).toInt()}% of the sentence (need ≥ ${(MIN_CHUNK_COVERAGE * 100).toInt()}%): cover it left→right without gaps"
        }
        val unalignedVocab = full.vocab.filter { it.alignment == SpanAlignment.UNALIGNED }
        val alignedChunkVocab = full.vocab.size
        if (unalignedVocab.isNotEmpty()) {
            val msg = "vocab not found verbatim inside its chunk: " + unalignedVocab.joinToString { "\"${it.surface}\"" }
            if (unalignedVocab.size >= 2 && unalignedVocab.size * 4 > alignedChunkVocab) errors += msg else warnings += msg
        }
        full.vocab.forEach { v ->
            if (v.meaning.isBlank()) errors += "vocab \"${v.surface}\" has a blank meaning"
            if (JapaneseText.hasKanji(v.lemma) && v.lemmaReading.isBlank()) errors += "vocab \"${v.lemma}\" needs a hiragana reading"
        }
        full.grammar.forEachIndexed { gi, g ->
            if (g.pattern.isBlank() || g.explanation.isBlank()) errors += "gr[$gi] needs pattern and explanation"
        }
        // Every kanji must carry furigana after model ruby + chunk/vocab backfill + script overlay.
        var p = 0
        val missing = ArrayList<String>()
        for (t in full.ruby) {
            if (t.ruby == null && JapaneseText.hasKanji(t.base)) missing += t.base
            p += t.base.length
        }
        if (missing.isNotEmpty()) errors += "kanji without reading: ${missing.distinct().joinToString { "\"$it\"" }} — add [\"漢字\",\"かな\"] pairs to rb"
        if (full.ruby.joinToString("") { it.base } != jp) errors += "internal: ruby does not reproduce the sentence"

        // Clean-room storage + lossless round trip.
        val storageExpl = ExplainJsonCodec.toSentenceExplanation(raw, req.copy(scriptRuby = emptyList()), meta)
        val noteHash = if (unit.cnVariants > 1 && unit.cn != null) OfflineAiPack.noteScopeHash(unit.cn) else 0
        val entry = PackEntry.fromExplanation(storageExpl, req.copy(scriptRuby = emptyList()), noteHash)
        val decoded = PackCodec.decode(PackCodec.encode(entry))
        val back = decoded.toExplanation(req, "validate", "validate", PROMPT_VERSION, now)
        if (back == null) {
            errors += "internal: pack round trip failed (span bounds)"
        } else {
            if (rubyKey(back.ruby) != rubyKey(full.ruby)) errors += "internal: ruby changed in pack round trip"
            if (back.chunks.map { Triple(it.span, it.surface, it.textbookForm) } != full.chunks.map { Triple(it.span, it.surface, it.textbookForm) }) errors += "internal: chunks changed in pack round trip"
            if (back.vocab.map { listOf(it.span, it.surface, it.lemma, it.lemmaReading, it.meaning) } != full.vocab.map { listOf(it.span, it.surface, it.lemma, it.lemmaReading, it.meaning) }) errors += "internal: vocab changed in pack round trip"
            if (back.grammar.map { it.chunkIds } != full.grammar.map { it.chunkIds }) errors += "internal: grammar links changed in pack round trip"
        }
        return ItemResult(unit.id, errors, warnings, if (errors.isEmpty()) entry else null, full)
    }

    /**
     * Deterministic clean-room entry for a non-lexical line (only punctuation such as `「……」`,
     * `「……！」`, `――`): one pause chunk, no model call.
     */
    fun silentEntry(jp: String): PackEntry {
        val q = jp.any { it == '？' || it == '?' }
        val e = jp.any { it == '！' || it == '!' }
        val dash = jp.any { it == '―' || it == '—' || it == '─' }
        val tr = when {
            q && e -> "（……？！ 惊愕得说不出话）"
            q -> "（……？ 带着疑惑的沉默）"
            e -> "（……！ 一时语塞的惊讶）"
            dash -> "（——话语中断 / 欲言又止）"
            else -> "（……沉默）"
        }
        val meaning = when {
            q && e -> "省略号＋「？！」：惊讶又困惑到说不出话，只发出无声的反应。"
            q -> "省略号＋「？」：用沉默表达疑问或不解，相当于「嗯？」「怎么了？」的无声版本。"
            e -> "省略号＋「！」：被吓到或受到冲击，一时说不出话。"
            dash -> "破折号「――」表示话说到一半被打断、停住，或情绪上无法继续说下去。"
            else -> "省略号「……」表示沉默、迟疑或无言以对，是视觉小说中常见的无声反应。"
        }
        val start = jp.indexOfFirst { it != '「' && it != '『' && it != '（' && it != '(' }.coerceAtLeast(0)
        val end = (jp.indexOfLast { it != '」' && it != '』' && it != '）' && it != ')' } + 1).coerceIn(start, jp.length)
        return PackEntry(
            sentenceHash = OfflineAiPack.hash64(jp),
            sentenceLength = jp.length,
            translation = tr,
            literalTranslation = null,
            officialTranslationNote = null,
            register = null,
            tone = "无声的停顿 · 沉默",
            ruby = emptyList(),
            chunks = listOf(
                PackChunk(
                    start = start, end = end, alignment = SpanAlignment.EXACT, unalignedSurface = "",
                    readingMode = PackChunk.READING_SURFACE, reading = "", textbookDiff = "",
                    spokenEvolution = null, role = "标点 · 无声停顿", meaning = meaning,
                ),
            ),
            grammar = emptyList(),
        )
    }

    /** Parses a JSONL file of objects, reporting malformed lines as `(lineNo, message)`. */
    fun readJsonl(text: String): Pair<List<JsonObject>, List<Pair<Int, String>>> {
        val ok = ArrayList<JsonObject>()
        val bad = ArrayList<Pair<Int, String>>()
        text.lineSequence().forEachIndexed { n, line ->
            var t = line.trim()
            if (t.isEmpty() || t.startsWith("```") || t.startsWith("//")) return@forEachIndexed
            if (t.endsWith(",")) t = t.dropLast(1)
            val el = runCatching { AiJson.parseToJsonElement(t) }.getOrElse {
                bad += (n + 1) to "invalid JSON: ${it.message?.take(100)}"
                return@forEachIndexed
            }
            when (el) {
                is JsonObject -> ok += el
                is JsonArray -> el.forEach { e -> if (e is JsonObject) ok += e else bad += (n + 1) to "array element is not an object" }
                else -> bad += (n + 1) to "line is not a JSON object"
            }
        }
        return ok to bad
    }
}
