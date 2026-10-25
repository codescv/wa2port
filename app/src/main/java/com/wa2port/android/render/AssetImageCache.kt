package com.wa2port.android.render

import android.graphics.Bitmap
import android.util.Log
import android.util.LruCache
import com.wa2port.core.archive.Wa2Assets
import com.wa2port.core.image.AmpLut
import com.wa2port.core.image.BmpDecoder
import com.wa2port.core.image.TgaDecoder
import com.wa2port.core.image.Wa2Image
import java.io.Closeable
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel

/**
 * Asynchronous decoder + LRU cache turning WA2 archive images (TGA sprites/backgrounds/CGs, BMP
 * dissolve masks and UI bitmaps) into premultiplied `ARGB_8888` [Bitmap]s.
 *
 * * Decoding runs on [Dispatchers.IO]; concurrent requests for the same file share one decode.
 * * The cache is bounded by [budgetBytes] (default ~150 MB, i.e. ~40 full-screen 1280×720 images).
 * * Files that do not exist / fail to decode are remembered as missing and return `null` quickly.
 * * `.AMP` colour-grading LUTs are cached separately ([lut]).
 *
 * Names are looked up through [Wa2Assets.read] (`dir` = archive stem such as `bak`, `char`, `grp`).
 * A name without an extension gets `.tga` appended.
 *
 * Thread-safe. Call [close] when the game screen is torn down (it cancels pending decodes and
 * drops every cached bitmap; the bitmaps themselves are left to the GC because views may still be
 * drawing them).
 */
class AssetImageCache(
    val assets: Wa2Assets,
    val budgetBytes: Int = DEFAULT_BUDGET_BYTES,
) : Closeable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lru = object : LruCache<String, Bitmap>(budgetBytes) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
    }
    private val inflight = ConcurrentHashMap<String, Deferred<Bitmap?>>()
    private val missing: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val luts = ConcurrentHashMap<String, AmpLut>()

    /** Returns the cached bitmap for `dir/name` without decoding, or `null`. */
    fun peek(dir: String, name: String): Bitmap? = lru.get(key(dir, name))

    /** True when a previous lookup of `dir/name` found no (decodable) file. */
    fun isMissing(dir: String, name: String): Boolean = key(dir, name) in missing

    /**
     * Returns the decoded bitmap for `dir/name`, decoding it on [Dispatchers.IO] if needed, or
     * `null` when the file does not exist or cannot be decoded.
     */
    suspend fun get(dir: String, name: String): Bitmap? {
        val k = key(dir, name)
        lru.get(k)?.let { return it }
        if (k in missing) return null
        // Lazy start so the deferred is registered before it can remove itself from [inflight].
        val fresh = scope.async(start = CoroutineStart.LAZY) {
            try {
                decodeBitmap(dir, normalize(name))?.also { lru.put(k, it) } ?: run { missing += k; null }
            } finally {
                inflight.remove(k)
            }
        }
        val job = inflight.putIfAbsent(k, fresh) ?: fresh
        if (job !== fresh) fresh.cancel()
        job.start()
        return job.await()
    }

    /** Starts decoding `dir/name` in the background (fire-and-forget). */
    fun prefetch(dir: String, name: String) {
        if (peek(dir, name) != null || isMissing(dir, name)) return
        scope.async { get(dir, name) }
    }

    /** Loads (and caches) the `.AMP` LUT [name] from `grp`, or `null` if absent/invalid. Blocking; call off the UI thread. */
    fun lut(name: String): AmpLut? {
        luts[name.lowercase()]?.let { return it }
        val bytes = assets.read("grp", name) ?: return null
        return runCatching { AmpLut.parse(name, bytes) }.getOrNull()?.also { luts[name.lowercase()] = it }
    }

    /** Drops every cached bitmap (e.g. on `onTrimMemory`). */
    fun trim() {
        lru.evictAll()
    }

    /** Shrinks the cache to [fraction] of its budget (0..1). */
    fun trimTo(fraction: Float) {
        lru.trimToSize((budgetBytes * fraction.coerceIn(0f, 1f)).toInt())
    }

    override fun close() {
        scope.cancel()
        inflight.clear()
        lru.evictAll()
        luts.clear()
    }

    private fun decodeBitmap(dir: String, name: String): Bitmap? {
        val bytes = assets.read(dir, name) ?: return null
        return try {
            val img = if (name.lowercase().endsWith(".bmp")) BmpDecoder.decode(bytes) else TgaDecoder.decode(bytes)
            toBitmap(img)
        } catch (e: Exception) {
            Log.w(TAG, "decode failed: $dir/$name", e)
            null
        } catch (e: OutOfMemoryError) {
            Log.w(TAG, "OOM decoding $dir/$name", e)
            lru.evictAll()
            null
        }
    }

    companion object {
        private const val TAG = "AssetImageCache"

        /** Default memory budget of the bitmap LRU (150 MB). */
        const val DEFAULT_BUDGET_BYTES = 150 * 1024 * 1024

        private fun key(dir: String, name: String) = dir.lowercase() + "/" + normalize(name).lowercase()

        private fun normalize(name: String) = if ('.' in name) name else "$name.tga"

        /**
         * Converts a premultiplied [Wa2Image] into an `ARGB_8888` bitmap (Android bitmaps are
         * premultiplied, and `ARGB_8888` memory order is R,G,B,A = [Wa2Image.toRgbaBytes]).
         */
        fun toBitmap(img: Wa2Image): Bitmap {
            val src = if (img.premultiplied) img else Wa2Image(
                img.width, img.height, IntArray(img.pixels.size) { Wa2Image.premultiply(img.pixels[it]) },
                img.hasAlpha, true,
            )
            val bmp = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
            bmp.copyPixelsFromBuffer(ByteBuffer.wrap(src.toRgbaBytes()))
            bmp.setHasAlpha(src.hasAlpha)
            return bmp
        }
    }
}
