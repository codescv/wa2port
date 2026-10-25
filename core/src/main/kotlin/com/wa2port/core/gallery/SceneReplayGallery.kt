package com.wa2port.core.gallery

import com.wa2port.core.archive.Wa2Assets
import com.wa2port.core.engine.SystemData
import java.util.Locale

/** Chapter filter for Scene Replay (`WA2.exe` `0x42e300`, `.data 0x4c1bf0`). */
enum class ReplayCategory(val id: Int, val label: String) {
    IC(0, "IC 序章"),
    CC(1, "CC 终章"),
    CODA(2, "Coda"),
}

/**
 * One entry in `WA2.exe`'s 24-slot Scene Replay table at `.data 0x4c1bf0..0x4c1dd0`
 * (20 bytes per record: `(script_ptr, gflag_id, thumb_cg_id, category, title_ptr)`).
 *
 * @property slotNumber 1-based replay slot number (`1..24`), stored in `Wa2Engine.replayMode` and returned by `GetReplayMode()` (`222`).
 * @property scriptName Target `.bnr` script name dispatched by `9999.bnr`.
 * @property gflagId Global flag index in [SystemData.gflags] (`80` for IC slot 1, `300..322` for CC/Coda slots `2..24`).
 * @property thumbCgId 6-digit CG stem ID used for thumbnail `tv%06d.tga` (`272×160`) and full CG `v%06d.tga` (`1280×720`).
 * @property category Chapter category (`IC`, `CC`, or `CODA`).
 * @property heroineJp Heroine name in Japanese.
 * @property heroineCn Heroine name in Simplified Chinese.
 * @property titleJp Official Japanese scene title (`WA2.exe` / `grp.pak/sys_05301.tga`).
 * @property titleCn CK-GAL Simplified Chinese scene title (`Loose grp/sys_05301.tga`).
 */
data class SceneReplayEntry(
    val slotNumber: Int,
    val scriptName: String,
    val gflagId: Int,
    val thumbCgId: Int,
    val category: ReplayCategory,
    val heroineJp: String,
    val heroineCn: String,
    val titleJp: String,
    val titleCn: String,
) {
    /** Pre-baked 272×160 thumbnail filename in `grp.pak` / `IC/grp.pak` (`tv%06d.tga`). */
    val thumbFile: String get() = "tv%06d.tga".format(Locale.ROOT, thumbCgId)

    /** Full-resolution 1280×720 event CG filename in `grp.pak` / `IC/grp.pak` (`v%06d.tga`). */
    val cgFile: String get() = "v%06d.tga".format(Locale.ROOT, thumbCgId)

    /** Checks whether this replay slot is unlocked in [system] (`system.gflags[gflagId] != 0`). */
    fun isUnlocked(system: SystemData): Boolean =
        gflagId in system.gflags.indices && system.gflags[gflagId] != 0

    /** Resolves the best available thumbnail filename in `grp` (`tv%06d.tga` first, then `v%06d.tga`). */
    fun resolveThumbnail(assets: Wa2Assets): String? = when {
        assets.exists("grp", thumbFile) -> thumbFile
        assets.exists("grp", cgFile) -> cgFile
        else -> null
    }
}

/**
 * Catalog of all 24 Scene Replay slots (`回想モード`, `.data 0x4c1bf0..0x4c1dd0`).
 */
object SceneReplayGallery {
    /**
     * Exact 24-record Scene Replay table from `WA2.exe` `.data 0x4c1bf0` (`#01..#24`).
     */
    val entries: List<SceneReplayEntry> = listOf(
        // ---- IC (Slot 1)
        SceneReplayEntry(
            slotNumber = 1, scriptName = "1012_030_2", gflagId = 80, thumbCgId = 101700,
            category = ReplayCategory.IC, heroineJp = "冬馬かずさ", heroineCn = "冬马和纱",
            titleJp = "初めての、きっと最後の裏切り", titleCn = "最初的、也一定是最后的背叛",
        ),
        // ---- CC: Setsuna (Slots 2..4)
        SceneReplayEntry(
            slotNumber = 2, scriptName = "2031", gflagId = 300, thumbCgId = 200800,
            category = ReplayCategory.CC, heroineJp = "小木曽雪菜", heroineCn = "小木曾雪菜",
            titleJp = "初めての、きっと最後の贖罪", titleCn = "最初的、也一定是最后的赎罪",
        ),
        SceneReplayEntry(
            slotNumber = 3, scriptName = "2031", gflagId = 301, thumbCgId = 201200,
            category = ReplayCategory.CC, heroineJp = "小木曽雪菜", heroineCn = "小木曾雪菜",
            titleJp = "寒いの嫌い。狭いの好き", titleCn = "讨厌寒冷。喜欢狭窄",
        ),
        SceneReplayEntry(
            slotNumber = 4, scriptName = "2031", gflagId = 302, thumbCgId = 201700,
            category = ReplayCategory.CC, heroineJp = "小木曽雪菜", heroineCn = "小木曾雪菜",
            titleJp = "根暗で、恨みがましくて、エッチな女の子", titleCn = "腹黑且令人怨恨的好色女",
        ),
        // ---- CC: Koharu (Slots 5..7)
        SceneReplayEntry(
            slotNumber = 5, scriptName = "2312", gflagId = 303, thumbCgId = 202200,
            category = ReplayCategory.CC, heroineJp = "杉浦小春", heroineCn = "杉浦小春",
            titleJp = "あなただけのビッチ", titleCn = "只成为你的菜",
        ),
        SceneReplayEntry(
            slotNumber = 6, scriptName = "2313", gflagId = 304, thumbCgId = 202400,
            category = ReplayCategory.CC, heroineJp = "杉浦小春", heroineCn = "杉浦小春",
            titleJp = "独り占めの週末", titleCn = "独占的周末",
        ),
        SceneReplayEntry(
            slotNumber = 7, scriptName = "2313", gflagId = 305, thumbCgId = 202700,
            category = ReplayCategory.CC, heroineJp = "杉浦小春", heroineCn = "杉浦小春",
            titleJp = "可愛くて、そして残酷な嘘", titleCn = "可爱、并且残酷的谎言",
        ),
        // ---- CC: Chiaki (Slots 8..10)
        SceneReplayEntry(
            slotNumber = 8, scriptName = "2401", gflagId = 306, thumbCgId = 203400,
            category = ReplayCategory.CC, heroineJp = "和泉千晶", heroineCn = "和泉千晶",
            titleJp = "身代わりはサンタクロース", titleCn = "圣诞play",
        ),
        SceneReplayEntry(
            slotNumber = 9, scriptName = "2402", gflagId = 307, thumbCgId = 203500,
            category = ReplayCategory.CC, heroineJp = "和泉千晶", heroineCn = "和泉千晶",
            titleJp = "温かな泥沼", titleCn = "温婉的泥沼",
        ),
        SceneReplayEntry(
            slotNumber = 10, scriptName = "2402", gflagId = 308, thumbCgId = 203800,
            category = ReplayCategory.CC, heroineJp = "和泉千晶", heroineCn = "和泉千晶",
            titleJp = "冬休みの終わり、楽園の終わり", titleCn = "冬休的结束、乐园的结束",
        ),
        // ---- CC: Mari (Slots 11..14)
        SceneReplayEntry(
            slotNumber = 11, scriptName = "2504", gflagId = 309, thumbCgId = 204900,
            category = ReplayCategory.CC, heroineJp = "風岡麻理", heroineCn = "风冈麻理",
            titleJp = "すれ違いと逆恨みと最低な過ち", titleCn = "交错与反怨与最低级的过失",
        ),
        SceneReplayEntry(
            slotNumber = 12, scriptName = "2507", gflagId = 310, thumbCgId = 205100,
            category = ReplayCategory.CC, heroineJp = "風岡麻理", heroineCn = "风冈麻理",
            titleJp = "淑女の先走りすぎな嗜み", titleCn = "淑女那爱抢先的嗜好",
        ),
        SceneReplayEntry(
            slotNumber = 13, scriptName = "2511", gflagId = 311, thumbCgId = 205300,
            category = ReplayCategory.CC, heroineJp = "風岡麻理", heroineCn = "风冈麻理",
            titleJp = "覆せない主従関係（但しベッドの中）", titleCn = "无法推翻的主从关系（床上例外）",
        ),
        SceneReplayEntry(
            slotNumber = 14, scriptName = "2513", gflagId = 312, thumbCgId = 205500,
            category = ReplayCategory.CC, heroineJp = "風岡麻理", heroineCn = "风冈麻理",
            titleJp = "身代わりが先か、別れが先か", titleCn = "是替身在先、还是分别在先",
        ),
        // ---- Coda (Slots 15..24)
        SceneReplayEntry(
            slotNumber = 15, scriptName = "3001", gflagId = 313, thumbCgId = 205900,
            category = ReplayCategory.CODA, heroineJp = "小木曽雪菜", heroineCn = "小木曾雪菜",
            titleJp = "いつまでも熱いままで", titleCn = "永远如此热下去",
        ),
        SceneReplayEntry(
            slotNumber = 16, scriptName = "3015", gflagId = 314, thumbCgId = 206500,
            category = ReplayCategory.CODA, heroineJp = "冬馬かずさ", heroineCn = "冬马和纱",
            titleJp = "激情の、あってはならない理由", titleCn = "激情、不需要理由",
        ),
        SceneReplayEntry(
            slotNumber = 17, scriptName = "3210", gflagId = 315, thumbCgId = 208400,
            category = ReplayCategory.CODA, heroineJp = "小木曽雪菜", heroineCn = "小木曾雪菜",
            titleJp = "すべてを捧げられるひと", titleCn = "奉献出所有的一切",
        ),
        SceneReplayEntry(
            slotNumber = 18, scriptName = "3901", gflagId = 316, thumbCgId = 207003,
            category = ReplayCategory.CODA, heroineJp = "冬馬かずさ", heroineCn = "冬马和纱",
            titleJp = "ふたたびの裏切り、崩壊の序曲", titleCn = "再次的背叛、崩坏的序曲",
        ),
        SceneReplayEntry(
            slotNumber = 19, scriptName = "3902", gflagId = 317, thumbCgId = 209800,
            category = ReplayCategory.CODA, heroineJp = "冬馬かずさ", heroineCn = "冬马和纱",
            titleJp = "三人で…", titleCn = "三人游…",
        ),
        SceneReplayEntry(
            slotNumber = 20, scriptName = "3902", gflagId = 318, thumbCgId = 209900,
            category = ReplayCategory.CODA, heroineJp = "冬馬かずさ", heroineCn = "冬马和纱",
            titleJp = "本気過ぎる嘘", titleCn = "太过真实的谎言",
        ),
        SceneReplayEntry(
            slotNumber = 21, scriptName = "3904", gflagId = 319, thumbCgId = 210200,
            category = ReplayCategory.CODA, heroineJp = "冬馬かずさ", heroineCn = "冬马和纱",
            titleJp = "誰かに見られてしまえたならば", titleCn = "会被别人看到的…",
        ),
        SceneReplayEntry(
            slotNumber = 22, scriptName = "3906", gflagId = 320, thumbCgId = 210500,
            category = ReplayCategory.CODA, heroineJp = "冬馬かずさ", heroineCn = "冬马和纱",
            titleJp = "世界でたったふたりの獣", titleCn = "孤寂的世界、两只野兽",
        ),
        SceneReplayEntry(
            slotNumber = 23, scriptName = "3907", gflagId = 321, thumbCgId = 210600,
            category = ReplayCategory.CODA, heroineJp = "冬馬かずさ", heroineCn = "冬马和纱",
            titleJp = "全てを、俺のものに", titleCn = "全部成为我的所有",
        ),
        SceneReplayEntry(
            slotNumber = 24, scriptName = "3907", gflagId = 322, thumbCgId = 210800,
            category = ReplayCategory.CODA, heroineJp = "冬馬かずさ", heroineCn = "冬马和纱",
            titleJp = "一生の、けれど一晩だけの誓い", titleCn = "一生、却也仅限一晚的誓言",
        ),
    )

    /** Returns replay entries filtered by [category] (or all 24 entries when [category] is `null`). */
    fun filter(category: ReplayCategory?): List<SceneReplayEntry> =
        if (category == null) entries else entries.filter { it.category == category }

    /** Looks up a replay entry by its 1-based [slotNumber] (`1..24`). */
    fun bySlot(slotNumber: Int): SceneReplayEntry? = entries.getOrNull(slotNumber - 1)
}
