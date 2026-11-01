// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.power

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
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
 * The advert watch and the CUTs the SwitchBot's missing timer calls for (v1.36 — M72), piano safety: the keeper over the
 * real [SwitchBotRail] and [PlugAdverts], the fake plug's adverts every 333 ms as the real one sends them, on a fake clock.
 * Nothing wanting the rail, an "on" advert is CUT within one advert, and so is the plug at app start and at a new
 * connection; something wanting it, nothing is. The power watch reads its draw from the adverts: a stuck key still trips,
 * and adverts gone silent read as unread.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AdvertWatchTest {
    private inner class Stack(private val test: TestScope, plugOn: Boolean) {
        val plug = EmulatedSwitchBot({ test.currentTime }, on = plugOn)
        val transport = FakePlugTransport(plug)
        val logs = mutableListOf<String>()
        val adverts = PlugAdverts({ test.currentTime }, log = { logs += it })
        var keeper: RailKeeper? = null
        var advertising = true
        val client = PlugClient(
            transport,
            test.backgroundScope,
            address = { EmulatedSwitchBot.ADDRESS },
            io = StandardTestDispatcher(test.testScheduler),
            log = { logs += it },
            connected = { test.backgroundScope.launch { keeper?.plugConnected() } },
        )
        val host = Host()
        val link = MutableStateFlow(true)

        /** The keeper, started, with the adverts flowing to it as the app wires them ([advertise] false: none). */
        fun keeper(adverts: Boolean = true): RailKeeper {
            val made = RailKeeper(
                scope = test.backgroundScope,
                plug = SwitchBotRail(client, this.adverts),
                check = ::check,
                connected = link,
                stopSequence = {},
                stopPlaying = {},
                available = MutableStateFlow(true),
                now = { test.currentTime },
                log = { logs += it },
                power = host,
            )
            keeper = made
            made.start()
            if (adverts) {
                test.backgroundScope.launch { this@Stack.adverts.run(transport, EmulatedSwitchBot.ADDRESS, plug.mac, fast = true) { made.advert(it.on) } }
                test.backgroundScope.launch {
                    while (true) {
                        delay(ADVERT_MS)
                        if (advertising) transport.advertise()
                    }
                }
            }
            test.runCurrent()
            return made
        }

        /** The piano's boards: up 0.9 s after the plug's relay closes. */
        suspend fun check(): Boards {
            delay(CHECK_MS)
            val up = plug.poweredSince()?.let { test.currentTime - it >= BOARDS_UP_MS } == true
            return Boards(List(Boards.TOTAL) { up })
        }
    }

    /** The router holds no key and its pedal is up; nothing struck, so no check after the piece. */
    private class Host : PowerHost {
        var due = 0

        override fun held(from: Long, to: Long): Double = 0.0

        override fun pedalDown(from: Long, to: Long): Boolean = false

        override fun struckSince(at: Long): Boolean = false

        override suspend fun ask(fact: String): String? = "0"

        override val fullPower: Boolean = true

        override fun checkDue() {
            due++
        }

        override fun usualIdle(): List<Double> = emptyList()

        override fun keepIdle(watts: Double) = Unit

        override fun aboveUsual() = Unit
    }

    @Test
    fun `idle and an advert says on - CUT within one advert, and wanted - no CUT`() = runTest {
        val s = Stack(this, plugOn = true)   // plugged in, it came on by itself
        val keeper = s.keeper()
        advanceTimeBy(ADVERT_MS + 1)
        assertTrue("the first advert said on", s.logs.any { "advert says on" in it })
        advanceTimeBy(ADVERT_MS - 1)
        assertFalse("CUT before the next advert", s.plug.output())
        assertEquals(listOf("570f50010100"), s.transport.frames)
        assertNull("logged, not said", keeper.message.value)

        s.plug.switchOnFromOutside()   // someone with the SwitchBot app
        advanceTimeBy(RailKeeper.ADVERT_SETTLE_MS + ADVERT_MS)
        assertFalse("switched straight back off", s.plug.output())

        keeper.wanted(RailKeeper.PLAY, true)
        advanceTimeBy(3_000)
        assertEquals(RailPhase.Up, keeper.phase.value)
        val up = s.transport.frames.size
        advanceTimeBy(15_000)
        assertTrue("wanted: on, and not one CUT", s.plug.output())
        assertFalse(s.transport.frames.drop(up).contains("570f50010100"))
    }

    @Test
    fun `app start and a new connection - CUT when nothing wants the rail, never when something does`() = runTest {
        val s = Stack(this, plugOn = true)
        val keeper = s.keeper(adverts = false)
        keeper.appStarted()
        advanceTimeBy(500)
        assertFalse("CUT at app start", s.plug.output())
        advanceTimeBy(RailKeeper.WELD_CHECK_MS)   // the weld look after it, over its own connection

        s.plug.switchOnFromOutside()
        s.client.drop()
        advanceTimeBy(RailKeeper.CONNECT_CUT_GAP_MS)
        assertEquals("a look over a new connection", true, s.client.state())
        advanceTimeBy(500)
        assertFalse("CUT at the new connection", s.plug.output())

        keeper.wanted(RailKeeper.PLAY, true)
        advanceTimeBy(3_000)
        assertEquals(RailPhase.Up, keeper.phase.value)
        keeper.appStarted()
        s.client.drop()
        advanceTimeBy(RailKeeper.KEEP_EVERY_MS + 500)   // the next ARM connects again
        assertTrue("wanted: still on", s.plug.output())
        assertEquals(RailPhase.Up, keeper.phase.value)
    }

    @Test
    fun `a want waiting for the piano - an advert saying on is never CUT, and the rail wakes the piano (v1_38 M75)`() = runTest {
        val s = Stack(this, plugOn = true)
        s.link.value = false   // the piano off: its controller is on the plug too
        val keeper = s.keeper()
        // The race M72 lost: a want put, and an "on" advert before the routine took it up (it CUT then, with no ARM of its own).
        keeper.keysOnScreen(true)
        keeper.advert(true)
        runCurrent()
        advanceTimeBy(2 * ADVERT_MS)
        assertTrue("wanted: on, never CUT", s.plug.output())
        assertFalse(s.transport.frames.contains("570f50010100"))
        s.link.value = true   // the controller up on the plug's power
        advanceTimeBy(3_000)
        assertEquals("up with the piano, through its CHECK", RailPhase.Up, keeper.phase.value)
        assertTrue(s.plug.output())
        assertFalse(s.transport.frames.contains("570f50010100"))
    }

    @Test
    fun `CUTs that don't take - three in a row and the plug still says on show the amber, and an off clears it`() = runTest {
        val s = Stack(this, plugOn = true)
        s.plug.silent = true   // its adverts say on, its GATT answers nothing
        val keeper = s.keeper()
        advanceTimeBy(20_000)
        assertTrue("the tablet can't cut the 24 V: amber", keeper.welded.value)
        assertTrue(s.logs.any { "can't switch the 24 V off" in it })
        s.plug.silent = false
        advanceTimeBy(10_000)
        assertFalse(s.plug.output())
        assertFalse("off at last: no amber", keeper.welded.value)
    }

    @Test
    fun `the power watch from adverts - a stuck key still trips, and adverts silent for 10 s read as unread`() = runTest {
        val s = Stack(this, plugOn = false)
        val keeper = s.keeper()
        keeper.wanted(RailKeeper.PLAY, true)
        advanceTimeBy(3_000)
        assertEquals(RailPhase.Up, keeper.phase.value)
        advanceTimeBy(12_000)
        assertNull("idle, no key held: nothing", keeper.message.value)

        s.advertising = false
        s.plug.extraWatts = PowerWatch.COIL_WATTS   // a coil on that the music isn't holding, but no advert says so
        advanceTimeBy(40_000)
        assertNull("unread: no verdict", keeper.message.value)
        assertEquals(RailPhase.Up, keeper.phase.value)
        assertTrue("said once in the log", s.logs.count { "no advert" in it } == 1)

        s.advertising = true
        val back = currentTime
        advanceTimeBy(25_000)
        assertEquals(RailMessage.StuckKey(afterPiece = false), keeper.message.value)
        assertFalse("CUT", s.plug.output())
        assertEquals(1, s.host.due)
        assertTrue("within about 20 s of the adverts' return", currentTime - back <= 25_000)
    }

    private companion object {
        const val ADVERT_MS = 333L
        const val CHECK_MS = 60L
        const val BOARDS_UP_MS = 900L
    }
}
