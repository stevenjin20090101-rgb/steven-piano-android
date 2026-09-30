// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import java.util.concurrent.locks.LockSupport

/**
 * The tablet's speaker for the [PianoVoice] (v1.8 — M25): an [AudioTrack] of float PCM, stereo (the mono
 * mix on both sides), at the device's own output rate (48 or 44.1 kHz), in low-latency mode with about
 * [BUFFER_MS] of buffer, fed by its own thread at `THREAD_PRIORITY_URGENT_AUDIO` ("steven-piano-sound")
 * a burst at a time with blocking writes. The thread waits, parked, while nothing sounds: a note queued
 * wakes it ([PianoVoice.onPost]), it applies the queue and, once a voice sounds (or [keepOpen] says a
 * piece is playing), asks for transient audio focus and opens the track; after [IDLE_MS] of silence
 * with nothing keeping it open it closes the track and gives the focus back. Focus refused or lost, the
 * voices go at once and only a new note (not a piece still playing) opens the output again. Underruns are counted
 * ([AudioTrack.getUnderrunCount]); each new one grows the buffer by a burst, up to its capacity. The
 * media volume stream governs it (usage media, content music), and Android ducks it under a
 * notification by itself. [onFocusLost] hears a loss of focus (or a refusal) on the main thread: the
 * owner pauses playback there; the output has already faded the voice out and closed.
 */
class AudioOut(
    context: Context,
    private val voice: PianoVoice,
    private val keepOpen: () -> Boolean,
    private val onFocusLost: () -> Unit,
    private val log: (String) -> Unit = {},
) {
    private val audio: AudioManager = context.getSystemService(AudioManager::class.java)
    private val main = Handler(Looper.getMainLooper())

    /** The device's own output rate (what the voice renders at): 48 kHz unless the device says 44.1. */
    val rate: Int = nativeRate(audio)
    private val burst: Int = (audio?.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)?.toIntOrNull() ?: 0)
        .takeIf { it in 32..2048 } ?: (rate * DEFAULT_BURST_MS / 1000)

    @Volatile
    private var running = true

    @Volatile
    private var focusLost = false

    /** After a loss of focus a piece playing no longer keeps the output open: only a new note opens it again. */
    @Volatile
    private var heldOff = false

    /** Sessions, underruns and the most voices at once, for the log and the evidence. */
    @Volatile
    var underruns = 0
        private set

    @Volatile
    var open = false
        private set

    @Volatile
    var maxVoices = 0
        private set

    private val thread = Thread(::loop, "steven-piano-sound").apply { isDaemon = true }

    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()

    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        .setAudioAttributes(attributes)
        .setOnAudioFocusChangeListener({ change -> onFocusChange(change) }, main)
        .build()

    fun start() {
        voice.onPost = { LockSupport.unpark(thread) }
        thread.start()
    }

    /** The owner's state changed ([keepOpen] may say otherwise now): the thread looks again. */
    fun poke() = LockSupport.unpark(thread)

    private fun loop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        while (running) {
            voice.drain()
            val sounding = voice.sounding()
            if (sounding) heldOff = false
            if (sounding || (keepOpen() && !heldOff)) {
                play()
            } else {
                LockSupport.park(this)
            }
        }
    }

    /** One session: focus, the track, rendering until [IDLE_MS] of silence with nothing keeping it open. */
    private fun play() {
        focusLost = false
        if (audio.requestAudioFocus(focusRequest) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            log("Tablet sound: audio focus refused")
            stopped()
            main.post(onFocusLost)
            return
        }
        val track = try {
            makeTrack()
        } catch (e: Exception) {
            log("Tablet sound: the output couldn't open: ${e.message}")
            audio.abandonAudioFocusRequest(focusRequest)
            stopped()
            return
        }
        val mono = FloatArray(burst)
        val stereo = FloatArray(burst * 2)
        var lastSound = SystemClock.elapsedRealtime()
        val startUnderruns = track.underrunCount
        var seenUnderruns = startUnderruns
        open = true
        log("Tablet sound: open at $rate Hz, ${track.bufferSizeInFrames} frames of buffer, bursts of $burst")
        try {
            track.play()
            while (running && !focusLost) {
                val sounding = voice.render(mono, burst)
                val voices = voice.voices()
                if (voices > maxVoices) maxVoices = voices
                for (i in 0 until burst) {
                    stereo[2 * i] = mono[i]
                    stereo[2 * i + 1] = mono[i]
                }
                val written = track.write(stereo, 0, stereo.size, AudioTrack.WRITE_BLOCKING)
                if (written < 0) {
                    log("Tablet sound: the output failed ($written)")
                    break
                }
                val now = SystemClock.elapsedRealtime()
                if (sounding || voice.pending()) lastSound = now
                else if (now - lastSound > IDLE_MS && !keepOpen()) break
                val count = track.underrunCount
                if (count > seenUnderruns) {
                    underruns += count - seenUnderruns
                    seenUnderruns = count
                    val grown = (track.bufferSizeInFrames + burst).coerceAtMost(track.bufferCapacityInFrames)
                    track.bufferSizeInFrames = grown
                    log("Tablet sound: underrun; the buffer is now $grown frames")
                }
            }
        } finally {
            open = false
            if (focusLost) stopped()
            runCatching { track.pause() }
            runCatching { track.flush() }
            track.release()
            audio.abandonAudioFocusRequest(focusRequest)
            log("Tablet sound: closed (${seenUnderruns - startUnderruns} underruns, at most $maxVoices voices)")
        }
    }

    /** Nothing more will be heard of what sounds now: the voices go at once, and a piece playing no longer keeps the output open. */
    private fun stopped() {
        voice.reset()
        voice.drain()
        heldOff = true
    }

    private fun makeTrack(): AudioTrack {
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
            .setSampleRate(rate)
            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
            .build()
        val minBytes = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_FLOAT)
        val wantFrames = rate * BUFFER_MS / 1000
        val bytes = maxOf(minBytes, wantFrames * BYTES_PER_FRAME * 2)   // room to grow after an underrun
        val track = AudioTrack.Builder()
            .setAudioAttributes(attributes)
            .setAudioFormat(format)
            .setBufferSizeInBytes(bytes)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .build()
        // About 20 ms, never less than two bursts, never more than the track holds.
        track.bufferSizeInFrames = maxOf(wantFrames, burst * 2).coerceAtMost(track.bufferCapacityInFrames)
        return track
    }

    private fun onFocusChange(change: Int) {
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                log("Tablet sound: audio focus lost ($change)")
                focusLost = true
                heldOff = true
                LockSupport.unpark(thread)
                onFocusLost()
            }
            // AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK: Android lowers the track by itself (API 26+). GAIN: nothing to do.
        }
    }

    /** Ends the thread (the process is going). */
    fun shutdown() {
        running = false
        LockSupport.unpark(thread)
    }

    companion object {
        /** The buffer asked for. */
        const val BUFFER_MS = 20

        /** A burst when the device doesn't say its own. */
        private const val DEFAULT_BURST_MS = 5

        /** Silence this long closes the output (and gives the focus back). */
        const val IDLE_MS = 3_000L

        private const val BYTES_PER_FRAME = 8   // float stereo

        /** The device's output rate when it is 44.1 or 48 kHz, else 48 kHz. */
        fun nativeRate(audio: AudioManager?): Int {
            val said = audio?.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull()
            return if (said == 44_100 || said == 48_000) said else 48_000
        }
    }
}
