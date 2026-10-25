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
 * Save slots as JSON files: `saves/quick.json` (slot [QUICK_SLOT]) and `saves/slot_NN.json`
 * (1..[SLOT_COUNT]). Writes are atomic (tmp + rename).
 */
class SaveStore(private val dir: File) {
    init {
        dir.mkdirs()
    }

    fun file(slot: Int): File = File(dir, if (slot == QUICK_SLOT) "quick.json" else "slot_%02d.json".format(Locale.ROOT, slot))

    fun exists(slot: Int): Boolean = file(slot).isFile

    suspend fun write(slot: Int, data: SaveData) = withContext(Dispatchers.IO) {
        val f = file(slot)
        val tmp = File(dir, f.name + ".tmp")
        tmp.writeText(data.toJson())
        f.delete()
        tmp.renameTo(f)
    }

    suspend fun read(slot: Int): SaveData? = withContext(Dispatchers.IO) {
        runCatching { SaveData.fromJson(file(slot).readText()) }.getOrNull()
    }

    suspend fun delete(slot: Int) = withContext(Dispatchers.IO) { file(slot).delete() }

    /** Metadata of every existing slot, keyed by slot number. */
    suspend fun listMeta(): Map<Int, SlotMeta> = withContext(Dispatchers.IO) {
        (listOf(QUICK_SLOT) + (1..SLOT_COUNT)).mapNotNull { slot ->
            if (!exists(slot)) return@mapNotNull null
            val d = runCatching { SaveData.fromJson(file(slot).readText()) }.getOrNull() ?: return@mapNotNull null
            slot to SlotMeta(slot, d.timestamp, d.scene, d.previewJp, d.previewCn, decodeThumbnail(d.thumbnail))
        }.toMap()
    }

    companion object {
        const val QUICK_SLOT = 0
        const val SLOT_COUNT = 24
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
