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
                return PlugStatus(outputNow(), watts = if (outputNow()) IDLE_WATTS else 0.0)
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

        fun keeper(): RailKeeper = RailKeeper(
            scope = test.backgroundScope,
            plug = plug,
            check = ::check,
            connected = link,
            stopSequence = { log += Event(now(), "STOP") },
            stopPlaying = { log += Event(now(), "STOP PLAYING") },
            available = available,
            now = ::now,
            guest = guest,
        ).also {
            keeper = it
            it.start()
            test.runCurrent()
        }

        /** What the keeper did from [from], in order; the welded-relay look after a CUT ("STATUS") left out. */
        fun kinds(from: Long = 0L): List<String> = log.filter { it.at >= from && it.kind != "STATUS" }.map { it.kind }

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
        val keeper = bench.keeper()
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
        assertTrue("what wanted it is ended: nothing comes back by itself", bench.log.isEmpty())
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
