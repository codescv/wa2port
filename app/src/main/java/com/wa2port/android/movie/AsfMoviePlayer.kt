package com.wa2port.android.movie

import android.graphics.Bitmap
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "AsfMoviePlayer"

/**
 * Playback clock: the AudioTrack head position while audio plays, otherwise (no audio, or audio
 * finished before the video) a monotonic wall clock continuing from the last audio time.
 * Pausing freezes both.
 */
private class MovieClock {
    @Volatile private var track: AudioTrack? = null
    @Volatile private var rate = 0
    @Volatile var paused = false
        private set
    private var anchorMs = 0L
    private var anchorNs = System.nanoTime()

    @Synchronized
    fun attach(t: AudioTrack, sampleRate: Int) {
        track = t
        rate = sampleRate
    }

    /** Switches to the wall clock (audio ended or failed), continuing from the current time. */
    @Synchronized
    fun detach() {
        val now = ms()
        track = null
        anchorMs = now
        anchorNs = System.nanoTime()
    }

    @Synchronized
    fun setPaused(p: Boolean) {
        if (p == paused) return
        val now = ms()
        paused = p
        anchorMs = now
        anchorNs = System.nanoTime()
        track?.let { if (p) it.pause() else it.play() }
    }

    @Synchronized
    fun ms(): Long {
        val t = track
        if (t != null && rate > 0) return (t.playbackHeadPosition.toLong() and 0xFFFFFFFFL) * 1000L / rate
        return if (paused) anchorMs else anchorMs + (System.nanoTime() - anchorNs) / 1_000_000L
    }
}

/**
 * Full-screen player for an original WA2 movie ([file] = `mvNN0.pak`, raw ASF) using
 * [NativeMovieDecoder]: audio through [AudioTrack], video paced by the audio clock, letterboxed to
 * the movie's display aspect on black, tap to skip, paused while the host lifecycle is paused.
 *
 * [onFinished] is invoked exactly once, on the main thread: at the end of the movie, on tap, or
 * immediately when the file cannot be opened. Check [NativeMovieDecoder.isAvailable] first if a
 * fallback is wanted.
 */
@Composable
fun AsfMoviePlayer(file: File, onFinished: () -> Unit, modifier: Modifier = Modifier) {
    val finish = rememberUpdatedState(onFinished)
    val done = remember(file) { BooleanArray(1) }
    val complete: () -> Unit = {
        if (!done[0]) {
            done[0] = true
            finish.value()
        }
    }
    val clock = remember(file) { MovieClock() }
    var frame by remember(file) { mutableStateOf<ImageBitmap?>(null) }
    var frameNo by remember(file) { mutableIntStateOf(0) }
    var aspect by remember(file) { mutableStateOf(16f / 9f) }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, clock) {
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_PAUSE -> clock.setPaused(true)
                Lifecycle.Event.ON_RESUME -> clock.setPaused(false)
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    LaunchedEffect(file) {
        val dec = withContext(Dispatchers.IO) {
            runCatching { NativeMovieDecoder.open(file.absolutePath) }
                .onFailure { Log.w(TAG, "cannot play ${file.name}", it) }
                .getOrNull()
        }
        if (dec == null) {
            complete()
            return@LaunchedEffect
        }
        aspect = dec.displayAspect
        try {
            coroutineScope {
                if (dec.hasAudio) launch(Dispatchers.IO) { audioLoop(dec, clock) }
                launch(Dispatchers.Default) {
                    videoLoop(dec, clock) { img ->
                        frame = img
                        frameNo++
                    }
                }
            }
            complete()
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            Log.w(TAG, "playback of ${file.name} failed", t)
            complete()
        } finally {
            withContext(NonCancellable + Dispatchers.IO) { dec.close() }
        }
    }

    Canvas(
        modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(file) { detectTapGestures(onTap = { complete() }) },
    ) {
        // Reading frameNo subscribes the draw to every new frame (the bitmaps are reused).
        if (frameNo < 0 || size == Size.Zero) return@Canvas
        val img = frame ?: return@Canvas
        // Letterbox / pillarbox to the display aspect.
        val (dw, dh) = if (size.width / size.height > aspect) Pair(size.height * aspect, size.height)
        else Pair(size.width, size.width / aspect)
        drawImage(
            image = img,
            srcOffset = IntOffset.Zero,
            srcSize = IntSize(img.width, img.height),
            dstOffset = IntOffset(((size.width - dw) / 2).roundToInt(), ((size.height - dh) / 2).roundToInt()),
            dstSize = IntSize(dw.roundToInt(), dh.roundToInt()),
            filterQuality = FilterQuality.Low,
        )
    }
}

/** Decodes audio and streams it to an [AudioTrack] until the audio ends; then hands the clock to the wall clock. */
private suspend fun audioLoop(dec: NativeMovieDecoder, clock: MovieClock) = coroutineScope {
    val rate = dec.sampleRate
    val minBuf = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
    val track = try {
        AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(rate)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build(),
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(max(minBuf * 2, rate / 5 * 4))  // ≥200 ms
            .build()
    } catch (t: Throwable) {
        Log.w(TAG, "AudioTrack unavailable; playing without sound", t)
        null
    }
    if (track == null) {
        // Keep draining so the demuxer does not park audio packets forever.
        val sink = ShortArray(8192)
        while (isActive && dec.readAudio(sink) > 0) Unit
        return@coroutineScope
    }
    var written = 0L  // frames
    try {
        clock.attach(track, rate)
        if (!clock.paused) track.play()
        val buf = ShortArray(4096)
        var len = 0
        var off = 0
        while (isActive) {
            if (off >= len) {
                len = dec.readAudio(buf)
                off = 0
                if (len <= 0) break
            }
            // Non-blocking so that a paused track never wedges cancellation.
            val n = track.write(buf, off, len - off, AudioTrack.WRITE_NON_BLOCKING)
            if (n < 0) {
                Log.w(TAG, "AudioTrack.write error $n")
                break
            }
            off += n
            written += n / 2
            if (n == 0) delay(10)
        }
        // Let the tail play out, then switch the clock over.
        while (isActive) {
            val head = track.playbackHeadPosition.toLong() and 0xFFFFFFFFL
            if (head >= written || track.playState == AudioTrack.PLAYSTATE_STOPPED) break
            delay(20)
        }
    } finally {
        clock.detach()
        runCatching { track.pause(); track.flush() }
        track.release()
    }
}

/**
 * Decodes video into three rotating bitmaps (one shown, one possibly being uploaded, one being
 * written) and publishes each when the clock reaches its pts; frames already late are skipped.
 */
private suspend fun videoLoop(dec: NativeMovieDecoder, clock: MovieClock, publish: (ImageBitmap) -> Unit) {
    val w = max(dec.width, 1)
    val h = max(dec.height, 1)
    val bitmaps = Array(3) { Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888) }
    val images = Array(3) { bitmaps[it].asImageBitmap() }
    var idx = 0
    var first = true
    var shown = 0
    var lastPts = 0L
    try {
        coroutineScope {
            while (isActive) {
                val now = clock.ms()
                // Skip frames that are more than ~2 frames behind (never the first one).
                val pts = dec.nextVideoFrame(bitmaps[idx], if (first) Long.MIN_VALUE else now - 50)
                if (pts < 0) break
                while (isActive) {
                    val wait = pts - clock.ms()
                    if (wait <= 0) break
                    delay(min(wait, 20L))
                }
                withContext(Dispatchers.Main) { publish(images[idx]) }
                first = false
                shown++
                lastPts = pts
                idx = (idx + 1) % bitmaps.size
            }
        }
    } finally {
        Log.i(TAG, "video: $shown frames shown over $lastPts ms (${if (lastPts > 0) shown * 1000L / lastPts else 0} fps)")
    }
}
