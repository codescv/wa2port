package com.wa2port.core.script

import com.wa2port.core.archive.Cp932
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Signature of one script-callable engine function (from `LFScriptFunc*.fnc`). */
data class FuncSpec(
    val id: Int,
    val name: String,
    val argc: Int,
    val retType: Int,
    /** Argument type codes: 3 = int, 4 = float, 5 = string, 6 = any, 7 = int/text. */
    val types: IntArray,
    /** Default value per argument, or null when the argument is mandatory. */
    val defaults: List<Value?>,
)

/**
 * Parser for `LFScriptFunc.fnc` (opcodes 0..18) and `LFScriptFuncEx.fnc` (opcodes 128..238).
 *
 * Each file starts with the u32 id of its first function; records are laid out back to back as
 * `char name[32]` (junk after the NUL), `u8 argc, u8 hasDefaults, u16 pad, u32 retType,
 * u8 types[16], {u32|f32} defaults[argc]` (defaults only when `hasDefaults`; 0x80000000 = none),
 * followed by a little padding of variable length — so records are located by scanning.
 */
object FunctionTable {
    private const val NO_DEFAULT = 0x80000000.toInt()

    fun parse(data: ByteArray): List<FuncSpec> {
        val bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        val firstId = bb.getInt(0)
        val out = ArrayList<FuncSpec>()
        var o = 4
        var id = firstId
        while (o + 56 <= data.size) {
            val nameEnd = (o until o + 32).firstOrNull { data[it].toInt() == 0 } ?: (o + 32)
            val name = Cp932.decode(data, o, nameEnd - o)
            val argc = data[o + 32].toInt() and 0xFF
            val hasDef = data[o + 33].toInt() != 0
            val ret = bb.getInt(o + 36)
            val types = IntArray(argc) { data[o + 40 + it].toInt() and 0xFF }
            val defaults = (0 until argc).map { k ->
                if (!hasDef || o + 56 + k * 4 + 4 > data.size) null else {
                    val raw = bb.getInt(o + 56 + k * 4)
                    when {
                        raw == NO_DEFAULT -> null
                        types[k] == 4 -> Value.F(java.lang.Float.intBitsToFloat(raw))
                        else -> Value.I(raw)
                    }
                }
            }
            out += FuncSpec(id, name, argc, ret, types, defaults)
            id++
            o = findNext(data, bb, o + 100) ?: break
        }
        return out
    }

    /** Finds the next record's name start: identifier after NUL/',' with a sane argc and return type. */
    private fun findNext(data: ByteArray, bb: ByteBuffer, from: Int): Int? {
        var p = from
        while (p + 40 <= data.size && p < from + 200) {
            val c = data[p].toInt() and 0xFF
            val prev = data[p - 1].toInt() and 0xFF
            val isIdent = c == '_'.code || c in 'A'.code..'Z'.code || c in 'a'.code..'z'.code
            if (isIdent && (prev == 0 || prev == ','.code)) {
                val argc = data[p + 32].toInt() and 0xFF
                val ret = bb.getInt(p + 36)
                if (argc <= 16 && (ret == 3 || ret == 4)) return p
            }
            p++
        }
        return null
    }
}
