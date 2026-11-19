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
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * A key's own bands (M83b; the firmware chat's advice from how the hardware behaves): a string is pitched and lasts, a
 * solenoid's click is broadband and over in a few ms. So the loudness steps hear a key only in its bands, its fundamental
 * and harmonics 2 to 4, each [SEMITONES] either side of its equal-tempered frequency: the school piano is out of tune (why
 * M69 made the key test pitch-blind), and a band this wide holds a key 50 cents off. Bass keys carry little at the
 * fundamental: the harmonics carry them. A harmonic at or past the capture's top ([TOP_HZ]) is left out; a band narrower
 * than one bin of the spectrum takes its nearest bin.
 *
 * [Power] measures a stretch of samples in one key's bands, or in every key's from one spectrum (the room's). Powers are
 * mean squares, full scale 1: a sine of amplitude A in a band reads A² / 2. Not thread-safe: one per thread.
 */
object KeyBands {
    /** Each band reaches this far either side of its harmonic. */
    const val SEMITONES = 1.0

    /** The fundamental and harmonics 2 to 4. */
    const val HARMONICS = 4

    /** The highest frequency a band may reach (the 16 kHz capture's 8 kHz less a margin). */
    const val TOP_HZ = 7_800.0

    /** The spectrum's size: 4,096 points (bins 3.9 Hz apart at 16 kHz), the stretch zero-padded. */
    const val SIZE = 4_096

    /** Key [key]'s bands as the bins of a [size]-point spectrum at [rate]. */
    fun bins(key: Int, size: Int = SIZE, rate: Int = KeyCheck.RATE): List<IntRange> {
        val f0 = KeyCheck.frequency(key)
        val spread = 2.0.pow(SEMITONES / 12.0)
        val hz = rate.toDouble() / size
        return (1..HARMONICS).mapNotNull { h ->
            val centre = f0 * h
            val high = centre * spread
            if (high > TOP_HZ) return@mapNotNull null
            val from = kotlin.math.ceil(centre / spread / hz).toInt()
            val to = kotlin.math.floor(high / hz).toInt()
            if (to >= from) from..to else (centre / hz).roundToInt().let { it..it }
        }
    }

    /** A stretch's power in a key's bands: Hann-windowed, zero-padded to [SIZE], at [rate]. */
    class Power(private val rate: Int = KeyCheck.RATE) {
        private val re = DoubleArray(SIZE)
        private val im = DoubleArray(SIZE)
        private val cosines = DoubleArray(SIZE / 2) { cos(2.0 * PI * it / SIZE) }
        private val sines = DoubleArray(SIZE / 2) { -sin(2.0 * PI * it / SIZE) }
        private val byKey = HashMap<Int, List<IntRange>>()

        /** [count] samples of [samples] from [from], in key [key]'s bands. */
        fun of(samples: ShortArray, from: Int, count: Int, key: Int): Double {
            val scale = spectrum(samples, from, count)
            return inBands(key, scale)
        }

        /** The same stretch in every key of [keys]'s bands, from one spectrum. */
        fun ofEach(samples: ShortArray, from: Int, count: Int, keys: IntRange): Map<Int, Double> {
            val scale = spectrum(samples, from, count)
            return keys.associateWith { inBands(it, scale) }
        }

        private fun inBands(key: Int, scale: Double): Double {
            var sum = 0.0
            for (band in byKey.getOrPut(key) { bins(key, SIZE, rate) }) {
                for (k in band) sum += 2.0 * (re[k] * re[k] + im[k] * im[k])
            }
            return sum * scale
        }

        /** The stretch's spectrum into [re] and [im]; the scale that makes its bins mean squares. */
        private fun spectrum(samples: ShortArray, from: Int, count: Int): Double {
            val n = count.coerceIn(0, SIZE)
            var weights = 0.0
            for (i in 0 until SIZE) {
                if (i < n) {
                    val w = 0.5 - 0.5 * cos(2.0 * PI * i / n)
                    re[i] = samples[from + i] / FULL_SCALE * w
                    weights += w * w
                } else {
                    re[i] = 0.0
                }
                im[i] = 0.0
            }
            transform()
            return if (weights > 0.0) 1.0 / (SIZE * weights) else 0.0
        }

        /** An in-place radix-2 FFT of [re] and [im]. */
        private fun transform() {
            var j = 0
            for (i in 1 until SIZE) {
                var bit = SIZE shr 1
                while (j and bit != 0) {
                    j = j xor bit
                    bit = bit shr 1
                }
                j = j xor bit
                if (i < j) {
                    var t = re[i]
                    re[i] = re[j]
                    re[j] = t
                    t = im[i]
                    im[i] = im[j]
                    im[j] = t
                }
            }
            var length = 2
            while (length <= SIZE) {
                val half = length / 2
                val step = SIZE / length
                var start = 0
                while (start < SIZE) {
                    for (k in 0 until half) {
                        val a = start + k
                        val b = a + half
                        val c = cosines[k * step]
                        val s = sines[k * step]
                        val xr = re[b] * c - im[b] * s
                        val xi = re[b] * s + im[b] * c
                        re[b] = re[a] - xr
                        im[b] = im[a] - xi
                        re[a] += xr
                        im[a] += xi
                    }
                    start += length
                }
                length = length shl 1
            }
        }
    }

    private const val FULL_SCALE = 32_768.0
}
