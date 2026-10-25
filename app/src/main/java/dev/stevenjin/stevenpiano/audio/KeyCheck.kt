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

/** One key of a test: the MIDI [key] and whether the microphone [heard] it sound. */
data class KeyResult(val key: Int, val heard: Boolean)

/** A key struck at sample [at] of a recording. */
data class Strike(val key: Int, val at: Int)

/**
 * Whether a struck key sounded, by ear (v1.32 — M69; DESIGN.md › v1.32 — M69: any sound): a clear new sound starts right
 * after its strike, whatever its pitch, so a piano out of tune passes. (M55's rule listened at the key's own
 * equal-tempered frequencies, which an old piano's upper keys miss.)
 *
 * The sound is read as spectra of [SPECTRUM_MS], one every [HOP_MS] (bins 31 Hz apart at 16 kHz):
 * - **Before**: each frequency's loudest in the [BEFORE_MS] before the strike, averaged with its [NEIGHBOURS] on each
 *   side; and each band's mean level there.
 * - **After**: each frequency's level over [SMOOTH] spectra in turn (about 90 ms), from [FROM_MS] to [TO_MS] after it.
 * - **New sound**: what each frequency gained beyond [RISE_DB] over its loudest before, summed in three bands (60 to 500
 *   Hz, 500 to 2,000 Hz, 2,000 Hz to the capture's 8 kHz). Heard once a band's new sound reaches [SHARE] of the band's
 *   level before (6 dB under it) and [FLOOR]; else "not heard".
 *
 * Frequency by frequency, a new note shows even while the keys before it still ring (the top keys have no dampers); the
 * loudest-before and the averaging keep a room's steady noise, rumble and hum from passing for a key. Under about 500 Hz
 * a key and the one before it share bins (a semitone is narrower than one), so there a key shows by sounding 6 dB over
 * what the key before still rang in the 120 ms before it: its damper must have quietened it. Still not heard: a key
 * already held down (no new blow), a dead channel, a steady room, digital silence, and any key whose audio before the
 * strike is missing ([SILENT_MS] or more of exact zeros, where [KeyTake] filled a gap): with no baseline, the safety
 * check's second sweep decides.
 *
 * The accepted trade-off: a solenoid's click counts as a sound (it proves the channel moved), and a loud sound in the
 * room at the very moment of a strike can count as that key. A key stuck on is still caught by the coil count
 * (`!active`) and by the power plug's watch and its usual idle. Pure, and cheap enough for the microphone's thread: 36
 * spectra of 512 points a key.
 */
object KeyCheck {
    /** The capture's rate (16 kHz mono). */
    const val RATE = ListenWire.RATE

    /** The baseline: the 120 ms before the strike (the key before was let go 300 ms before it). */
    const val BEFORE_MS = 120

    /** The sound: from 30 ms after the strike to 330 ms after it (the next strike comes at 450 ms). */
    const val FROM_MS = 30
    const val TO_MS = 330

    /** A frequency's new sound: what it gained beyond 6 dB (×4 in power) over its loudest before the strike. */
    const val RISE_DB = 6.0
    private val RISE = 10.0.pow(RISE_DB / 10.0)

    /** Heard: a band's new sound at least a quarter of the band's level before the strike (6 dB under it). */
    const val SHARE = 0.25

    /** The least new sound that counts, in power (full scale 1; about −70 dB), so near-silence never passes for a key. */
    const val FLOOR = 1e-7

    /** One spectrum: 32 ms (512 samples at 16 kHz), Hann-windowed; one every 10 ms. */
    private const val SPECTRUM_MS = 32
    private const val HOP_MS = 10

    /** The level after: the mean of 7 spectra in turn (about 90 ms of sound). */
    private const val SMOOTH = 7

    /** The loudest before, averaged with 2 neighbours on each side (±62 Hz at 16 kHz). */
    private const val NEIGHBOURS = 2

    /** The bands' lower edges, Hz: rumble and DC under the first are left out; the last band reaches the capture's top. */
    private val EDGES_HZ = doubleArrayOf(60.0, 500.0, 2_000.0)

    /** This much digital silence (exact zeros) before the strike is audio missing, not a quiet room. */
    private const val SILENT_MS = 10

    /** The frequency of MIDI [key] (A4, key 69, at 440 Hz). */
    fun frequency(key: Int): Double = 440.0 * 2.0.pow((key - 69) / 12.0)

    /** The key struck at [at] in [samples] ([rate] a second) sounded: see the object's notes. Any key: [key] isn't used. */
    @Suppress("UNUSED_PARAMETER")
    fun heard(samples: ShortArray, at: Int, key: Int, rate: Int = RATE): Boolean {
        val size = (SPECTRUM_MS * rate / 1000).takeHighestOneBit()
        val hop = HOP_MS * rate / 1000
        val before = BEFORE_MS * rate / 1000
        if (size < MIN_SIZE || hop < 1 || at - before < 0 || at > samples.size) return false
        if (missing(samples, at - before, at, SILENT_MS * rate / 1000)) return false
        val spectrum = Spectrum(size)
        val edges = IntArray(EDGES_HZ.size + 1) { if (it < EDGES_HZ.size) (EDGES_HZ[it] * size / rate).roundToInt() else size / 2 }
        val low = edges.first()   // at 16 kHz the edges are bins 2, 16, 64 and 256: 62.5, 500, 2,000 and 8,000 Hz
        val top = edges.last()

        // Before: each frequency's loudest, and its mean, over the spectra that end by the strike.
        val power = DoubleArray(top)
        val loudest = DoubleArray(top)
        val mean = DoubleArray(top)
        var count = 0
        var last = at
        while (last - size >= at - before) {
            spectrum.power(samples, last - size, power)
            for (k in low until top) {
                loudest[k] = maxOf(loudest[k], power[k])
                mean[k] += power[k]
            }
            count++
            last -= hop
        }
        if (count == 0) return false
        val line = DoubleArray(top)   // the level to rise beyond: RISE over the loudest before, with its neighbours
        for (k in low until top) {
            val from = maxOf(low, k - NEIGHBOURS)
            val to = minOf(top - 1, k + NEIGHBOURS)
            var sum = 0.0
            for (j in from..to) sum += loudest[j]
            line[k] = RISE * sum / (to - from + 1)
        }
        val needed = DoubleArray(edges.size - 1) { band ->   // each band's share of its level before, and never under FLOOR
            var sum = 0.0
            for (k in edges[band] until edges[band + 1]) sum += mean[k]
            maxOf(SHARE * sum / count, FLOOR)
        }

        // After: each frequency's level over SMOOTH spectra in turn; heard once a band's new sound reaches what it needs.
        val recent = Array(SMOOTH) { DoubleArray(top) }
        val total = DoubleArray(top)
        val end = minOf(at + TO_MS * rate / 1000, samples.size)
        var spectra = 0
        var first = at + FROM_MS * rate / 1000
        while (first + size <= end) {
            val slot = recent[spectra % SMOOTH]
            if (spectra >= SMOOTH) for (k in low until top) total[k] -= slot[k]
            spectrum.power(samples, first, slot)
            for (k in low until top) total[k] += slot[k]
            spectra++
            first += hop
            if (spectra < SMOOTH) continue
            for (band in needed.indices) {
                var fresh = 0.0
                for (k in edges[band] until edges[band + 1]) fresh += maxOf(0.0, total[k] / SMOOTH - line[k])
                if (fresh >= needed[band]) return true
            }
        }
        return false
    }

    /** Every strike of a recording, in the order given. */
    fun check(samples: ShortArray, strikes: List<Strike>, rate: Int = RATE): List<KeyResult> =
        strikes.map { KeyResult(it.key, heard(samples, it.at, it.key, rate)) }

    /** [run] exact zeros in a row in [samples] from [from] to [to]: audio missing there. */
    private fun missing(samples: ShortArray, from: Int, to: Int, run: Int): Boolean {
        var zeros = 0
        for (i in from until to) {
            zeros = if (samples[i].toInt() == 0) zeros + 1 else 0
            if (zeros >= run) return true
        }
        return false
    }

    /**
     * Power spectra of [size] samples (a power of two), Hann-windowed, scaled so that a frame's bins sum to its mean
     * square (full scale 1): a sine of amplitude A to about A² / 2 over its bins, white noise of σ to σ².
     */
    private class Spectrum(private val size: Int) {
        private val window = DoubleArray(size) { 0.5 - 0.5 * cos(2.0 * PI * it / size) }
        private val cosines = DoubleArray(size / 2) { cos(2.0 * PI * it / size) }
        private val sines = DoubleArray(size / 2) { -sin(2.0 * PI * it / size) }
        private val re = DoubleArray(size)
        private val im = DoubleArray(size)
        private val scale = 1.0 / (size * window.sumOf { it * it })

        /** The power of each bin below [out]'s size, of the frame of [samples] starting at [from], into [out]. */
        fun power(samples: ShortArray, from: Int, out: DoubleArray) {
            for (i in 0 until size) {
                re[i] = samples[from + i] / FULL_SCALE * window[i]
                im[i] = 0.0
            }
            transform()
            for (k in out.indices) {
                val m = re[k] * re[k] + im[k] * im[k]
                out[k] = (if (k == 0) m else 2.0 * m) * scale
            }
        }

        /** An in-place radix-2 FFT of [re] and [im]. */
        private fun transform() {
            var j = 0
            for (i in 1 until size) {
                var bit = size shr 1
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
            while (length <= size) {
                val half = length / 2
                val step = size / length
                var start = 0
                while (start < size) {
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

    /** The least spectrum worth the name (16 ms at 4 kHz). */
    private const val MIN_SIZE = 64

    private const val FULL_SCALE = 32_768.0
}
