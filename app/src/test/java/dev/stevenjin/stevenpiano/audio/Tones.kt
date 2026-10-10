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
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * A piano as a tablet's microphone hears it, made up for the tests (v1.23 — M55): each key a note of six harmonics falling
 * off, heard 20 ms after it went down, held 150 ms, then damped; and the room's noise.
 */
object Tones {
    const val RATE = KeyCheck.RATE

    private const val LATENCY_S = 0.02
    private const val ATTACK_S = 0.005
    private const val HOLD_S = 0.15
    private const val DAMP_S = 0.06

    /** How long a note is heard for at most, after it went down. */
    const val HEARD_S = 0.8

    /** Key [key]'s note [since] seconds after it went down, full scale 1. */
    fun note(key: Int, since: Double, amplitude: Double = 0.1): Double {
        val t = since - LATENCY_S
        if (t < 0) return 0.0
        val envelope = when {
            t < ATTACK_S -> t / ATTACK_S
            t < HOLD_S -> 1.0
            else -> exp(-(t - HOLD_S) / DAMP_S)
        }
        val fundamental = KeyCheck.frequency(key)
        var v = 0.0
        for (harmonic in 1..6) {
            val hz = fundamental * harmonic
            if (hz >= RATE / 2.0) break
            v += sin(2 * PI * hz * t) / harmonic
        }
        return amplitude * envelope * v
    }

    /** [doubles] as 16-bit samples. */
    fun pcm(doubles: DoubleArray): ShortArray = ShortArray(doubles.size) { (doubles[it].coerceIn(-1.0, 1.0) * 32_767).roundToInt().toShort() }
}
