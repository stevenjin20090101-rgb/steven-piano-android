// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.audio

import dev.stevenjin.stevenpiano.ble.EmulatedConsole
import dev.stevenjin.stevenpiano.piano.LoudnessSteps
import dev.stevenjin.stevenpiano.ui.ListenCopy
import dev.stevenjin.stevenpiano.ui.StepsCopy
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
import kotlin.math.pow

/**
 * Find the loudness steps (M83; piano safety): its refusals, the command sequence (one `calstrike` every 300 ms at most,
 * then the rows, `velsteps`, `fullpower 0` and `save`, each answered before the next), nothing written when it stops
 * partway, and every reason it stops, each with the player's stop sequence. The piano is the emulated 2.1.5 console on the
 * test's clock; the microphone hears each strike at a made-up loudness.
 */
class StepsCalibrationTest {
    /** The piano: the emulated console answers each line, a little later; what was sent, struck and asked is kept. */
    private class Piano(val now: () -> Long) : StepsCalibration.Piano {
        val console = EmulatedConsole(now = now)
        override var connected = true
        override var steps = true
        override var writable = true
        override var playing = false
        var quietWords: String? = null
        override var guest = false
        override var updating = false
        override var inFront = true
        var busyWords: String? = null
        var plugWords: String? = null
        override var railUp = true
        val sent = mutableListOf<String>()
        val struck = mutableListOf<Triple<Int, Int, Long>>()
        var stops = 0
        var muted = false
        var rereads = 0

        /** The piano's reply to a line: the console's own, unless a test says otherwise. */
        var answer: (String) -> String? = { line -> console.handle(line).firstOrNull { LoudnessSteps.answers(line, it) } }

        override fun quiet(): String? = quietWords

        override fun busy(): String? = busyWords

        override fun plugUnsafe(): String? = plugWords

        override suspend fun send(line: String): String? {
            val at = now()
            sent += line
            val reply = answer(line)
            delay(REPLY_MS)
            if (reply != null && LoudnessSteps.read(line, reply) is LoudnessSteps.Reply.Struck) {
                val (key, pwm) = line.removePrefix("calstrike ").split(' ').map(String::toInt)
                struck += Triple(key, pwm, at)
            }
            return reply
        }

        override fun stopSequence() {
            stops++
        }

        override fun muteTablet(on: Boolean) {
            muted = on
        }

        override fun reread() {
            rereads++
        }

        /** What was written: every line but the strikes. */
        val written: List<String> get() = sent.filterNot { it.startsWith("calstrike") }
    }

    private class FakeEars : Ears {
        var allowed = true
        var frames: ((MicFrame) -> Unit)? = null
        var ended: (() -> Unit)? = null

        override fun allowed(token: String?): Boolean = allowed

        override fun tap(token: String?, frames: (MicFrame) -> Unit, ended: () -> Unit): ListenAnswer {
            this.frames = frames
            this.ended = ended
            return ListenAnswer.Started
        }

        override fun untap() {
            frames = null
            ended = null
        }
    }

    private class Rig(val piano: Piano, val ears: FakeEars, val steps: StepsCalibration)

    private fun TestScope.setUp(before: suspend () -> Boolean = { true }, noise: Double = NOISE): Rig {
        val piano = Piano { testScheduler.currentTime }
        val ears = FakeEars()
        val steps = StepsCalibration(backgroundScope, piano, ears, nanos = { testScheduler.currentTime * NANOS_PER_MS }, before = before, keys = KEYS)
        room(piano, ears, noise)
        return Rig(piano, ears, steps)
    }

    /**
     * The room, a frame every 100 ms of virtual time while the microphone hears: a little noise and, for each strike, its
     * key's note at the loudness [loudness] gives it.
     */
    private fun TestScope.room(piano: Piano, ears: FakeEars, noise: Double) = backgroundScope.launch {
        var index = 0L
        val random = java.util.Random(5)
        while (true) {
            delay(FRAME_MS)
            val hear = ears.frames ?: continue
            val from = testScheduler.currentTime - FRAME_MS
            val samples = DoubleArray(Microphone.FRAME) { random.nextGaussian() * noise }
            for ((key, pwm, at) in piano.struck.toList()) {
                if (from - at > 800) continue
                val db = loudness(key, pwm) ?: continue
                val amplitude = 10.0.pow(db / 20.0) / NOTE_RMS
                for (n in samples.indices) {
                    val since = (from - at) / 1000.0 + n.toDouble() / Tones.RATE
                    if (since >= 0) samples[n] += Tones.note(key, since, amplitude = amplitude)
                }
            }
            hear(MicFrame(Tones.pcm(samples), index, from * NANOS_PER_MS))
            index += samples.size
        }
    }

    /** The made-up keys: 60 wide, 61 too narrow for two steps, 62 never sounding, 63 a fair window. */
    private fun loudness(key: Int, pwm: Int): Double? {
        val (floor, top, rise) = when (key) {
            60 -> Triple(1_000, 3_000, 30.0)
            61 -> Triple(1_200, 2_400, 2.5)
            62 -> return null
            else -> Triple(1_400, 3_300, 14.0)
        }
        if (pwm < floor) return null
        val x = ((pwm - floor).toDouble() / (top - floor)).coerceIn(0.0, 1.0)
        return SOFT_DB + rise * (1 - (1 - x).pow(2))
    }

    private fun TestScope.advance(ms: Long) {
        testScheduler.advanceTimeBy(ms)
        runCurrent()
    }

    @Test
    fun `it is refused, saying why, and nothing is struck`() = runTest {
        val (piano, ears, steps) = setUp().let { Triple(it.piano, it.ears, it.steps) }
        fun refused(message: String) {
            val answer = steps.start()
            assertTrue("$answer", answer is ListenAnswer.Refused && answer.message == message)
            assertFalse(steps.running)
        }
        piano.connected = false
        refused(StepsCopy.NOT_CONNECTED)
        piano.connected = true
        piano.steps = false
        refused(StepsCopy.NO_STEPS)
        piano.steps = true
        piano.writable = false
        refused(StepsCopy.READ_ONLY)
        piano.writable = true
        piano.playing = true
        refused(StepsCopy.PLAYING)
        piano.playing = false
        piano.quietWords = "Quiet until 9:30. Use Play anyway."
        refused("Quiet until 9:30. Use Play anyway.")
        piano.quietWords = null
        piano.guest = true
        refused(StepsCopy.GUEST)
        piano.guest = false
        piano.updating = true
        refused(StepsCopy.UPDATING)
        piano.updating = false
        piano.inFront = false
        refused(StepsCopy.AWAY)
        piano.inFront = true
        piano.busyWords = StepsCopy.BUSY
        refused(StepsCopy.BUSY)
        piano.busyWords = null
        piano.plugWords = "The power plug may be stuck on."
        refused("The power plug may be stuck on.")
        piano.plugWords = null
        ears.allowed = false
        refused(ListenCopy.NO_PERMISSION)
        ears.allowed = true
        assertEquals(ListenAnswer.Started, steps.start())
        assertEquals(ListenAnswer.Refused(StepsCalibration.RUNNING, StepsCopy.RUNNING), steps.start())
        steps.stop()
        assertTrue("nothing struck by the refused starts", piano.sent.isEmpty())
    }

    @Test
    fun `every key is struck, paced, then the rows go, then velsteps, fullpower 0 and save, each answered before the next`() = runTest {
        val rig = setUp()
        val piano = rig.piano
        assertEquals(ListenAnswer.Started, rig.steps.start())
        assertTrue("the tablet's own sound held silent", piano.muted)
        advance(StepsCalibration.ROOM_MS + 10 * StepsCalibration.STRIKE_EVERY_MS)
        val mid = rig.steps.state.value
        assertTrue(mid.running)
        assertEquals(StepsPhase.Keys, mid.phase)
        assertEquals(60, mid.key)
        assertEquals("key 1 of 4", 1 to 4, mid.number to mid.total)
        assertTrue("nothing written while it strikes", piano.written.isEmpty())
        advance(2 * 60_000)
        val done = rig.steps.state.value
        assertFalse(done.running)
        assertNull(done.stopped)
        val result = done.result!!
        assertEquals("60 and 63 calibrated", setOf(60, 63), result.rows.keys)
        assertEquals("61 too narrow, 62 never heard", listOf(61, 62), result.uncalibrated)
        // One calstrike every 300 ms at most, every one struck (the console's own limit never said "too soon").
        val times = piano.struck.map { it.third }
        assertTrue(times.zipWithNext().all { (a, b) -> b - a >= LoudnessSteps.STRIKE_GAP_MS })
        assertTrue(piano.sent.filter { it.startsWith("calstrike") }.size == piano.struck.size)
        assertEquals("the never-heard key had the coarse look only", StepPicker.COARSE.size, piano.struck.count { it.first == 62 })
        assertEquals(StepsCalibration.PER_KEY, piano.struck.count { it.first == 60 })
        // The writing: the old rows of the uncalibrated keys cleared, a row per calibrated key, then velsteps, fullpower 0, save.
        val rows = result.rows
        val longest = rows.values.maxOf { it.size }
        assertEquals(
            listOf("steptable 61 clear", "steptable 62 clear") +
                rows.map { (key, pwms) -> LoudnessSteps.row(key, pwms) } +
                listOf("velsteps $longest", "fullpower 0", "save"),
            piano.written,
        )
        assertTrue("60's window holds more steps than 63's", rows.getValue(60).size > rows.getValue(63).size)
        assertTrue(rows.values.all { pwms -> pwms.size in 2..8 && pwms.zipWithNext().all { (a, b) -> b > a } })
        // What the piano holds now.
        assertEquals(rows, piano.console.stepRows())
        assertEquals("$longest", piano.console.valueOf("velsteps"))
        assertEquals("0", piano.console.valueOf("fullpower"))
        assertEquals("${rows.size}", piano.console.valueOf("!stepkeys"))
        assertEquals("read again", 1, piano.rereads)
        assertTrue("its end comes with the stop sequence", piano.stops >= 1)
        assertFalse(piano.muted)
        assertNull("the microphone let go", rig.ears.frames)
        assertTrue(done.shown)
        rig.steps.dismiss()
        assertFalse(rig.steps.state.value.shown)
    }

    @Test
    fun `stopped partway, nothing is written, with the stop sequence`() = runTest {
        val rig = setUp()
        rig.steps.start()
        advance(StepsCalibration.ROOM_MS + 40 * StepsCalibration.STRIKE_EVERY_MS)
        assertTrue(rig.steps.running)
        rig.steps.stop()
        val stopped = rig.steps.state.value
        assertEquals(StepsCopy.STOPPED, stopped.stopped)
        assertEquals(0, stopped.written)
        val sent = rig.piano.sent.size
        advance(60_000)
        assertEquals("nothing after the stop", sent, rig.piano.sent.size)
        assertTrue("only strikes", rig.piano.written.isEmpty())
        assertEquals(1, rig.piano.stops)
        assertFalse(rig.piano.muted)
        assertNull(rig.ears.frames)
        assertTrue(rig.piano.console.stepRows().isEmpty())
        assertEquals("Full power as it was", "1", rig.piano.console.valueOf("fullpower"))
    }

    /** Starts, strikes a while, makes [cause] happen, and checks it stopped saying [words], nothing written, the stop sequence sent. */
    private fun stopsFor(words: String, cause: (Rig) -> Unit) = runTest {
        val rig = setUp()
        assertEquals(ListenAnswer.Started, rig.steps.start())
        advance(StepsCalibration.ROOM_MS + 5 * StepsCalibration.STRIKE_EVERY_MS)
        assertTrue(rig.steps.running)
        cause(rig)
        advance(1_500)   // the slowest: "too soon" ten times over
        val state = rig.steps.state.value
        assertFalse(words, state.running)
        assertEquals(words, state.stopped)
        assertTrue("nothing written ($words)", rig.piano.written.isEmpty())
        assertEquals("the stop sequence ($words)", 1, rig.piano.stops)
        assertNull(rig.ears.frames)
        val sent = rig.piano.sent.size
        advance(10_000)
        assertEquals("nothing more ($words)", sent, rig.piano.sent.size)
    }

    @Test
    fun `it stops at once, saying why, for every reason`() {
        stopsFor(StepsCopy.STOPPED_DISCONNECTED) { it.piano.connected = false }
        stopsFor(StepsCopy.STOPPED_PLAYING) { it.piano.playing = true }
        stopsFor(StepsCopy.STOPPED_QUIET) { it.piano.quietWords = "Quiet until 9:30." }
        stopsFor(StepsCopy.STOPPED_UPDATING) { it.piano.updating = true }
        stopsFor(StepsCopy.STOPPED_GUEST) { it.piano.guest = true }
        stopsFor(StepsCopy.STOPPED_AWAY) { it.piano.inFront = false }
        stopsFor(StepsCopy.STOPPED_PLUG) { it.piano.plugWords = "The power plug isn't answering." }
        stopsFor(StepsCopy.STOPPED_RAIL) { it.piano.railUp = false }
        stopsFor(StepsCopy.STOPPED_MICROPHONE) { it.ears.ended?.invoke() }
        stopsFor(StepsCopy.STOPPED) { it.steps.stop() }
        stopsFor(StepsCopy.STOPPED_SILENT) { rig -> rig.piano.answer = { null } }
        stopsFor(StepsCopy.refused("calstrike refused: that key is busy or has no board")) { rig ->
            rig.piano.answer = { "  calstrike refused: that key is busy or has no board" }
        }
        stopsFor(StepsCopy.refused("refused — updating firmware")) { rig -> rig.piano.answer = { "  refused — updating firmware" } }
        stopsFor(StepsCopy.refused("calstrike 60 at 9: struck for 100 ms")) { rig -> rig.piano.answer = { "  calstrike 60 at 9: struck for 100 ms" } }
        stopsFor(StepsCopy.STOPPED_TOO_SOON) { rig -> rig.piano.answer = { "  calstrike refused: too soon (one every 300 ms)" } }
    }

    @Test
    fun `it stops before striking when the 24 V doesn't come up, and when the room is too loud`() = runTest {
        val rail = setUp(before = { false })
        rail.steps.start()
        advance(1)
        assertEquals(StepsCopy.STOPPED_RAIL, rail.steps.state.value.stopped)
        assertTrue(rail.piano.sent.isEmpty())
        assertEquals(1, rail.piano.stops)

        val loud = setUp(noise = 0.05)
        loud.steps.start()
        advance(StepsCalibration.ROOM_MS + 1_000)
        assertEquals(StepsCopy.TOO_LOUD, loud.steps.state.value.stopped)
        assertTrue(loud.piano.sent.isEmpty())
    }

    @Test
    fun `"too soon" is waited out and sent again`() = runTest {
        val rig = setUp()
        var refusals = 0
        val console = rig.piano.answer
        rig.piano.answer = { line ->
            if (line.startsWith("calstrike") && refusals < 3) {
                refusals++
                "  calstrike refused: too soon (one every 300 ms)"
            } else {
                console(line)
            }
        }
        rig.steps.start()
        advance(StepsCalibration.ROOM_MS + 3 * StepsCalibration.STRIKE_EVERY_MS)
        assertTrue(rig.steps.running)
        assertEquals("the first strike sent four times", 4, rig.piano.sent.count { it == "calstrike 60 600" })
        assertEquals(listOf(60 to 600, 60 to 920, 60 to 1240), rig.piano.struck.take(3).map { it.first to it.second })
        rig.steps.stop()
    }

    @Test
    fun `a refusal while writing stops it there, saying how far it got`() = runTest {
        val rig = setUp()
        val console = rig.piano.answer
        rig.piano.answer = { line -> if (line == "save") "[settings] save FAILED — could not open NVS RW" else console(line) }
        rig.steps.start()
        advance(2 * 60_000)
        val state = rig.steps.state.value
        assertEquals(StepsCopy.refused("[settings] save FAILED — could not open NVS RW"), state.stopped)
        assertEquals("two cleared, two rows", 4, state.written)
        assertTrue("Full power had gone off", state.fullPowerOff)
        assertEquals(0, rig.piano.rereads)
        assertEquals(1, rig.piano.stops)
    }

    private companion object {
        val KEYS = 60..63
        const val FRAME_MS = 100L
        const val REPLY_MS = 30L
        const val NANOS_PER_MS = 1_000_000L
        const val NOISE = 0.0003
        const val SOFT_DB = -50.0

        /** [Tones.note]'s RMS for an amplitude of 1 (six harmonics falling as 1/n). */
        const val NOTE_RMS = 0.87
    }
}
