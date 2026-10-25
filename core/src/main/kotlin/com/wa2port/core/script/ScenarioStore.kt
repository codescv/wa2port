package com.wa2port.core.script

import com.wa2port.core.archive.Cp932
import com.wa2port.core.archive.KcapArchive
import com.wa2port.core.archive.Wa2Assets
import com.wa2port.core.text.RichText
import com.wa2port.core.text.ScenarioTextParser
import java.util.concurrent.ConcurrentHashMap

/** Comma-token stream of one `NNNN.txt` (raw cp932/pseudo-SJIS bytes per token). */
class TokenStream(val tokens: List<ByteArray>) {
    val size: Int get() = tokens.size

    operator fun get(i: Int): ByteArray = tokens.getOrElse(i) { ByteArray(0) }

    fun rich(i: Int): RichText = if (i in tokens.indices) ScenarioTextParser.parse(tokens[i]) else RichText.EMPTY

    companion object {
        /** Naive split on 0x2C: commas never occur as SJIS trail bytes (trail ≥ 0x40). */
        fun split(data: ByteArray): TokenStream {
            val out = ArrayList<ByteArray>()
            var s = 0
            for (i in data.indices) {
                if (data[i].toInt() == 0x2C) {
                    out += data.copyOfRange(s, i); s = i + 1
                }
            }
            out += data.copyOfRange(s, data.size)
            return TokenStream(out)
        }
    }
}

/** One scene: shared `.bnr` program plus the paired JP and CN token streams (1:1 by ordinal). */
class Scene(val name: String, val program: LscrProgram, val jp: TokenStream, val cn: TokenStream?)

/** `Global.vrb` entry: `type,name,init`. */
data class GlobalVar(val type: Int, val name: String, val init: Int)

/**
 * Loads scenes from `script.pak` (JP, + `IC/script.pak` fallback) and `ck-gal.pak` (CN).
 * `.bnr`/`.fnc`/`.vrb`/`.dat` are byte-identical in both, so programs come from the JP archive
 * (falling back to CN when a JP archive is absent).
 */
class ScenarioStore(private val assets: Wa2Assets) {
    private val jpArchives: List<KcapArchive> = listOfNotNull(assets.kcap("script"), assets.kcap("script", ic = true))
    private val cnArchives: List<KcapArchive> = listOfNotNull(assets.kcap("ck-gal"), assets.kcap("ck-gal", ic = true))
    private val cache = ConcurrentHashMap<String, Scene>()

    val hasJp: Boolean get() = jpArchives.isNotEmpty()
    val hasCn: Boolean get() = cnArchives.isNotEmpty()

    private fun readFirst(list: List<KcapArchive>, name: String): ByteArray? {
        for (a in list) a.find(name)?.let { return a.read(it) }
        return null
    }

    private fun readAny(name: String): ByteArray? = readFirst(jpArchives, name) ?: readFirst(cnArchives, name)

    /** All scene names (entries with both `.txt` and `.bnr`), sorted. */
    val sceneNames: List<String> by lazy {
        val names = LinkedHashSet<String>()
        for (a in jpArchives + cnArchives) {
            val files = a.files.map { it.name }
            val bnr = files.filter { it.lowercase().endsWith(".bnr") }.map { it.dropLast(4) }.toSet()
            files.filter { it.lowercase().endsWith(".txt") }.map { it.dropLast(4) }.filter { it in bnr }.forEach(names::add)
        }
        names.sortedWith(compareBy({ it.firstOrNull()?.isDigit() != true }, { it }))
    }

    fun scene(name: String): Scene? {
        cache[name.lowercase()]?.let { return it }
        val bnr = readAny("$name.bnr") ?: return null
        val prog = LscrProgram.parse(name, bnr)
        val jpTxt = readFirst(jpArchives, "$name.txt") ?: readFirst(cnArchives, "$name.txt") ?: ByteArray(0)
        val cnTxt = readFirst(cnArchives, "$name.txt")
        val sc = Scene(name, prog, TokenStream.split(jpTxt), cnTxt?.let { TokenStream.split(it) })
        cache[name.lowercase()] = sc
        return sc
    }

    val functions: Map<Int, FuncSpec> by lazy {
        val out = HashMap<Int, FuncSpec>()
        for (f in listOf("LFScriptFunc.fnc", "LFScriptFuncEx.fnc")) {
            readAny(f)?.let { data -> FunctionTable.parse(data).forEach { out[it.id] = it } }
        }
        out
    }

    val globalVars: List<GlobalVar> by lazy {
        val data = readAny("Global.vrb") ?: return@lazy emptyList()
        Cp932.decode(data).lines().mapNotNull { line ->
            val f = line.trim().split(',')
            if (f.size < 3) null else GlobalVar(f[0].toIntOrNull() ?: 0, f[1], f[2].trim().toIntOrNull() ?: 0)
        }
    }

    /** `GFLAG.dat`: u32 count, then count × {u32 value, char name[256]}. */
    val gflagNames: List<String> by lazy {
        val data = readAny("GFLAG.dat") ?: return@lazy emptyList()
        val bb = java.nio.ByteBuffer.wrap(data).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        val n = bb.getInt(0)
        (0 until n).mapNotNull { k ->
            val o = 4 + k * 260 + 4
            if (o + 256 > data.size) null else {
                val end = (o until o + 256).firstOrNull { data[it].toInt() == 0 } ?: (o + 256)
                Cp932.decode(data, o, end - o)
            }
        }
    }
}
