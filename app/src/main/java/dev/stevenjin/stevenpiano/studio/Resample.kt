// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.studio

import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Sample-rate conversion for Studio's audio (v1.7 — M23): a windowed-sinc low-pass evaluated at each
 * output sample's exact position in the input (Kaiser window, [ZERO_CROSSINGS] zero crossings each side,
 * the cutoff at [ROLLOFF] of the lower Nyquist), so going down to 16 kHz keeps what lies below about
 * 7.5 kHz and folds nothing above 8 kHz back into it, and going up leaves no images. Pure: no Android.
 */
object Resample {
    /** The transcription model's rate. */
    const val MODEL_RATE = 16_000

    /** Where the pass band ends, as a share of the lower of the two Nyquist frequencies. */
    const val ROLLOFF = 0.94

    /** The sinc's zero crossings on each side of its centre (the filter's length). */
    const val ZERO_CROSSINGS = 24

    /** The Kaiser window's β: about 90 dB down in the stop band. */
    const val KAISER_BETA = 9.0

    /** [input] (mono, at [fromRate]) at 16 kHz. The same array when it is 16 kHz already. */
    fun to16k(input: FloatArray, fromRate: Int): FloatArray = convert(input, fromRate, MODEL_RATE)

    /** [input] (mono, at [fromRate]) at [toRate]. The same array when the rates are equal. */
    fun convert(input: FloatArray, fromRate: Int, toRate: Int): FloatArray {
        if (fromRate == toRate) return input
        val resampler = Resampler(fromRate, toRate)
        val out = FloatBuilder(resampler.outputLength(input.size.toLong()).toInt() + 1)
        resampler.push(input, input.size, out)
        resampler.finish(out)
        return out.toArray()
    }
}

/** Where a [Resampler] puts its output: a growing array, or the next stage. */
fun interface FloatSink {
    fun write(samples: FloatArray, count: Int)
}

/** Floats appended to one array that grows as needed; [array] up to [size] is the content. */
class FloatBuilder(initialCapacity: Int = 1024) : FloatSink {
    var array = FloatArray(initialCapacity.coerceAtLeast(16))
        private set
    var size = 0
        private set

    override fun write(samples: FloatArray, count: Int) {
        ensure(size + count)
        System.arraycopy(samples, 0, array, size, count)
        size += count
    }

    fun add(value: Float) {
        ensure(size + 1)
        array[size++] = value
    }

    /** Room for at least [capacity] floats, grown by a quarter at a time beyond the first estimate. */
    fun ensure(capacity: Int) {
        if (capacity <= array.size) return
        val grown = maxOf(capacity, array.size + array.size / 4 + 1024)
        array = array.copyOf(grown)
    }

    fun toArray(): FloatArray = if (size == array.size) array else array.copyOf(size)
}

/**
 * One stream from [fromRate] to [toRate], fed in pieces ([push]) and ended ([finish]); the output is
 * the same whatever the pieces. Output sample n sits at input position n × from / to, computed exactly
 * (whole numbers), and is the sum of the input around it weighted by the windowed sinc, read from a
 * table of [RESOLUTION] points per input sample with linear interpolation. The input beyond the end
 * counts as silence.
 */
class Resampler(val fromRate: Int, val toRate: Int) {
    init {
        require(fromRate in MIN_RATE..MAX_RATE && toRate in MIN_RATE..MAX_RATE) { "rates $fromRate → $toRate" }
    }

    /** The cutoff as cycles per input sample, times two: the sinc's scale. */
    private val scale = min(1.0, toRate.toDouble() / fromRate) * Resample.ROLLOFF

    /** How far the filter reaches on each side, in input samples. */
    private val halfWidth = Resample.ZERO_CROSSINGS / scale
    private val reach = floor(halfWidth).toInt() + 1

    /** The kernel from −[reach] to +[reach] input samples, [RESOLUTION] points each, and one more. */
    private val table = FloatArray(2 * reach * RESOLUTION + 2).also { t ->
        val i0Beta = besselI0(Resample.KAISER_BETA)
        for (i in t.indices) {
            val x = i.toDouble() / RESOLUTION - reach
            t[i] = if (kotlin.math.abs(x) >= halfWidth) 0f else (scale * sinc(scale * x) * kaiser(x / halfWidth, i0Beta)).toFloat()
        }
    }

    /** Input kept: [history] holds input samples from index [base] on. */
    private var history = FloatArray(4096)
    private var kept = 0
    private var base = 0L

    /** Input samples seen so far. */
    private var received = 0L

    /** The next output sample's index. */
    private var next = 0L
    private val chunk = FloatArray(CHUNK)

    /** How many output samples [inputLength] input samples make. */
    fun outputLength(inputLength: Long): Long = (inputLength * toRate + fromRate - 1) / fromRate

    /** Takes [count] samples of [input]; writes to [out] every output sample they complete. */
    fun push(input: FloatArray, count: Int, out: FloatSink) {
        if (kept + count > history.size) compact(count)
        System.arraycopy(input, 0, history, kept, count)
        kept += count
        received += count
        emit(out, complete = false)
    }

    /** The end of the input: the output samples still owed, with silence after the last input. */
    fun finish(out: FloatSink) = emit(out, complete = true)

    private fun emit(out: FloatSink, complete: Boolean) {
        val total = outputLength(received)
        var filled = 0
        while (next < total) {
            // Output sample `next` sits at input position whole + frac / toRate.
            val position = next * fromRate
            val whole = position / toRate
            val frac = (position % toRate).toDouble() / toRate
            // It needs input up to whole + reach, which is there, or is silence at the end.
            if (!complete && whole + reach >= received) break
            chunk[filled++] = sample(whole, frac)
            next++
            if (filled == chunk.size) {
                out.write(chunk, filled)
                filled = 0
            }
        }
        if (filled > 0) out.write(chunk, filled)
    }

    /** One output sample at input position [whole] + [frac]. */
    private fun sample(whole: Long, frac: Double): Float {
        val first = whole - reach + 1
        // Input sample `first` is reach - 1 + frac before the position; the table starts at -reach.
        val start = (2.0 * reach - 1 + frac) * RESOLUTION
        var index = floor(start).toInt()
        val a = (start - index).toFloat()
        var sum = 0f
        var k = first
        val last = whole + reach
        while (k <= last) {
            if (k >= base && k < received) {
                val x = history[(k - base).toInt()]
                val h0 = table[index]
                sum += x * (h0 + a * (table[index + 1] - h0))
            }
            index -= RESOLUTION
            k++
        }
        return sum
    }

    /** Drops input no output sample will read again, and makes room for [incoming] more. */
    private fun compact(incoming: Int) {
        val needed = (next * fromRate / toRate) - reach - 1
        val drop = (needed - base).coerceIn(0L, kept.toLong()).toInt()
        if (drop > 0) {
            System.arraycopy(history, drop, history, 0, kept - drop)
            kept -= drop
            base += drop
        }
        if (kept + incoming > history.size) history = history.copyOf(maxOf(kept + incoming, history.size * 2))
    }

    private companion object {
        const val RESOLUTION = 512
        const val CHUNK = 4096
        const val MIN_RATE = 1_000
        const val MAX_RATE = 768_000

        fun sinc(x: Double): Double = if (x == 0.0) 1.0 else sin(PI * x) / (PI * x)

        fun kaiser(t: Double, i0Beta: Double): Double {
            val r = 1.0 - t * t
            return if (r <= 0.0) 0.0 else besselI0(Resample.KAISER_BETA * sqrt(r)) / i0Beta
        }

        /** The modified Bessel function of the first kind, order 0, by its series. */
        fun besselI0(x: Double): Double {
            var sum = 1.0
            var term = 1.0
            val half = x / 2
            var k = 1
            while (k < 64) {
                term *= (half / k) * (half / k)
                sum += term
                if (term < sum * 1e-17) break
                k++
            }
            return sum
        }
    }
}
