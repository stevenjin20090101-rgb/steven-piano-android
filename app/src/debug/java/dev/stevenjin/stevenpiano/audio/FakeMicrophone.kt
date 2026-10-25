// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.audio

import android.util.Log
import dev.stevenjin.stevenpiano.SystemProperty
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/**
 * The emulator's microphone (debug builds only, v1.26 — M61; [MicrophoneStandIn]): it hears a quiet room and, for each key
 * the key test strikes (its state's key, looked at every 10 ms: [struck]), that key's tone, as the piano's string would
 * sound it (the fundamental and two harmonics, a quick rise, a fall while held and a damper's stop when let go), so the
 * safety check's sweep and Test every key can run to their end on the emulator. `adb shell setprop
 * debug.stevenpiano.silentkeys 36,61` leaves those keys silent (read at each start), so the failure and the lock can be
 * seen; `setprop debug.stevenpiano.silentkeys ''` hears every key again. `setprop debug.stevenpiano.detune 40` plays each
 * key up to 40 cents off its pitch (the same each time: v1.32 — M69, a piano out of tune), `''` in tune again. Frames as
 * [AndroidMicrophone]'s: 100 ms each, on a thread of their own, timed on `System.nanoTime`. Nothing is recorded.
 */
class FakeMicrophone(
    private val struck: () -> StateFlow<KeyTestState>,
    private val silentKeys: () -> Set<Int> = ::silentFromProperty,
    private val detune: () -> Double = ::detuneFromProperty,
) : Microphone {
    private class Hearing(val frames: (MicFrame) -> Unit) {
        @Volatile
        var on = true
    }

    /** A key's sound: its fundamental, and when it began (`System.nanoTime`). */
    private class Tone(val hz: Double, val fromNanos: Long)

    @Volatile
    private var current: Hearing? = null

    @Synchronized
    override fun start(frames: (MicFrame) -> Unit, ended: () -> Unit): Boolean {
        if (current != null) return true
        val hearing = Hearing(frames)
        current = hearing
        val test = struck()
        Thread({
            val silent = silentKeys()   // on this thread: reading a property starts a process
            val cents = detune()
            if (silent.isNotEmpty()) Log.i(TAG, "Silent keys: ${silent.sorted()}")
            if (cents > 0.0) Log.i(TAG, "Keys detuned by up to $cents cents")
            hear(hearing, test, silent, cents)
        }, "steven-piano-fake-mic").apply {
            isDaemon = true
            start()
        }
        return true
    }

    @Synchronized
    override fun stop() {
        current?.on = false
        current = null
    }

    private fun hear(hearing: Hearing, test: StateFlow<KeyTestState>, silent: Set<Int>, cents: Double) {
        val start = System.nanoTime()
        val room = Random(ROOM_SEED)
        val tones = ArrayList<Tone>()
        var seen: Pair<Int, Int>? = null
        var index = 0L
        while (hearing.on) {
            val samples = ShortArray(Microphone.FRAME)
            for (slice in 0 until SLICES) {
                val first = index + slice * SLICE
                // The slice is heard once its time has passed, as a real microphone's would be.
                sleepUntil(start + (first + SLICE) * NANOS_PER_SECOND / Microphone.RATE)
                if (!hearing.on) return
                val now = test.value
                val key = now.key
                if (now.running && key != null && (key to now.done) != seen) {
                    seen = key to now.done
                    if (key !in silent) {
                        val hz = KeyCheck.frequency(key) * 2.0.pow(off(key, cents) / 1200.0)
                        tones += Tone(hz, System.nanoTime() + LATENCY_NANOS)
                    }
                }
                for (i in 0 until SLICE) {
                    val n = first + i
                    val at = start + n * NANOS_PER_SECOND / Microphone.RATE
                    var value = (room.nextDouble() - 0.5) * ROOM
                    for (tone in tones) value += sound(tone, at)
                    samples[slice * SLICE + i] = value.roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                }
                tones.removeAll { System.nanoTime() - it.fromNanos > TONE_NANOS }
            }
            if (!hearing.on) return
            hearing.frames(MicFrame(samples, index, start + index * NANOS_PER_SECOND / Microphone.RATE))
            index += Microphone.FRAME
        }
    }

    /** [tone] at [at] (`System.nanoTime`): its harmonics under the key's envelope. */
    private fun sound(tone: Tone, at: Long): Double {
        val t = (at - tone.fromNanos) / NANOS_PER_SECOND.toDouble()
        if (t < 0.0 || t > TONE_SECONDS) return 0.0
        var envelope = minOf(1.0, t / ATTACK_SECONDS) * exp(-t / DECAY_SECONDS)
        if (t > HELD_SECONDS) envelope *= exp(-(t - HELD_SECONDS) / DAMPER_SECONDS)
        var value = 0.0
        for ((h, weight) in HARMONICS.withIndex()) {
            val hz = tone.hz * (h + 1)
            if (hz >= TOP_HZ) break
            value += weight * sin(2.0 * PI * hz * t)
        }
        return value * envelope * LOUDNESS
    }

    private fun sleepUntil(nanos: Long) {
        val wait = nanos - System.nanoTime()
        if (wait > 0) Thread.sleep(wait / 1_000_000L, (wait % 1_000_000L).toInt())
    }

    companion object {
        private const val TAG = "FakeMicrophone"
        private const val PROPERTY = "debug.stevenpiano.silentkeys"

        /** The keys `debug.stevenpiano.silentkeys` names ("36,61"); none when unset. */
        fun silentFromProperty(): Set<Int> = parse(SystemProperty.read(PROPERTY))

        /** "36, 61" as keys; anything else is left out. */
        fun parse(text: String): Set<Int> = text.split(',', ' ').mapNotNull { it.trim().toIntOrNull() }.filter { it in 0..127 }.toSet()

        private const val DETUNE = "debug.stevenpiano.detune"

        /** How far `debug.stevenpiano.detune` lets each key stray, in cents (0 to 100; 0 when unset or not a number). */
        fun detuneFromProperty(): Double = SystemProperty.read(DETUNE).toDoubleOrNull()?.takeIf { it.isFinite() }?.coerceIn(0.0, 100.0) ?: 0.0

        /** Key [key] off its pitch by up to ±[cents], the same for a key every time. */
        fun off(key: Int, cents: Double): Double = if (cents > 0.0) (Random(DETUNE_SEED + key).nextDouble() * 2.0 - 1.0) * cents else 0.0

        private const val DETUNE_SEED = 69

        private const val NANOS_PER_SECOND = 1_000_000_000L

        /** Ten slices of 10 ms to a frame: a strike is heard within 10 ms of the key test's. */
        private const val SLICES = 10
        private const val SLICE = Microphone.FRAME / SLICES

        /** From the strike to the string sounding. */
        private const val LATENCY_NANOS = 8_000_000L
        private const val TONE_SECONDS = 0.6
        private const val TONE_NANOS = 700_000_000L
        private const val ATTACK_SECONDS = 0.005
        private const val DECAY_SECONDS = 0.3

        /** The key test holds each key this long (KeyTest.HOLD_MS); the damper then stops the string quickly. */
        private const val HELD_SECONDS = KeyTest.HOLD_MS / 1000.0
        private const val DAMPER_SECONDS = 0.03

        /** The fundamental and two harmonics, weighed. */
        private val HARMONICS = doubleArrayOf(1.0, 0.5, 0.25)
        private const val TOP_HZ = 7_500.0

        /** About −16 dBFS for a key, and a room about 55 dB under it. */
        private const val LOUDNESS = 5_000.0
        private const val ROOM = 120.0
        private const val ROOM_SEED = 7
    }
}
