package com.wa2port.core.gallery

import com.wa2port.core.archive.Wa2Assets
import com.wa2port.core.engine.SystemData
import java.util.Locale

/** Chapter bucket for event CG cards (`IC` = records 0–26, `CC` = records 36–161). */
enum class CgCategory(val labelCn: String, val labelEn: String) {
    IC("IC 序章", "Introductory Chapter"),
    CC("CC 终章·Coda", "Closing Chapter / Coda"),
    OTHER("其他", "Other"),
    ;

    companion object {
        /** Maps the first digit of a CG stem (`1` = IC, `2`/`3` = CC/Coda, others = OTHER). */
        fun fromStem(stemDigits: String): CgCategory = when (stemDigits.firstOrNull()) {
            '1' -> IC
            '2', '3' -> CC
            else -> OTHER
        }
    }
}

/**
 * One event CG gallery card from the `WA2.exe` card table at `.data 0x4c01b0`
 * (or 5-digit stem fallback if the table resource is absent).
 */
data class CgGroup(
    val id: String,
    val category: CgCategory,
    val variations: List<String>,
    val variationArchives: Map<String, String> = emptyMap(),
    val record: Int = -1,
) {
    /** Numeric stem of the card's primary CG (e.g. `"100100"` for `"v100100"`). */
    val stemDigits: String get() = id.dropWhile { !it.isDigit() }

    /** First variation filename, used as the default card thumbnail in the gallery grid. */
    val cover: String get() = variations.first()

    /** Returns the first unlocked variation for thumbnail display when [showAll] is false, or [cover]. */
    fun coverFor(system: SystemData, showAll: Boolean = false): String =
        if (showAll) cover else variations.firstOrNull { CgGallery.isUnlocked(it, system) } ?: cover

    /** A card counts as unlocked when any of its variations is marked seen in [system]. */
    fun isUnlocked(system: SystemData): Boolean =
        variations.any { CgGallery.isUnlocked(it, system) } || CgGallery.isUnlocked(id, system)

    /** Number of variations in this card that have been seen in [system]. */
    fun unlockedCount(system: SystemData): Int =
        variations.count { CgGallery.isUnlocked(it, system) }
}

/**
 * Indexes event CGs (`v??????.tga` / `h??????.tga`) across loose `grp/`, `patch.pak`, `grp.pak`,
 * and `IC/grp.pak`, organized according to the `WA2.exe` card table (`wa2/cg_cards.tsv`, 149 cards /
 * 272 variants) with a fallback to 5-digit stem grouping when the table resource is absent.
 *
 * Composite layer parts (`_bg`, `_chr`, `_chreff`, `_eff`, `_fence`) and the 8 unreferenced `v*.tga`
 * files (`101301`, `101401`, `101501`, `101601`, `102301`, `103201`, `200301`, `220300`) are excluded.
 */
class CgGallery(
    val groups: List<CgGroup>,
    val variationArchives: Map<String, String>,
) {
    /** Returns the archive/source label (`"grp"`, `"patch.pak"`, `"grp.pak"`, `"IC/grp.pak"`) holding [variation]. */
    fun archiveOf(variation: String): String? = variationArchives[normalizeVariationFile(variation)]

    /** Returns cards matching [category], or all [groups] when [category] is `null`. */
    fun groupsFor(category: CgCategory?): List<CgGroup> =
        if (category == null) groups else groups.filter { it.category == category }

    /** True when [group] has at least one seen variation in [system]. */
    fun isUnlocked(group: CgGroup, system: SystemData): Boolean = group.isUnlocked(system)

    /** Checks whether a variation or group [name] is unlocked in [system]. */
    fun isUnlocked(name: String, system: SystemData): Boolean {
        groups.firstOrNull { it.id.equals(name.trim(), ignoreCase = true) && !name.endsWith(".tga", ignoreCase = true) }
            ?.let { if (it.variations.size > 1 && name.length <= 6) return it.isUnlocked(system) }
        return Companion.isUnlocked(name, system)
    }

    companion object {
        const val CARD_TABLE_RESOURCE = "/wa2/cg_cards.tsv"

        /** Strict 6-digit event CG filename pattern; rejects `_bg`, `_chr`, `_eff`, etc. */
        private val CG_FILE_REGEX = Regex("""^([vh])(\d{6})\.tga$""", RegexOption.IGNORE_CASE)
        private val DIGITS_6_REGEX = Regex("""\d{6}""")
        private val DIGITS_5_REGEX = Regex("""\d{5}""")

        /**
         * 8 `v*.tga` files present in the archives that belong to no card in `WA2.exe` (`.data 0x4c01b0`)
         * and must remain hidden in the gallery.
         */
        val HIDDEN_STEMS: Set<Int> = setOf(
            101301, 101401, 101501, 101601, 102301, 103201, 200301, 220300,
        )

        val HIDDEN_FILES: Set<String> = HIDDEN_STEMS.mapTo(LinkedHashSet()) { "v%06d.tga".format(Locale.ROOT, it) }

        /**
         * Builds the CG gallery index from [assets]. Uses the embedded `wa2/cg_cards.tsv` table from
         * `WA2.exe` by default and falls back to 5-digit stem grouping if the table is unavailable.
         */
        fun fromAssets(assets: Wa2Assets, cardTableResource: String? = CARD_TABLE_RESOURCE): CgGallery {
            val allSources = scanArchiveSources(assets)
            val cardRows = cardTableResource?.let { loadCardTable(it) }.orEmpty()

            if (cardRows.isNotEmpty()) {
                val cardGroups = ArrayList<CgGroup>(cardRows.size)
                val usedSources = LinkedHashMap<String, String>()
                for (row in cardRows) {
                    val vars = row.stems
                        .map { "v%06d.tga".format(Locale.ROOT, it) }
                        .filter { it in allSources }
                    if (vars.isEmpty()) continue
                    val groupArchives = LinkedHashMap<String, String>(vars.size)
                    for (v in vars) {
                        val src = allSources.getValue(v)
                        groupArchives[v] = src
                        usedSources[v] = src
                    }
                    val primaryStem = row.stems.first()
                    cardGroups += CgGroup(
                        id = "v%06d".format(Locale.ROOT, primaryStem),
                        category = row.category,
                        variations = vars,
                        variationArchives = groupArchives,
                        record = row.record,
                    )
                }
                if (cardGroups.isNotEmpty()) {
                    return CgGallery(cardGroups, usedSources)
                }
            }

            return fromStemFallback(allSources)
        }

        /** Fallback grouping by 5-digit stem prefix when `cg_cards.tsv` is missing. */
        internal fun fromStemFallback(allSources: Map<String, String>): CgGallery {
            val filteredSources = allSources.filterKeys { it !in HIDDEN_FILES }
            val byGroup = LinkedHashMap<String, MutableList<String>>()
            for (fileName in filteredSources.keys) {
                val groupId = fileName.substring(0, 6) // e.g. "v10010"
                byGroup.getOrPut(groupId) { ArrayList() } += fileName
            }
            val groups = byGroup.entries
                .sortedWith(
                    compareBy<Map.Entry<String, MutableList<String>>> { it.key.substring(1, 6) }
                        .thenBy { if (it.key.startsWith('v')) 0 else 1 }
                        .thenBy { it.key },
                )
                .map { (groupId, vars) ->
                    val sortedVars = vars.sorted()
                    val stem5 = groupId.substring(1, 6)
                    CgGroup(
                        id = groupId,
                        category = CgCategory.fromStem(stem5),
                        variations = sortedVars,
                        variationArchives = sortedVars.associateWith { filteredSources.getValue(it) },
                    )
                }
            return CgGallery(groups, filteredSources)
        }

        /**
         * Checks whether a CG variation or stem [name] (e.g. `"v100100"`, `"v100100.tga"`, `"v10010"`)
         * is marked seen in [SystemData.cgFlags].
         */
        fun isUnlocked(name: String, system: SystemData): Boolean {
            if (system.cgFlags.isEmpty()) return false
            val trimmed = name.trim().lowercase().removeSuffix(".tga")
            if (trimmed.isEmpty()) return false

            val stem6 = DIGITS_6_REGEX.find(trimmed)?.value
            if (stem6 != null) {
                val prefix = if (trimmed.startsWith('h')) 'h' else 'v'
                val canonical = "$prefix$stem6"
                val stem5 = stem6.substring(0, 5)
                return system.cgFlags.any { rawFlag ->
                    val f = rawFlag.trim().lowercase().removeSuffix(".tga")
                    f == canonical || f == stem6 || (f.contains(':') && f.contains(stem5))
                }
            }

            val stem5 = DIGITS_5_REGEX.find(trimmed)?.value ?: trimmed
            return system.cgFlags.any { rawFlag ->
                val f = rawFlag.trim().lowercase().removeSuffix(".tga")
                f == trimmed || f.contains(stem5)
            }
        }

        private fun scanArchiveSources(assets: Wa2Assets): LinkedHashMap<String, String> {
            val sources = LinkedHashMap<String, String>()

            fun recordName(rawName: String, archiveLabel: String) {
                val m = CG_FILE_REGEX.matchEntire(rawName.trim()) ?: return
                val prefix = m.groupValues[1].lowercase()
                val stem6 = m.groupValues[2]
                sources.putIfAbsent("$prefix$stem6.tga", archiveLabel)
            }

            // 1. Loose <root>/grp/ directory
            val looseGrp = assets.root.listFiles()
                ?.firstOrNull { it.isDirectory && it.name.equals("grp", ignoreCase = true) }
            looseGrp?.listFiles()?.forEach { f ->
                if (f.isFile) recordName(f.name, "grp")
            }

            // 2. Optional patch.pak
            assets.kcap("patch")?.files?.forEach { e -> recordName(e.name, "patch.pak") }

            // 3. Root grp.pak
            assets.kcap("grp", ic = false)?.files?.forEach { e -> recordName(e.name, "grp.pak") }

            // 4. IC/grp.pak
            assets.kcap("grp", ic = true)?.files?.forEach { e -> recordName(e.name, "IC/grp.pak") }

            return sources
        }

        private data class CardRow(
            val record: Int,
            val category: CgCategory,
            val stems: List<Int>,
        )

        private fun loadCardTable(resourcePath: String): List<CardRow>? {
            val stream = CgGallery::class.java.getResourceAsStream(resourcePath) ?: return null
            return stream.bufferedReader(Charsets.UTF_8).useLines { lines ->
                lines
                    .map { it.trim() }
                    .filter { it.isNotEmpty() && !it.startsWith('#') }
                    .mapNotNull { line ->
                        val parts = line.split('\t')
                        if (parts.size < 3) return@mapNotNull null
                        val rec = parts[0].trim().toIntOrNull() ?: return@mapNotNull null
                        val cat = runCatching { CgCategory.valueOf(parts[1].trim().uppercase()) }
                            .getOrDefault(if (rec < 36) CgCategory.IC else CgCategory.CC)
                        val stems = parts[2].split(',').mapNotNull { it.trim().toIntOrNull() }.filter { it > 0 }
                        if (stems.isEmpty()) null else CardRow(rec, cat, stems)
                    }
                    .toList()
            }
        }

        private fun normalizeVariationFile(variation: String): String {
            val lower = variation.trim().lowercase()
            return if (lower.endsWith(".tga")) lower else "$lower.tga"
        }
    }
}
