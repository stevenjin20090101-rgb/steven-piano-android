// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.audio

import dev.stevenjin.stevenpiano.audio.Sf2Fixture.Instrument
import dev.stevenjin.stevenpiano.audio.Sf2Fixture.Preset
import dev.stevenjin.stevenpiano.audio.Sf2Fixture.Zone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow

/**
 * The sampler (v1.8 — M25) on the fixture piano ([Sf2Fixture.piano]: sines at C3, C4 in two layers and
 * C5, a 10 ms attack, 50 ms hold, 200 ms decay to 6 dB down and a 300 ms release): keys sound at their
 * pitch from the nearest sample, the velocity chooses the layer and sets the level as SF2 does, the
 * envelope has its shape, the pedal holds and lets go, a struck key releases its old voice, polyphony
 * steals the oldest, and the gain laws and the limiter hold. Everything at 48 kHz, volume 50.
 */
class SamplerTest {
    private val rate = 48_000
    private val font = Sf2Fixture.font(Sf2Fixture.piano())

    private fun sampler(polyphony: Int = Sampler.POLYPHONY) = Sampler(font, rate, polyphony).apply { volume(TEST_VOLUME) }

    private fun Sampler.run(seconds: Double): FloatArray {
        val out = FloatArray((seconds * rate).toInt())
        render(out, out.size)
        return out
    }

    private fun peak(x: FloatArray, from: Double, to: Double): Float {
        var p = 0f
        for (i in (from * rate).toInt() until (to * rate).toInt().coerceAtMost(x.size)) p = maxOf(p, abs(x[i]))
        return p
    }

    /** The frequency of the tone in [x] between [from] and [to] seconds, by its upward zero crossings. */
    private fun frequency(x: FloatArray, from: Double, to: Double): Double {
        val a = (from * rate).toInt()
        val b = (to * rate).toInt()
        var first = -1.0
        var last = -1.0
        var crossings = 0
        for (i in a + 1 until b) {
            if (x[i - 1] < 0f && x[i] >= 0f) {
                val t = i - 1 + x[i - 1] / (x[i - 1] - x[i]).toDouble()
                if (first < 0) first = t else crossings++
                last = t
            }
        }
        return crossings * rate / (last - first)
    }

    /** The master gain the tests play at: low enough that the limiter never acts on the fixture's sines. */
    private val full = Sampler.gainFor(TEST_VOLUME)

    private companion object {
        const val TEST_VOLUME = 50
    }

    @Test
    fun `keys sound at their own pitch from the nearest sample`() {
        for ((key, zone) in listOf(40 to "C3", 48 to "C3", 57 to "C4", 60 to "C4", 69 to "C5", 84 to "C5", 21 to "C3", 107 to "C5")) {
            val s = sampler()
            s.noteOn(key, 100)
            val out = s.run(0.5)
            val expected = Sf2Fixture.frequencyOf(key)
            assertEquals("key $key from $zone", expected, frequency(out, 0.1, 0.45), expected * 0.002)
        }
    }

    @Test
    fun `the velocity picks the layer and scales it by SF2's curve`() {
        val soft = sampler().apply { noteOn(60, 64) }.run(0.05)
        val loud = sampler().apply { noteOn(60, 100) }.run(0.05)
        // During the hold (10–50 ms) the envelope is full: the sample's amplitude × (velocity / 127)² × the master gain.
        assertEquals(0.25f * (64 / 127f).pow(2) * full, peak(soft, 0.015, 0.05), 0.002f)
        assertEquals(0.5f * (100 / 127f).pow(2) * full, peak(loud, 0.015, 0.05), 0.002f)
        // Within one layer the level goes as the velocity squared: twice the velocity is 12 dB.
        val half = sampler().apply { noteOn(72, 50) }.run(0.05)
        val double = sampler().apply { noteOn(72, 100) }.run(0.05)
        assertEquals(12.0, 20 * log10(peak(double, 0.015, 0.05) / peak(half, 0.015, 0.05).toDouble()), 0.1)
    }

    @Test
    fun `the envelope attacks, holds, decays to its sustain and releases over 100 dB`() {
        val s = sampler()
        s.noteOn(72, 127)
        val held = s.run(1.0)
        val top = 0.5f * full
        assertTrue("rising in the attack", peak(held, 0.0, 0.004) < peak(held, 0.006, 0.010))
        assertEquals("full through the hold", top, peak(held, 0.015, 0.055), 0.003f)
        // 60 cB of sustain: 6 dB down once the decay has run.
        val sustain = top * 10.0.pow(-60 / 200.0).toFloat()
        assertEquals("at the sustain level", sustain, peak(held, 0.35, 0.99), 0.003f)
        // The decay is linear in decibels, 100 dB in 200 ms: the 6 dB take 12 ms after the hold's 60.
        assertTrue("decaying", peak(held, 0.060, 0.064) > sustain + 0.01f)
        assertEquals("at the sustain level 12 ms later", sustain, peak(held, 0.074, 0.2), 0.003f)
        s.noteOff(72)
        val released = s.run(0.4)
        // 300 ms for 100 dB: 60 ms in, 20 dB below where it was.
        assertEquals(sustain * 0.1f, peak(released, 0.058, 0.062), sustain * 0.02f)
        assertTrue("silent after the release", peak(released, 0.31, 0.4) == 0f)
        assertEquals(0, s.activeVoices)
    }

    @Test
    fun `a held note loops on past the end of its sample`() {
        val s = sampler()
        s.noteOn(60, 100)
        val out = s.run(2.5)
        assertTrue("still sounding at 2.4 s of a 1 s sample", peak(out, 2.3, 2.5) > 0.5f * peak(out, 0.4, 0.9))
        assertEquals(Sf2Fixture.frequencyOf(60), frequency(out, 1.5, 2.4), 1.0)
    }

    @Test
    fun `a sample that doesn't loop ends at its end`() {
        val sample = Sf2Fixture.toneSample("once", 60, seconds = 0.2)
        val bytes = Sf2Fixture.build(
            "Once",
            listOf(Preset("Once", 0, 0, listOf(Zone(emptyList(), 0)))),
            listOf(Instrument("Once", listOf(Zone(emptyList(), 0)))),
            listOf(sample),
        )
        val s = Sampler(Sf2Fixture.font(bytes), rate).apply { volume(TEST_VOLUME) }
        s.noteOn(60, 127)
        val out = s.run(0.4)
        assertTrue(peak(out, 0.05, 0.15) > 0.4f * full)
        assertEquals(0f, peak(out, 0.22, 0.4))
        assertEquals(0, s.activeVoices)
    }

    @Test
    fun `the pedal holds released keys until it comes up`() {
        val s = sampler()
        s.sustain(true)
        s.noteOn(60, 100)
        s.run(0.1)
        s.noteOff(60)
        val pedalled = s.run(0.6)
        assertTrue("held by the pedal", peak(pedalled, 0.5, 0.6) > 0.1f * full)
        assertEquals(setOf(60), s.heldKeys())
        s.sustain(false)
        val after = s.run(0.4)
        assertEquals(0f, peak(after, 0.32, 0.4))
        assertEquals(0, s.activeVoices)
        assertFalse(s.sustainDown)
    }

    @Test
    fun `a key struck again releases what it still sounded`() {
        val s = sampler()
        s.sustain(true)
        s.noteOn(60, 100)
        s.noteOff(60)
        s.run(0.1)
        s.noteOn(60, 100)
        assertEquals("the old voice releasing beside the new", 2, s.activeVoices)
        s.run(0.35)
        assertEquals(1, s.activeVoices)
        assertEquals(setOf(60), s.heldKeys())
    }

    @Test
    fun `past its polyphony the oldest voice is stolen, a released one first`() {
        val s = sampler(polyphony = 4)
        for (key in 60..63) {
            s.noteOn(key, 100)
            s.run(0.01)
        }
        s.noteOn(64, 100)
        assertEquals(4, s.liveVoices)
        assertEquals(setOf(61, 62, 63, 64), s.heldKeys())
        s.run(0.05)
        assertEquals("the stolen voice has faded out", 4, s.activeVoices)
        // A released voice goes before a held one, however young.
        s.noteOff(63)
        s.noteOn(65, 100)
        assertEquals(setOf(61, 62, 64, 65), s.heldKeys())
        assertEquals(4, s.liveVoices)
    }

    @Test
    fun `all off releases every voice and lifts the pedal, and silence fades them at once`() {
        val s = sampler()
        s.sustain(true)
        for (key in listOf(48, 60, 72)) s.noteOn(key, 100)
        s.run(0.1)
        s.allOff()
        assertFalse(s.sustainDown)
        assertTrue(s.heldKeys().isEmpty())
        val released = s.run(0.4)
        assertEquals(0f, peak(released, 0.32, 0.4))

        for (key in listOf(48, 60, 72)) s.noteOn(key, 100)
        s.run(0.1)
        s.silence()
        val faded = s.run(0.05)
        assertEquals(0f, peak(faded, Sampler.FADE_MS / 1000.0 + 0.002, 0.05))
        assertEquals(0, s.activeVoices)
    }

    @Test
    fun `the volume law is the square of the percentage, zero silent, and it ramps`() {
        assertEquals(Sampler.HEADROOM, Sampler.gainFor(100), 0f)
        assertEquals(Sampler.HEADROOM / 4, Sampler.gainFor(50), 1e-6f)
        assertEquals(0f, Sampler.gainFor(0), 0f)
        assertEquals(Sampler.gainFor(100), Sampler.gainFor(250), 0f)
        for (pct in 1..100) assertTrue(Sampler.gainFor(pct) > Sampler.gainFor(pct - 1))
        assertEquals(1f, Sampler.velocityGain(127), 0f)
        assertEquals((64 / 127.0).pow(2).toFloat(), Sampler.velocityGain(64), 1e-6f)
        // SF2's modulator: -40·log10(v / 127) dB.
        assertEquals(-40 * log10(40 / 127.0), -20 * log10(Sampler.velocityGain(40).toDouble()), 1e-4)

        val s = sampler()
        s.volume(100)
        s.noteOn(60, 80)   // the soft layer's quarter-scale sine
        val loud = s.run(0.05)
        s.volume(50)
        val softer = s.run(0.009)   // still in the hold, which ends at 60 ms
        assertEquals(peak(loud, 0.02, 0.05) / 4, peak(softer, 0.002, 0.009), 0.002f)
        s.volume(0)
        val silent = s.run(0.05)
        assertEquals(0f, peak(silent, 0.002, 0.05))
    }

    @Test
    fun `the limiter leaves the mix alone under its ceiling, turns a peak down at once and comes back`() {
        val l = Limiter(rate)
        repeat(1_000) { assertEquals(0.5f, l.process(0.5f), 0f) }
        assertEquals(1f, l.gain, 0f)
        assertEquals("a peak of 2 leaves at the ceiling", Limiter.CEILING, l.process(2f), 1e-6f)
        val down = l.gain
        assertEquals(Limiter.CEILING / 2f, down, 1e-6f)
        // The gain comes back with the release as its time constant: after one, 63 % of the way.
        repeat(rate * Limiter.RELEASE_MS / 1000) { l.process(0f) }
        assertEquals(1f - (1f - down) * kotlin.math.exp(-1f), l.gain, 0.002f)
        assertEquals(down, l.lowest, 0f)
        // A chord far too loud for the headroom never passes the ceiling, and the sampler says how hard it worked.
        val s = sampler()
        s.volume(100)
        for (key in 30..90) s.noteOn(key, 127)
        val out = s.run(0.2)
        assertTrue(out.all { it in -Limiter.CEILING..Limiter.CEILING })
        assertTrue(peak(out, 0.0, 0.2) > 0.8f)
        assertTrue(s.takeLowestLimiterGain() < 0.5f)
        assertEquals("asked again, it starts afresh", 1f, s.takeLowestLimiterGain(), 0f)
    }

    @Test
    fun `a stereo pair sounds once, its channels mixed to mono`() {
        val left = Sf2Fixture.toneSample("L", 69, amplitude = 0.6, type = Sf2Sample.LEFT, link = 1)
        val right = Sf2Fixture.toneSample("R", 69, amplitude = 0.2, type = Sf2Sample.RIGHT, link = 0)
        val bytes = Sf2Fixture.build(
            "Stereo",
            listOf(Preset("Stereo", 0, 0, listOf(Zone(emptyList(), 0)))),
            listOf(Instrument("Stereo", listOf(Zone(listOf(Sf2Fixture.PAN to -500), 0), Zone(listOf(Sf2Fixture.PAN to 500), 1)))),
            listOf(left, right),
        )
        val s = Sampler(Sf2Fixture.font(bytes), rate).apply { volume(TEST_VOLUME) }
        s.noteOn(69, 127)
        assertEquals(1, s.activeVoices)
        val out = s.run(0.2)
        // The two channels are in phase: their mean.
        assertEquals((0.6f + 0.2f) / 2 * full, peak(out, 0.05, 0.2), 0.003f)
        assertEquals(440.0, frequency(out, 0.05, 0.2), 1.0)
    }
}
