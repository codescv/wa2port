package com.wa2port.core.script

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Static info for a `for` loop: `(0,5,end,cond,incr,body)`; all byte offsets. */
data class ForInfo(val end: Int, val cond: Int, val incr: Int, val body: Int)

/**
 * A compiled `NNNN.bnr` LSCR program.
 *
 * File layout: `"LSCR" u32 headerSize u32 labelCount {u32 labelId, u32 byteOffset}[labelCount]`,
 * then a stream of little-endian i32 words. Statements end with `(6,30)`. Word tags:
 * `(0,sub,…)` control, `(1,X)` global var, `(2,X)` local array, `(3,X)` text token,
 * `(4,X)` function call, `(5,3,int)` / `(5,4,float)` push, `(6,X)` operator.
 * All jump targets / labels are absolute byte offsets into the file. Label 0 = script entry.
 */
class LscrProgram(val name: String, val words: IntArray, val labels: Map<Int, Int>) {
    /** `(0,4)` else-marker address → end address of its `if` (pre-scanned). */
    val elseEnd: Map<Int, Int>

    /** `for` loops keyed by the address of their condition statement. */
    val forByCond: Map<Int, ForInfo>

    /** Byte offset of every `(4,X)` call with X in [ops] (for corpus statistics). */
    val callCensus: Map<Int, Int>

    val entry: Int get() = labels[0] ?: (headerBytes)
    val headerBytes: Int

    init {
        headerBytes = words.getOrElse(1) { 8 }
        val ee = HashMap<Int, Int>()
        val fc = HashMap<Int, ForInfo>()
        val census = HashMap<Int, Int>()
        var i = headerBytes / 4
        while (i < words.size) {
            val t = words[i]
            when (t) {
                5 -> i += 3
                1, 2, 3, 6 -> i += 2
                4 -> { census.merge(words.getOrElse(i + 1) { -1 }, 1, Int::plus); i += 2 }
                0 -> {
                    val sub = words.getOrElse(i + 1) { -1 }
                    when (sub) {
                        2 -> {
                            val a = words.getOrElse(i + 2) { 0 }
                            val b = words.getOrElse(i + 3) { 0 }
                            if (a != 0) ee[a] = b
                        }
                        5 -> {
                            val info = ForInfo(words[i + 2], words[i + 3], words[i + 4], words[i + 5])
                            fc[info.cond] = info
                        }
                    }
                    i += controlLength(words, i)
                }
                else -> i += 1
            }
        }
        // Propagate the chain end through `else if` headers: (0,2,A,B) with (0,3,T) at A → T ends at B too.
        for ((a, b) in ee.entries.toList()) {
            var at = a
            var guard = 0
            while (guard++ < 64) {
                val wi = at / 4
                if (words.getOrNull(wi) != 0 || words.getOrNull(wi + 1) != 3) break
                val t = words.getOrElse(wi + 2) { 0 }
                if (t == 0 || ee.containsKey(t)) break
                ee[t] = b
                at = t
            }
        }
        elseEnd = ee
        forByCond = fc
        callCensus = census
    }

    /** Number of words occupied by the control instruction at word index [i]. */
    fun controlLength(i: Int): Int = controlLength(words, i)

    fun wordAt(byteOffset: Int): Int = words.getOrElse(byteOffset / 4) { 0 }

    companion object {
        fun parse(name: String, data: ByteArray): LscrProgram {
            require(data.size >= 12 && data[0] == 'L'.code.toByte() && data[1] == 'S'.code.toByte() &&
                data[2] == 'C'.code.toByte() && data[3] == 'R'.code.toByte()) { "$name: not LSCR" }
            val n = data.size / 4
            val bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
            val words = IntArray(n) { bb.getInt(it * 4) }
            val count = words[2]
            val labels = HashMap<Int, Int>()
            for (k in 0 until count) {
                val idx = 3 + k * 2
                if (idx + 1 < n) labels[words[idx]] = words[idx + 1]
            }
            return LscrProgram(name, words, labels)
        }

        fun controlLength(words: IntArray, i: Int): Int = when (words.getOrElse(i + 1) { -1 }) {
            2 -> 4
            3, 6, 12 -> 3
            5 -> 6
            7 -> 4 + 2 * words.getOrElse(i + 2) { 0 }.coerceIn(0, 4096)
            else -> 2
        }
    }
}
