// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.audio

import dev.stevenjin.stevenpiano.midi.MidiBatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * The voice's queue (v1.8 — M25): what the player's thread queues reaches the sampler in order when the
 * audio thread takes it, the piano's messages mean what they mean to the piano, a queue nobody empties
 * starts again from silence rather than growing, and a font swapped in plays at once.
 */
class PianoVoiceTest {
    private val piano = Sf2Fixture.font(Sf2Fixture.piano())
    private val rate = 48_000

    private fun voice() = PianoVoice(rate).apply {
        load(piano)
        volume(50)
    }

    private fun PianoVoice.run(frames: Int = 4_800): FloatArray = FloatArray(frames).also { render(it, frames) }

    @Test
    fun `the piano's messages play as the piano plays them`() {
        val voice = voice()
        val batch = MidiBatch().apply {
            add(0xB0, 64, 127)   // pedal down
            add(0x90, 60, 90)
            add(0x80, 60, 0)     // held by the pedal
            add(0x90, 64, 0)     // a Note On of velocity 0 is a release
            add(0xE0, 0, 64)     // pitch bend: ignored, as the piano ignores it
        }
        voice.play(batch)
        voice.run()
        assertEquals(1, voice.voices())
        assertTrue(voice.sounding())
        voice.play(MidiBatch().apply { add(0xB0, 64, 0); add(0xB0, 123, 0) })   // the stop sequence
        voice.run(rate / 2)
        assertFalse("released within the fixture's 300 ms", voice.sounding())
    }

    @Test
    fun `the player's thread queues, the audio thread takes, in order`() {
        val voice = voice()
        val done = CountDownLatch(1)
        thread {
            for (i in 0 until 500) {
                voice.noteOn(48 + i % 24, 80)
                voice.noteOff(48 + i % 24)
            }
            done.countDown()
        }
        assertTrue(done.await(5, TimeUnit.SECONDS))
        voice.run(64)
        assertTrue("the notes played, the last ones releasing", voice.voices() > 0)
        voice.run(rate)
        assertEquals(0, voice.voices())
    }

    @Test
    fun `a queue nobody takes from starts again from silence`() {
        val voice = voice()
        voice.noteOn(60, 100)
        voice.run()
        assertEquals(1, voice.voices())
        repeat(10_000) { voice.noteOff(62) }   // nothing renders meanwhile (the output refused): the queue overflows
        voice.run(Sampler.FADE_MS * rate / 1000 + 256)
        assertEquals("the silence it started from faded the held note out", 0, voice.voices())
    }

    @Test
    fun `reset silences at once, and a font swapped in plays at once`() {
        val voice = voice()
        for (key in listOf(48, 60, 72)) voice.noteOn(key, 100)
        voice.run()
        assertEquals(3, voice.voices())
        voice.reset()
        voice.drain()
        assertEquals(0, voice.voices())
        voice.load(null)
        voice.noteOn(60, 100)
        assertFalse(voice.render(FloatArray(256), 256))
        voice.load(piano)
        voice.noteOn(60, 100)
        assertTrue(voice.render(FloatArray(256), 256))
    }

    @Test
    fun `each queued event wakes the output`() {
        val voice = voice()
        var woken = 0
        voice.onPost = { woken++ }
        voice.play(MidiBatch().apply { add(0x90, 60, 100); add(0x80, 60, 0) })
        voice.sustain(true)
        assertEquals(3, woken)
    }
}
