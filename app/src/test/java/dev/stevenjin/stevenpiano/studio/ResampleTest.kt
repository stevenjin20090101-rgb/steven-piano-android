// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.studio

import dev.stevenjin.stevenpiano.studio.StudioFixtures.amplitude
import dev.stevenjin.stevenpiano.studio.StudioFixtures.sine
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * The windowed-sinc resampler (v1.7 — M23): a tone below the new Nyquist keeps its frequency and level,
 * one above it is gone rather than folded back (no aliasing), upsampling leaves no images, and the
 * output is the same whatever pieces the input comes in.
 */
class ResampleTest {
    /** Away from the ends, where the filter reaches past the input. */
    private fun middle(out: FloatArray) = out.size / 8 until out.size * 7 / 8

    @Test
    fun `a 1 kHz tone at 44_1 kHz is a 1 kHz tone at 16 kHz, at its level`() {
        val input = sine(44_100, 1_000.0, 44_100)
        val out = Resample.to16k(input, 44_100)
        assertEquals("ceil(44,100 × 16,000 / 44,100)", 16_000, out.size)
        val range = middle(out)
        val level = amplitude(out, 16_000, 1_000.0, range.first, range.last + 1)
        assertEquals("the pass band keeps the level", 0.5, level, 0.002)
        for (other in listOf(500.0, 2_000.0, 3_000.0, 6_000.0)) {
            assertTrue("nothing at $other Hz", amplitude(out, 16_000, other, range.first, range.last + 1) < 0.0005)
        }
        // Sample by sample, it is the tone itself (the filter's delay is zero: it is centred).
        val expected = sine(16_000, 1_000.0, 16_000)
        for (i in range) assertEquals("sample $i", expected[i], out[i], 0.003f)
    }

    @Test
    fun `tones above 8 kHz are filtered out before they can fold back`() {
        for ((rate, tone) in listOf(44_100 to 10_000.0, 44_100 to 12_000.0, 48_000 to 9_000.0, 48_000 to 15_000.0, 22_050 to 10_000.0)) {
            val out = Resample.to16k(sine(rate, tone, rate), rate)
            val range = middle(out)
            val alias = 16_000 - tone   // where the tone would land if it folded
            val folded = amplitude(out, 16_000, alias, range.first, range.last + 1)
            val rms = sqrt(range.sumOf { out[it].toDouble() * out[it] } / range.count())
            assertTrue("$tone Hz at $rate Hz: $folded at $alias Hz", folded < 0.5 * 0.001)
            assertTrue("$tone Hz at $rate Hz: rms $rms", rms < 0.5 * 0.001)
        }
    }

    @Test
    fun `the pass band reaches past 6 kHz at every common rate`() {
        for (rate in listOf(22_050, 32_000, 44_100, 48_000, 96_000)) {
            val out = Resample.to16k(sine(rate, 6_000.0, rate), rate)
            val range = middle(out)
            assertEquals("6 kHz at $rate Hz", 0.5, amplitude(out, 16_000, 6_000.0, range.first, range.last + 1), 0.005)
        }
    }

    @Test
    fun `upsampling 8 kHz keeps the tone and leaves no image`() {
        val out = Resample.to16k(sine(8_000, 1_000.0, 8_000), 8_000)
        assertEquals(16_000, out.size)
        val range = middle(out)
        assertEquals(0.5, amplitude(out, 16_000, 1_000.0, range.first, range.last + 1), 0.003)
        assertTrue("no image at 7 kHz", amplitude(out, 16_000, 7_000.0, range.first, range.last + 1) < 0.0005)
    }

    @Test
    fun `16 kHz is left as it is`() {
        val input = sine(16_000, 440.0, 1_000)
        assertSame(input, Resample.to16k(input, 16_000))
    }

    @Test
    fun `the output is the same whatever pieces the input comes in`() {
        val input = FloatArray(30_000) { ((it * 7919) % 2001 - 1000) / 1000f }   // noise-like, every frequency
        val whole = Resample.convert(input, 44_100, 16_000)
        for (piece in listOf(1, 7, 333, 4_096, 29_999)) {
            val resampler = Resampler(44_100, 16_000)
            val out = FloatBuilder(16)
            var at = 0
            while (at < input.size) {
                val n = minOf(piece, input.size - at)
                resampler.push(input.copyOfRange(at, at + n), n, out)
                at += n
            }
            resampler.finish(out)
            assertArrayEquals("pieces of $piece", whole, out.toArray(), 0f)
        }
        assertEquals(10_885, whole.size)
    }

    @Test
    fun `silence stays silent and DC keeps its level`() {
        assertTrue(Resample.to16k(FloatArray(5_000), 44_100).all { it == 0f })
        val dc = Resample.to16k(FloatArray(44_100) { 0.25f }, 44_100)
        for (i in middle(dc)) assertTrue("sample $i: ${dc[i]}", abs(dc[i] - 0.25f) < 0.0005f)
    }
}
