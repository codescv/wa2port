package com.wa2port.android.data

import java.util.Locale
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.wa2port.core.engine.SaveData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File

/** Lightweight description of a save slot for the Save/Load grid. */
data class SlotMeta(
    val slot: Int,
    val timestamp: Long,
    val scene: String,
    val previewJp: String,
    val previewCn: String,
    val thumbnail: ImageBitmap?,
)

/**
 * Save slots as JSON files: `saves/quick.json` (slot [QUICK_SLOT]), `saves/slot_NN.json`
 * (1..[SLOT_COUNT]) and two reserved slots: `saves/auto.json` ([AUTO_SLOT], written automatically
 * on backgrounding / scene change) and `saves/undo.json` ([UNDO_SLOT], the state replaced by the last
 * in-session load, never shown in the grid nor offered as 「继续」). Writing the quick slot first
 * copies the previous `quick.json` to `quick.prev.json` so a quick save can be undone
 * ([restoreQuickPrev]). Writes are atomic (tmp + rename).
 */
class SaveStore(private val dir: File) {
    init {
        dir.mkdirs()
    }

    fun file(slot: Int): File = File(dir, fileNameFor(slot))

    private val quickPrevFile: File get() = File(dir, QUICK_PREV_NAME)

    fun exists(slot: Int): Boolean = file(slot).isFile

    suspend fun write(slot: Int, data: SaveData) = withContext(Dispatchers.IO) {
        val f = file(slot)
        if (slot == QUICK_SLOT) {
            // Keep the previous quick save so 「撤销 Undo」 can bring it back.
            if (f.isFile) runCatching { f.copyTo(quickPrevFile, overwrite = true) } else quickPrevFile.delete()
        }
        writeAtomic(f, data.toJson())
    }

    private fun writeAtomic(f: File, text: String) {
        val tmp = File(dir, f.name + ".tmp")
        tmp.writeText(text)
        f.delete()
        tmp.renameTo(f)
    }

    suspend fun read(slot: Int): SaveData? = withContext(Dispatchers.IO) {
        runCatching { SaveData.fromJson(file(slot).readText()) }.getOrNull()
    }

    suspend fun delete(slot: Int) = withContext(Dispatchers.IO) { file(slot).delete() }

    /**
     * Undoes the last quick save: puts `quick.prev.json` back as `quick.json` (or deletes
     * `quick.json` when there was no quick save before). Returns the restored data (null = none).
     */
    suspend fun restoreQuickPrev(): SaveData? = withContext(Dispatchers.IO) {
        val prev = quickPrevFile
        val quick = file(QUICK_SLOT)
        if (!prev.isFile) {
            quick.delete()
            return@withContext null
        }
        val text = runCatching { prev.readText() }.getOrNull()
        val data = text?.let { runCatching { SaveData.fromJson(it) }.getOrNull() }
        if (text != null && data != null) writeAtomic(quick, text) else quick.delete()
        prev.delete()
        data
    }

    /** Metadata of [slot], or null when it is empty / unreadable. */
    private fun metaOf(slot: Int): SlotMeta? {
        if (!exists(slot)) return null
        val d = runCatching { SaveData.fromJson(file(slot).readText()) }.getOrNull() ?: return null
        return SlotMeta(slot, d.timestamp, d.scene, d.previewJp, d.previewCn, decodeThumbnail(d.thumbnail))
    }

    /** Metadata of every existing slot (incl. [AUTO_SLOT], never [UNDO_SLOT]), keyed by slot number. */
    suspend fun listMeta(): Map<Int, SlotMeta> = withContext(Dispatchers.IO) {
        CONTINUABLE_SLOTS.mapNotNull { slot -> metaOf(slot)?.let { slot to it } }.toMap()
    }

    /** Metadata of the autosave ([AUTO_SLOT]), or null when there is none. */
    suspend fun readAuto(): SlotMeta? = withContext(Dispatchers.IO) { metaOf(AUTO_SLOT) }

    /**
     * The most recent save among [AUTO_SLOT], [QUICK_SLOT] and `1..`[SLOT_COUNT] (by
     * [SlotMeta.timestamp]; [UNDO_SLOT] is never considered) — the target of the title 「继续」.
     */
    suspend fun latestContinuable(): SlotMeta? = latestOf(listMeta().values)

    companion object {
        const val QUICK_SLOT = 0
        /** Reserved autosave slot (`auto.json`). */
        const val AUTO_SLOT = -1
        /** Reserved load-undo slot (`undo.json`); excluded from the grid and from 「继续」. */
        const val UNDO_SLOT = -2
        const val SLOT_COUNT = 24
        private const val QUICK_PREV_NAME = "quick.prev.json"

        /** Slots shown in the Load grid / considered for 「继续」, in display order. */
        val CONTINUABLE_SLOTS: List<Int> = listOf(AUTO_SLOT, QUICK_SLOT) + (1..SLOT_COUNT)

        fun fileNameFor(slot: Int): String = when (slot) {
            AUTO_SLOT -> "auto.json"
            UNDO_SLOT -> "undo.json"
            QUICK_SLOT -> "quick.json"
            else -> "slot_%02d.json".format(Locale.ROOT, slot)
        }

        /** Newest of [metas] (ties → the first one), ignoring [UNDO_SLOT]. */
        fun latestOf(metas: Collection<SlotMeta>): SlotMeta? =
            metas.filter { it.slot != UNDO_SLOT }.maxByOrNull { it.timestamp }
        private const val THUMB_W = 320
        private const val THUMB_H = 180

        /** Scales [bitmap] to 320×180 and returns Base64 JPEG for [SaveData.thumbnail]. */
        fun encodeThumbnail(bitmap: Bitmap?): String? {
            bitmap ?: return null
            return runCatching {
                val scaled = Bitmap.createScaledBitmap(bitmap, THUMB_W, THUMB_H, true)
                val bos = ByteArrayOutputStream()
                scaled.compress(Bitmap.CompressFormat.JPEG, 80, bos)
                if (scaled !== bitmap) scaled.recycle()
                Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP)
            }.getOrNull()
        }

        fun decodeThumbnail(b64: String?): ImageBitmap? {
            b64 ?: return null
            return runCatching {
                val bytes = Base64.decode(b64, Base64.DEFAULT)
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
            }.getOrNull()
        }
    }
}
