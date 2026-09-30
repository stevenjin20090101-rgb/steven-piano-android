// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.audio

import dev.stevenjin.stevenpiano.midi.SmfBuilder
import dev.stevenjin.stevenpiano.midi.SmfParser
import dev.stevenjin.stevenpiano.studio.WavReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

/**
 * A phrase played end to end without a tablet (v1.8 — M25): a MIDI file through the app's parser and
 * [dev.stevenjin.stevenpiano.player.PlaybackEngine] (its router, the pedal, the stop sequence at the
 * end), into the tablet's voice on the fixture piano, rendered and written as the output writes it, a
 * 48 kHz stereo WAV; the app's own [WavReader] reads it back, and its level is what the fixture and the
 * gain laws say, with nothing clipped and silence after the release.
 */
class OfflineRenderTest {
    private val rate = 48_000

    /** An arpeggio under the pedal, a chord, and the pedal up: 480 ticks a quarter at 120 bpm (a tick is 1/960 s). */
    private val phrase = SmfParser.parse(
        SmfBuilder(format = 0).track {
            cc(0, 64, 127)
            for ((i, key) in listOf(48, 55, 60, 64, 67, 72).withIndex()) {
                noteOn(i * 240L, key, 70)
                noteOff(i * 240L + 200, key)
            }
            for (key in listOf(60, 64, 67)) noteOn(1_920, key, 96)
            for (key in listOf(60, 64, 67)) noteOff(2_880, key)
            cc(2_900, 64, 0)
            end(3_000)
        }.build(),
    )

    @Test
    fun `a phrase renders to a WAV the app reads back, loud enough, never clipped, silent after`() {
        val mono = OfflineRender.piece(Sf2Fixture.font(Sf2Fixture.piano()), phrase, rate, volume = 50, tailSeconds = 1.0)
        val seconds = mono.size.toDouble() / rate
        // To the last event (the pedal up at tick 2,900: 3.02 s), where the stop sequence goes, then the second's tail.
        assertEquals(2_900 / 960.0 + 1.0, seconds, 0.001)

        val wav = OfflineRender.wav(mono, rate)
        val back = WavReader.decode(ByteArrayInputStream(wav))
        assertEquals(rate, back.sourceRate)
        assertEquals(2, back.sourceChannels)
        assertEquals(seconds, back.seconds, 0.01)
        val read = back.samples.copyOf(back.size)

        val peak = OfflineRender.peak(read)
        val rms = OfflineRender.rms(read.copyOf((3.0 * 16_000).toInt()))
        // Six notes and a chord of the fixture's 0.5 and 0.25 sines at velocities 70 and 96, at half volume.
        assertTrue("peak $peak", peak in 0.05f..Limiter.CEILING)
        assertTrue("rms $rms", rms in 0.01f..0.3f)
        val pcm = java.nio.ByteBuffer.wrap(wav, 44, wav.size - 44).order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        var clipped = 0
        for (i in 0 until pcm.limit()) if (pcm.get(i) >= Short.MAX_VALUE || pcm.get(i) <= -Short.MAX_VALUE) clipped++
        assertEquals("no sample at full scale", 0, clipped)
        // The pedal lifts at 3.02 s and the stop sequence follows at the end: 300 ms of release, then silence.
        assertEquals(0f, OfflineRender.peak(mono.copyOfRange((3.5 * rate).toInt(), mono.size)), 0f)
        assertTrue("still sounding under the pedal", OfflineRender.peak(mono.copyOfRange((1.6 * rate).toInt(), (1.9 * rate).toInt())) > 0.01f)
    }

    @Test
    fun `the tablet plays nothing while it isn't the sink's to play`() {
        val voice = PianoVoice(rate)
        voice.load(Sf2Fixture.font(Sf2Fixture.piano()))
        var active = false
        val sink = voice.sink { active }
        val batch = dev.stevenjin.stevenpiano.midi.MidiBatch().apply { add(0x90, 60, 100) }
        sink.send(batch, false)
        assertTrue(!voice.pending())
        active = true
        sink.send(batch, false)
        assertTrue(voice.pending())
        val out = FloatArray(4_800)
        assertTrue(voice.render(out, out.size))
        assertTrue(OfflineRender.peak(out) > 0.01f)
    }
}
