// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.safety

import dev.stevenjin.stevenpiano.audio.KeyResult
import dev.stevenjin.stevenpiano.audio.KeyTestState
import dev.stevenjin.stevenpiano.player.Played
import dev.stevenjin.stevenpiano.ui.ListenCopy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The safety check's runner (v1.26 — M61), piano safety: the count (the 6th piece played on the piano begins the check;
 * the count and the lock survive a restart; pieces the piano didn't play aren't counted), the deferrals and the Play that
 * waits for a check due, a coil on (the 24 V cut, everything stopped, Play locked), keys still silent after the restart
 * and the repower (Play locked; only a check that passes unlocks it), and the microphone off (no sweep, no key lock).
 * The piano, the player and the key test are stand-ins; time is the test's.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SafetyRunnerTest {
    private val allOk = "OK,OK,OK,OK,OK,OK,OK"

    private class MemoryStore(var record: SafetyRecord = SafetyRecord()) : SafetyStore {
        var writes = 0

        override suspend fun read(): SafetyRecord = record

        override suspend fun write(record: SafetyRecord) {
            this.record = record
            writes++
        }
    }

    /** The player, the piano and the key test as the check sees them; what it was asked to do, in [calls]. */
    private class FakeHost(private val scope: CoroutineScope) : SafetyHost {
        val pieces = MutableSharedFlow<Played>(extraBufferCapacity = 16)
        override val played: Flow<Played> = pieces
        val calls = mutableListOf<String>()
        var away = false
        var quiet = false
        var updating = false
        var guest = false
        var ready = true
        var busy = false
        var facts: Map<String, String>? = mapOf("active" to "0", "boards" to "OK,OK,OK,OK,OK,OK,OK", "restartproto" to "1")

        /** What the piano's dump gives when asked for again (v1.27 — M64); null: still none. */
        var factsAfterDump: Map<String, String>? = null
        var active = "0"
        var boards = "OK,OK,OK,OK,OK,OK,OK"
        var ota = "confirmed"
        var restartReply: String? = "restarting"

        /** After the restart: the boards as they come back. */
        var boardsAfterRestart: String? = null

        /** Keys the microphone doesn't hear. */
        var silent: Set<Int> = emptySet()
        var sweepRefusal: String? = null

        /** Whether the key test's own wait finds the 24 V up, for each sweep in turn (then up). */
        val testRail = ArrayDeque<Boolean>()
        private val test = MutableStateFlow(KeyTestState())
        private var sweeping: Job? = null

        override fun deferral(): Deferral? = when {
            updating -> Deferral.UPDATING
            quiet -> Deferral.QUIET
            away -> Deferral.AWAY
            guest -> Deferral.GUEST_PLAY
            !ready -> Deferral.NO_PIANO
            else -> null
        }

        override fun pianoReady(): Boolean = ready

        override fun playerBusy(): Boolean = busy

        override fun pausePlayer() {
            calls += "pause"
        }

        override fun release() {
            calls += "release"
        }

        override fun stopEverything() {
            calls += "stop everything"
        }

        override suspend fun facts(): Map<String, String>? = facts

        override fun dumpAgain(): Boolean {
            calls += "dump again"
            facts = factsAfterDump
            return true
        }

        override suspend fun ask(fact: String): String? {
            calls += "ask $fact"
            return when (fact) {
                "active" -> active
                "boards" -> boards
                "ota" -> ota
                else -> null
            }
        }

        override suspend fun restart(): String? {
            calls += "restart"
            if (restartReply == "restarting") {
                scope.launch {
                    delay(200)   // the piano lets go of the link about 200 ms after its reply
                    ready = false
                    delay(4_000)   // and advertises again a few seconds later
                    boardsAfterRestart?.let { boards = it }
                    ready = true
                }
            }
            return restartReply
        }

        override fun startSweep(): String? {
            sweepRefusal?.let { return it }
            calls += "sweep"
            test.value = KeyTestState(running = true)
            sweeping = scope.launch {
                if (testRail.removeFirstOrNull() == false) {
                    test.value = KeyTestState(stopped = ListenCopy.STOPPED_RAIL)
                    return@launch
                }
                for (i in 1..84) {
                    delay(450)
                    test.value = KeyTestState(running = true, key = 23 + i, done = i)
                }
                delay(500)
                test.value = KeyTestState(results = (24..107).map { KeyResult(it, it !in silent) })
            }
            return null
        }

        override val sweep: StateFlow<KeyTestState> = test

        override fun stopSweep() {
            if (!test.value.running) return
            calls += "stop sweep"
            sweeping?.cancel()
            test.value = KeyTestState(stopped = "Stopped before the end.")
        }

        override fun nextPiece(): Long? = 2L

        override suspend fun titleOf(pieceId: Long): String = "Piece $pieceId"

        fun sweeps(): Int = calls.count { it == "sweep" }
    }

    private class Rig(scope: TestScope, store: MemoryStore = MemoryStore(), listens: Boolean = true) {
        val host = FakeHost(scope.backgroundScope)
        val store = store
        var listens = listens
        var repowers = 0
        var repowerOk = true
        val power = mutableListOf<Boolean>()

        /** Whether the 24 V comes up for each sweep, in turn (then up); and when it was waited for. */
        val rail = ArrayDeque<Boolean>()
        val runner = SafetyRunner(
            scope = scope.backgroundScope,
            store = store,
            host = host,
            listens = { this.listens },
            repower = {
                host.calls += "repower"
                repowers++
                repowerOk
            },
            cutNow = { host.calls += "cut" },
            plugged = { true },
            wantRail = { power += it },
            awaitRail = {
                host.calls += "await rail"
                rail.removeFirstOrNull() ?: true
            },
        )

        fun play(count: Int = 1, id: Long = 1L) = repeat(count) { host.pieces.tryEmit(Played(id, ended = true)) }
    }

    /**
     * Lets the check run its course, and its moment on the card pass: the runner and the stand-ins live in the test's
     * background, which only advancing the clock moves.
     */
    private fun TestScope.settle() {
        advanceTimeBy(SETTLE_MS)
        runCurrent()
    }

    private fun TestScope.started(store: MemoryStore = MemoryStore(), listens: Boolean = true): Rig =
        Rig(this, store, listens).also {
            it.runner.start()
            runCurrent()
        }

    @Test
    fun `the 6th piece played on the piano begins the check right after it, and a pass starts the count again`() = runTest {
        val rig = started()
        rig.play(5)
        runCurrent()
        assertEquals(5, rig.runner.state.value.count)
        assertFalse(rig.runner.state.value.running)
        rig.host.ready = false   // a piece the piano didn't play (no piano, a MIDI piano, guest play): not counted
        rig.play()
        runCurrent()
        assertEquals(5, rig.runner.state.value.count)
        rig.host.ready = true
        rig.play()   // the 6th
        runCurrent()
        assertTrue(rig.runner.state.value.running)
        assertTrue("the card shows", rig.runner.state.value.card)
        assertEquals("the rail wanted from the check's start", listOf(true), rig.power)
        settle()
        val done = rig.runner.state.value
        assertFalse(done.running)
        assertNull(done.lock)
        assertEquals(0, done.count)
        assertFalse(done.pending)
        assertTrue(done.last!!.passed)
        assertEquals(84, done.last!!.keys)
        assertEquals("wanted again for the sweep, let go at the end", listOf(true, true, false), rig.power)
        assertEquals(
            listOf("pause", "release", "ask active", "ask boards", "await rail", "sweep"),
            rig.host.calls,
        )
        assertEquals(0, rig.store.record.count)
    }

    @Test
    fun `the count and the lock survive a restart of the app`() = runTest {
        val store = MemoryStore()
        val first = started(store)
        first.play(4)
        runCurrent()
        assertEquals(4, store.record.count)
        val again = started(store)
        assertEquals(4, again.runner.state.value.count)
        again.play(2)
        runCurrent()
        assertTrue("the 6th since the last check, across the restart", again.runner.state.value.running)

        val locked = started(MemoryStore(SafetyRecord(lock = SafetyLock.KeysSilent(listOf(36, 66)))))
        assertEquals(SafetyLock.KeysSilent(listOf(36, 66)), locked.runner.state.value.lock)
        var played = 0
        assertFalse(locked.runner.admit(1L) { played++ })
        assertEquals("Safety check failed: C2, F♯4", locked.runner.refusal())
        assertEquals(0, played)
    }

    @Test
    fun `a check that can't run when it is due waits, and runs before the next Play, which waits for it`() = runTest {
        for (why in listOf(Deferral.QUIET, Deferral.AWAY, Deferral.UPDATING, Deferral.GUEST_PLAY)) {
            val rig = started(MemoryStore(SafetyRecord(count = 5)))
            when (why) {
                Deferral.QUIET -> rig.host.quiet = true
                Deferral.AWAY -> rig.host.away = true
                Deferral.UPDATING -> rig.host.updating = true
                else -> rig.host.guest = true
            }
            rig.play()
            runCurrent()
            assertFalse("$why: not now", rig.runner.state.value.running)
            assertTrue("$why: due", rig.runner.state.value.pending)
            assertFalse("$why: Play waits for it", rig.runner.open)
            rig.host.quiet = false
            rig.host.away = false
            rig.host.updating = false
            rig.host.guest = false
            var played = 0
            assertFalse("$why: the next Play waits", rig.runner.admit(7L) { played++ })
            runCurrent()
            assertTrue("$why: the check runs first", rig.runner.state.value.running)
            assertEquals(0, played)
            settle()
            assertEquals("$why: then the Play goes", 1, played)
            assertFalse(rig.runner.state.value.pending)
            assertTrue(rig.runner.open)
        }
    }

    @Test
    fun `with a check due, a Play from afar while the app is away is refused, and one the piano can't hear goes`() = runTest {
        val rig = started(MemoryStore(SafetyRecord(count = 6, pending = true)))
        rig.host.away = true
        var played = 0
        assertFalse(rig.runner.admit(1L) { played++ })
        assertEquals("A safety check runs before the next piece. Open Steven Piano on the tablet.", rig.runner.refusal())
        assertFalse(rig.runner.state.value.running)
        rig.host.away = false
        rig.host.ready = false   // no piano: nothing reaches its coils; the check waits for the next Play
        assertTrue(rig.runner.admit(1L) { played++ })
        assertTrue(rig.runner.state.value.pending)
        rig.host.ready = true
        rig.host.guest = true   // guest play likewise
        assertTrue(rig.runner.admit(1L) { played++ })
        assertNull(rig.runner.refusal())
    }

    @Test
    fun `a coil still on - the 24 V is cut at once, everything stops, and Play is locked`() = runTest {
        val rig = started(MemoryStore(SafetyRecord(count = 5)))
        rig.host.active = "1"
        rig.play()
        settle()
        val state = rig.runner.state.value
        assertEquals(SafetyLock.CoilOn, state.lock)
        assertEquals(listOf("pause", "release", "ask active", "cut", "stop everything"), rig.host.calls)
        assertEquals(0, rig.host.sweeps())
        assertTrue(state.card)
        assertEquals(SafetyLock.CoilOn, rig.store.record.lock)
        assertTrue(rig.store.record.last!!.coilOn)
        var played = 0
        assertFalse(rig.runner.admit(1L) { played++ })
        assertEquals("Safety check failed: a key is still on", rig.runner.refusal())
        assertFalse(rig.runner.open)
    }

    @Test
    fun `keys still silent after the restart and the repower lock Play, and only a check that passes unlocks it`() = runTest {
        val rig = started(MemoryStore(SafetyRecord(count = 5)))
        rig.host.silent = setOf(36, 66)
        var waited = 0
        rig.play()
        runCurrent()
        assertFalse("a Play during the check waits", rig.runner.admit(1L) { waited++ })
        settle()
        assertEquals(
            listOf(
                "pause", "release", "ask active", "ask boards", "await rail", "sweep", "ask ota", "restart", "ask boards", "repower",
                "ask active", "ask boards", "await rail", "sweep",
            ),
            rig.host.calls,
        )
        assertEquals(SafetyLock.KeysSilent(listOf(36, 66)), rig.runner.state.value.lock)
        assertEquals("the Play that waited never goes", 0, waited)
        assertEquals("Safety check failed: C2, F♯4", rig.runner.refusal())

        // Locked: every start is refused, and counted (the card comes back at each).
        val refusedBefore = rig.runner.state.value.refused
        assertFalse(rig.runner.admit(1L) {})
        assertFalse(rig.runner.admit(null) {})
        assertEquals(refusedBefore + 2, rig.runner.state.value.refused)

        // A check that can't finish never unlocks.
        rig.host.sweepRefusal = "The piano isn't connected."
        assertNull(rig.runner.runNow())
        settle()
        assertEquals(SafetyLock.KeysSilent(listOf(36, 66)), rig.runner.state.value.lock)

        // Nor one whose keys still don't sound.
        rig.host.sweepRefusal = null
        assertNull(rig.runner.runNow())
        settle()
        assertEquals(SafetyLock.KeysSilent(listOf(36, 66)), rig.runner.state.value.lock)

        // The keys mended, Run the safety check passes, and Play is free again.
        rig.host.silent = emptySet()
        assertNull(rig.runner.runNow())
        settle()
        assertNull(rig.runner.state.value.lock)
        assertNull(rig.store.record.lock)
        assertTrue(rig.runner.admit(1L) {})
        assertTrue(rig.runner.open)
    }

    @Test
    fun `a missing board calls for the restart and the repower before the sweep`() = runTest {
        val rig = started(MemoryStore(SafetyRecord(count = 5)))
        rig.host.boards = "OK,OK,MISSING,OK,OK,OK,OK"
        rig.host.boardsAfterRestart = allOk
        rig.play()
        settle()
        val calls = rig.host.calls
        assertTrue(calls.indexOf("restart") in 0 until calls.indexOf("sweep"))
        assertTrue(calls.indexOf("repower") in 0 until calls.indexOf("sweep"))
        assertEquals(1, rig.host.sweeps())
        assertNull(rig.runner.state.value.lock)
        assertTrue(rig.runner.state.value.last!!.recovered)
    }

    @Test
    fun `on firmware without restart the step is left out, and the repower still comes before the second sweep`() = runTest {
        val rig = started(MemoryStore(SafetyRecord(count = 5)))
        rig.host.facts = mapOf("active" to "0", "boards" to allOk)   // firmware 2.1.0: no !restartproto
        rig.host.silent = setOf(50)
        rig.play()
        settle()
        assertFalse(rig.host.calls.contains("restart"))
        assertFalse(rig.host.calls.contains("ask ota"))
        assertEquals(1, rig.repowers)
        assertEquals(2, rig.host.sweeps())
        assertTrue(rig.host.calls.indexOf("repower") in rig.host.calls.indexOf("sweep") until rig.host.calls.lastIndexOf("sweep"))
        assertEquals(SafetyLock.KeysSilent(listOf(50)), rig.runner.state.value.lock)
    }

    @Test
    fun `with the microphone off there is no sweep, and no key can lock Play`() = runTest {
        val rig = started(MemoryStore(SafetyRecord(count = 5)), listens = false)
        rig.host.silent = setOf(36)
        rig.play()
        settle()
        assertEquals(0, rig.host.sweeps())
        assertNull(rig.runner.state.value.lock)
        assertTrue(rig.runner.state.value.last!!.passed)
        assertNull("keys not tested", rig.runner.state.value.last!!.keys)
        assertEquals(listOf("pause", "release", "ask active", "ask boards"), rig.host.calls)
    }

    @Test
    fun `no dump from the piano's console - asked once more, then unfinished and due again, never passed (v1_27 M64)`() = runTest {
        val rig = started(MemoryStore(SafetyRecord(count = 5)), listens = false)
        rig.host.facts = null   // the console didn't answer `dump` on this connection
        rig.play()
        advanceTimeBy(5_000)
        assertEquals(SafetyOutcome.Unfinished(Why.NO_DUMP), rig.runner.state.value.ended)
        assertEquals("the keys released, nothing read", listOf("pause", "dump again", "release"), rig.host.calls)
        settle()
        assertTrue("due before the next Play", rig.store.record.pending)
        assertEquals(6, rig.store.record.count)
        assertNull("no check passed", rig.store.record.last)
        assertNull(rig.runner.state.value.lock)
        assertFalse("Play waits for a check", rig.runner.open)

        // Its dump comes when asked again: the coils are read, as on any connection.
        val again = started(MemoryStore(SafetyRecord(count = 5)), listens = false)
        again.host.facts = null
        again.host.factsAfterDump = mapOf("active" to "0", "boards" to allOk, "restartproto" to "1")
        again.play()
        settle()
        assertEquals(listOf("pause", "dump again", "release", "ask active", "ask boards"), again.host.calls)
        assertTrue(again.runner.state.value.last!!.passed)

        // A piano with no console at all: its facts left out, and with the microphone off it passes, as before.
        val none = started(MemoryStore(SafetyRecord(count = 5)), listens = false)
        none.host.facts = emptyMap()
        none.play()
        settle()
        assertEquals(listOf("pause", "release"), none.host.calls)
        assertTrue(none.runner.state.value.last!!.passed)
    }

    @Test
    fun `the 24 V not up for the keys fails none of them - the repower, then the keys, or due again (v1_26 M61)`() = runTest {
        val rig = started(MemoryStore(SafetyRecord(count = 5)))
        rig.rail += false   // the first wait: the rail doesn't come up; the second: it does
        rig.play()
        settle()
        assertEquals(
            listOf("pause", "release", "ask active", "ask boards", "await rail", "repower", "ask active", "ask boards", "await rail", "sweep"),
            rig.host.calls,
        )
        assertNull(rig.runner.state.value.lock)
        assertTrue(rig.runner.state.value.last!!.passed)

        val never = started(MemoryStore(SafetyRecord(count = 5)))
        never.rail += listOf(false, false)
        never.host.silent = setOf(36)   // would fail, were it swept
        never.play()
        settle()
        assertEquals(0, never.host.sweeps())
        assertEquals(1, never.repowers)
        assertNull("no key judged, no lock", never.runner.state.value.lock)
        assertTrue("due again before the next Play", never.runner.state.value.pending)

        // The key test's own wait finding it down is the same: no key judged, the repower, then the keys.
        val own = started(MemoryStore(SafetyRecord(count = 5)))
        own.host.testRail += false
        own.host.silent = setOf(36)   // only heard on the second sweep, after the repower: one restart-free failure
        own.play()
        settle()
        assertEquals(2, own.host.sweeps())
        assertEquals(1, own.repowers)
        assertTrue("no restart for a rail", "restart" !in own.host.calls)
        assertEquals(SafetyLock.KeysSilent(listOf(36)), own.runner.state.value.lock)
    }

    @Test
    fun `a check stopped on the way is due again before the next Play, and the Play that waited is not played`() = runTest {
        val rig = started(MemoryStore(SafetyRecord(count = 5)))
        rig.play()
        runCurrent()
        var waited = 0
        assertFalse(rig.runner.admit(3L) { waited++ })
        // The sweep under way; the app leaves the screen.
        advanceTimeBy(10_000)
        runCurrent()
        assertTrue(rig.runner.state.value.check?.running?.step == SafetyStep.SWEEP)
        rig.host.away = true
        rig.host.stopSweep()   // the microphone stops with the app
        settle()
        val state = rig.runner.state.value
        assertFalse(state.running)
        assertTrue(state.pending)
        assertNull(state.lock)
        assertEquals(0, waited)
        assertTrue(rig.store.record.pending)
        assertEquals("the count stands until a check passes", 6, rig.store.record.count)
    }

    @Test
    fun `a check under way when the app was killed is due when it starts again`() = runTest {
        val store = MemoryStore(SafetyRecord(count = 5))
        val rig = started(store)
        rig.play()
        runCurrent()
        assertTrue(rig.runner.state.value.running)
        assertTrue("due while under way", store.record.pending)
        val again = started(MemoryStore(store.record))
        assertTrue(again.runner.state.value.pending)
        assertFalse(again.runner.open)
    }

    private companion object {
        /** Longer than any check takes: two sweeps, the restart's minute, the repower, and the card's moment after. */
        const val SETTLE_MS = 5 * 60_000L
    }
}
