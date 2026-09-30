// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.audio

import dev.stevenjin.stevenpiano.audio.Sf2Fixture.ATTACK
import dev.stevenjin.stevenpiano.audio.Sf2Fixture.ATTENUATION
import dev.stevenjin.stevenpiano.audio.Sf2Fixture.COARSE_TUNE
import dev.stevenjin.stevenpiano.audio.Sf2Fixture.END_OFFSET
import dev.stevenjin.stevenpiano.audio.Sf2Fixture.FINE_TUNE
import dev.stevenjin.stevenpiano.audio.Sf2Fixture.Instrument
import dev.stevenjin.stevenpiano.audio.Sf2Fixture.KEY_RANGE
import dev.stevenjin.stevenpiano.audio.Sf2Fixture.LOOP_START_OFFSET
import dev.stevenjin.stevenpiano.audio.Sf2Fixture.PAN
import dev.stevenjin.stevenpiano.audio.Sf2Fixture.Preset
import dev.stevenjin.stevenpiano.audio.Sf2Fixture.RELEASE
import dev.stevenjin.stevenpiano.audio.Sf2Fixture.ROOT_KEY
import dev.stevenjin.stevenpiano.audio.Sf2Fixture.SAMPLE_MODES
import dev.stevenjin.stevenpiano.audio.Sf2Fixture.SCALE_TUNING
import dev.stevenjin.stevenpiano.audio.Sf2Fixture.START_OFFSET
import dev.stevenjin.stevenpiano.audio.Sf2Fixture.VEL_RANGE
import dev.stevenjin.stevenpiano.audio.Sf2Fixture.Zone
import dev.stevenjin.stevenpiano.audio.Sf2Fixture.range
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer

/**
 * The SoundFont reader (v1.8 — M25) on fonts the tests write ([Sf2Fixture]): the samples and their
 * loops, the zones resolved as SF2 2.04 § 9.4 says (global zones, the preset's generators added, the
 * ranges intersected), stereo pairs as one region, the preset chosen, and damaged files refused.
 */
class Sf2ReaderTest {
    @Test
    fun `the fixture piano reads back its name, samples and regions`() {
        val font = Sf2Fixture.font(Sf2Fixture.piano())
        assertEquals("Fixture piano", font.name)
        assertEquals("Fixture piano", font.presetName)
        assertEquals(4, font.samples.size)
        val c4 = font.samples[1]
        assertEquals("C4soft", c4.name)
        assertEquals(22_050, c4.rate)
        assertEquals(60, c4.originalPitch)
        assertEquals(c4.end - c4.start, 22_050)
        assertEquals(4, font.regions.size)
        // Keys map onto their zones, the velocity choosing between C4's layers.
        assertEquals(listOf(0), font.regionsFor(40).map { it.sample })
        assertEquals(listOf(1, 2), font.regionsFor(60).map { it.sample })
        assertEquals(listOf(1), font.regionsFor(60).filter { it.matches(60, 64) }.map { it.sample })
        assertEquals(listOf(2), font.regionsFor(60).filter { it.matches(60, 100) }.map { it.sample })
        assertEquals(listOf(3), font.regionsFor(108).map { it.sample })
        // The global zone's envelope reaches every zone.
        for (r in font.regions) {
            assertEquals(Sf2Fixture.tc(0.010), r.attackTc)
            assertEquals(Sf2Fixture.tc(0.300), r.releaseTc)
            assertEquals(60, r.sustainCb)
            assertEquals(Region.LOOP_CONTINUOUS, r.loopMode)
            assertTrue(r.loops)
        }
    }

    @Test
    fun `loop points and frames are absolute in the sample data`() {
        val font = Sf2Fixture.font(Sf2Fixture.piano())
        for ((i, r) in font.regions.withIndex()) {
            val s = font.samples[r.sample]
            assertEquals("region $i start", s.start, r.start)
            assertEquals(s.end, r.end)
            assertEquals(s.loopStart, r.loopStart)
            assertEquals(s.loopEnd, r.loopEnd)
            assertTrue(r.start < r.loopStart && r.loopStart < r.loopEnd && r.loopEnd <= r.end)
        }
        // Each sample's frames are the fixture's, 46 zero frames apart.
        val second = font.samples[1]
        assertEquals(22_050 + 46, second.start)
        val expected = Sf2Fixture.sine(Sf2Fixture.frequencyOf(60), 22_050, 22_050, 0.25)
        for (f in listOf(0, 1, 100, 22_049)) assertEquals(expected[f], font.data.get(second.start + f))
    }

    @Test
    fun `an instrument zone overrides its global zone and the preset adds to both`() {
        val bytes = Sf2Fixture.build(
            "Layers",
            listOf(
                Preset(
                    "Layers", 0, 0,
                    listOf(
                        Zone(listOf(FINE_TUNE to 7, ATTENUATION to 30), terminal = null),   // the preset's global zone
                        Zone(listOf(KEY_RANGE to range(40, 80), COARSE_TUNE to 1, RELEASE to 1200), 0),
                    ),
                ),
            ),
            listOf(
                Instrument(
                    "Layers",
                    listOf(
                        Zone(listOf(ATTENUATION to 20, RELEASE to -1200, COARSE_TUNE to -2), terminal = null),
                        Zone(listOf(KEY_RANGE to range(0, 60), ATTENUATION to 100, FINE_TUNE to -3), 0),
                        Zone(listOf(KEY_RANGE to range(61, 127), SCALE_TUNING to 50), 0),
                    ),
                ),
            ),
            listOf(Sf2Fixture.toneSample("A", 60)),
        )
        val font = Sf2Fixture.font(bytes)
        val (low, high) = font.regions
        // Ranges intersect: the preset's 40-80 with the zones' 0-60 and 61-127.
        assertEquals(40, low.keyLo)
        assertEquals(60, low.keyHi)
        assertEquals(61, high.keyLo)
        assertEquals(80, high.keyHi)
        // Attenuation: the zone's own 100 (over the global 20), plus the preset's 30.
        assertEquals(130, low.attenuationCb)
        assertEquals(50, high.attenuationCb)
        // Tune: coarse -2 (global) + 1 (preset) semitones, fine -3 (zone) + 7 (preset global) cents.
        assertEquals(-100 + 4, low.tuneCents)
        assertEquals(-100 + 7, high.tuneCents)
        // Release: -1200 at the instrument, +1200 at the preset.
        assertEquals(0, low.releaseTc)
        assertEquals(100, low.scaleTuning)
        assertEquals(50, high.scaleTuning)
        assertTrue(font.regionsFor(30).isEmpty())
        assertTrue(font.regionsFor(90).isEmpty())
    }

    @Test
    fun `address offsets move the sample's start, end and loop, and a root key override wins`() {
        val sample = Sf2Fixture.toneSample("A", 57)
        val bytes = Sf2Fixture.build(
            "Offsets",
            listOf(Preset("Offsets", 0, 0, listOf(Zone(emptyList(), 0)))),
            listOf(Instrument("Offsets", listOf(Zone(listOf(START_OFFSET to 10, END_OFFSET to -20, LOOP_START_OFFSET to 5, ROOT_KEY to 69, SAMPLE_MODES to 3), 0)))),
            listOf(sample),
        )
        val font = Sf2Fixture.font(bytes)
        val s = font.samples.single()
        val r = font.regions.single()
        assertEquals(s.start + 10, r.start)
        assertEquals(s.end - 20, r.end)
        assertEquals(s.loopStart + 5, r.loopStart)
        assertEquals(69, r.rootKey)
        assertEquals(Region.LOOP_UNTIL_RELEASE, r.loopMode)
        // Without the override, the sample's own pitch.
        assertEquals(57, Sf2Fixture.font(Sf2Fixture.build("Plain", listOf(Preset("Plain", 0, 0, listOf(Zone(emptyList(), 0)))), listOf(Instrument("Plain", listOf(Zone(emptyList(), 0)))), listOf(sample))).regions.single().rootKey)
    }

    @Test
    fun `a stereo pair plays as one region, its right channel inside the left's`() {
        val left = Sf2Fixture.toneSample("L", 60, type = Sf2Sample.LEFT, link = 1)
        val right = Sf2Fixture.toneSample("R", 60, amplitude = 0.4, type = Sf2Sample.RIGHT, link = 0)
        val bytes = Sf2Fixture.build(
            "Stereo",
            listOf(Preset("Stereo", 0, 0, listOf(Zone(emptyList(), 0)))),
            listOf(
                Instrument(
                    "Stereo",
                    listOf(
                        Zone(listOf(KEY_RANGE to range(0, 127), PAN to -500, SAMPLE_MODES to 1), 0),
                        Zone(listOf(KEY_RANGE to range(0, 127), PAN to 500, SAMPLE_MODES to 1), 1),
                    ),
                ),
            ),
            listOf(left, right),
        )
        val font = Sf2Fixture.font(bytes)
        assertEquals(2, font.regions.size)
        val (l, r) = font.regions
        assertSame(r, l.partner)
        assertTrue(r.paired)
        assertEquals(-500, l.pan)
        assertEquals(listOf(l), font.regionsFor(60).toList())
    }

    @Test
    fun `a pair whose zones differ is two regions, each played alone`() {
        val left = Sf2Fixture.toneSample("L", 60, type = Sf2Sample.LEFT, link = 1)
        val right = Sf2Fixture.toneSample("R", 60, type = Sf2Sample.RIGHT, link = 0)
        val bytes = Sf2Fixture.build(
            "Uneven",
            listOf(Preset("Uneven", 0, 0, listOf(Zone(emptyList(), 0)))),
            listOf(Instrument("Uneven", listOf(Zone(listOf(VEL_RANGE to range(0, 90)), 0), Zone(listOf(VEL_RANGE to range(0, 127)), 1)))),
            listOf(left, right),
        )
        val font = Sf2Fixture.font(bytes)
        assertNull(font.regions[0].partner)
        assertFalse(font.regions[1].paired)
        assertEquals(2, font.regionsFor(60).size)
    }

    @Test
    fun `bank 0 preset 0 is chosen wherever it stands`() {
        val a = Sf2Fixture.toneSample("A", 48)
        val b = Sf2Fixture.toneSample("B", 72)
        val bytes = Sf2Fixture.build(
            "Two presets",
            listOf(Preset("Organ", 0, 19, listOf(Zone(emptyList(), 0))), Preset("Piano", 0, 0, listOf(Zone(emptyList(), 1)))),
            listOf(Instrument("Organ", listOf(Zone(emptyList(), 0))), Instrument("Piano", listOf(Zone(emptyList(), 1)))),
            listOf(a, b),
        )
        val font = Sf2Fixture.font(bytes)
        assertEquals("Piano", font.presetName)
        assertEquals(1, font.regions.single().sample)
    }

    @Test
    fun `ROM samples and zones without a sample are left out`() {
        val rom = Sf2Fixture.Sample("rom", ShortArray(100), 22_050, 60, type = Sf2Sample.ROM or Sf2Sample.MONO)
        val ok = Sf2Fixture.toneSample("ok", 60)
        val bytes = Sf2Fixture.build(
            "Rom",
            listOf(Preset("Rom", 0, 0, listOf(Zone(emptyList(), 0)))),
            listOf(Instrument("Rom", listOf(Zone(listOf(KEY_RANGE to range(0, 60)), 0), Zone(listOf(KEY_RANGE to range(61, 127)), 1), Zone(listOf(ATTACK to 100), null)))),
            listOf(rom, ok),
        )
        val font = Sf2Fixture.font(bytes)
        assertEquals(listOf(1), font.regions.map { it.sample })
        assertTrue(font.regionsFor(40).isEmpty())
    }

    @Test
    fun `a font on disk is memory-mapped and reads the same`() {
        val file = File.createTempFile("fixture", ".sf2")
        try {
            file.writeBytes(Sf2Fixture.piano())
            val mapped = Sf2Reader.read(file)
            val heap = Sf2Fixture.font(Sf2Fixture.piano())
            assertEquals(heap.frames, mapped.frames)
            assertEquals(heap.regions.size, mapped.regions.size)
            for (f in 0 until heap.frames step 997) assertEquals(heap.data.get(f), mapped.data.get(f))
        } finally {
            file.delete()
        }
    }

    @Test
    fun `damaged files are refused in words`() {
        val good = Sf2Fixture.piano()
        refused("not a RIFF", "RIFX".toByteArray() + good.copyOfRange(4, good.size))
        refused("not a SoundFont", good.copyOf(8) + "WAVE".toByteArray() + good.copyOfRange(12, good.size))
        refused("too short", good.copyOf(6))
        refused("cut off mid-chunk", good.copyOf(good.size - 300))
        // A table whose size isn't a whole number of records.
        val broken = good.copyOf()
        val at = indexOf(broken, "shdr")
        ByteBuffer.wrap(broken, at + 4, 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).putInt(45)
        refused("shdr of 45 bytes", broken)
        // A zone naming a sample that isn't there.
        val stray = Sf2Fixture.build(
            "Stray",
            listOf(Preset("Stray", 0, 0, listOf(Zone(emptyList(), 0)))),
            listOf(Instrument("Stray", listOf(Zone(emptyList(), 5)))),
            listOf(Sf2Fixture.toneSample("A", 60)),
        )
        refused("a stray sample id", stray)
        // A preset naming an instrument that isn't there.
        val noInstrument = Sf2Fixture.build(
            "Lost",
            listOf(Preset("Lost", 0, 0, listOf(Zone(emptyList(), 3)))),
            listOf(Instrument("Only", listOf(Zone(emptyList(), 0)))),
            listOf(Sf2Fixture.toneSample("A", 60)),
        )
        refused("a stray instrument", noInstrument)
    }

    private fun refused(what: String, bytes: ByteArray) {
        try {
            Sf2Fixture.font(bytes)
            fail("$what was read")
        } catch (e: Sf2Exception) {
            assertTrue("$what: ${e.message}", !e.message.isNullOrBlank())
        }
    }

    private fun indexOf(bytes: ByteArray, id: String): Int {
        val needle = id.toByteArray()
        outer@ for (i in 0..bytes.size - 4) {
            for (j in 0 until 4) if (bytes[i + j] != needle[j]) continue@outer
            return i
        }
        error("no $id")
    }
}
