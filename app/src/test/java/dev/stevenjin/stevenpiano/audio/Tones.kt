// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.audio

import java.util.Random
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A piano as a tablet's microphone hears it, made up for the tests (v1.23 — M55): each key a note of six harmonics falling
 * off, heard 20 ms after it went down, held 150 ms, then damped; and the room's noise. Since v1.32 — M69 a note can also be
 * out of tune (cents off), its overtones stretched as a real string's are, and undamped (the top keys ring on).
 */
object Tones {
    const val RATE = KeyCheck.RATE

    private const val LATENCY_S = 0.02
    private const val ATTACK_S = 0.005
    private const val HOLD_S = 0.15
    private const val DAMP_S = 0.06
    private const val HARMONICS = 6

    /** How long a damped note is heard for at most, after it went down. */
    const val HEARD_S = 0.8

    /**
     * Key [key]'s note [since] seconds after it went down, full scale 1: [amplitude] its level, [cents] off its equal-tempered
     * pitch, its overtones stretched by a string's inharmonicity [stretch] (the nth at n·f0·√(1 + B·n²)); with [ring] (s)
     * undamped, falling by e every [ring] s from the strike instead of being damped when let go.
     */
    fun note(key: Int, since: Double, amplitude: Double = 0.1, cents: Double = 0.0, stretch: Double = 0.0, ring: Double = 0.0): Double {
        val t = since - LATENCY_S
        if (t < 0) return 0.0
        val rise = minOf(1.0, t / ATTACK_S)
        val envelope = when {
            ring > 0.0 -> rise * exp(-t / ring)
            t < HOLD_S -> rise
            else -> exp(-(t - HOLD_S) / DAMP_S)
        }
        val fundamental = KeyCheck.frequency(key) * 2.0.pow(cents / 1200.0)
        var v = 0.0
        for (harmonic in 1..HARMONICS) {
            val hz = harmonic * fundamental * sqrt(1.0 + stretch * harmonic * harmonic)
            if (hz >= RATE / 2.0) break
            v += sin(2 * PI * hz * t) / harmonic
        }
        return amplitude * envelope * v
    }

    /** How long a note is heard for after it went down: [HEARD_S] damped, about 60 dB of its fall when it rings on ([ring] s). */
    fun heardFor(ring: Double): Double = if (ring > 0.0) maxOf(HEARD_S, 7.0 * ring) else HEARD_S

    /** A piano string's inharmonicity for [key]: about 1e-4 in the bass to 1e-3 in the treble. */
    fun stretch(key: Int): Double = 1e-4 * 10.0.pow((key - 24) / 83.0)

    /** Each of the key test's keys off its pitch by up to ±[cents], at random from [seed]. */
    fun detune(cents: Double, seed: Long): Map<Int, Double> {
        val random = Random(seed)
        return KeyTest.KEYS.associateWith { (random.nextDouble() * 2.0 - 1.0) * cents }
    }

    /** [doubles] as 16-bit samples. */
    fun pcm(doubles: DoubleArray): ShortArray = ShortArray(doubles.size) { (doubles[it].coerceIn(-1.0, 1.0) * 32_767).roundToInt().toShort() }
}
