package com.wa2port.android.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import com.wa2port.core.archive.FileSlice
import com.wa2port.core.archive.Wa2Assets
import com.wa2port.core.engine.EngineEvent
import com.wa2port.core.engine.SE_ONESHOT_CHANNEL
import com.wa2port.core.engine.VoiceRef
import java.io.FileInputStream

/**
 * Plays WA2 audio straight out of the LAC archives (`BGM.PAK`, `SE.PAK`, `VOICE.PAK`) with
 * [MediaPlayer] reading byte ranges of the archive file (no extraction).
 *
 * * **BGM**: `BGM_nnn_A` intro once, then `BGM_nnn_B` looping, chained gaplessly with
 *   [MediaPlayer.setNextMediaPlayer]; fade-in/out and cross-fade between tracks.
 * * **Voice**: one-shot, a new voice stops the previous one; [isVoicePlaying] for AUTO mode.
 * * **SE**: one-shots on [SE_ONESHOT_CHANNEL] may overlap; other channels (ambient 0..3, …) hold one
 *   sound each (looping or not) with fade/volume control.
 *
 * All work runs on a private audio thread, so [handle] is cheap to call from the game loop.
 * Volumes: engine volumes are 0..255, master volumes ([setVolumes]) 0..1.
 */
class AudioEngine(context: Context, private val assets: Wa2Assets) {
    private val thread = HandlerThread("wa2-audio").apply { start() }
    private val handler = Handler(thread.looper)

    @Volatile private var bgmMaster = 1f
    @Volatile private var seMaster = 1f
    @Volatile private var voiceMaster = 1f

    /** True while a voice clip is playing. */
    @Volatile var isVoicePlaying: Boolean = false
        private set

    private var paused = false
    private var released = false

    // ---- state (audio thread only)
    private var bgm: Track? = null
    private val fadingOut = ArrayList<Track>()
    private var voice: Track? = null
    private val seChannels = HashMap<Int, Track>()
    private val oneShots = ArrayList<Track>()
    private var ticking = false

    /** Dispatches one engine audio event (non-audio events are ignored). Thread-safe. */
    fun handle(event: EngineEvent) {
        post {
            when (event) {
                is EngineEvent.PlayBgm -> playBgm(event)
                is EngineEvent.StopBgm -> bgm?.let { fadeOutAndRelease(it, event.fadeMs); bgm = null }
                is EngineEvent.PlaySe -> playSe(event)
                is EngineEvent.StopSe -> seChannels.remove(event.channel)?.let { fadeOutAndRelease(it, event.fadeMs) }
                is EngineEvent.SeVolume -> seChannels[event.channel]?.fadeTo(event.volume / 255f, event.fadeMs)
                is EngineEvent.PlayVoice -> playVoice(event.voice)
                EngineEvent.StopVoice -> stopVoice()
                else -> {}
            }
            ensureTicking()
        }
    }

    /** Replays a voice clip (backlog ▶ button). Thread-safe. */
    fun replayVoice(ref: VoiceRef) {
        post { playVoice(ref) }
    }

    /** Sets master volumes (0..1) for BGM, sound effects and voice. Thread-safe. */
    fun setVolumes(bgm: Float, se: Float, voice: Float) {
        bgmMaster = bgm.coerceIn(0f, 1f)
        seMaster = se.coerceIn(0f, 1f)
        voiceMaster = voice.coerceIn(0f, 1f)
        post { allTracks().forEach { it.applyVolume() } }
    }

    /** Pauses everything (activity `onPause`). */
    fun pause() {
        post {
            paused = true
            allTracks().forEach { it.pause() }
        }
    }

    /** Resumes what [pause] paused (activity `onResume`). */
    fun resume() {
        post {
            paused = false
            allTracks().forEach { it.resume() }
            ensureTicking()
        }
    }

    /** Stops all sound immediately (e.g. returning to the title screen). */
    fun stopAll() {
        post { releaseAll() }
    }

    /** Releases every player and the audio thread. The engine cannot be used afterwards. */
    fun release() {
        post {
            releaseAll()
            released = true
            thread.quitSafely()
        }
    }

    // ------------------------------------------------------------------ internals (audio thread)

    private fun post(block: () -> Unit) {
        if (!released) handler.post { if (!released) block() }
    }

    private fun allTracks(): List<Track> =
        listOfNotNull(bgm, voice) + fadingOut + seChannels.values + oneShots

    private fun releaseAll() {
        allTracks().forEach { it.release() }
        bgm = null
        voice = null
        fadingOut.clear()
        seChannels.clear()
        oneShots.clear()
        isVoicePlaying = false
    }

    private fun playBgm(e: EngineEvent.PlayBgm) {
        val volume = e.volume.coerceIn(0, 255) / 255f
        val cur = bgm
        if (cur != null && cur.key == e.loop) {
            cur.fadeTo(volume, e.fadeMs) // same track: only adjust volume
            return
        }
        cur?.let { fadeOutAndRelease(it, e.fadeMs) }
        bgm = null
        val loop = createPlayer("BGM", e.loop) ?: return
        loop.isLooping = true
        val intro = e.intro?.let { createPlayer("BGM", it) }
        val track = Track(e.loop, Kind.BGM, intro ?: loop, if (intro != null) loop else null)
        track.level = if (e.fadeMs > 0) 0f else volume
        track.applyVolume()
        if (intro != null) {
            // Gapless chain; if the platform refuses, start the loop manually when the intro completes.
            val chained = runCatching { intro.setNextMediaPlayer(loop) }.isSuccess
            intro.setOnCompletionListener { if (track.active === intro) track.promoteNext(start = !chained) }
        }
        track.start(paused)
        if (e.fadeMs > 0) track.fadeTo(volume, e.fadeMs)
        bgm = track
    }

    private fun playSe(e: EngineEvent.PlaySe) {
        val volume = e.volume.coerceIn(0, 255) / 255f
        val mp = createPlayer("SE", e.name) ?: return
        mp.isLooping = e.loop
        val track = Track(e.name, Kind.SE, mp, null)
        track.level = if (e.fadeMs > 0) 0f else volume
        track.applyVolume()
        if (e.channel == SE_ONESHOT_CHANNEL && !e.loop) {
            oneShots += track
            mp.setOnCompletionListener { oneShots.remove(track); track.release() }
        } else {
            seChannels.remove(e.channel)?.release()
            seChannels[e.channel] = track
            if (!e.loop) mp.setOnCompletionListener { if (seChannels[e.channel] === track) seChannels.remove(e.channel); track.release() }
        }
        track.start(paused)
        if (e.fadeMs > 0) track.fadeTo(volume, e.fadeMs)
    }

    private fun playVoice(ref: VoiceRef) {
        stopVoice()
        val mp = createPlayer("VOICE", ref.file) ?: return
        val track = Track(ref.file, Kind.VOICE, mp, null)
        track.level = 1f
        track.applyVolume()
        mp.setOnCompletionListener {
            if (voice === track) {
                voice = null
                isVoicePlaying = false
            }
            track.release()
        }
        voice = track
        isVoicePlaying = true
        track.start(paused)
    }

    private fun stopVoice() {
        voice?.release()
        voice = null
        isVoicePlaying = false
    }

    private fun fadeOutAndRelease(t: Track, fadeMs: Long) {
        if (fadeMs <= 0) {
            t.release()
            return
        }
        fadingOut += t
        t.fadeTo(0f, fadeMs) {
            fadingOut.remove(t)
            t.release()
        }
    }

    /** Opens `group/name` from its archive as a prepared [MediaPlayer], or null when missing/broken. */
    private fun createPlayer(group: String, name: String): MediaPlayer? {
        val slice: FileSlice = assets.audio(group, name) ?: run {
            Log.w(TAG, "missing audio $group/$name")
            return null
        }
        val mp = MediaPlayer()
        return try {
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(if (group == "VOICE") AudioAttributes.CONTENT_TYPE_SPEECH else AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            // The descriptor may be closed as soon as setDataSource returns.
            FileInputStream(slice.file).use { mp.setDataSource(it.fd, slice.offset, slice.length) }
            mp.prepare()
            mp
        } catch (e: Exception) {
            Log.w(TAG, "cannot play $group/$name", e)
            mp.release()
            null
        }
    }

    private fun ensureTicking() {
        if (ticking) return
        ticking = true
        handler.postDelayed(tick, TICK_MS)
    }

    private val tick = object : Runnable {
        override fun run() {
            if (released) return
            val now = SystemClock.uptimeMillis()
            var any = false
            for (t in allTracks()) if (t.stepFade(now)) any = true
            if (any) handler.postDelayed(this, TICK_MS) else ticking = false
        }
    }

    private enum class Kind { BGM, SE, VOICE }

    /** A playing sound: [active] player (+ queued [next] for intro→loop BGM) with a fade envelope. */
    private inner class Track(val key: String, val kind: Kind, var active: MediaPlayer, var next: MediaPlayer?) {
        /** Current fade level 0..1 (multiplied by the master volume). */
        var level = 1f
        private var fadeFrom = 1f
        private var fadeTarget = 1f
        private var fadeStart = 0L
        private var fadeMs = 0L
        private var fadeDone: (() -> Unit)? = null
        private var wasPlaying = false
        private var dead = false

        fun master() = when (kind) {
            Kind.BGM -> bgmMaster
            Kind.SE -> seMaster
            Kind.VOICE -> voiceMaster
        }

        fun applyVolume() {
            if (dead) return
            val v = (level * master()).coerceIn(0f, 1f)
            runCatching { active.setVolume(v, v) }
            next?.let { n -> runCatching { n.setVolume(v, v) } }
        }

        fun start(startPaused: Boolean) {
            if (startPaused) {
                wasPlaying = true
            } else {
                runCatching { active.start() }
            }
        }

        fun promoteNext(start: Boolean) {
            val n = next ?: return
            val old = active
            active = n
            next = null
            applyVolume()
            if (start && !paused) runCatching { n.start() }
            runCatching { old.release() }
        }

        fun fadeTo(target: Float, ms: Long, done: (() -> Unit)? = null) {
            fadeDone?.invoke().also { fadeDone = null }
            if (ms <= 0) {
                level = target
                applyVolume()
                done?.invoke()
                return
            }
            fadeFrom = level
            fadeTarget = target
            fadeStart = SystemClock.uptimeMillis()
            fadeMs = ms
            fadeDone = done
            ensureTicking()
        }

        /** Advances the fade; returns true while still fading. */
        fun stepFade(now: Long): Boolean {
            if (dead || fadeMs <= 0) return false
            val p = ((now - fadeStart).toFloat() / fadeMs).coerceIn(0f, 1f)
            level = fadeFrom + (fadeTarget - fadeFrom) * p
            applyVolume()
            if (p >= 1f) {
                fadeMs = 0
                val d = fadeDone
                fadeDone = null
                d?.invoke()
                return false
            }
            return !paused
        }

        fun pause() {
            wasPlaying = runCatching { active.isPlaying }.getOrDefault(false) || wasPlaying
            runCatching { if (active.isPlaying) active.pause() }
        }

        fun resume() {
            if (wasPlaying && !dead) runCatching { active.start() }
            wasPlaying = false
        }

        fun release() {
            if (dead) return
            dead = true
            runCatching { active.stop() }
            runCatching { active.release() }
            next?.let { runCatching { it.release() } }
            next = null
        }
    }

    private companion object {
        const val TAG = "AudioEngine"
        const val TICK_MS = 20L
    }
}
