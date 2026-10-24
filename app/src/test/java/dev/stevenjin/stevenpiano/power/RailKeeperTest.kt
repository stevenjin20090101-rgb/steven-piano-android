// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.power

import dev.stevenjin.stevenpiano.ble.FakePianoLink
import dev.stevenjin.stevenpiano.midi.MidiBatch
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToInt

/**
 * The 24 V rail's routine (v1.26 — M60; SMART_PLUG.md › The app's routine), piano safety: on a fake clock, with a fake
 * plug whose relay feeds fake boards (they answer 900 ms after the supply comes up, as firmware 2.1.0's re-probe does)
 * and a piano that can fall silent or lose a board. Every call the keeper makes is in [Bench.log], in order, with its time.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RailKeeperTest {
    /** One thing the keeper did: its [kind] ("CHECK", "ARM", "CUT", "STOP", "STOP PLAYING", …), whether a CHECK was [good]. */
    data class Event(val at: Long, val kind: String, val good: Boolean? = null, val answered: Int? = null)

    /** The plug, its relay, the supply and the seven boards behind it, and the piano's console. */
    inner class Bench(private val test: TestScope) {
        val log = mutableListOf<Event>()
        var output = false
        private var armedAt = 0L
        private var toggleS = 0
        private var onSince = 0L
        val link = MutableStateFlow(true)
        var connected: Boolean
            get() = link.value
            set(value) {
                link.value = value
            }
        var silent = false
        var missing = setOf<Int>()
        var controllerFed = false
        var armWorks = true
        var cutWorks = true
        val available = MutableStateFlow(true)
        var keeper: RailKeeper? = null
        val guestCalls = mutableListOf<String>()

        fun now(): Long = test.currentTime

        /** v1.29 — M66: switched on by another phone, no timer. */
        fun switchOnFromOutside() {
            if (!outputNow()) onSince = now()
            output = true
            armedAt = now()
            toggleS = Int.MAX_VALUE / 1_000
        }

        /** v1.29 — M66: what the next settings look finds changed (and puts back). */
        var settingsChanged = false

        /** v1.30 — M67: what the supply draws beyond its idle while the plug is on (a coil stuck on: 12 W). */
        var extraWatts = 0.0

        private fun outputNow(): Boolean {
            if (output && now() - armedAt >= toggleS * 1_000L) output = false
            return output
        }

        val plug = object : RailPlug {
            override suspend fun arm(seconds: Int): Boolean {
                delay(RPC_MS)
                if (!armWorks) {
                    log += Event(now(), "ARM FAILED")
                    return false
                }
                if (!outputNow()) onSince = now()
                output = true
                armedAt = now()
                toggleS = seconds
                log += Event(now(), "ARM")
                return true
            }

            override suspend fun cut(): Boolean {
                delay(RPC_MS)
                if (!cutWorks) {
                    log += Event(now(), "CUT FAILED")
                    return false
                }
                output = false
                log += Event(now(), "CUT")
                return true
            }

            override suspend fun status(): PlugStatus {
                delay(RPC_MS)
                log += Event(now(), "STATUS")
                return PlugStatus(outputNow(), watts = if (outputNow()) IDLE_WATTS + extraWatts else 0.0)
            }

            override suspend fun putBack(): Boolean {
                delay(RPC_MS)
                log += Event(now(), "SETTINGS")
                return settingsChanged.also { settingsChanged = false }
            }
        }

        /** The piano's `get boards`: 60 ms when it answers, 2 s and null when it doesn't (or at once without a link). */
        suspend fun check(): Boards? {
            if (!connected) {
                log += Event(now(), "CHECK", good = false)
                return null
            }
            if (silent) {
                delay(CHECK_TIMEOUT_MS)
                log += Event(now(), "CHECK", good = false)
                return null
            }
            delay(CHECK_MS)
            val up = outputNow() && now() - onSince >= BOARDS_UP_MS
            val boards = Boards((0 until Boards.TOTAL).map { it !in missing && (up || controllerFed) })
            val baseline = keeper?.baseline?.value
            log += Event(now(), "CHECK", good = baseline == null || boards.holds(baseline), answered = boards.answered)
            return boards
        }

        val guest = object : GuestLink {
            override fun letGo() {
                guestCalls += "let go"
                connected = false
            }

            override suspend fun reconnect(): Boolean {
                delay(RECONNECT_MS)
                guestCalls += "reconnect"
                connected = true
                return true
            }

            override fun endOnPiano() {
                guestCalls += "lock guest 0"
            }
        }

        /**
         * The power watch's other readings (v1.30 — M67): the router holding [heldKeys] keys with its pedal up, keys struck
         * this rail-up ([struck]), the piano's `!active` and `!i2cfails` ([active], [fails]; "ASK …" in the log), Full power;
         * a safety check made due is "CHECK DUE".
         */
        inner class Watch : PowerHost {
            var heldKeys = 0.0
            var pedal = false
            var struck = true
            var active = "0"
            var fails = "0"
            var due = 0

            override fun held(from: Long, to: Long): Double = heldKeys

            override fun pedalDown(from: Long, to: Long): Boolean = pedal

            override fun struckSince(at: Long): Boolean = struck

            override suspend fun ask(fact: String): String? {
                log += Event(now(), "ASK $fact")
                return when (fact) {
                    RailKeeper.ACTIVE -> active
                    RailKeeper.I2C_FAILS -> fails
                    else -> null
                }
            }

            override val fullPower: Boolean = true

            override fun checkDue() {
                due++
                log += Event(now(), "CHECK DUE")
            }

            /** v1.31 — M68: the plug's usual idle as kept (the last 5), and the trips above it. */
            val usual = mutableListOf<Double>()
            var above = 0

            override fun usualIdle(): List<Double> = usual.toList()

            override fun keepIdle(watts: Double) {
                usual += watts
                if (usual.size > PowerWatch.USUAL_KEPT) usual.removeAt(0)
            }

            override fun aboveUsual() {
                above++
            }
        }

        fun keeper(power: PowerHost? = null): RailKeeper = RailKeeper(
            scope = test.backgroundScope,
            plug = plug,
            check = ::check,
            connected = link,
            stopSequence = { log += Event(now(), "STOP") },
            stopPlaying = { log += Event(now(), "STOP PLAYING") },
            available = available,
            now = ::now,
            guest = guest,
            power = power,
        ).also {
            keeper = it
            it.start()
            test.runCurrent()
        }

        /**
         * What the keeper did from [from], in order; the welded-relay look after a CUT, the watch's ("STATUS", "SETTINGS") and the
         * power watch's asks ("ASK …", v1.30 — M67) left out.
         */
        fun kinds(from: Long = 0L): List<String> =
            log.filter { it.at >= from && it.kind != "STATUS" && it.kind != "SETTINGS" && !it.kind.startsWith("ASK") }.map { it.kind }

        fun arms(from: Long = 0L, until: Long = Long.MAX_VALUE): List<Event> = log.filter { it.kind == "ARM" && it.at in from until until }
    }

    /**
     * The rule: every ARM outside [exempt] comes right after a good CHECK (its own: no ARM between them, 3 s at most). A
     * CHECK at rail-up is good when the piano answered; after the baseline, when every baseline board did.
     */
    private fun Bench.assertEveryArmChecked(exempt: List<LongRange> = emptyList()) {
        for ((i, event) in log.withIndex()) {
            if (event.kind != "ARM" || exempt.any { event.at in it }) continue
            val before = log.subList(0, i).lastOrNull { it.kind == "CHECK" || it.kind == "ARM" }
            assertTrue("the ARM at ${event.at} ms follows its own CHECK: $before", before?.kind == "CHECK")
            assertTrue("the CHECK before the ARM at ${event.at} ms was good", before?.good == true)
            assertTrue("the CHECK just before it", event.at - before!!.at <= JUST_BEFORE_MS)
        }
    }

    private fun TestScope.upFor(bench: Bench, keeper: RailKeeper) {
        keeper.wanted(RailKeeper.PLAY, true)
        advanceTimeBy(RAIL_UP_SETTLE_MS)
        runCurrent()
        assertEquals(RailPhase.Up, keeper.phase.value)
        assertFalse(keeper.notesHeld.value)
        bench.log.clear()
    }

    // ---- Without the plug ---------------------------------------------------------------------------------------

    @Test
    fun `feature off - no calls at all, nothing held, and repower answers false`() = runTest {
        val bench = Bench(this)
        bench.available.value = false
        val watch = bench.Watch()   // v1.30 — M67: nor the power watch's
        val keeper = bench.keeper(watch)
        keeper.wanted(RailKeeper.PLAY, true)
        keeper.wanted("safety", true)
        keeper.keysOnScreen(true)
        keeper.keyPressed()
        keeper.updateRunning(true)
        keeper.startGuest(60)
        advanceTimeBy(15 * 60_000L)
        assertFalse(keeper.repower())
        keeper.cutNow()
        keeper.release()
        keeper.endGuest()
        advanceTimeBy(60_000L)
        assertEquals("not one call to the plug or the piano", emptyList<Event>(), bench.log)
        assertEquals("no safety check made due", 0, watch.due)
        assertTrue(bench.guestCalls.isEmpty())
        assertFalse("notes go as they always did", keeper.notesHeld.value)
        assertEquals(RailPhase.Off, keeper.phase.value)
        assertNull(keeper.message.value)
    }

    // ---- Rail-up -------------------------------------------------------------------------------------------------

    @Test
    fun `rail-up - a good CHECK, ARM, then the CHECK every 500 ms, and notes held until every board answers`() = runTest {
        val bench = Bench(this)
        val keeper = bench.keeper()
        assertTrue("with the plug, nothing goes while the rail is off", keeper.notesHeld.value)
        keeper.wanted(RailKeeper.PLAY, true)
        runCurrent()
        assertEquals(RailPhase.Waking, keeper.phase.value)
        assertEquals(RailMessage.Waking, keeper.message.value)
        advanceTimeBy(CHECK_MS + RPC_MS + 1)
        assertEquals(listOf("CHECK", "ARM"), bench.kinds())
        assertEquals("the CHECK before ARM finds the boards off with the rail", 0, bench.log.first().answered)
        val armedAt = bench.arms().single().at
        advanceTimeBy(500)
        assertTrue("the boards not up yet: notes still wait", keeper.notesHeld.value)
        assertEquals(RailPhase.Waking, keeper.phase.value)
        advanceTimeBy(BOARDS_UP_MS)
        runCurrent()
        assertEquals(RailPhase.Up, keeper.phase.value)
        assertFalse("every board answered: notes go", keeper.notesHeld.value)
        assertEquals((0 until 7).toSet(), keeper.baseline.value)
        assertNull("no message once up", keeper.message.value)
        val checks = bench.log.filter { it.kind == "CHECK" && it.at > armedAt }
        assertEquals("every 500 ms after ARM until all seven", listOf(armedAt + 500 + CHECK_MS, armedAt + 1000 + CHECK_MS), checks.map { it.at })
        assertEquals(listOf(0, 7), checks.map { it.answered })
        bench.assertEveryArmChecked()
    }

    @Test
    fun `rail-up holds notes at the gate - only what releases goes until the baseline`() = runTest {
        val bench = Bench(this)
        val keeper = bench.keeper()
        val link = FakePianoLink()
        val gate = RailGate(link, keeper.notesHeld)
        keeper.wanted(RailKeeper.PLAY, true)
        advanceTimeBy(300)
        gate.send(batch(0x90, 60, 90, 0x80, 61, 0, 0xB0, 64, 127, 0xB0, 64, 0, 0xB0, 123, 0), dropPending = false)
        gate.sendLive(batch(0x90, 62, 90))
        assertEquals("a Note On and the pedal down wait; Note Off, the pedal up and CC123 go", listOf("80 3D 00", "B0 40 00", "B0 7B 00"), link.messages)
        assertTrue("the crash handler's stop always goes", gate.emergencySilence(100))
        advanceTimeBy(2_000)
        runCurrent()
        assertFalse(keeper.notesHeld.value)
        link.clear()
        gate.send(batch(0x90, 60, 90), dropPending = false)
        assertEquals(listOf("90 3C 5A"), link.messages)
    }

    @Test
    fun `a trunk fault at rail-up - the boards that answered in 10 s are the baseline, and it is said`() = runTest {
        val bench = Bench(this)
        bench.missing = setOf(4)
        val keeper = bench.keeper()
        keeper.wanted(RailKeeper.PLAY, true)
        advanceTimeBy(RailKeeper.RAIL_UP_MS + 1_000)
        runCurrent()
        assertEquals(RailPhase.Up, keeper.phase.value)
        assertEquals((0 until 7).toSet() - 4, keeper.baseline.value)
        assertEquals(RailMessage.TrunkFault(6, 7, cut = false), keeper.message.value)
        assertFalse("the six play", keeper.notesHeld.value)
        advanceTimeBy(60_000)
        assertTrue("it is kept with six", bench.arms().size >= 6)
        bench.assertEveryArmChecked()
    }

    @Test
    fun `no board at all after 10 s - CUT, and said`() = runTest {
        val bench = Bench(this)
        bench.missing = (0 until 7).toSet()
        val keeper = bench.keeper()
        keeper.wanted(RailKeeper.PLAY, true)
        advanceTimeBy(RailKeeper.RAIL_UP_MS + 3_000)
        assertEquals(RailPhase.Off, keeper.phase.value)
        assertEquals(RailMessage.TrunkFault(0, 7, cut = true), keeper.message.value)
        assertEquals(listOf("STOP PLAYING", "STOP", "CUT"), bench.kinds().takeLast(3))
    }

    @Test
    fun `boards that answer with the rail off prove nothing - a fixed 1_5 s after ARM`() = runTest {
        val bench = Bench(this)
        bench.controllerFed = true
        val keeper = bench.keeper()
        keeper.wanted(RailKeeper.PLAY, true)
        advanceTimeBy(1_000)
        assertEquals(RailPhase.Waking, keeper.phase.value)
        val armedAt = bench.arms().single().at
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(RailPhase.Up, keeper.phase.value)
        val check = bench.log.last { it.kind == "CHECK" }
        assertEquals(armedAt + RailKeeper.CONTROLLER_FED_MS + CHECK_MS, check.at)
    }

    @Test
    fun `no piano connected - nothing asked, nothing held, and the rail comes up once it connects`() = runTest {
        val bench = Bench(this)
        bench.connected = false
        val keeper = bench.keeper()
        keeper.wanted(RailKeeper.PLAY, true)
        advanceTimeBy(60_000)
        assertEquals("the rail waits for the piano", RailPhase.Off, keeper.phase.value)
        assertTrue("no CHECK, no ARM without it", bench.log.isEmpty())
        assertFalse("notes go nowhere anyway: nothing to hold, as before the plug", keeper.notesHeld.value)
        assertNull(keeper.message.value)
        bench.connected = true
        runCurrent()
        assertEquals(RailPhase.Waking, keeper.phase.value)
        assertTrue("held from the connection until the rail is up", keeper.notesHeld.value)
        advanceTimeBy(3_000)
        assertEquals(RailPhase.Up, keeper.phase.value)
        bench.assertEveryArmChecked()
    }

    @Test
    fun `the piano gone mid-piece - two failed CHECKs, the stop and CUT`() = runTest {
        val bench = Bench(this)
        val keeper = bench.keeper()
        upFor(bench, keeper)
        bench.connected = false
        advanceTimeBy(15_000)
        assertEquals(listOf("CHECK", "CHECK", "STOP PLAYING", "STOP", "CUT"), bench.kinds())
        assertEquals(RailMessage.PianoSilent, keeper.message.value)
        assertTrue(bench.arms().isEmpty())
    }

    @Test
    fun `the safety check's restart - the piano gone inside its window is no failure, and the rail-up follows it back (v1_27 M64)`() = runTest {
        val bench = Bench(this)
        val keeper = bench.keeper()
        upFor(bench, keeper)
        keeper.wanted("safety", true)
        keeper.expectRestart(60_000)
        bench.connected = false   // `restarting`: the piano lets go of the link
        advanceTimeBy(10_000)   // the keep's CHECK, and the retry 2 s after it, fall in here
        assertTrue("no stop, no CUT while the piano restarts: ${bench.kinds()}", bench.kinds().none { it == "STOP PLAYING" || it == "STOP" || it == "CUT" })
        assertNull("nothing said", keeper.message.value)
        assertEquals(RailPhase.Off, keeper.phase.value)
        val back = currentTime
        bench.connected = true
        advanceTimeBy(3_000)
        assertEquals("the wants kept: up again with the piano", RailPhase.Up, keeper.phase.value)
        assertEquals(listOf("CHECK", "ARM"), bench.kinds(from = back).take(2))
        assertEquals((0 until 7).toSet(), keeper.baseline.value)
        bench.assertEveryArmChecked()

        // The check stopped while the piano restarts: nothing wants the rail, so the usual stop, not the plug's timer.
        keeper.expectRestart(60_000)
        bench.connected = false
        advanceTimeBy(12_000)
        assertEquals(RailPhase.Off, keeper.phase.value)
        val ended = currentTime
        keeper.wanted("safety", false)
        keeper.wanted(RailKeeper.PLAY, false)
        advanceTimeBy(2_000)
        assertEquals(listOf("STOP", "CUT"), bench.kinds(from = ended))
        assertEquals("calm, not a failure", RailMessage.Off, keeper.message.value)
    }

    @Test
    fun `the same silence outside the restart's window still fails and cuts - the window over, or the piano back (v1_27 M64)`() = runTest {
        val late = Bench(this)
        val keeper = late.keeper()
        upFor(late, keeper)
        keeper.expectRestart(5_000)
        advanceTimeBy(6_000)
        late.connected = false
        advanceTimeBy(15_000)
        assertEquals(listOf("CHECK", "CHECK", "STOP PLAYING", "STOP", "CUT"), late.kinds())
        assertEquals(RailMessage.PianoSilent, keeper.message.value)

        val again = Bench(this)
        val second = again.keeper()
        upFor(again, second)
        second.expectRestart(60_000)
        again.connected = false
        advanceTimeBy(10_000)
        again.connected = true   // back from its restart: the expectation is spent
        advanceTimeBy(3_000)
        assertEquals(RailPhase.Up, second.phase.value)
        again.log.clear()
        again.connected = false   // gone again inside the minute: a dead piano now
        advanceTimeBy(15_000)
        assertEquals(listOf("CHECK", "CHECK", "STOP PLAYING", "STOP", "CUT"), again.kinds())
        assertEquals(RailMessage.PianoSilent, second.message.value)
    }

    // ---- While wanted --------------------------------------------------------------------------------------------

    @Test
    fun `never ARM without a good CHECK - a long evening of play, the Keys tab, a flaky piano and a lost board`() = runTest {
        val bench = Bench(this)
        val keeper = bench.keeper()
        keeper.wanted(RailKeeper.PLAY, true)
        advanceTimeBy(3 * 60_000L)
        bench.silent = true   // one CHECK goes unanswered, then it answers again
        advanceTimeBy(9_000)
        bench.silent = false
        advanceTimeBy(60_000)
        keeper.wanted(RailKeeper.PLAY, false)
        advanceTimeBy(5_000)
        keeper.keysOnScreen(true)
        advanceTimeBy(2 * 60_000L)
        bench.missing = setOf(2)   // a board lost: no ARM, then the cut
        advanceTimeBy(30_000)
        keeper.keyPressed()
        bench.missing = emptySet()
        advanceTimeBy(60_000)
        assertTrue("it ran", bench.arms().size > 20)
        bench.assertEveryArmChecked()
    }

    @Test
    fun `while up - CHECK then ARM every 10 s`() = runTest {
        val bench = Bench(this)
        val keeper = bench.keeper()
        upFor(bench, keeper)
        advanceTimeBy(60_000)
        val arms = bench.arms()
        assertEquals(6, arms.size)
        for ((a, b) in arms.zipWithNext()) assertEquals("10 s apart, the CHECK's 60 ms on each", RailKeeper.KEEP_EVERY_MS + CHECK_MS + RPC_MS, b.at - a.at)
        bench.assertEveryArmChecked()
    }

    @Test
    fun `two failed CHECKs in a row - stop playback, the stop sequence, then CUT, and the piano stopped answering`() = runTest {
        val bench = Bench(this)
        val keeper = bench.keeper()
        upFor(bench, keeper)
        bench.silent = true
        advanceTimeBy(30_000)
        assertEquals(listOf("CHECK", "CHECK", "STOP PLAYING", "STOP", "CUT"), bench.kinds())
        assertTrue("not one ARM after the first failed CHECK", bench.arms().isEmpty())
        assertEquals(RailMessage.PianoSilent, keeper.message.value)
        assertEquals(RailPhase.Off, keeper.phase.value)
        assertTrue(keeper.notesHeld.value)
        val (first, second) = bench.log.filter { it.kind == "CHECK" }
        assertEquals("the second tried 2 s after the first", RailKeeper.RETRY_MS + CHECK_TIMEOUT_MS, second.at - first.at)
        bench.log.clear()
        advanceTimeBy(5 * 60_000L)
        assertTrue("what wanted it is ended: nothing comes back by itself (the watch's reads aside)", bench.kinds().isEmpty())
    }

    @Test
    fun `one failed CHECK then a good one - kept up, no stop`() = runTest {
        val bench = Bench(this)
        val keeper = bench.keeper()
        upFor(bench, keeper)
        bench.silent = true
        val armedAt = keeper.armedAt.value!!
        advanceTimeBy(armedAt + RailKeeper.KEEP_EVERY_MS + CHECK_TIMEOUT_MS + 100 - currentTime)
        assertEquals("one CHECK unanswered", listOf("CHECK"), bench.kinds())
        bench.silent = false
        advanceTimeBy(5_000)
        assertEquals(listOf("CHECK", "CHECK", "ARM"), bench.kinds())
        assertEquals(RailPhase.Up, keeper.phase.value)
        bench.assertEveryArmChecked()
    }

    @Test
    fun `two failed ARMs in a row - stop playback, and lost the power plug`() = runTest {
        val bench = Bench(this)
        val keeper = bench.keeper()
        upFor(bench, keeper)
        bench.armWorks = false
        advanceTimeBy(20_000)
        assertEquals(listOf("CHECK", "ARM FAILED", "CHECK", "ARM FAILED", "STOP PLAYING", "STOP", "CUT FAILED").take(6), bench.kinds().take(6))
        assertTrue("stopped", "STOP PLAYING" in bench.kinds())
        assertEquals(RailMessage.PlugLost, keeper.message.value)
        assertEquals(RailPhase.Off, keeper.phase.value)
        bench.assertEveryArmChecked()
    }

    @Test
    fun `a baseline board lost - no ARM, and the second CHECK cuts`() = runTest {
        val bench = Bench(this)
        val keeper = bench.keeper()
        upFor(bench, keeper)
        bench.missing = setOf(3)
        advanceTimeBy(20_000)
        assertEquals(listOf("CHECK", "CHECK", "STOP PLAYING", "STOP", "CUT"), bench.kinds())
        assertTrue("no ARM with a board gone", bench.arms().isEmpty())
        assertEquals(RailMessage.TrunkFault(6, 7, cut = true), keeper.message.value)
    }

    // ---- Stop --------------------------------------------------------------------------------------------------------

    @Test
    fun `stop - the stop sequence, a second, then CUT, and 24 V off for a few seconds`() = runTest {
        val bench = Bench(this)
        val keeper = bench.keeper()
        upFor(bench, keeper)
        val stoppedAt = currentTime
        keeper.wanted(RailKeeper.PLAY, false)
        advanceTimeBy(2_000)
        assertEquals(listOf("STOP", "CUT"), bench.kinds())
        val (stop, cut) = bench.log
        assertEquals("the stop sequence first, at once", stoppedAt, stop.at)
        assertEquals("CUT a second later", stop.at + RailKeeper.STOP_TO_CUT_MS + RPC_MS, cut.at)
        assertEquals(RailPhase.Off, keeper.phase.value)
        assertEquals(RailMessage.Off, keeper.message.value)
        advanceTimeBy(RailKeeper.CALM_MS + RailKeeper.WELD_CHECK_MS)
        assertNull("calm: it goes by itself", keeper.message.value)
        assertFalse("the relay let go", keeper.welded.value)
    }

    @Test
    fun `wanted again within the second - no CUT, the rail stays up`() = runTest {
        val bench = Bench(this)
        val keeper = bench.keeper()
        upFor(bench, keeper)
        keeper.wanted(RailKeeper.PLAY, false)
        advanceTimeBy(500)
        keeper.wanted(RailKeeper.PLAY, true)
        advanceTimeBy(30_000)
        assertFalse("CUT" in bench.kinds())
        assertEquals(RailPhase.Up, keeper.phase.value)
        bench.assertEveryArmChecked()
    }

    @Test
    fun `a relay that stays on after a CUT is seen`() = runTest {
        val bench = Bench(this)
        val keeper = bench.keeper()
        upFor(bench, keeper)
        val welded = object : RailPlug by bench.plug {
            override suspend fun status(): PlugStatus = PlugStatus(output = false, watts = IDLE_WATTS)
        }
        val keeper2 = RailKeeper(backgroundScope, welded, bench::check, MutableStateFlow(true), {}, {}, bench.available, { currentTime })
        keeper2.start()
        keeper2.wanted(RailKeeper.PLAY, true)
        advanceTimeBy(3_000)
        keeper2.wanted(RailKeeper.PLAY, false)
        advanceTimeBy(RailKeeper.STOP_TO_CUT_MS + RailKeeper.WELD_CHECK_MS + 500)
        assertTrue("the supply still draws after the CUT", keeper2.welded.value)
        assertFalse(keeper.welded.value)
    }

    @Test
    fun `a welded relay - apower after a CUT above half the idle draw read at rail-up, at least 2 W (v1_26 M62)`() = runTest {
        val bench = Bench(this)
        var idle: Double? = 20.0   // the supply's draw at rail-up, the piano silent (null: the plug didn't say)
        var after = 9.0   // what it still draws after the CUT
        var cut = false
        val plug = object : RailPlug {
            override suspend fun arm(seconds: Int): Boolean = bench.plug.arm(seconds).also { cut = false }

            override suspend fun cut(): Boolean = bench.plug.cut().also { cut = true }

            override suspend fun status(): PlugStatus? = if (cut) PlugStatus(output = false, watts = after) else idle?.let { PlugStatus(output = true, watts = it) }
        }
        fun keeper(): RailKeeper = RailKeeper(backgroundScope, plug, bench::check, MutableStateFlow(true), {}, {}, bench.available, { currentTime }).also { it.start() }
        fun pieceThenCut(keeper: RailKeeper) {
            keeper.wanted(RailKeeper.PLAY, true)
            advanceTimeBy(3_000)
            assertEquals(RailPhase.Up, keeper.phase.value)
            keeper.wanted(RailKeeper.PLAY, false)
            advanceTimeBy(RailKeeper.STOP_TO_CUT_MS + RailKeeper.WELD_CHECK_MS + 500)
        }
        val keeper = keeper()
        pieceThenCut(keeper)
        assertFalse("9 W after the CUT, under half of the 20 W idle: the relay let go", keeper.welded.value)
        after = 11.0
        pieceThenCut(keeper)
        assertTrue("11 W after the CUT, over half of the 20 W idle: stuck", keeper.welded.value)
        // No idle reading at rail-up: 2 W.
        idle = null
        after = 3.0
        val fresh = keeper()
        pieceThenCut(fresh)
        assertTrue("3 W after the CUT, over 2 W", fresh.welded.value)
    }

    // ---- The update exception ------------------------------------------------------------------------------------

    @Test
    fun `the update - ARM every 10 s without a CHECK from BEGIN, until its outcome`() = runTest {
        val bench = Bench(this)
        bench.silent = true   // the piano restarts during the update and can't answer
        val keeper = bench.keeper()
        val begin = currentTime
        keeper.updateRunning(true)
        advanceTimeBy(95_000)
        assertTrue("no CHECK during the update", bench.log.none { it.kind == "CHECK" })
        val arms = bench.arms()
        assertEquals(10, arms.size)
        assertEquals("the first at BEGIN", begin + RPC_MS, arms.first().at)
        assertEquals(RailPhase.Kept, keeper.phase.value)
        assertFalse(keeper.notesHeld.value)
        keeper.updateRunning(false)   // its outcome: confirmed
        advanceTimeBy(3_000)
        assertEquals(listOf("STOP", "CUT"), bench.kinds().takeLast(2))
        assertEquals(RailPhase.Off, keeper.phase.value)
    }

    @Test
    fun `the update - the unchecked ARMs stop after 3 minutes, and the CHECK rules again`() = runTest {
        val bench = Bench(this)
        bench.silent = true
        val keeper = bench.keeper()
        val begin = currentTime
        keeper.updateRunning(true)
        advanceTimeBy(5 * 60_000L)
        val cap = begin + RailKeeper.UPDATE_CAP_MS
        assertTrue("unchecked only within the 3 minutes", bench.arms().all { it.at <= cap + RPC_MS })
        assertTrue(bench.arms().size >= 17)
        bench.assertEveryArmChecked(exempt = listOf(begin..cap + RPC_MS))
        assertEquals("the piano never answered: cut", RailMessage.PianoSilent, keeper.message.value)
        assertTrue("CUT" in bench.kinds())
    }

    // ---- Guest play ----------------------------------------------------------------------------------------------

    @Test
    fun `guest play - unchecked only for the tablet's own window, 60 minutes at most, ending with the full sequence`() = runTest {
        val bench = Bench(this)
        val keeper = bench.keeper()
        val start = currentTime
        keeper.startGuest(240)
        runCurrent()
        assertEquals("capped at 60 minutes", start + 60 * 60_000L, keeper.guestUntil.value)
        assertEquals(listOf("let go"), bench.guestCalls)
        advanceTimeBy(59 * 60_000L)
        assertTrue("no CHECK: the iPad has the piano", bench.log.none { it.kind == "CHECK" })
        assertTrue("armed every 10 s", bench.arms().size >= 350)
        assertEquals(RailPhase.Kept, keeper.phase.value)
        val end = start + 60 * 60_000L
        advanceTimeBy(2 * 60_000L)
        assertTrue("no unchecked ARM after the window", bench.arms().none { it.at > end + RPC_MS })
        assertEquals(listOf("let go", "reconnect", "lock guest 0"), bench.guestCalls)
        val after = bench.log.filter { it.at >= end }.map { it.kind }
        assertEquals("then the stop sequence and CUT", listOf("STOP", "CUT"), after.filter { it == "STOP" || it == "CUT" })
        assertTrue(after.indexOf("STOP") < after.indexOf("CUT"))
        assertEquals(RailPhase.Off, keeper.phase.value)
        assertNull(keeper.guestUntil.value)
    }

    @Test
    fun `End guest play - reconnect, lock guest 0, the stop sequence, then CUT`() = runTest {
        val bench = Bench(this)
        val keeper = bench.keeper()
        keeper.startGuest(60)
        advanceTimeBy(20 * 60_000L)
        bench.log.clear()
        val job = launch { keeper.endGuest() }
        advanceTimeBy(RECONNECT_MS + 3_000)
        job.join()
        assertEquals(listOf("let go", "reconnect", "lock guest 0"), bench.guestCalls)
        assertEquals(listOf("STOP", "CUT"), bench.kinds().filter { it == "STOP" || it == "CUT" })
        assertTrue("no ARM after End guest play", bench.arms().isEmpty())
        advanceTimeBy(60 * 60_000L)
        assertTrue(bench.arms().isEmpty())
    }

    @Test
    fun `a guest window the tablet didn't start - nothing unchecked, the CHECK rules`() = runTest {
        val bench = Bench(this)
        bench.connected = false   // the iPad holds the piano
        val keeper = bench.keeper()
        keeper.wanted("panel", true)
        advanceTimeBy(30 * 60_000L)
        assertTrue("never armed without the piano's CHECK", bench.arms().isEmpty())
    }

    // ---- The Keys tab --------------------------------------------------------------------------------------------

    @Test
    fun `the Keys tab - up while open, cut after 10 minutes without a key, the next key wakes it`() = runTest {
        val bench = Bench(this)
        val keeper = bench.keeper()
        val opened = currentTime
        keeper.keysOnScreen(true)
        advanceTimeBy(3_000)
        assertEquals(RailPhase.Up, keeper.phase.value)
        advanceTimeBy(9 * 60_000L)
        keeper.keyPressed()   // at about 9 minutes
        advanceTimeBy(9 * 60_000L)
        assertEquals("a key 9 minutes in: still up at 18", RailPhase.Up, keeper.phase.value)
        advanceTimeBy(2 * 60_000L)
        assertEquals("10 minutes without a key: cut", RailPhase.Off, keeper.phase.value)
        val cut = bench.log.last { it.kind == "CUT" }
        assertTrue(cut.at in opened + 19 * 60_000L..opened + 20 * 60_000L)
        assertEquals("STOP", bench.log[bench.log.indexOf(cut) - 1].kind)
        keeper.keyPressed()
        runCurrent()
        assertEquals("the next key wakes it", RailPhase.Waking, keeper.phase.value)
        assertEquals(RailMessage.Waking, keeper.message.value)
        advanceTimeBy(3_000)
        assertEquals(RailPhase.Up, keeper.phase.value)
        keeper.keysOnScreen(false)
        advanceTimeBy(2_000)
        assertEquals(RailPhase.Off, keeper.phase.value)
        bench.assertEveryArmChecked()
    }

    // ---- RailPower ------------------------------------------------------------------------------------------------

    @Test
    fun `repower - the stop sequence, CUT, 3 s, then the rail-up, true when every baseline board answers`() = runTest {
        val bench = Bench(this)
        val keeper = bench.keeper()
        upFor(bench, keeper)
        var answer: Boolean? = null
        val job = launch { answer = keeper.repower() }
        advanceTimeBy(10_000)
        job.join()
        assertEquals(true, answer)
        val kinds = bench.kinds()
        assertEquals(listOf("STOP", "CUT", "CHECK", "ARM"), kinds.take(4))
        val cut = bench.log[1]
        val check = bench.log[2]
        assertTrue("3 s off", check.at - cut.at >= RailKeeper.REPOWER_OFF_MS)
        assertEquals(RailPhase.Up, keeper.phase.value)
        bench.assertEveryArmChecked()
        // A board that doesn't come back: false.
        bench.missing = setOf(6)
        val again = launch { answer = keeper.repower() }
        advanceTimeBy(20_000)
        again.join()
        assertEquals(false, answer)
    }

    @Test
    fun `cutNow - what plays stops, the stop sequence, then CUT at once`() = runTest {
        val bench = Bench(this)
        val keeper = bench.keeper()
        upFor(bench, keeper)
        val job = launch { keeper.cutNow() }
        advanceTimeBy(RPC_MS + 1)
        job.join()
        assertEquals(listOf("STOP PLAYING", "STOP", "CUT"), bench.kinds())
        assertEquals(RailPhase.Off, keeper.phase.value)
        advanceTimeBy(60_000)
        assertTrue("nothing brings it back by itself", bench.arms().isEmpty())
    }

    @Test
    fun `the switch off mid-piece - the stop, CUT, then nothing more`() = runTest {
        val bench = Bench(this)
        val keeper = bench.keeper()
        upFor(bench, keeper)
        bench.available.value = false
        advanceTimeBy(3_000)
        assertEquals(listOf("STOP PLAYING", "STOP", "CUT"), bench.log.map { it.kind })
        assertFalse(keeper.notesHeld.value)
        bench.log.clear()
        advanceTimeBy(10 * 60_000L)
        assertTrue(bench.log.isEmpty())
    }

    // ---- The plug watch (v1.29 — M66) ------------------------------------------------------------------------------

    @Test
    fun `the plug watch - a plug found on while idle is CUT and said, changed settings put back, and nothing extra while a piece plays`() = runTest {
        val bench = Bench(this)
        val keeper = bench.keeper()
        advanceTimeBy(RailKeeper.WATCH_EVERY_MS + 1_000)
        assertEquals("one small read a minute, and the settings the first time", listOf("STATUS", "SETTINGS"), bench.log.map { it.kind })
        assertNull("nothing changed: nothing said", keeper.message.value)

        bench.switchOnFromOutside()
        val on = currentTime
        advanceTimeBy(RailKeeper.WATCH_EVERY_MS)
        assertFalse("switched back off", bench.output)
        assertEquals(listOf("CUT"), bench.kinds(from = on))
        assertEquals(RailMessage.SwitchedOn, keeper.message.value)
        val cut = bench.log.last { it.kind == "CUT" }
        assertTrue("its settings put back first", bench.log.indexOfLast { it.kind == "SETTINGS" } < bench.log.indexOf(cut))
        advanceTimeBy(RailKeeper.WELD_CHECK_MS + 500)
        assertFalse("the weld look after it, as after any CUT", keeper.welded.value)
        keeper.dismissMessage()

        bench.settingsChanged = true
        advanceTimeBy(RailKeeper.SETTINGS_EVERY_MS)
        assertEquals(RailMessage.SettingsPutBack, keeper.message.value)
        keeper.dismissMessage()

        upFor(bench, keeper)
        advanceTimeBy(3 * RailKeeper.WATCH_EVERY_MS)
        assertTrue("nothing extra while a piece plays", bench.log.none { it.kind == "STATUS" || it.kind == "SETTINGS" })
        keeper.wanted(RailKeeper.PLAY, false)
        advanceTimeBy(5_000)
        bench.log.clear()
        keeper.updateRunning(true)
        advanceTimeBy(RailKeeper.UPDATE_CAP_MS)
        keeper.updateRunning(false)
        assertTrue("never in the update's window", bench.log.none { it.kind == "SETTINGS" })
    }

    // ---- The power watch (v1.30 — M67) -----------------------------------------------------------------------------

    @Test
    fun `the floor watch - a key stuck mid-piece stops it, the stop sequence then CUT, said, and the safety check due`() = runTest {
        val bench = Bench(this)
        val watch = bench.Watch()
        val keeper = bench.keeper(watch)
        upFor(bench, keeper)
        advanceTimeBy(12_000)
        assertNull("readings at idle, no key held: nothing", keeper.message.value)
        assertTrue("about every 2 s", bench.log.count { it.kind == "STATUS" } in 5..8)
        val stuck = currentTime
        bench.extraWatts = PowerWatch.COIL_WATTS   // a coil on that the music isn't holding
        advanceTimeBy(25_000)
        assertEquals(RailMessage.StuckKey(afterPiece = false), keeper.message.value)
        assertEquals(listOf("STOP PLAYING", "STOP", "CUT", "CHECK DUE"), bench.kinds(from = stuck).filter { it != "CHECK" && it != "ARM" })
        val cut = bench.log.single { it.kind == "CUT" }
        assertTrue("within about 20 s (${cut.at - stuck} ms)", cut.at - stuck in PowerWatch.RUN_MS..22_000L)
        assertEquals(RailPhase.Off, keeper.phase.value)
        assertEquals(1, watch.due)
        bench.log.clear()
        advanceTimeBy(60_000)
        assertTrue("what wanted it is ended", bench.arms().isEmpty())
        bench.assertEveryArmChecked()
    }

    @Test
    fun `the floor watch - with the pedal down the readings are skipped, and the router's keys are subtracted`() = runTest {
        val bench = Bench(this)
        val watch = bench.Watch()
        val keeper = bench.keeper(watch)
        upFor(bench, keeper)
        watch.pedal = true
        bench.extraWatts = PowerWatch.COIL_WATTS
        advanceTimeBy(40_000)
        assertNull("pedal down: no verdict", keeper.message.value)
        watch.pedal = false
        watch.heldKeys = 1.0   // the music holds one key: its 12 W are the music's
        advanceTimeBy(40_000)
        assertNull(keeper.message.value)
        assertEquals(RailPhase.Up, keeper.phase.value)
        assertEquals(0, watch.due)
    }

    @Test
    fun `after the piece - two readings a second apart and the piano's facts before the CUT, a key on said and the check due`() = runTest {
        val bench = Bench(this)
        val watch = bench.Watch()
        val keeper = bench.keeper(watch)
        upFor(bench, keeper)
        advanceTimeBy(5_000)
        val stopped = currentTime
        keeper.wanted(RailKeeper.PLAY, false)
        advanceTimeBy(4_000)
        assertEquals("a quiet piano: the CUT, about a second later than before", listOf("STOP", "CUT"), bench.kinds(from = stopped))
        val after = bench.log.filter { it.at >= stopped }
        assertEquals(listOf("STOP", "STATUS", "ASK active", "ASK i2cfails", "STATUS", "CUT"), after.map { it.kind }.filter { it != "CHECK" && it != "ARM" }.take(6))
        val cut = after.first { it.kind == "CUT" }.at - stopped
        assertTrue("the CUT ${cut} ms after the stop sequence", cut in 2 * RailKeeper.STOP_TO_CUT_MS..2 * RailKeeper.STOP_TO_CUT_MS + 3 * RPC_MS)
        assertEquals(RailMessage.Off, keeper.message.value)
        assertEquals(0, watch.due)

        // The same with a coil left on: 12 W in both readings.
        upFor(bench, keeper)
        bench.extraWatts = PowerWatch.COIL_WATTS
        val second = currentTime
        keeper.wanted(RailKeeper.PLAY, false)
        advanceTimeBy(4_000)
        assertEquals(listOf("STOP", "STOP PLAYING", "STOP", "CUT", "CHECK DUE"), bench.kinds(from = second))
        assertEquals(RailMessage.StuckKey(afterPiece = true), keeper.message.value)
        assertEquals(1, watch.due)

        // !active above 0, and !i2cfails up since the rail-up, each the same.
        bench.extraWatts = 0.0
        for ((active, fails) in listOf("1" to "0", "0" to "5")) {
            keeper.dismissMessage()
            watch.active = "0"
            watch.fails = "0"
            upFor(bench, keeper)
            watch.active = active
            watch.fails = fails
            keeper.wanted(RailKeeper.PLAY, false)
            advanceTimeBy(4_000)
            assertEquals("!active $active, !i2cfails 0 → $fails", RailMessage.StuckKey(afterPiece = true), keeper.message.value)
        }
        assertEquals(3, watch.due)

        // Nothing struck since the rail-up (the Keys tab opened and closed): the stop as before.
        keeper.dismissMessage()
        watch.active = "1"
        watch.struck = false
        upFor(bench, keeper)
        val idle = currentTime
        keeper.wanted(RailKeeper.PLAY, false)
        advanceTimeBy(3_000)
        assertEquals(listOf("STOP", "CUT"), bench.log.filter { it.at >= idle }.map { it.kind }.filter { it == "STOP" || it == "CUT" || it.startsWith("ASK") })
        assertEquals(RailMessage.Off, keeper.message.value)
    }

    @Test
    fun `the rail-up cap - above 30 W twice a second apart with nothing sent, CUT, said and the check due, notes held throughout`() = runTest {
        val bench = Bench(this)
        val watch = bench.Watch()
        val keeper = bench.keeper(watch)
        val held = mutableListOf<Boolean>()
        backgroundScope.launch { keeper.notesHeld.collect { held += it } }
        bench.extraWatts = 30.0
        keeper.wanted(RailKeeper.PLAY, true)
        advanceTimeBy(5_000)
        assertEquals(RailMessage.Overdraw((IDLE_WATTS + 30.0).roundToInt()), keeper.message.value)
        assertEquals(listOf("CHECK", "ARM", "CHECK", "CHECK", "STOP PLAYING", "STOP", "CUT", "CHECK DUE"), bench.kinds())
        assertTrue("no note went", held.all { it })
        assertEquals(1, watch.due)

        // A supply still waking: above the cap at the first look only.
        keeper.dismissMessage()
        bench.log.clear()
        keeper.wanted(RailKeeper.PLAY, false)
        advanceTimeBy(1_000)
        keeper.wanted(RailKeeper.PLAY, true)
        launch {
            delay(1_800)   // after the first look (about 1.3 s in), before the second a second later
            bench.extraWatts = 0.0
        }
        advanceTimeBy(5_000)
        assertEquals(RailPhase.Up, keeper.phase.value)
        assertNull(keeper.message.value)
        assertEquals(1, watch.due)
        bench.assertEveryArmChecked()
    }

    // ---- The usual idle (v1.31 — M68) -------------------------------------------------------------------------------

    @Test
    fun `the usual idle - a rail-up a coil above it with nothing sent is CUT as the cap is, never kept, and a second look back to usual is no trip`() = runTest {
        val bench = Bench(this)
        val watch = bench.Watch()
        val keeper = bench.keeper(watch)
        repeat(3) {
            upFor(bench, keeper)
            keeper.wanted(RailKeeper.PLAY, false)
            advanceTimeBy(4_000)
        }
        assertEquals("three rail-ups kept", listOf(IDLE_WATTS, IDLE_WATTS, IDLE_WATTS), watch.usual)
        val held = mutableListOf<Boolean>()
        backgroundScope.launch { keeper.notesHeld.collect { held += it } }
        bench.log.clear()
        bench.extraWatts = PowerWatch.COIL_WATTS   // a channel on since before the rail came up: a shorted MOSFET, under the cap
        keeper.wanted(RailKeeper.PLAY, true)
        advanceTimeBy(5_000)
        assertEquals(RailMessage.AboveUsual(PowerWatch.COIL_WATTS.roundToInt()), keeper.message.value)
        assertEquals(listOf("CHECK", "ARM", "CHECK", "CHECK", "STOP PLAYING", "STOP", "CUT", "CHECK DUE"), bench.kinds())
        assertEquals("looked at twice, a second apart", 2, bench.log.count { it.kind == "STATUS" && it.at < bench.log.first { e -> e.kind == "CUT" }.at })
        assertTrue("no note went", held.all { it })
        assertEquals(1, watch.due)
        assertEquals(1, watch.above)
        assertEquals("the reading that tripped isn't kept", listOf(IDLE_WATTS, IDLE_WATTS, IDLE_WATTS), watch.usual)

        // The supply above the usual at the first look only: no trip, and the second look is kept.
        keeper.dismissMessage()
        keeper.wanted(RailKeeper.PLAY, false)
        advanceTimeBy(1_000)
        keeper.wanted(RailKeeper.PLAY, true)
        launch {
            delay(1_800)   // after the first look (about 1.3 s in), before the second a second later
            bench.extraWatts = 0.0
        }
        advanceTimeBy(5_000)
        assertEquals(RailPhase.Up, keeper.phase.value)
        assertNull(keeper.message.value)
        assertEquals(1, watch.due)
        assertEquals(listOf(IDLE_WATTS, IDLE_WATTS, IDLE_WATTS, IDLE_WATTS), watch.usual)
        bench.assertEveryArmChecked()
    }

    private fun batch(vararg bytes: Int): MidiBatch = MidiBatch().apply { for (i in bytes.indices step 3) add(bytes[i], bytes[i + 1], bytes[i + 2]) }

    private companion object {
        const val RPC_MS = 80L
        const val CHECK_MS = 60L
        const val CHECK_TIMEOUT_MS = 2_000L
        const val BOARDS_UP_MS = 900L
        const val RECONNECT_MS = 2_000L
        const val IDLE_WATTS = 6.4
        const val JUST_BEFORE_MS = 3_000L
        const val RAIL_UP_SETTLE_MS = 3_000L
    }
}
