// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTimestamp
import android.media.MediaRecorder
import android.os.Process
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * 100 ms of the room (v1.23 — M55): [samples], 1,600 of 16-bit mono at 16 kHz; [index], the hearing's sample number of the
 * first; [startNanos], when that sample was heard, on `System.nanoTime`'s clock (the key test lays its strikes on it).
 */
class MicFrame(val samples: ShortArray, val index: Long, val startNanos: Long)

/** The tablet's microphone (v1.23 — M55): one hearing at a time, never written anywhere. */
interface Microphone {
    /**
     * Starts hearing: each 100 ms frame goes to [frames] on the microphone's own thread, and [ended] is called (on that
     * thread) should the hearing stop by itself. False when it can't start (no permission, the microphone busy).
     */
    fun start(frames: (MicFrame) -> Unit, ended: () -> Unit): Boolean

    /** Stops hearing; nothing more comes. */
    fun stop()

    companion object {
        const val RATE = ListenWire.RATE

        /** 100 ms. */
        const val FRAME = RATE / 10
        const val FRAME_NANOS = 100_000_000L
    }
}

/**
 * Android's `AudioRecord` at 16 kHz, mono, 16-bit, from the source meant for recognition (no automatic gain or noise
 * suppression, which would hide a key's note or pump the room), read on its own thread ("steven-piano-mic") a frame at a
 * time. Each frame's time comes from Android's own timestamp where it gives one, else from when the read returned. Only
 * while the app is in the foreground ([Listening] sees to it): Android forbids starting the microphone from the background.
 */
class AndroidMicrophone(private val context: Context) : Microphone {
    /** One hearing: its recorder and where its frames go; [on] until it is stopped. */
    private class Hearing(val record: AudioRecord, val frames: (MicFrame) -> Unit, val ended: () -> Unit) {
        @Volatile
        var on = true
    }

    @Volatile
    private var current: Hearing? = null

    @Synchronized
    override fun start(frames: (MicFrame) -> Unit, ended: () -> Unit): Boolean {
        if (current != null) return true
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return false
        val least = AudioRecord.getMinBufferSize(Microphone.RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (least <= 0) return false
        val made = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                Microphone.RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(least, Microphone.FRAME * BYTES_PER_SAMPLE * BUFFERED_FRAMES),
            )
        } catch (e: SecurityException) {
            return false
        } catch (e: IllegalArgumentException) {
            return false
        }
        if (made.state != AudioRecord.STATE_INITIALIZED) {
            made.release()
            return false
        }
        try {
            made.startRecording()
        } catch (e: IllegalStateException) {
            made.release()
            return false
        }
        if (made.recordingState != AudioRecord.RECORDSTATE_RECORDING) {   // another app holds the microphone
            made.release()
            return false
        }
        val hearing = Hearing(made, frames, ended)
        current = hearing
        Thread({ hear(hearing) }, "steven-piano-mic").apply {
            isDaemon = true
            start()
        }
        return true
    }

    @Synchronized
    override fun stop() {
        val hearing = current ?: return
        current = null
        hearing.on = false
        try {
            hearing.record.stop()   // a read waiting returns; the hearing's thread lets the recorder go
        } catch (e: IllegalStateException) {
            // Stopped already.
        }
    }

    private fun hear(hearing: Hearing) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
        val record = hearing.record
        val stamp = AudioTimestamp()
        var index = 0L
        var failed = false
        try {
            while (hearing.on) {
                val samples = ShortArray(Microphone.FRAME)
                var got = 0
                while (got < samples.size && hearing.on) {
                    val n = record.read(samples, got, samples.size - got)
                    if (n < 0) {
                        failed = true
                        break
                    }
                    got += n
                }
                if (failed || !hearing.on) break
                val heard = if (record.getTimestamp(stamp, AudioTimestamp.TIMEBASE_MONOTONIC) == AudioRecord.SUCCESS) {
                    stamp.nanoTime + (index - stamp.framePosition) * NANOS_PER_SECOND / Microphone.RATE
                } else {
                    System.nanoTime() - Microphone.FRAME_NANOS
                }
                hearing.frames(MicFrame(samples, index, heard))
                index += samples.size
            }
        } catch (e: RuntimeException) {
            Log.w(TAG, "The microphone stopped: ${e.javaClass.simpleName}")
            failed = true
        } finally {
            try {
                record.stop()
            } catch (e: IllegalStateException) {
                // Stopped already.
            }
            record.release()
        }
        if (failed && hearing.on) {
            hearing.on = false
            synchronized(this) { if (current === hearing) current = null }
            hearing.ended()
        }
    }

    private companion object {
        const val TAG = "Microphone"
        const val BYTES_PER_SAMPLE = 2

        /** Android's own buffer: four frames, so a slow moment on the thread loses nothing. */
        const val BUFFERED_FRAMES = 4
        const val NANOS_PER_SECOND = 1_000_000_000L
    }
}
