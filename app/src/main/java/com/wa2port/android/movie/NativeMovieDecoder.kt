package com.wa2port.android.movie

import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.Closeable
import java.io.IOException

/**
 * Decoder for the original WA2 movies (`mvNN0.pak` = raw ASF: WMV3 video + WMA v2 audio) backed by a
 * minimal LGPL FFmpeg in `libwa2movie.so`.
 *
 * Threading: [nextVideoFrame] and [readAudio] may run concurrently on two different threads (one
 * each); [close] must only be called once both have returned for good.
 */
class NativeMovieDecoder private constructor(private var handle: Long) : Closeable {

    /** Coded video size in pixels. */
    val width: Int

    val height: Int

    /** Display aspect ratio (width / height) including the sample aspect ratio (SD movies are 40:33). */
    val displayAspect: Float

    /** Container duration in ms, or -1 when unknown. */
    val durationMs: Long

    /** Output sample rate of [readAudio] (interleaved stereo s16), or 0 when there is no audio. */
    val sampleRate: Int

    val hasAudio: Boolean get() = sampleRate > 0

    init {
        val info = LongArray(6)
        nativeInfo(handle, info)
        width = info[0].toInt()
        height = info[1].toInt()
        val sar = if (info[2] > 0 && info[3] > 0) info[2].toFloat() / info[3] else 1f
        displayAspect = if (height > 0) width * sar / height else 16f / 9f
        durationMs = info[4]
        sampleRate = info[5].toInt()
    }

    /**
     * Decodes the next video frame, converts it to RGBA and writes it into [into] (an ARGB_8888
     * bitmap, normally [width]×[height]; other sizes are scaled). Frames that end before
     * [dropBeforeMs] are decoded but skipped (catch-up when rendering lags).
     *
     * @return presentation time in ms, or -1 at the end of the video stream.
     */
    fun nextVideoFrame(into: Bitmap, dropBeforeMs: Long = Long.MIN_VALUE): Long {
        check(handle != 0L) { "closed" }
        require(into.config == Bitmap.Config.ARGB_8888 && into.isMutable) { "need a mutable ARGB_8888 bitmap" }
        val pts = nativeNextVideoFrame(handle, into, dropBeforeMs)
        if (pts == -2L) throw IOException("bitmap conversion failed")
        return pts
    }

    /**
     * Fills [out] with interleaved stereo 16-bit PCM at [sampleRate].
     *
     * @return number of shorts written (always even), or -1 at the end of the audio stream.
     */
    fun readAudio(out: ShortArray): Int {
        check(handle != 0L) { "closed" }
        if (!hasAudio) return -1
        return nativeReadAudio(handle, out)
    }

    @Synchronized
    override fun close() {
        val h = handle
        handle = 0
        if (h != 0L) nativeClose(h)
    }

    companion object {
        private const val TAG = "NativeMovieDecoder"

        /** True when `libwa2movie.so` and its FFmpeg dependencies loaded on this device. */
        val isAvailable: Boolean by lazy {
            try {
                System.loadLibrary("wa2movie")
                true
            } catch (t: Throwable) {
                Log.w(TAG, "libwa2movie unavailable", t)
                false
            }
        }

        /** Opens the movie at filesystem [path]. Throws [IOException] when it cannot be decoded. */
        @JvmStatic
        fun open(path: String): NativeMovieDecoder {
            if (!isAvailable) throw IOException("libwa2movie not loaded")
            val h = nativeOpen(path)
            if (h == 0L) throw IOException("cannot open movie $path")
            return NativeMovieDecoder(h)
        }

        /** Opens the movie behind [pfd] (e.g. a SAF document). The descriptor is dup'ed; caller keeps ownership. */
        @JvmStatic
        fun open(pfd: ParcelFileDescriptor): NativeMovieDecoder {
            if (!isAvailable) throw IOException("libwa2movie not loaded")
            val h = nativeOpenFd(pfd.fd)
            if (h == 0L) throw IOException("cannot open movie fd ${pfd.fd}")
            return NativeMovieDecoder(h)
        }

        @JvmStatic private external fun nativeOpen(path: String): Long
        @JvmStatic private external fun nativeOpenFd(fd: Int): Long
        @JvmStatic private external fun nativeClose(handle: Long)
        @JvmStatic private external fun nativeInfo(handle: Long, out: LongArray)
        @JvmStatic private external fun nativeNextVideoFrame(handle: Long, bitmap: Bitmap, dropBeforeMs: Long): Long
        @JvmStatic private external fun nativeReadAudio(handle: Long, out: ShortArray): Int
    }
}
