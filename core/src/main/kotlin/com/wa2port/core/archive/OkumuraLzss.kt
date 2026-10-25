package com.wa2port.core.archive

/**
 * Classic Okumura LZSS as used by Leaf's KCAP packer.
 *
 * 4 KiB ring buffer pre-filled with zeros, write position starts at 0xFEE, each flag byte covers
 * eight items (LSB first), flag bit 1 = literal byte, flag bit 0 = 2-byte back-reference
 * `lo | (hi & 0xF0) << 4` (12-bit ring offset) with length `(hi & 0x0F) + 3`.
 */
object OkumuraLzss {
    private const val RING_SIZE = 0x1000
    private const val RING_MASK = RING_SIZE - 1
    private const val RING_START = 0xFEE

    /**
     * Decompresses [srcLen] bytes of [src] starting at [srcOff] until exactly [usize] bytes have been
     * produced (or the input runs out, in which case the output is zero-padded to [usize]).
     *
     * The decoder deliberately ignores any "compressed size" header field: CK-GAL's repacker wrote
     * `csize = entry.size - 8` for 181 entries in `ck-gal.pak`, so callers pass the whole entry
     * payload and stop on [usize].
     */
    fun decompress(src: ByteArray, srcOff: Int, srcLen: Int, usize: Int): ByteArray {
        val out = ByteArray(usize)
        val ring = ByteArray(RING_SIZE)
        var r = RING_START
        var o = 0
        var i = srcOff
        val end = (srcOff + srcLen).coerceAtMost(src.size)
        while (i < end && o < usize) {
            var flags = src[i++].toInt() and 0xFF
            var bit = 0
            while (bit < 8 && o < usize) {
                if (flags and 1 != 0) {
                    if (i >= end) break
                    val c = src[i++]
                    out[o++] = c
                    ring[r] = c
                    r = (r + 1) and RING_MASK
                } else {
                    if (i + 1 >= end) {
                        i = end
                        break
                    }
                    val lo = src[i].toInt() and 0xFF
                    val hi = src[i + 1].toInt() and 0xFF
                    i += 2
                    var pos = lo or ((hi and 0xF0) shl 4)
                    val len = (hi and 0x0F) + 3
                    var k = 0
                    while (k < len && o < usize) {
                        val c = ring[pos]
                        out[o++] = c
                        ring[r] = c
                        r = (r + 1) and RING_MASK
                        pos = (pos + 1) and RING_MASK
                        k++
                    }
                }
                flags = flags ushr 1
                bit++
            }
        }
        return out
    }
}
