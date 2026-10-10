// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow

/** One key of a test: the MIDI [key] and whether the microphone [heard] it sound. */
data class KeyResult(val key: Int, val heard: Boolean)

/** A key struck at sample [at] of a recording. */
data class Strike(val key: Int, val at: Int)

/**
 * Whether a struck key sounded, by ear (v1.23 — M55; DESIGN.md › v1.23 — M55): for the key, the energy at its fundamental
 * and its harmonics 2 to 4 (a Goertzel filter over a Hann window each), in the 300 ms from 30 ms after its strike, against
 * the same frequencies in the 120 ms before it; a rise of 6 dB or more on any of them is "sounded", else "not heard".
 * A harmonic over [TOP_HZ] is left out (the 16 kHz capture's edge: B7's fundamental alone is checked). So that a room's
 * noise never passes for a key (the ratio of two noise readings swings far past 6 dB by chance), a rise counts only over
 * the room as well: the before window's whole power, spread over every frequency as noise would be, 6 dB under the
 * baseline at the least ([NOISE_MARGIN]), and never under [FLOOR] (a window with no sound in it at all). Pure.
 */
object KeyCheck {
    /** The capture's rate (16 kHz mono). */
    const val RATE = ListenWire.RATE

    /** The baseline: the 120 ms before the strike. */
    const val BEFORE_MS = 120

    /** The sound: from 30 ms after the strike to 330 ms after it. */
    const val FROM_MS = 30
    const val TO_MS = 330

    /** The fundamental and harmonics 2 to 4. */
    const val HARMONICS = 4

    /** "Sounded": a rise of 6 dB or more, in power (×4). */
    const val RISE_DB = 6.0
    private val RISE = 10.0.pow(RISE_DB / 10.0)

    /** The highest frequency checked, under the capture's anti-aliasing edge at 16 kHz. */
    const val TOP_HZ = 7_200.0

    /** How far over the room's noise a frequency's baseline stands, in power (6 dB). */
    private const val NOISE_MARGIN = 4.0

    /** A Hann window spreads white noise of power σ² over N samples as 1.5 σ² / N in each frequency's reading. */
    private const val HANN_NOISE = 1.5

    /** The least baseline, a sine of about −90 dBFS: a window of digital silence (no sound came) is no baseline. */
    private const val FLOOR = 1e-10

    /** A window shorter than this (a recording cut short) says nothing. */
    private const val MIN_WINDOW_MS = 40

    /** The frequency of MIDI [key] (A4, key 69, at 440 Hz). */
    fun frequency(key: Int): Double = 440.0 * 2.0.pow((key - 69) / 12.0)

    /** The key struck at [at] in [samples] ([rate] a second) sounded: see the object's notes. */
    fun heard(samples: ShortArray, at: Int, key: Int, rate: Int = RATE): Boolean {
        val beforeFrom = (at - BEFORE_MS * rate / 1000).coerceIn(0, samples.size)
        val beforeTo = at.coerceIn(0, samples.size)
        val afterFrom = (at + FROM_MS * rate / 1000).coerceIn(0, samples.size)
        val afterTo = (at + TO_MS * rate / 1000).coerceIn(0, samples.size)
        val least = MIN_WINDOW_MS * rate / 1000
        if (beforeTo - beforeFrom < least || afterTo - afterFrom < least) return false
        val room = meanSquare(samples, beforeFrom, beforeTo) * HANN_NOISE / (afterTo - afterFrom)
        val fundamental = frequency(key)
        for (harmonic in 1..HARMONICS) {
            val hz = fundamental * harmonic
            if (hz > TOP_HZ || hz >= rate / 2.0) break
            val after = power(samples, afterFrom, afterTo, hz, rate)
            val baseline = maxOf(power(samples, beforeFrom, beforeTo, hz, rate), room * NOISE_MARGIN, FLOOR)
            if (after >= baseline * RISE) return true
        }
        return false
    }

    /** Every strike of a recording, in the order given. */
    fun check(samples: ShortArray, strikes: List<Strike>, rate: Int = RATE): List<KeyResult> =
        strikes.map { KeyResult(it.key, heard(samples, it.at, it.key, rate)) }

    /**
     * The power of [hz] in [samples] from [from] to [to] (a Goertzel filter over a Hann window), scaled so that a steady
     * sine of amplitude A (full scale 1) reads A² / 4 whatever the window's length.
     */
    fun power(samples: ShortArray, from: Int, to: Int, hz: Double, rate: Int = RATE): Double {
        val n = to - from
        if (n <= 1) return 0.0
        val coeff = 2.0 * cos(2.0 * PI * hz / rate)
        var s1 = 0.0
        var s2 = 0.0
        var weights = 0.0
        val span = (n - 1).toDouble()
        for (i in 0 until n) {
            val w = 0.5 - 0.5 * cos(2.0 * PI * i / span)
            weights += w
            val s0 = samples[from + i] / FULL_SCALE * w + coeff * s1 - s2
            s2 = s1
            s1 = s0
        }
        val magnitude = s1 * s1 + s2 * s2 - coeff * s1 * s2
        return if (weights > 0.0) magnitude.coerceAtLeast(0.0) / (weights * weights) else 0.0
    }

    /** The mean of the squares of [samples] from [from] to [to], full scale 1. */
    private fun meanSquare(samples: ShortArray, from: Int, to: Int): Double {
        var sum = 0.0
        for (i in from until to) {
            val v = samples[i] / FULL_SCALE
            sum += v * v
        }
        return if (to > from) sum / (to - from) else 0.0
    }

    private const val FULL_SCALE = 32_768.0
}
