package com.wa2port.core.gallery

import com.wa2port.core.archive.Wa2Assets
import com.wa2port.core.engine.EngineEvent
import com.wa2port.core.engine.Stage
import com.wa2port.core.engine.SystemData
import java.util.Locale

/**
 * Page/chapter grouping in the BGM Gallery (`WA2.exe` `0x428400`).
 *
 * Page 1 holds slots `#01..#31` (IC + Campus Fes + Full Version); Page 2 holds slots `#32..#63`
 * (CC + Coda).
 */
enum class BgmPage(val pageIndex: Int, val label: String) {
    PAGE_1(0, "Page 1 · IC"),
    PAGE_2(1, "Page 2 · CC / Coda"),
}

/**
 * One entry in `WA2.exe`'s 63-track BGM Gallery table at `.data 0x4c1dd0`.
 *
 * @property slotNumber 1-based gallery display number (`1..63`).
 * @property bgmId Primary BGM ID (`BGM_%03d.OGG` or `BGM_%03d_A.OGG` + `BGM_%03d_B.OGG`).
 * @property page Gallery page (`PAGE_1` for slots `1..31`, `PAGE_2` for slots `32..63`).
 * @property titleJp Track title from `grp.pak/sys_04101.tga` (`344×1512`, 63 rows × 24px).
 * @property creditsJp Singer / lyricist / composer / arranger credits from `grp.pak/sys_04031.tga`.
 */
data class BgmTrackEntry(
    val slotNumber: Int,
    val bgmId: Int,
    val page: BgmPage,
    val titleJp: String,
    val creditsJp: String,
) {
    /**
     * Checks whether this track slot is unlocked in [system] (`WA2.exe` `0x428453`).
     *
     * - Standard rule: `system.gflags[100 + bgmId] != 0` (or legacy `bgmId in system.bgmFlags`).
     * - Special rule for Slot `#25` (`bgmId == 21`, `届かない恋 Live at Campus Fes`): unlocked if
     *   either `gflags[121] != 0` (`BGM_021`) or `gflags[135] != 0` (`BGM_035` set in `2001.bnr`).
     * - Auto-unlock rule for Slot `#31` (`bgmId == 34`, `届かない恋 Full Version`): unlocked if
     *   `gflags[134] != 0` or both `gflags[104] != 0 && gflags[163] != 0` (IC OP + Coda OP).
     */
    fun isUnlocked(system: SystemData): Boolean {
        val gf = system.gflags
        fun flag(idx: Int): Boolean = idx in gf.indices && gf[idx] != 0
        if (bgmId == 21) {
            return flag(121) || flag(135) || 21 in system.bgmFlags || 35 in system.bgmFlags
        }
        if (bgmId == 34 && flag(104) && flag(163)) {
            return true
        }
        return flag(100 + bgmId) || bgmId in system.bgmFlags
    }

    /**
     * Effective BGM ID played when this slot is selected (`WA2.exe` `0x428b49..0x428b9c`).
     *
     * For Slot `#25` (`bgmId == 21`), if `gflags[135] != 0` (`BGM_035.OGG` unlocked in CC),
     * `WA2.exe` plays `BGM_035.OGG` (`35`); otherwise `BGM_021.OGG` (`21`).
     */
    fun effectiveBgmId(system: SystemData): Int {
        if (bgmId == 21) {
            val gf = system.gflags
            if ((135 in gf.indices && gf[135] != 0) || 35 in system.bgmFlags) return 35
        }
        return bgmId
    }

    /** Formatted badge label (e.g. `"BGM_004"` or `"BGM_021 / 035"`). */
    fun badgeLabel(system: SystemData): String = "BGM_%03d".format(Locale.ROOT, effectiveBgmId(system))
}

/**
 * Catalog of all 63 tracks in the WA2 BGM Gallery (`音楽モード`, `.data 0x4c1dd0`).
 */
object BgmGallery {
    /**
     * Exact 63-track table from `WA2.exe` `.data 0x4c1dd0` (`#01..#63`).
     */
    val tracks: List<BgmTrackEntry> = listOf(
        // ---- Page 1 (Slots 1..31)
        BgmTrackEntry(1, 4, BgmPage.PAGE_1, "届かない恋 (introductory chapter Opening Theme)", "歌：上原れな / 作詞：須谷尚子 / 作曲：石川真也 / 編曲：松岡純也"),
        BgmTrackEntry(2, 1, BgmPage.PAGE_1, "冬の街路樹", "作曲・編曲：衣笠道雄"),
        BgmTrackEntry(3, 2, BgmPage.PAGE_1, "Dear Friends", "作曲・編曲：松岡純也"),
        BgmTrackEntry(4, 3, BgmPage.PAGE_1, "イルミネーション・タウン", "作曲・編曲：衣笠道雄"),
        BgmTrackEntry(5, 5, BgmPage.PAGE_1, "いつか見た景色", "作曲・編曲：松岡純也"),
        BgmTrackEntry(6, 6, BgmPage.PAGE_1, "SLAPSTICK STREET", "作曲・編曲：中上和英"),
        BgmTrackEntry(7, 18, BgmPage.PAGE_1, "boy meets girl and girl", "作曲・編曲：中上和英"),
        BgmTrackEntry(8, 7, BgmPage.PAGE_1, "穏やかに過ぎゆく時", "作曲・編曲：衣笠道雄"),
        BgmTrackEntry(9, 8, BgmPage.PAGE_1, "言葉にできない想い", "作曲・編曲：衣笠道雄"),
        BgmTrackEntry(10, 9, BgmPage.PAGE_1, "あの頃のように", "作曲：石川真也 / 編曲：松岡純也"),
        BgmTrackEntry(11, 10, BgmPage.PAGE_1, "静かな冬の夜", "作曲・編曲：松岡純也"),
        BgmTrackEntry(12, 11, BgmPage.PAGE_1, "一途な心", "作曲・編曲：衣笠道雄"),
        BgmTrackEntry(13, 12, BgmPage.PAGE_1, "氷の刃", "作曲・編曲：衣笠道雄"),
        BgmTrackEntry(14, 13, BgmPage.PAGE_1, "本当の、嘘", "作曲・編曲：衣笠道雄"),
        BgmTrackEntry(15, 14, BgmPage.PAGE_1, "WINTER VACATION", "作曲：石川真也 / 編曲：衣笠道雄"),
        BgmTrackEntry(16, 15, BgmPage.PAGE_1, "聖夜", "作曲：石川真也 / 編曲：衣笠道雄"),
        BgmTrackEntry(17, 16, BgmPage.PAGE_1, "綺麗で儚いもの", "作曲・編曲：松岡純也"),
        BgmTrackEntry(18, 17, BgmPage.PAGE_1, "FILL YOU", "作曲：石川真也 / 編曲：衣笠道雄"),
        BgmTrackEntry(19, 27, BgmPage.PAGE_1, "POWDER SNOW PIANO 2010", "作曲：下川直哉 / 編曲：松岡純也"),
        BgmTrackEntry(20, 33, BgmPage.PAGE_1, "POWDER SNOW PIANO ～kazusa～", "作曲：下川直哉 / 編曲：松岡純也"),
        BgmTrackEntry(21, 32, BgmPage.PAGE_1, "POWDER SNOW Guitar solo", "作曲：下川直哉 / 編曲：松岡純也"),
        BgmTrackEntry(22, 28, BgmPage.PAGE_1, "深愛", "歌：小木曽雪菜 / 作詞：水樹奈々 / 作曲：上松範康(Elements Garden) / 編曲：松岡純也"),
        BgmTrackEntry(23, 19, BgmPage.PAGE_1, "WHITE ALBUM Live at Campus Fes", "歌：小木曽雪菜 / 作詞：森川由綺 / 作曲：石川真也 / 編曲：冬馬かずさ・飯塚武也"),
        BgmTrackEntry(24, 20, BgmPage.PAGE_1, "SOUND OF DESTINY Live at Campus Fes", "歌：小木曽雪菜 / 作詞：須谷尚子 / 作曲：中上和英 / 編曲：冬馬かずさ・飯塚武也"),
        BgmTrackEntry(25, 21, BgmPage.PAGE_1, "届かない恋 Live at Campus Fes", "歌：小木曽雪菜 / 作詞：北原春希 / 作曲・編曲：冬馬かずさ"),
        BgmTrackEntry(26, 26, BgmPage.PAGE_1, "After All ～綴る想い～ instrumental", "作曲：石川真也 / 編曲：衣笠道雄"),
        BgmTrackEntry(27, 29, BgmPage.PAGE_1, "After All ～綴る想い～", "歌：上原れな / 作詞：未海 / 作曲：石川真也 / 編曲：衣笠道雄"),
        BgmTrackEntry(28, 23, BgmPage.PAGE_1, "Twinkle Snow instrumental", "作曲：松岡純也 / 編曲：衣笠道雄"),
        BgmTrackEntry(29, 24, BgmPage.PAGE_1, "Twinkle Snow Piano quartet", "作曲・編曲：松岡純也"),
        BgmTrackEntry(30, 22, BgmPage.PAGE_1, "Twinkle Snow", "歌：津田朱里 / 作詞：須谷尚子 / 作曲：松岡純也 / 編曲：衣笠道雄"),
        BgmTrackEntry(31, 34, BgmPage.PAGE_1, "届かない恋 (Full Version)", "歌：上原れな / 作詞：須谷尚子 / 作曲：石川真也 / 編曲：松岡純也"),

        // ---- Page 2 (Slots 32..63)
        BgmTrackEntry(32, 60, BgmPage.PAGE_2, "幸せな記憶", "歌：上原れな / 作詞：須谷尚子 / 作曲：石川真也 / 編曲：衣笠道雄"),
        BgmTrackEntry(33, 31, BgmPage.PAGE_2, "Extended Winter", "作曲・編曲：衣笠道雄"),
        BgmTrackEntry(34, 40, BgmPage.PAGE_2, "吐露", "作曲・編曲：衣笠道雄"),
        BgmTrackEntry(35, 41, BgmPage.PAGE_2, "Snowfalls", "作曲・編曲：石川真也"),
        BgmTrackEntry(36, 42, BgmPage.PAGE_2, "キャンドルライト", "作曲・編曲：衣笠道雄"),
        BgmTrackEntry(37, 43, BgmPage.PAGE_2, "Honesty", "作曲・編曲：松岡純也"),
        BgmTrackEntry(38, 44, BgmPage.PAGE_2, "Answer", "作曲・編曲：衣笠道雄"),
        BgmTrackEntry(39, 45, BgmPage.PAGE_2, "最後に残るもの", "作曲・編曲：松岡純也"),
        BgmTrackEntry(40, 46, BgmPage.PAGE_2, "溶け合う心", "作曲・編曲：石川真也"),
        BgmTrackEntry(41, 47, BgmPage.PAGE_2, "誰かが傷ついても", "作曲・編曲：松岡純也"),
        BgmTrackEntry(42, 69, BgmPage.PAGE_2, "届かない恋 Valentine Live", "歌：小木曽雪菜 / 作詞：北原春希 / 作曲：冬馬かずさ / 編曲：北原春希"),
        BgmTrackEntry(43, 74, BgmPage.PAGE_2, "届かない恋 song by Chiaki", "歌：和泉千晶 / 作詞：北原春希 / 作曲・編曲：冬馬かずさ"),
        BgmTrackEntry(44, 52, BgmPage.PAGE_2, "優しい嘘 instrumental", "作曲・編曲：松岡純也"),
        BgmTrackEntry(45, 53, BgmPage.PAGE_2, "優しい嘘 Piano", "作曲・編曲：松岡純也"),
        BgmTrackEntry(46, 61, BgmPage.PAGE_2, "優しい嘘", "歌：上原れな / 作詞：須谷尚子 / 作曲・編曲：松岡純也"),
        BgmTrackEntry(47, 50, BgmPage.PAGE_2, "愛する心 instrumental", "作曲・編曲：衣笠道雄"),
        BgmTrackEntry(48, 51, BgmPage.PAGE_2, "愛する心 Music Box", "作曲・編曲：衣笠道雄"),
        BgmTrackEntry(49, 62, BgmPage.PAGE_2, "愛する心", "歌：津田朱里 / 作詞：須谷尚子 / 作曲・編曲：衣笠道雄"),
        BgmTrackEntry(50, 63, BgmPage.PAGE_2, "届かない恋 (coda Opening Theme)", "歌：上原れな / 作詞：須谷尚子 / 作曲：石川真也 / 編曲：松岡純也"),
        BgmTrackEntry(51, 67, BgmPage.PAGE_2, "Routes", "歌：小木曽雪菜 / 作詞：須谷尚子 / 作曲：M.I.S.N. / 編曲：松岡純也"),
        BgmTrackEntry(52, 68, BgmPage.PAGE_2, "あなたを想いたい", "歌：小木曽雪菜 / 作詞：須谷尚子 / 作曲：石川真也 / 編曲：松岡純也"),
        BgmTrackEntry(53, 70, BgmPage.PAGE_2, "POWDER SNOW (弾き語り)", "歌：小木曽雪菜 / 作詞：須谷尚子 / 作曲：下川直哉 / 編曲：小木曽雪菜"),
        BgmTrackEntry(54, 71, BgmPage.PAGE_2, "幸せな記憶 Piano", "作曲：石川真也 / 編曲：松岡純也"),
        BgmTrackEntry(55, 54, BgmPage.PAGE_2, "時の魔法 instrumental", "作曲：下川直哉 / 編曲：衣笠道雄"),
        BgmTrackEntry(56, 55, BgmPage.PAGE_2, "時の魔法 Piano", "作曲：下川直哉 / 編曲：衣笠道雄"),
        BgmTrackEntry(57, 64, BgmPage.PAGE_2, "時の魔法", "歌：小木曽雪菜 / 作詞：北原春希 / 作曲・編曲：冬馬かずさ"),
        BgmTrackEntry(58, 58, BgmPage.PAGE_2, "心はいつもあなたのそばに instrumental", "作曲：下川直哉 / 編曲：衣笠道雄"),
        BgmTrackEntry(59, 59, BgmPage.PAGE_2, "心はいつもあなたのそばに Piano", "作曲：下川直哉 / 編曲：衣笠道雄"),
        BgmTrackEntry(60, 66, BgmPage.PAGE_2, "心はいつもあなたのそばに", "歌：津田朱里 / 作詞：須谷尚子 / 作曲：下川直哉 / 編曲：衣笠道雄"),
        BgmTrackEntry(61, 56, BgmPage.PAGE_2, "closing instrumental", "作曲：下川直哉 / 編曲：衣笠道雄"),
        BgmTrackEntry(62, 57, BgmPage.PAGE_2, "closing Acoustic Guitar", "作曲：下川直哉 / 編曲：衣笠道雄"),
        BgmTrackEntry(63, 65, BgmPage.PAGE_2, "closing", "歌：上原れな / 作詞：下川直哉・須谷尚子 / 作曲：下川直哉 / 編曲：衣笠道雄"),
    )

    /**
     * Applies `WA2.exe` `0x428453` gallery initialization rule: when both `BGM_004` (IC OP, `gflags[104]`)
     * and `BGM_063` (Coda OP, `gflags[163]`) are unlocked, `BGM_034` (`届かない恋 Full Version`, `gflags[134]`)
     * is automatically unlocked in [system].
     */
    fun applyAutoUnlocks(system: SystemData) {
        val gf = system.gflags
        if (104 in gf.indices && 163 in gf.indices && 134 in gf.indices) {
            if (gf[104] != 0 && gf[163] != 0) {
                gf[134] = 1
            }
        }
    }

    /** Returns tracks filtered by [page] (or all 63 tracks when [page] is `null`). */
    fun filter(page: BgmPage?): List<BgmTrackEntry> =
        if (page == null) tracks else tracks.filter { it.page == page }

    /**
     * Resolves [track] (`1..199`) in [assets] (`BGM.PAK` / `IC/BGM.PAK`) to an [EngineEvent.PlayBgm]
     * event with optional `_A` intro and `_B` loop (`WA2.exe` `0x4068a0`).
     */
    fun resolvePlayBgm(assets: Wa2Assets, track: Int, fadeFrames: Int = 0, vol: Int = 255): EngineEvent.PlayBgm? {
        val a = "BGM_%03d_A.OGG".format(Locale.ROOT, track)
        val b = "BGM_%03d_B.OGG".format(Locale.ROOT, track)
        val single = "BGM_%03d.OGG".format(Locale.ROOT, track)
        val fade = Stage.framesToMs(fadeFrames)
        return when {
            assets.audio("BGM", b) != null -> EngineEvent.PlayBgm(track, if (assets.audio("BGM", a) != null) a else null, b, fade, vol)
            assets.audio("BGM", single) != null -> EngineEvent.PlayBgm(track, null, single, fade, vol)
            else -> null
        }
    }

    /** Resolves [entry] using [BgmTrackEntry.effectiveBgmId] for [system]. */
    fun resolvePlayEvent(
        assets: Wa2Assets,
        entry: BgmTrackEntry,
        system: SystemData,
        fadeFrames: Int = 0,
        vol: Int = 255,
    ): EngineEvent.PlayBgm? = resolvePlayBgm(assets, entry.effectiveBgmId(system), fadeFrames, vol)
}
