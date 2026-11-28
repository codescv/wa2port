package com.wa2port.core.text

import com.wa2port.core.TestAssets
import com.wa2port.core.ai.AiPackMeta
import com.wa2port.core.ai.JpSentence
import com.wa2port.core.ai.OfflineAiPack
import com.wa2port.core.ai.OfflineAiPackWriter
import com.wa2port.core.ai.PackEntry
import com.wa2port.core.ai.PackRuby
import com.wa2port.core.archive.Cp932
import com.wa2port.core.script.ScenarioStore
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.Deflater
import java.util.zip.Inflater

class FuriganaPackTest {
    private val decoder = BilingualTextDecoder()

    private fun parseJp(text: String): RichText = ScenarioTextParser.parse(text.toByteArray(Cp932.charset))

    @Test
    fun wa2furi1BinaryRoundTripAndLookup() {
        val s1 = "今日は本当に寒いね。"
        val s2 = "明日の放課後、音楽室で待ってるよ。"
        val s3 = "ひらがなだけ"
        val entries = listOf(
            FuriganaPack.Entry(
                sentenceHash = OfflineAiPack.hash64(s1),
                sentenceUtf16Len = s1.length,
                spans = listOf(PackRuby(0, 2, "きょう"), PackRuby(3, 5, "ほんとう"), PackRuby(6, 7, "さむ")),
            ),
            FuriganaPack.Entry(
                sentenceHash = OfflineAiPack.hash64(s2),
                sentenceUtf16Len = s2.length,
                spans = listOf(PackRuby(0, 2, "あした"), PackRuby(3, 6, "ほうかご"), PackRuby(7, 10, "おんがくしつ"), PackRuby(11, 12, "ま")),
            ),
            FuriganaPack.Entry(
                sentenceHash = OfflineAiPack.hash64(s3),
                sentenceUtf16Len = s3.length,
                spans = emptyList(),
            ),
        )
        val encoded = FuriganaPack.encode(entries)
        assertEquals("WA2FURI1", String(encoded, 0, 8, Charsets.US_ASCII))
        val decoded = FuriganaPack.decode(encoded)
        assertEquals(3, decoded.entryCount)
        assertEquals(7, decoded.readingCount)
        assertFalse(decoded.isEmpty)

        assertEquals(
            listOf(PackRuby(0, 2, "きょう"), PackRuby(3, 5, "ほんとう"), PackRuby(6, 7, "さむ")),
            decoded.lookup(s1),
        )
        assertEquals(
            listOf(PackRuby(0, 2, "あした"), PackRuby(3, 6, "ほうかご"), PackRuby(7, 10, "おんがくしつ"), PackRuby(11, 12, "ま")),
            decoded.lookup(s2),
        )
        assertTrue(decoded.lookup(s3).isEmpty())
        assertTrue(decoded.lookup("未登録の文章").isEmpty())
        assertTrue(decoded.lookup("").isEmpty())
    }

    @Test
    fun rejectsMalformedBinaryInputs() {
        assertThrows(IllegalArgumentException::class.java) { FuriganaPack.decode(ByteArray(10)) }
        val badMagic = FuriganaPack.encode(emptyList()).also { it[0] = 'X'.code.toByte() }
        assertThrows(IllegalArgumentException::class.java) { FuriganaPack.decode(badMagic) }

        val badVersion = FuriganaPack.encode(emptyList()).also {
            ByteBuffer.wrap(it, 8, 4).order(ByteOrder.BIG_ENDIAN).putInt(2)
        }
        assertThrows(IllegalArgumentException::class.java) { FuriganaPack.decode(badVersion) }

        val truncated = FuriganaPack.encode(emptyList()).let { it.copyOf(it.size - 2) }
        assertThrows(IllegalArgumentException::class.java) { FuriganaPack.decode(truncated) }

        assertSame(FuriganaPack.EMPTY, FuriganaPack.loadFromResource("/wa2/does_not_exist.bin"))
    }

    private fun wrapRawPayload(raw: ByteArray, declaredRawLen: Int = raw.size): ByteArray {
        val d = Deflater()
        val compOut = ByteArrayOutputStream()
        try {
            d.setInput(raw)
            d.finish()
            val buf = ByteArray(1024)
            while (!d.finished()) compOut.write(buf, 0, d.deflate(buf))
        } finally {
            d.end()
        }
        val comp = compOut.toByteArray()
        return ByteBuffer.allocate(20 + comp.size).order(ByteOrder.BIG_ENDIAN).apply {
            put("WA2FURI1".toByteArray(Charsets.US_ASCII))
            putInt(1)
            putInt(declaredRawLen)
            putInt(comp.size)
            put(comp)
        }.array()
    }

    private fun writeVarint(out: ByteArrayOutputStream, v: Int) {
        var x = v
        while (x and 0x7F.inv() != 0) {
            out.write((x and 0x7F) or 0x80)
            x = x ushr 7
        }
        out.write(x)
    }

    @Test
    fun rejectsBinaryParserOverflowsOomBombsVarintTruncationAndTrailingZlib() {
        // 1) readingCount OOM bomb (500_000_000 in 6-byte raw payload)
        val oomReadings = ByteArrayOutputStream().apply { writeVarint(this, 500_000_000); write(0) }.toByteArray()
        assertThrows(IllegalArgumentException::class.java) { FuriganaPack.decode(wrapRawPayload(oomReadings)) }

        // 2) entryCount OOM bomb (Int.MAX_VALUE in tiny payload)
        val oomEntries = ByteArrayOutputStream().apply { write(0); writeVarint(this, Int.MAX_VALUE) }.toByteArray()
        assertThrows(IllegalArgumentException::class.java) { FuriganaPack.decode(wrapRawPayload(oomEntries)) }

        // 3) utf8 length Int.MAX_VALUE overflow (must throw IllegalStateException, not StringIndexOutOfBounds)
        val utf8Overflow = ByteArrayOutputStream().apply {
            write(1) // readingCount = 1
            writeVarint(this, Int.MAX_VALUE) // byteLen = Int.MAX_VALUE
        }.toByteArray()
        assertThrows(IllegalStateException::class.java) { FuriganaPack.decode(wrapRawPayload(utf8Overflow)) }

        // 4) 5th-byte varint truncation (0x80 0x80 0x80 0x80 0x10 = 2^32) and sign-bit (0x08)
        for (fifth in intArrayOf(0x08, 0x10, 0x7F)) {
            val badVarint = byteArrayOf(0x80.toByte(), 0x80.toByte(), 0x80.toByte(), 0x80.toByte(), fifth.toByte())
            assertThrows(IllegalStateException::class.java) { FuriganaPack.decode(wrapRawPayload(badVarint)) }
        }

        // 5) Span Int32 wraparound: prevEnd=5, startDelta=Int.MAX_VALUE, spanLen=1
        val spanOverflow1 = ByteArrayOutputStream().apply {
            write(1); write(1); write('あ'.code and 0x7F) // dummy 1-byte reading (ASCII)
            write(1) // entryCount = 1
            repeat(8) { write(0) } // hash = 0
            writeVarint(this, 100) // slen = 100
            write(2) // spanCount = 2
            write(0); write(5); write(0) // span 0: [0, 5)
            writeVarint(this, Int.MAX_VALUE); write(1); write(0) // span 1: 5 + Int.MAX_VALUE wraps negative!
        }.toByteArray()
        assertThrows(IllegalArgumentException::class.java) { FuriganaPack.decode(wrapRawPayload(spanOverflow1)) }

        // 6) Span Int32 wraparound: start=1, spanLen=Int.MAX_VALUE
        val spanOverflow2 = ByteArrayOutputStream().apply {
            write(1); write(1); write('a'.code)
            write(1)
            repeat(8) { write(0) }
            writeVarint(this, 100)
            write(1)
            write(1); writeVarint(this, Int.MAX_VALUE); write(0)
        }.toByteArray()
        assertThrows(IllegalArgumentException::class.java) { FuriganaPack.decode(wrapRawPayload(spanOverflow2)) }

        // 7) Zlib stream with extra uncompressed bytes beyond declared rawLen
        val extraZlib = wrapRawPayload(byteArrayOf(0, 0, 1, 2, 3), declaredRawLen = 2)
        assertThrows(IllegalStateException::class.java) { FuriganaPack.decode(extraZlib) }
    }

    @Test
    fun bundledWa2FuriganaBinLoadsValidatesAndIsCleanRoom() {
        val pack = FuriganaPack.shared
        assertFalse(pack.isEmpty, "Bundled /wa2/wa2_furigana.bin must load")
        assertTrue(pack.entryCount > 50_000, "Expected >50k entries, got ${pack.entryCount}")
        assertTrue(pack.readingCount > 10_000, "Expected >10k readings, got ${pack.readingCount}")

        // Clean-room audit: decompress raw payload and verify zero kanji (U+4E00..U+9FFF) exist in reading table.
        val rawBytes = FuriganaPack::class.java.getResourceAsStream(FuriganaPack.RESOURCE_PATH)!!.use { it.readBytes() }
        val bb = ByteBuffer.wrap(rawBytes, 8, 12).order(ByteOrder.BIG_ENDIAN)
        assertEquals(1, bb.int)
        val rawLen = bb.int
        val compLen = bb.int
        val inf = Inflater()
        val raw = ByteArray(rawLen)
        try {
            inf.setInput(rawBytes, 20, compLen)
            var got = 0
            while (got < rawLen) {
                val k = inf.inflate(raw, got, rawLen - got)
                if (k == 0) break
                got += k
            }
            assertEquals(rawLen, got)
        } finally {
            inf.end()
        }
        var p = 0
        fun varint(): Int {
            var shift = 0
            var r = 0
            while (true) {
                val x = raw[p++].toInt() and 0xFF
                r = r or ((x and 0x7F) shl shift)
                if (x and 0x80 == 0) return r
                shift += 7
            }
        }
        val rc = varint()
        assertEquals(pack.readingCount, rc)
        for (i in 0 until rc) {
            val n = varint()
            val s = String(raw, p, n, Charsets.UTF_8)
            p += n
            for (ch in s) {
                assertFalse(ch in '\u4E00'..'\u9FFF', "Kanji leak in clean-room reading #$i: $s")
            }
            assertNotNull(SjisKanaEncoder.encode(s), "Bundled reading #$i '$s' must encode cleanly to WA2 Shift-JIS")
        }

        // If real WA2 assets are present on this machine, verify live lookup coverage on scene 1001.
        if (TestAssets.available) {
            val store = ScenarioStore(TestAssets.assets)
            val sc = store.scene("1001")
            assertNotNull(sc, "Expected scene 1001 in WA2 assets")
            var hits = 0
            for (i in 0 until sc!!.jp.size) {
                val tok = sc.jp.rich(i)
                val sentence = JpSentence.fromRich(tok, decoder).text
                if (pack.lookup(sentence).isNotEmpty()) {
                    hits++
                    val overlaid = FuriganaOverlay.apply(tok, pack, decoder, FuriganaMode.ALL)
                    assertTrue(tok.baseCodes().contentEquals(overlaid.baseCodes()), "baseCodes must never change")
                }
            }
            assertTrue(hits >= 40, "Expected substantial furigana hits in scene 1001, got $hits")
        }
    }

    @Test
    fun offlineAiPackFallbackSuppliesSpansWhenPrimaryMisses(@TempDir tempDir: File) {
        val sentence = "特別な台詞のテスト"
        val aiFile = File(tempDir, "fallback.wa2ai")
        val entry = PackEntry(
            sentenceHash = OfflineAiPack.hash64(sentence),
            sentenceLength = sentence.length,
            translation = "test",
            ruby = listOf(PackRuby(0, 2, "とくべつ"), PackRuby(3, 5, "せりふ")),
        )
        OfflineAiPackWriter.write(
            aiFile,
            AiPackMeta("v1", "p1", "test-model", 0L, 1, 1, 1),
            listOf(entry),
            listOf(OfflineAiPackWriter.TokenRef("1001", 1, 0)),
        )
        OfflineAiPack.open(aiFile).use { aiPack ->
            assertEquals(
                listOf(PackRuby(0, 2, "とくべつ"), PackRuby(3, 5, "せりふ")),
                FuriganaPack.EMPTY.lookup(sentence, aiPack, "1001", 1),
            )
            val rich = parseJp(sentence)
            val overlaid = FuriganaOverlay.apply(rich, FuriganaPack.EMPTY, decoder, FuriganaMode.ALL, aiPack, "1001", 1)
            val autoRubies = overlaid.items.filterIsInstance<TextItem.Ruby>().filter { it.auto }
            assertEquals(2, autoRubies.size)
            assertTrue(rich.baseCodes().contentEquals(overlaid.baseCodes()))
        }
    }

    @Test
    fun sjisKanaEncoderHandlesAllHiraganaKatakanaMarksAndRejectsUnknown() {
        // Every hiragana ぁ..ん
        for (ch in '\u3041'..'\u3093') {
            val code = SjisKanaEncoder.encodeChar(ch)
            assertTrue(code >= 0 && Wa2FontMap.cellOf(code) >= 0, "Hiragana $ch failed")
            assertEquals(ch.toString(), decoder.jpChar(code))
        }
        // Every katakana ァ..ヶ (including the 0x837E ミ -> 0x8380 ム jump over trail 0x7F!)
        for (ch in '\u30A1'..'\u30F6') {
            val code = SjisKanaEncoder.encodeChar(ch)
            assertTrue(code >= 0 && Wa2FontMap.cellOf(code) >= 0, "Katakana $ch failed")
            assertEquals(ch.toString(), decoder.jpChar(code))
        }
        assertEquals(0x837E, SjisKanaEncoder.encodeChar('ミ'))
        assertEquals(0x8380, SjisKanaEncoder.encodeChar('ム'))
        assertEquals(0x815B, SjisKanaEncoder.encodeChar('ー'))
        assertEquals(0x8145, SjisKanaEncoder.encodeChar('・'))
        assertEquals(0x8152, SjisKanaEncoder.encodeChar('ヽ'))
        assertEquals(0x8153, SjisKanaEncoder.encodeChar('ヾ'))
        assertEquals(0x8154, SjisKanaEncoder.encodeChar('ゝ'))
        assertEquals(0x8155, SjisKanaEncoder.encodeChar('ゞ'))

        val encoded = SjisKanaEncoder.encode("ぱーとなー")
        assertNotNull(encoded)
        assertEquals("ぱーとなー", encoded!!.joinToString("") { decoder.jpChar(it) })

        assertNull(SjisKanaEncoder.encode(""))
        assertNull(SjisKanaEncoder.encode("   "))
        assertNull(SjisKanaEncoder.encode("😀"))
    }

    @Test
    fun overlayPreservesBaseCodesScriptRubyControlItemsSizesAndModes() {
        // Contains: <F14> whisper run, script <R本気|マジ>, NewLine, Wait, PageBreak, and plain kanji.
        val raw = parseJp("<F14今日>は<R本気|マジ>だ\\n<W10>明日\\k雪")
        val jp = JpSentence.fromRich(raw, decoder)
        assertEquals("今日は本気だ明日雪", jp.text)

        val pack = FuriganaPack.decode(
            FuriganaPack.encode(
                listOf(
                    FuriganaPack.Entry(
                        sentenceHash = OfflineAiPack.hash64(jp.text),
                        sentenceUtf16Len = jp.text.length,
                        spans = listOf(
                            PackRuby(0, 2, "きょう"), // inside <F14> Run
                            PackRuby(3, 5, "ほんき"), // overlaps script <R本気|マジ> -> MUST BE IGNORED
                            PackRuby(6, 8, "あした"), // after \n<W10>
                            PackRuby(8, 9, "ゆき"), // after \k
                        ),
                    ),
                ),
            ),
        )

        // OFF mode returns exact untouched original RichText.
        assertSame(raw, FuriganaOverlay.apply(raw, pack, decoder, FuriganaMode.OFF))

        // ALL and TAP produce identical RichText structure (TAP only veils auto rubies at draw time).
        val all = FuriganaOverlay.apply(raw, pack, decoder, FuriganaMode.ALL)
        val tap = FuriganaOverlay.apply(raw, pack, decoder, FuriganaMode.TAP)
        assertEquals(all, tap)

        // baseCodes() and plain sentence are 100% identical.
        assertTrue(raw.baseCodes().contentEquals(all.baseCodes()))
        val jpAfter = JpSentence.fromRich(all, decoder)
        assertEquals(jp.text, jpAfter.text)
        // JpSentence.scriptRuby still contains ONLY the authoritative script ruby (not auto rubies).
        assertEquals(jp.scriptRuby, jpAfter.scriptRuby)
        assertEquals(1, jpAfter.scriptRuby.size)
        assertEquals("マジ", jpAfter.scriptRuby[0].reading)

        // Control items preserved in exact order.
        assertEquals(
            raw.items.filter { it is TextItem.NewLine || it is TextItem.PageBreak || it is TextItem.Wait },
            all.items.filter { it is TextItem.NewLine || it is TextItem.PageBreak || it is TextItem.Wait },
        )

        // Verify <F14> size preserved on auto ruby for 今日, script ruby untouched (auto = false),
        // and auto rubies created for 明日 & 雪.
        val rubies = all.items.filterIsInstance<TextItem.Ruby>()
        assertEquals(4, rubies.size)
        assertEquals("今日" to "きょう", rubies[0].base.joinToString("") { decoder.jpChar(it) } to rubies[0].reading.joinToString("") { decoder.jpChar(it) })
        assertEquals(14, rubies[0].size)
        assertTrue(rubies[0].auto)

        assertEquals("本気" to "マジ", rubies[1].base.joinToString("") { decoder.jpChar(it) } to rubies[1].reading.joinToString("") { decoder.jpChar(it) })
        assertFalse(rubies[1].auto)

        assertEquals("明日" to "あした", rubies[2].base.joinToString("") { decoder.jpChar(it) } to rubies[2].reading.joinToString("") { decoder.jpChar(it) })
        assertTrue(rubies[2].auto)

        assertEquals("雪" to "ゆき", rubies[3].base.joinToString("") { decoder.jpChar(it) } to rubies[3].reading.joinToString("") { decoder.jpChar(it) })
        assertTrue(rubies[3].auto)
    }

    @Test
    fun overlaySkipsSpanCrossingControlItemWithoutCorruptingControls() {
        val raw = parseJp("今\\n日と雪")
        val jp = JpSentence.fromRich(raw, decoder)
        assertEquals("今日と雪", jp.text)
        // Span (0, 2) crosses NewLine between 今 and 日 -> must be skipped safely while (3, 4) still applies.
        val overlaid = FuriganaOverlay.applySpans(
            raw,
            listOf(PackRuby(0, 2, "きょう"), PackRuby(3, 4, "ゆき")),
            decoder,
        )
        assertTrue(raw.baseCodes().contentEquals(overlaid.baseCodes()))
        assertEquals(1, overlaid.items.count { it is TextItem.NewLine })
        val rubies = overlaid.items.filterIsInstance<TextItem.Ruby>()
        assertEquals(1, rubies.size)
        assertEquals("雪", rubies[0].base.joinToString("") { decoder.jpChar(it) })
        assertTrue(rubies[0].auto)
    }
}
