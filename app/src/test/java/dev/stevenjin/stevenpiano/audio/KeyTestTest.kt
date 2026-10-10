// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.audio

import dev.stevenjin.stevenpiano.ui.ListenCopy
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToInt

/**
 * Test every key (v1.23 — M55): its refusals in the brief's order, its timing (84 keys low to high, one every 450 ms, 150 ms
 * long, velocity 100), what it hears, and how it ends (Stop with the stop sequence; a piece starting; the piano gone).
 */
class KeyTestTest {
    /** The piano through the player: what it was asked, and when (virtual ms). */
    private class Piano(val now: () -> Long) : KeyTest.Piano {
        override var connected = true
        override var playing = false
        var quietWords: String? = null
        override var updating = false
        val asked = mutableListOf<String>()
        val struck = mutableListOf<Pair<Int, Long>>()
        var muted = false

        override fun quiet(): String? = quietWords

        override fun noteOn(key: Int, velocity: Int) {
            asked += "on $key $velocity @${now()}"
            struck += key to now()
        }

        override fun noteOff(key: Int) {
            asked += "off $key @${now()}"
        }

        override fun stopSequence() {
            asked += "stop @${now()}"
        }

        override fun muteTablet(on: Boolean) {
            muted = on
        }
    }

    /** The microphone as the test taps it. */
    private class FakeEars : Ears {
        var allowed = true
        var refusal: ListenAnswer.Refused? = null
        var token: String? = null
        var frames: ((MicFrame) -> Unit)? = null
        var ended: (() -> Unit)? = null

        override fun allowed(): Boolean = allowed

        override fun tap(token: String?, frames: (MicFrame) -> Unit, ended: () -> Unit): ListenAnswer {
            refusal?.let { return it }
            this.token = token
            this.frames = frames
            this.ended = ended
            return ListenAnswer.Started
        }

        override fun untap() {
            frames = null
            ended = null
        }
    }

    private fun TestScope.setUp(): Triple<Piano, FakeEars, KeyTest> {
        val piano = Piano { testScheduler.currentTime }
        val ears = FakeEars()
        val test = KeyTest(backgroundScope, piano, ears, nanos = { testScheduler.currentTime * 1_000_000 })
        return Triple(piano, ears, test)
    }

    /**
     * The room the microphone hears, a frame every 100 ms of virtual time: the notes of the keys struck, but [dead] ones,
     * over a little noise.
     */
    private fun TestScope.room(piano: Piano, ears: FakeEars, dead: Set<Int>) = backgroundScope.launch {
        var index = 0L
        val random = java.util.Random(3)
        while (true) {
            delay(FRAME_MS)
            val from = testScheduler.currentTime - FRAME_MS
            val samples = DoubleArray(Microphone.FRAME) { random.nextGaussian() * 0.001 }
            for ((key, at) in piano.struck.toList()) {
                if (key in dead || from - at > (Tones.HEARD_S * 1000).toLong()) continue
                for (n in samples.indices) {
                    val since = (from - at) / 1000.0 + n.toDouble() / Tones.RATE
                    if (since >= 0 && since < Tones.HEARD_S) samples[n] += Tones.note(key, since)
                }
            }
            ears.frames?.invoke(MicFrame(Tones.pcm(samples), index, from * 1_000_000))
            index += samples.size
        }
    }

    @Test
    fun `it is refused, in the brief's order, and nothing is struck`() = runTest {
        val (piano, ears, test) = setUp()
        fun refused(code: String, message: String) {
            assertEquals(ListenAnswer.Refused(code, message), test.start("A"))
            assertFalse(test.running)
        }
        piano.connected = false
        piano.playing = true
        refused(ListenAnswer.NOT_CONNECTED, ListenCopy.NOT_CONNECTED)
        piano.connected = true
        refused(ListenAnswer.PLAYING, ListenCopy.PLAYING)
        piano.playing = false
        piano.quietWords = "Quiet until 9:30. Use Play anyway."
        piano.updating = true
        refused(ListenAnswer.QUIET, "Quiet until 9:30. Use Play anyway.")
        piano.quietWords = null
        refused(ListenAnswer.UPDATING, ListenCopy.UPDATING)
        piano.updating = false
        ears.allowed = false
        refused(ListenAnswer.OFF, ListenCopy.OFF)
        ears.allowed = true
        ears.refusal = ListenAnswer.Refused(ListenAnswer.AWAY, ListenCopy.AWAY)
        refused(ListenAnswer.AWAY, ListenCopy.AWAY)
        ears.refusal = null
        assertEquals(ListenAnswer.Started, test.start("A"))
        assertEquals(ListenAnswer.Refused(ListenAnswer.TESTING, ListenCopy.TESTING), test.start("B"))
        assertEquals("the first test hears on, for its own panel", "A", ears.token)
        assertTrue(test.running && piano.muted)
        test.stop()
        assertEquals("the refused starts struck nothing; Stop sent the stop sequence", listOf("stop @0"), piano.asked)
    }

    @Test
    fun `it strikes the 84 keys low to high, one every 450 ms, 150 ms each at velocity 100, the tablet silent, and names the keys not heard`() = runTest {
        val (piano, ears, test) = setUp()
        room(piano, ears, dead = setOf(36, 66, 93))
        assertEquals(ListenAnswer.Started, test.start("A"))
        assertEquals("the panel that asked hears it", "A", ears.token)
        assertTrue(piano.muted)
        testScheduler.advanceTimeBy(KeyTest.LEAD_MS + 36 * KeyTest.EVERY_MS + 1)
        runCurrent()
        assertEquals(KeyTestState(running = true, key = 60, done = 37), test.state.value)
        testScheduler.advanceTimeBy(KeyTest.TOTAL * KeyTest.EVERY_MS + KeyTest.TAIL_MS)
        runCurrent()
        val expected = KeyTest.KEYS.flatMapIndexed { i, key ->
            val at = KeyTest.LEAD_MS + i * KeyTest.EVERY_MS
            listOf("on $key 100 @$at", "off $key @${at + KeyTest.HOLD_MS}")
        }
        assertEquals(expected, piano.asked)
        val done = test.state.value
        assertFalse(done.running)
        assertNull(done.stopped)
        assertEquals(listOf(36, 66, 93), done.results!!.filterNot { it.heard }.map { it.key })
        assertEquals(84, done.results!!.size)
        assertFalse("the tablet sounds again", piano.muted)
        assertNull("the microphone let go", ears.frames)
        assertEquals("about 39 s", 38_900L, KeyTest.LEAD_MS + KeyTest.TOTAL * KeyTest.EVERY_MS + KeyTest.TAIL_MS)
        assertEquals(84, KeyTest.TOTAL)
        assertEquals((38_900 / 1000.0).roundToInt(), 39)
    }

    @Test
    fun `Stop ends it at once with the player's stop sequence, the key down let go`() = runTest {
        val (piano, ears, test) = setUp()
        test.start(null)
        assertNull("the tablet's own test: no panel hears it", ears.token)
        testScheduler.advanceTimeBy(KeyTest.LEAD_MS + 10 * KeyTest.EVERY_MS + 50)
        runCurrent()
        test.stop()
        val at = KeyTest.LEAD_MS + 10 * KeyTest.EVERY_MS + 50
        assertEquals(listOf("on 34 100 @${at - 50}", "off 34 @$at", "stop @$at"), piano.asked.takeLast(3))
        testScheduler.advanceTimeBy(5_000)
        runCurrent()
        assertEquals("nothing struck after", "stop @$at", piano.asked.last())
        assertEquals(KeyTestState(stopped = ListenCopy.STOPPED), test.state.value)
        assertFalse(piano.muted)
        assertNull(ears.frames)
    }

    @Test
    fun `it ends by itself when a piece starts, the piano goes or the microphone stops, and says why`() = runTest {
        val (piano, ears, test) = setUp()
        test.start("A")
        testScheduler.advanceTimeBy(KeyTest.LEAD_MS + 3 * KeyTest.EVERY_MS + 200)
        runCurrent()
        piano.playing = true
        testScheduler.advanceTimeBy(KeyTest.EVERY_MS)
        runCurrent()
        assertEquals(ListenCopy.STOPPED_PLAYING, test.state.value.stopped)
        assertFalse("a piece plays: its keys are not the test's to silence", piano.asked.any { it.startsWith("stop") })
        assertEquals(4, piano.asked.count { it.startsWith("on") })

        piano.playing = false
        test.start("A")
        testScheduler.advanceTimeBy(KeyTest.LEAD_MS + 1)
        runCurrent()
        piano.connected = false
        testScheduler.advanceTimeBy(KeyTest.EVERY_MS)
        runCurrent()
        assertEquals(ListenCopy.STOPPED_DISCONNECTED, test.state.value.stopped)

        piano.connected = true
        test.start("A")
        testScheduler.advanceTimeBy(KeyTest.LEAD_MS + 1)
        runCurrent()
        ears.ended!!.invoke()
        assertEquals(ListenCopy.STOPPED_MICROPHONE, test.state.value.stopped)
        assertTrue("the microphone gone mid-strike: the key let go, then the stop sequence", piano.asked.last().startsWith("stop"))
        assertNull(test.state.value.results)
    }

    private companion object {
        const val FRAME_MS = 100L
    }
}
