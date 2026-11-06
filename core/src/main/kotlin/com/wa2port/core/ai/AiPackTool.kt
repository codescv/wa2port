package com.wa2port.core.ai

import com.wa2port.core.archive.Wa2Assets
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import java.io.File
import kotlin.system.exitProcess

/**
 * Offline AI pack pipeline CLI (Method 3A). Default working dir `/tmp/wa2-ai-gen`.
 *
 * ```
 * extract  --wa2-root R [--dir D] [--batch-size 90]   occurrences.jsonl, unique.jsonl, batches/
 * reshard  [--dir D] --batch-size N                     re-split batches/ from unique.jsonl
 * validate <id|batch_0007|path/out.jsonl> [--dir D]    exit 0 only when 100 % of the batch passes
 * status   [--dir D]                                    batches with/without valid output
 * compile  --wa2-root R [--dir D] [--allow-missing] [--output F] [--model M] [--pack-version V]
 * verify   --wa2-root R --pack F                        round trip on every line + clean-room leak audit
 * ```
 */
object AiPackTool {
    const val DEFAULT_DIR = "/tmp/wa2-ai-gen"
    const val DEFAULT_BATCH = 90

    class Args(argv: Array<String>) {
        val positional = ArrayList<String>()
        val opts = HashMap<String, String>()
        val flags = HashSet<String>()

        init {
            var i = 0
            while (i < argv.size) {
                val a = argv[i]
                if (a.startsWith("--")) {
                    val eq = a.indexOf('=')
                    if (eq > 0) {
                        opts[a.substring(2, eq)] = a.substring(eq + 1)
                    } else if (i + 1 < argv.size && !argv[i + 1].startsWith("--") && a.substring(2) !in BOOL_FLAGS) {
                        opts[a.substring(2)] = argv[++i]
                    } else {
                        flags += a.substring(2)
                    }
                } else {
                    positional += a
                }
                i++
            }
        }

        fun dir(): File = File(opts["dir"] ?: System.getenv("WA2_AI_GEN_DIR") ?: DEFAULT_DIR)
        fun root(): File = File(
            opts["wa2-root"] ?: System.getProperty("wa2.root") ?: System.getenv("WA2_ROOT")
                ?: throw UsageError("--wa2-root (or -Dwa2.root / WA2_ROOT) is required"),
        )
    }

    private val BOOL_FLAGS = setOf("allow-missing", "quiet", "verbose", "skip-leak", "partial")

    class UsageError(msg: String) : RuntimeException(msg)

    @JvmStatic
    fun main(argv: Array<String>) {
        val code = try {
            run(argv)
        } catch (e: UsageError) {
            System.err.println("usage error: ${e.message}")
            System.err.println(USAGE)
            2
        }
        System.out.flush()
        exitProcess(code)
    }

    private const val USAGE = """commands:
  extract  --wa2-root R [--dir D] [--batch-size 90]
  reshard  [--dir D] --batch-size N
  validate <batch id | batch_0007 | path/to/out.jsonl> [--dir D] [--partial]
  status   [--dir D]
  compile  --wa2-root R [--dir D] [--allow-missing] [--output F] [--model M] [--pack-version V]
  verify   --wa2-root R --pack F"""

    fun run(argv: Array<String>): Int {
        val args = Args(argv)
        return when (args.positional.firstOrNull()) {
            "extract" -> extract(args)
            "reshard" -> reshard(args)
            "validate" -> validate(args)
            "status" -> status(args)
            "compile" -> compile(args)
            "verify" -> verify(args)
            null, "help", "-h" -> { println(USAGE); 0 }
            else -> throw UsageError("unknown command ${args.positional.first()}")
        }
    }

    // ------------------------------------------------------------------------------------- extract

    private fun extract(args: Args): Int {
        val dir = args.dir()
        val root = args.root()
        require(File(root, "script.pak").isFile) { "script.pak not found under $root" }
        val batchSize = args.opts["batch-size"]?.toInt() ?: DEFAULT_BATCH
        val t0 = System.currentTimeMillis()
        val ex = AiPackCorpus.extract(Wa2Assets(root))
        val units = ex.units()
        dir.mkdirs()
        File(dir, "occurrences.jsonl").bufferedWriter().use { w ->
            val uid = units.associate { it.jp to it.id }
            for (o in ex.occurrences) {
                val obj = buildJsonObject {
                    put("s", o.scene); put("t", o.token); put("o", o.order); put("u", uid.getValue(o.jp))
                    o.speakerJp?.let { put("sp", it) }; o.speakerCn?.let { put("spc", it) }
                    put("jp", o.jp); o.cn?.let { put("cn", it) }
                    if (o.scriptRuby.isNotEmpty()) put("srb", AiPackCorpus.scriptRubyJson(o.scriptRuby))
                    if (o.context.isNotEmpty()) put("ctx", kotlinx.serialization.json.JsonArray(o.context.map { JsonPrimitive(it) }))
                }
                w.write(obj.toString()); w.write("\n")
            }
        }
        File(dir, "unique.jsonl").bufferedWriter().use { w -> units.forEach { w.write(AiPackCorpus.unitJson(it).toString()); w.write("\n") } }
        val nBatches = writeBatches(dir, units, batchSize)

        val lexical = ex.occurrences.filter { it.lexical }
        val byJp = lexical.map { it.jp }.toSet().size
        val byJpCn = lexical.map { it.jp to it.cn.orEmpty() }.toSet().size
        val multiCn = units.count { it.lexical && it.cnVariants > 1 }
        val a = ex.audit
        val stats = buildJsonObject {
            put("scenes", a.scenes)
            put("scenesWithLines", ex.occurrences.map { it.scene }.toSet().size)
            put("tokens", a.tokens)
            put("messageCalls", a.messageCalls)
            put("nonLiteralMessageCalls", a.nonLiteralMessageCalls)
            put("occurrences", ex.occurrences.size)
            put("lexicalOccurrences", lexical.size)
            put("nonLexicalOccurrences", ex.occurrences.size - lexical.size)
            put("units", units.size)
            put("lexicalUnits", units.count { it.lexical })
            put("autoUnits", units.count { !it.lexical })
            put("dedupByJp", byJp)
            put("dedupByJpCn", byJpCn)
            put("lexicalUnitsWithMultipleCn", multiCn)
            put("speakerOnlyTokens", a.speakerOnlyTokens)
            put("choiceTokens", a.choiceTokens)
            put("otherTextTokens", a.otherTextTokens)
            put("unreferencedJapaneseTokens", a.unreferencedJapaneseTokens.size)
            put("batches", nBatches)
            put("batchSize", batchSize)
            put("jpChars", units.filter { it.lexical }.sumOf { it.jp.length })
        }
        File(dir, "corpus_stats.json").writeText(stats.toString())
        File(dir, "other_text_tokens.tsv").writeText(a.otherJapaneseTokens.joinToString("\n"))
        File(dir, "unreferenced_tokens.txt").writeText(a.unreferencedJapaneseTokens.joinToString("\n") { "${it.first}\t${it.second}" })
        println("extract OK in ${System.currentTimeMillis() - t0} ms → $dir")
        println(
            """
            scenes=${a.scenes} (with lines: ${ex.occurrences.map { it.scene }.toSet().size})  tokens=${a.tokens}  SetMessage calls=${a.messageCalls} (non-literal ${a.nonLiteralMessageCalls})
            occurrences=${ex.occurrences.size}  lexical=${lexical.size}  non-lexical(auto)=${ex.occurrences.size - lexical.size}
            dedup by jpPlain=$byJp  by jpPlain+cnPlain=$byJpCn  (lexical units with >1 CK-GAL variant: $multiCn)
            units=${units.size}  lexical units to generate=${units.count { it.lexical }}  auto units=${units.count { !it.lexical }}
            speaker-only tokens=${a.speakerOnlyTokens}  choice tokens=${a.choiceTokens}  other text-arg tokens=${a.otherTextTokens}  unreferenced JP tokens=${a.unreferencedJapaneseTokens.size}  other JP text args=${a.otherJapaneseTokens.size}
            batches=$nBatches × ≤$batchSize → ${File(dir, "batches")}
            """.trimIndent(),
        )
        return 0
    }

    private fun readUnits(dir: File): List<CorpusUnit> {
        val f = File(dir, "unique.jsonl")
        if (!f.isFile) throw UsageError("$f not found: run extract first")
        return AiPackCorpus.readJsonl(f.readText()).first.map { AiPackCorpus.parseUnit(it) }
    }

    private val ID_PREFIX = Regex("^\\{\"i\":\"(u\\d+)\"")

    /** Only the units whose id is in [ids] (cheap line prefix filter, no full JSON parse of the corpus). */
    private fun readUnits(dir: File, ids: Set<String>): Map<String, CorpusUnit> {
        val f = File(dir, "unique.jsonl")
        if (!f.isFile) throw UsageError("$f not found: run extract first")
        val out = HashMap<String, CorpusUnit>()
        f.bufferedReader().useLines { lines ->
            for (line in lines) {
                val id = ID_PREFIX.find(line)?.groupValues?.get(1) ?: continue
                if (id in ids) out[id] = AiPackCorpus.parseUnit(AiJson.parseToJsonElement(line) as JsonObject)
            }
        }
        return out
    }

    private fun outputIds(f: File): Set<String> = if (!f.isFile) emptySet() else
        f.readLines().mapNotNull { Regex("\"i\"\\s*:\\s*\"(u\\d+)\"").find(it)?.groupValues?.get(1) }.toSet()

    private fun writeBatches(dir: File, units: List<CorpusUnit>, batchSize: Int): Int {
        require(batchSize in 1..10_000) { "bad batch size $batchSize" }
        val bdir = File(dir, "batches")
        bdir.mkdirs()
        bdir.listFiles()?.filter { it.name.startsWith("batch_") && it.name.endsWith(".jsonl") }?.forEach { it.delete() }
        val lexical = units.filter { it.lexical }
        val chunks = lexical.chunked(batchSize)
        chunks.forEachIndexed { bi, list ->
            File(bdir, batchName(bi) + ".jsonl").bufferedWriter().use { w -> list.forEach { w.write(AiPackCorpus.unitJson(it).toString()); w.write("\n") } }
        }
        File(bdir, "INDEX.tsv").writeText(chunks.mapIndexed { bi, l -> "${batchName(bi)}\t${l.size}\t${l.first().id}\t${l.last().id}" }.joinToString("\n", postfix = "\n"))
        return chunks.size
    }

    private fun batchName(i: Int) = "batch_%04d".format(i)

    private fun reshard(args: Args): Int {
        val dir = args.dir()
        val size = args.opts["batch-size"]?.toInt() ?: throw UsageError("--batch-size required")
        val n = writeBatches(dir, readUnits(dir), size)
        println("reshard OK: $n batches × ≤$size")
        println("NOTE: existing out/ files keep working — validation and compile are keyed by item id \"i\", not by batch.")
        return 0
    }

    // ------------------------------------------------------------------------------------- validate

    /** Resolves `7` / `0007` / `batch_0007` / a path to (batchInputFile, outputFile). */
    private fun resolveBatch(dir: File, spec: String): Pair<File?, File> {
        val asFile = File(spec)
        if (asFile.isFile && Regex("batch_\\d+").find(asFile.name) == null) return null to asFile
        val name = Regex("batch_\\d+").find(asFile.name)?.value
            ?: spec.toIntOrNull()?.let { batchName(it) }
            ?: throw UsageError("cannot resolve batch \"$spec\"")
        val out = if (asFile.isFile) asFile else File(dir, "out/$name.jsonl")
        val input = File(dir, "batches/$name.jsonl").takeIf { it.isFile }
        return input to out
    }

    data class BatchReport(val name: String, val total: Int, val passed: Int, val failures: Map<String, List<String>>, val missing: List<String>, val extra: List<String>, val malformed: List<Pair<Int, String>>, val entries: Map<String, PackEntry>) {
        val ok: Boolean get() = failures.isEmpty() && missing.isEmpty() && malformed.isEmpty() && extra.isEmpty() && passed == total
    }

    fun validateBatch(units: Map<String, CorpusUnit>, input: File?, output: File, name: String, partial: Boolean = false): BatchReport {
        val expected = input?.takeIf { !partial }?.let { f -> AiPackCorpus.readJsonl(f.readText()).first.mapNotNull { (it["i"] as? JsonPrimitive)?.contentOrNull } }
        if (!output.isFile) {
            return BatchReport(name, expected?.size ?: 0, 0, emptyMap(), expected ?: emptyList(), emptyList(), listOf(0 to "output file ${output.path} not found"), emptyMap())
        }
        val (objs, malformed) = AiPackCorpus.readJsonl(output.readText())
        val byId = LinkedHashMap<String, JsonObject>()
        val failures = LinkedHashMap<String, List<String>>()
        val extra = ArrayList<String>()
        for (o in objs) {
            val id = (o["i"] as? JsonPrimitive)?.contentOrNull ?: (o["id"] as? JsonPrimitive)?.contentOrNull
            if (id == null) { extra += "<item without \"i\">"; continue }
            byId[id] = o
        }
        val targets = expected ?: byId.keys.toList()
        val entries = LinkedHashMap<String, PackEntry>()
        var passed = 0
        for (id in byId.keys) if (id !in units || (expected != null && id !in expected)) extra += id
        val missing = ArrayList<String>()
        for (id in targets) {
            val u = units[id]
            val o = byId[id]
            if (u == null) { failures[id] = listOf("unknown id (not in unique.jsonl)"); continue }
            if (o == null) { missing += id; continue }
            val r = AiPackCorpus.validateItem(u, o)
            if (r.errors.isEmpty() && r.entry != null) {
                passed++
                entries[id] = r.entry
            } else {
                failures[id] = r.errors
            }
        }
        return BatchReport(name, targets.size, passed, failures, missing, extra.distinct(), malformed, entries)
    }

    private fun validate(args: Args): Int {
        val spec = args.positional.getOrNull(1) ?: throw UsageError("validate needs a batch id or file")
        val dir = args.dir()
        val (input, output) = resolveBatch(dir, spec)
        // Fast path: a batch input file carries every unit it needs; otherwise pick ids from unique.jsonl.
        val units = if (input != null) {
            AiPackCorpus.readJsonl(input.readText()).first.map { AiPackCorpus.parseUnit(it) }.associateBy { it.id } +
                readUnits(dir, outputIds(output) - input.readText().let { t -> Regex("\"i\":\"(u\\d+)\"").findAll(t).map { it.groupValues[1] }.toSet() })
        } else {
            readUnits(dir, outputIds(output))
        }
        val name = Regex("batch_\\d+").find(output.name)?.value ?: output.nameWithoutExtension
        val r = validateBatch(units, input, output, name, partial = "partial" in args.flags)
        for ((line, msg) in r.malformed) println("MALFORMED line $line: $msg")
        for ((id, errs) in r.failures) {
            val jp = units[id]?.jp?.let { if (it.length > 40) it.take(40) + "…" else it } ?: "?"
            println("FAIL i=$id  $jp")
            errs.forEach { println("  - $it") }
        }
        if (r.missing.isNotEmpty()) println("MISSING ${r.missing.size} id(s): ${r.missing.joinToString(" ")}")
        if (r.extra.isNotEmpty()) println("UNEXPECTED id(s) not in this batch: ${r.extra.joinToString(" ")}")
        println("${r.name}: ${r.passed}/${r.total} pass, ${r.failures.size} fail, ${r.missing.size} missing${if (r.ok) " ✓ OK" else ""}")
        return if (r.ok) 0 else 1
    }

    private fun status(args: Args): Int {
        val dir = args.dir()
        val units = readUnits(dir).associateBy { it.id }
        val batches = File(dir, "batches").listFiles()?.filter { it.name.matches(Regex("batch_\\d+\\.jsonl")) }?.sortedBy { it.name } ?: emptyList()
        var okCount = 0
        var items = 0
        var passed = 0
        var notStarted = 0
        val pending = ArrayList<String>()
        for (b in batches) {
            val name = b.nameWithoutExtension
            val out = File(dir, "out/$name.jsonl")
            if (!out.isFile) {
                notStarted++
                items += b.readLines().count { it.isNotBlank() }
                continue
            }
            val r = validateBatch(units, b, out, name)
            items += r.total
            passed += r.passed
            if (r.ok) okCount++ else pending += "$name(${r.passed}/${r.total})"
        }
        println("batches OK: $okCount/${batches.size}   items pass: $passed/$items   not started: $notStarted")
        if (pending.isNotEmpty()) println("incomplete (${pending.size}): ${pending.take(60).joinToString(" ")}${if (pending.size > 60) " …" else ""}")
        return if (okCount == batches.size) 0 else 1
    }

    // ------------------------------------------------------------------------------------- compile

    private fun compile(args: Args): Int {
        val dir = args.dir()
        val root = args.root()
        val allowMissing = "allow-missing" in args.flags
        val units = readUnits(dir)
        val byId = units.associateBy { it.id }
        val t0 = System.currentTimeMillis()

        // Validate every output file (keyed by item id; later files win).
        val outs = File(dir, "out").listFiles()?.filter { it.name.endsWith(".jsonl") }?.sortedBy { it.name } ?: emptyList()
        val entries = HashMap<String, PackEntry>()
        var failed = 0
        for (f in outs) {
            val r = validateBatch(byId, null, f, f.nameWithoutExtension)
            entries.putAll(r.entries)
            if (r.failures.isNotEmpty() || r.malformed.isNotEmpty()) {
                failed += r.failures.size + r.malformed.size
                println("WARN ${f.name}: ${r.failures.size} invalid item(s), ${r.malformed.size} malformed line(s) — run validate ${f.nameWithoutExtension}")
            }
        }
        val missing = units.filter { it.lexical && it.id !in entries }
        if (missing.isNotEmpty() && !allowMissing) {
            println("compile FAILED: ${missing.size} lexical unit(s) have no valid output (first: ${missing.take(10).joinToString { it.id }}). Use --allow-missing for a partial test pack.")
            return 1
        }

        val ordered = ArrayList<PackEntry>()
        val entryOf = HashMap<String, Int>()
        for (u in units) {
            val e = if (u.lexical) entries[u.id] else AiPackCorpus.silentEntry(u.jp)
            if (e == null) continue
            entryOf[u.id] = ordered.size
            ordered += e
        }
        val occFile = File(dir, "occurrences.jsonl")
        val tokens = ArrayList<OfflineAiPackWriter.TokenRef>()
        val coveredScenes = HashSet<String>()
        var occCount = 0
        AiPackCorpus.readJsonl(occFile.readText()).first.forEach { o ->
            val s = (o["s"] as JsonPrimitive).content
            val t = (o["t"] as JsonPrimitive).intOrNull ?: return@forEach
            val u = (o["u"] as JsonPrimitive).content
            val idx = entryOf[u] ?: return@forEach
            tokens += OfflineAiPackWriter.TokenRef(s, t, idx)
            coveredScenes += s.uppercase()
            occCount++
        }
        val meta = AiPackMeta(
            packVersion = args.opts["pack-version"] ?: "v1",
            promptVersion = args.opts["prompt-version"] ?: "wa2-pack-v1",
            model = args.opts["model"] ?: "gemini-flash",
            createdAt = System.currentTimeMillis(),
            sceneCount = coveredScenes.size,
            occurrenceCount = occCount,
            entryCount = ordered.size,
            description = "WHITE ALBUM 2 全剧本离线 AI 逐句精讲（Method 3A clean-room，不含剧本原文）",
        )
        val output = File(args.opts["output"] ?: File(dir, OfflineAiPack.FILE_NAME).path)
        OfflineAiPackWriter.write(output, meta, ordered, tokens)
        println("compile wrote ${output.path}: ${ordered.size} entries, $occCount lines, ${coveredScenes.size} scenes, ${"%.2f".format(output.length() / 1048576.0)} MB (invalid items skipped: $failed, missing: ${missing.size}) in ${System.currentTimeMillis() - t0} ms")
        File("$output.sha256").writeText(sha256File(output) + "  " + output.name + "\n")
        return verifyPack(root, output, requireFull = !allowMissing, skipLeak = "skip-leak" in args.flags)
    }

    fun sha256File(f: File): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        f.inputStream().use { ins ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = ins.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun verify(args: Args): Int {
        val pack = File(args.opts["pack"] ?: File(args.dir(), OfflineAiPack.FILE_NAME).path)
        return verifyPack(args.root(), pack, requireFull = "allow-missing" !in args.flags, skipLeak = "skip-leak" in args.flags)
    }

    /** Result of [verifyPackFull]. */
    data class VerifyReport(val lines: Int, val hits: Int, val hashHits: Int, val misses: List<String>, val bad: List<String>, val leaks: List<String>, val millis: Long)

    /**
     * Round-trips `lookup(scene, token, req)` and `lookup(null, null, req)` on every playable line
     * of the real corpus, checks span / surface integrity, and audits every decompressed block for
     * verbatim script / CK-GAL lines (length ≥ [AiPackCorpus.MIN_LEAK_LEN]).
     */
    fun verifyPackFull(root: File, packFile: File, skipLeak: Boolean = false): VerifyReport {
        val t0 = System.currentTimeMillis()
        val ex = AiPackCorpus.extract(Wa2Assets(root))
        val misses = ArrayList<String>()
        val bad = ArrayList<String>()
        var hits = 0
        var hashHits = 0
        OfflineAiPack.open(packFile).use { pack ->
            for (o in ex.occurrences) {
                val req = o.request()
                val e = pack.lookup(o.scene, o.token, req)
                if (e == null) { misses += "${o.scene}/${o.token}"; continue }
                hits++
                if (e.sentence != o.jp || e.ruby.joinToString("") { it.base } != o.jp) bad += "${o.scene}/${o.token}: ruby/sentence mismatch"
                for (c in e.chunks) {
                    if (c.span.start < 0 || c.span.end > o.jp.length || c.span.start > c.span.end) bad += "${o.scene}/${o.token}: chunk span ${c.span}"
                    else if (c.alignment != SpanAlignment.UNALIGNED && c.surface != o.jp.substring(c.span.start, c.span.end)) bad += "${o.scene}/${o.token}: chunk surface"
                }
                for (v in e.vocab) if (v.span.start < 0 || v.span.end > o.jp.length || v.span.start > v.span.end) bad += "${o.scene}/${o.token}: vocab span ${v.span}"
                if (e.officialCnTranslation != o.cn?.takeIf { it.isNotBlank() }) bad += "${o.scene}/${o.token}: official CN not injected"
                if (pack.lookup(o.scene.lowercase(), o.token, req) == null) bad += "${o.scene}/${o.token}: scene name case-sensitive"
                if (pack.lookup(null, null, req) != null) hashHits++ else bad += "${o.scene}/${o.token}: hash index miss"
            }
            val leaks = if (skipLeak) emptyList() else leakAudit(pack, ex.occurrences)
            return VerifyReport(ex.occurrences.size, hits, hashHits, misses, bad, leaks, System.currentTimeMillis() - t0)
        }
    }

    /** Verbatim lines (≥ 12 chars) found inside any decompressed block. */
    fun leakAudit(pack: OfflineAiPack, occurrences: List<CorpusOccurrence>): List<String> {
        val n = AiPackCorpus.MIN_LEAK_LEN
        val lines = HashSet<String>()
        for (o in occurrences) {
            if (o.jp.length >= n) lines += o.jp
            o.cn?.let { if (it.length >= n) lines += it }
        }
        val byPrefix = HashMap<String, MutableList<String>>()
        for (l in lines) byPrefix.getOrPut(l.substring(0, n)) { ArrayList(1) } += l
        val leaks = ArrayList<String>()
        for (raw in pack.rawBlocks()) {
            val text = String(raw, Charsets.UTF_8)
            var i = 0
            while (i + n <= text.length) {
                val cands = byPrefix[text.substring(i, i + n)]
                if (cands != null) for (c in cands) if (text.startsWith(c, i)) leaks += c
                i++
            }
        }
        val metaText = AiJson.encodeToString(AiPackMeta.serializer(), pack.meta)
        for (l in lines) if (metaText.contains(l)) leaks += l
        return leaks.distinct()
    }

    private fun verifyPack(root: File, pack: File, requireFull: Boolean, skipLeak: Boolean): Int {
        val r = verifyPackFull(root, pack, skipLeak)
        println("verify: lines=${r.lines} lookup(scene,token) hits=${r.hits} hash-only hits=${r.hashHits} misses=${r.misses.size} integrity-errors=${r.bad.size} leaks=${r.leaks.size} (${r.millis} ms)")
        r.bad.take(20).forEach { println("  BAD $it") }
        r.leaks.take(20).forEach { println("  LEAK ${it.take(60)}") }
        if (r.misses.isNotEmpty()) println("  first misses: ${r.misses.take(10).joinToString(" ")}")
        val ok = r.bad.isEmpty() && r.leaks.isEmpty() && (!requireFull || r.misses.isEmpty())
        println(if (ok) "verify OK ✓ (clean-room: 0 verbatim lines ≥ ${AiPackCorpus.MIN_LEAK_LEN} chars)" else "verify FAILED")
        return if (ok) 0 else 1
    }
}

fun main(argv: Array<String>) = AiPackTool.main(argv)
