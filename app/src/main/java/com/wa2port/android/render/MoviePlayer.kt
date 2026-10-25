package com.wa2port.android.render

import android.media.MediaPlayer
import android.widget.VideoView
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.wa2port.android.movie.AsfMoviePlayer
import com.wa2port.android.movie.NativeMovieDecoder
import com.wa2port.core.archive.Wa2Assets
import java.io.File
import kotlinx.coroutines.delay

/**
 * Locates a playable replacement for engine movie [stem] (`mvNN`). Android has no WMV3/WMAv2
 * decoder, so the original `mvNN0.pak` (ASF) cannot be played; instead the user may drop a
 * transcoded `mvNN.mp4` or `mvNN0.mp4` next to the paks (install root, `IC/` or `movie/`).
 * Lookup is case-insensitive. Returns `null` when none exists.
 */
fun findMovieFile(assets: Wa2Assets, stem: String): File? {
    val names = listOf("$stem.mp4", "${stem}0.mp4", "$stem.webm", "${stem}0.webm")
    for (n in names) {
        assets.file(n)?.takeIf { it.isFile }?.let { return it }
        assets.icFile(n)?.takeIf { it.isFile }?.let { return it }
    }
    val movieDir = assets.root.listFiles()?.firstOrNull { it.isDirectory && it.name.equals("movie", ignoreCase = true) }
    movieDir?.listFiles()?.let { files ->
        for (n in names) files.firstOrNull { it.isFile && it.name.equals(n, ignoreCase = true) }?.let { return it }
    }
    return null
}

/**
 * Full-screen movie for `EngineEvent.PlayMovie(stem)`.
 *
 * Source priority: (1) a user-supplied MP4 found by [findMovieFile] (explicit override, played by
 * [VideoView]); (2) the original `mvNN0.pak` ASF (WMV3/WMAv2) via the native decoder
 * ([AsfMoviePlayer], when [NativeMovieDecoder.isAvailable]); (3) otherwise shows a short "movie unavailable" card for [unavailableMs] (tap to skip).
 * [onFinished] is invoked exactly once — on completion, skip or playback error — after which the host
 * should call `Wa2Engine.movieFinished()` and remove this composable.
 */
@Composable
fun MoviePlayer(
    stem: String,
    assets: Wa2Assets,
    onFinished: () -> Unit,
    modifier: Modifier = Modifier,
    unavailableMs: Long = 1500,
) {
    val finish = rememberUpdatedState(onFinished)
    val done = remember(stem) { BooleanArray(1) }
    val complete: () -> Unit = {
        if (!done[0]) {
            done[0] = true
            finish.value()
        }
    }
    val file = remember(stem, assets) { findMovieFile(assets, stem) }
    val asf = remember(stem, assets) {
        if (file == null && NativeMovieDecoder.isAvailable) assets.movie(stem, 0) else null
    }
    if (asf != null) {
        AsfMoviePlayer(asf, complete, modifier)
    } else Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(stem) { detectTapGestures(onTap = { complete() }) },
        contentAlignment = Alignment.Center,
    ) {
        if (file != null) {
            AndroidView(
                factory = { ctx ->
                    VideoView(ctx).apply {
                        setOnCompletionListener { complete() }
                        setOnErrorListener { _: MediaPlayer, _: Int, _: Int -> complete(); true }
                        setVideoPath(file.absolutePath)
                        start()
                    }
                },
                onRelease = { it.stopPlayback() },
                modifier = Modifier.fillMaxSize(),
            )
            // VideoView consumes touches; keep a transparent layer on top for tap-to-skip.
            Box(Modifier.fillMaxSize().pointerInput(stem) { detectTapGestures(onTap = { complete() }) })
        } else {
            LaunchedEffect(stem) {
                delay(unavailableMs)
                complete()
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("影片无法播放 / ムービーを再生できません", color = Color.White, fontSize = 18.sp, textAlign = TextAlign.Center)
                Text(
                    "Movie \"$stem\" unavailable — place $stem.mp4 next to the game files to enable it.",
                    color = Color(0xFFAAAAAA), fontSize = 13.sp, textAlign = TextAlign.Center,
                )
            }
        }
    }
}
