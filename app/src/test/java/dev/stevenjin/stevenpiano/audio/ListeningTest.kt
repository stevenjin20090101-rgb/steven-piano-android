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
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executor

/**
 * The microphone's safeguards (v1.23 — M55): nothing hears unless listening is on and the app is in front, one session
 * listens at a time, each frame goes to the sessions that hear and nowhere else, and live listening ends by itself.
 */
class ListeningTest {
    private class FakeMicrophone : Microphone {
        var works = true
        var on = false
        var starts = 0
        private var frames: ((MicFrame) -> Unit)? = null
        var ended: (() -> Unit)? = null

        override fun start(frames: (MicFrame) -> Unit, ended: () -> Unit): Boolean {
            if (!works) return false
            starts++
            on = true
            this.frames = frames
            this.ended = ended
            return true
        }

        override fun stop() {
            on = false
            frames = null
        }

        fun hear() = frames?.invoke(MicFrame(ShortArray(Microphone.FRAME) { (it * 7).toShort() }, 0, 0))
    }

    private class Room(scope: TestScope) {
        val mic = FakeMicrophone()
        var allowed = true
        val valid = mutableSetOf("A", "B")
        val open = mutableSetOf("A", "B")
        val sent = mutableListOf<Pair<String, String>>()
        val listening = Listening(
            scope.backgroundScope,
            mic,
            allowedNow = { allowed },
            sessionValid = { it in valid },
            send = { token, text ->
                sent += token to text
                if (token in open) 1 else 0
            },
            clock = { scope.testScheduler.currentTime },
            sender = Executor { it.run() },
            io = StandardTestDispatcher(scope.testScheduler),
        )
    }

    @Test
    fun `nothing hears unless listening is on and the app is in front, and one session listens at a time`() = runTest {
        val room = Room(this)
        val listening = room.listening
        room.allowed = false
        listening.foreground(true)
        assertEquals(ListenAnswer.Refused(ListenAnswer.OFF, ListenCopy.OFF), listening.startLive("A"))
        room.allowed = true
        listening.foreground(false)
        assertEquals(ListenAnswer.Refused(ListenAnswer.AWAY, ListenCopy.AWAY), listening.startLive("A"))
        assertEquals("never started", 0, room.mic.starts)
        listening.foreground(true)
        assertEquals(ListenAnswer.Started, listening.startLive("A"))
        assertEquals(ListenAnswer.Refused(ListenAnswer.BUSY, ListenCopy.BUSY), listening.startLive("B"))
        assertEquals("the same session again", ListenAnswer.Started, listening.startLive("A"))
        assertEquals(1, room.mic.starts)
        assertTrue(listening.state.value.on && listening.state.value.panel && listening.state.value.allowed)
        listening.stopLive()
        assertFalse(room.mic.on)
        room.mic.works = false
        assertEquals(ListenAnswer.Refused(ListenAnswer.MICROPHONE, ListenCopy.MICROPHONE), listening.startLive("B"))
        assertFalse(listening.state.value.on)
    }

    @Test
    fun `each frame goes to the sessions that hear and nowhere else, as one small audio message`() = runTest {
        val room = Room(this)
        val listening = room.listening
        listening.foreground(true)
        listening.startLive("A")
        room.mic.hear()
        assertEquals(listOf("A"), room.sent.map { it.first })
        val message = JSONObject(room.sent.single().second)
        assertEquals("audio", message.getString("type"))
        assertEquals(0, message.getLong("seq"))
        var tapped = 0
        listening.tap("B", { tapped++ }, {})   // the key test, started by session B
        room.mic.hear()
        assertEquals(listOf("A", "A", "B"), room.sent.map { it.first })
        assertEquals(1, JSONObject(room.sent.last().second).getLong("seq"))
        assertEquals(1, tapped)
        listening.untap()
        listening.stopLive()
        assertFalse("nobody hears: the microphone stops", room.mic.on)
        room.mic.hear()
        assertEquals(3, room.sent.size)
        listening.tap(null, { tapped++ }, {})   // the tablet's own key test: heard, sent nowhere
        room.mic.hear()
        assertEquals(3, room.sent.size)
        assertEquals(2, tapped)
        assertFalse("the banner says it hears for the tablet's test", listening.state.value.panel)
    }

    @Test
    fun `live listening ends by itself after five minutes, when its session ends, or when no panel of it is open`() = runTest {
        val room = Room(this)
        val listening = room.listening
        listening.foreground(true)
        listening.startLive("A")
        assertEquals(Listening.LIVE_MS, listening.state.value.until)
        repeat(299) {
            testScheduler.advanceTimeBy(1_000)
            runCurrent()
            room.mic.hear()
        }
        assertTrue("on at 4:59", listening.state.value.on)
        testScheduler.advanceTimeBy(2_000)
        runCurrent()
        assertFalse("off after five minutes", listening.state.value.on)
        assertFalse(room.mic.on)

        listening.startLive("A")
        room.valid -= "A"   // logged out, or the PIN changed
        testScheduler.advanceTimeBy(1_100)
        runCurrent()
        assertFalse(listening.state.value.on)

        room.valid += "A"
        room.open -= "A"   // its page closed
        listening.startLive("A")
        repeat(14) {
            testScheduler.advanceTimeBy(1_000)
            runCurrent()
            room.mic.hear()
        }
        assertTrue(listening.state.value.on)
        testScheduler.advanceTimeBy(2_000)
        runCurrent()
        assertFalse("no panel of its session reached for 15 s", listening.state.value.on)
    }

    @Test
    fun `the switch off, the app leaving the screen and the microphone failing end every hearing, the key test told`() = runTest {
        val room = Room(this)
        val listening = room.listening
        var ended = 0
        listening.foreground(true)
        listening.startLive("A")
        listening.tap("B", {}, { ended++ })
        room.allowed = false
        listening.refresh()
        assertFalse(listening.state.value.on || listening.state.value.allowed)
        assertEquals(1, ended)

        room.allowed = true
        listening.startLive("A")
        listening.tap(null, {}, { ended++ })
        listening.foreground(false)
        assertFalse(listening.state.value.on)
        assertEquals(2, ended)

        listening.foreground(true)
        listening.tap(null, {}, { ended++ })
        room.mic.ended!!.invoke()
        runCurrent()
        assertFalse(listening.state.value.on)
        assertEquals(3, ended)
    }
}
