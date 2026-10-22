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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A piece waits for the 24 V rail (v1.26 — M60), piano safety: notes never go before the rail is up, and nothing of the
 * piece is lost: it is paused just before its first note when the pause before it runs out first, and resumed once up.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RailHoldTest {
    private class Bench {
        val player = MutableStateFlow(PlayerNow(playing = false, stopped = true, pieceId = null))
        val held = MutableStateFlow(true)
        var position = -2_000_000L   // µs: the 2 s pause before the piece
        val calls = mutableListOf<String>()
        val wants = mutableListOf<Boolean>()

        fun hold(scope: TestScope): RailHold = RailHold(
            scope.backgroundScope,
            player,
            held,
            positionMicros = { position },
            pause = {
                calls += "pause"
                player.value = player.value.copy(playing = false)
            },
            resume = {
                calls += "resume"
                player.value = player.value.copy(playing = true)
            },
            want = { wants += it },
        ).also {
            it.start()
            scope.runCurrent()
        }

        fun play(piece: Long = 1L) {
            player.value = PlayerNow(playing = true, stopped = false, pieceId = piece)
        }
    }

    @Test
    fun `the pause before the piece runs out first - paused just before its first note, resumed once the rail is up`() = runTest {
        val bench = Bench()
        val hold = bench.hold(this)
        bench.play()
        runCurrent()
        assertEquals("a piece playing wants the rail", listOf(false, true), bench.wants)
        bench.position = -1_000_000L
        advanceTimeBy(500)
        assertTrue("a second of the pause left: it runs on", bench.calls.isEmpty())
        bench.position = -RailHold.MARGIN_US + 1
        advanceTimeBy(RailHold.POLL_MS + 1)
        assertEquals(listOf("pause"), bench.calls)
        assertTrue(hold.holdingNow)
        assertEquals("still wanted while it waits", true, bench.wants.last())
        bench.held.value = false
        runCurrent()
        assertEquals(listOf("pause", "resume"), bench.calls)
        assertFalse(hold.holdingNow)
        assertEquals("wanted throughout: never a stop between the hold and the music", listOf(false, true), bench.wants)
    }

    @Test
    fun `the rail up within the pause - no pause at all`() = runTest {
        val bench = Bench()
        bench.hold(this)
        bench.play()
        advanceTimeBy(1_000)
        bench.held.value = false
        bench.position = 0
        advanceTimeBy(5_000)
        assertTrue(bench.calls.isEmpty())
    }

    @Test
    fun `a Play while held is held again`() = runTest {
        val bench = Bench()
        bench.hold(this)
        bench.play()
        bench.position = 0
        advanceTimeBy(100)
        assertEquals(listOf("pause"), bench.calls)
        bench.play()   // a person taps Play while the rail still comes up
        runCurrent()
        assertEquals(listOf("pause", "pause"), bench.calls)
    }

    @Test
    fun `a stop while held ends the hold, and the rail is no longer wanted`() = runTest {
        val bench = Bench()
        val hold = bench.hold(this)
        bench.play()
        bench.position = 0
        advanceTimeBy(100)
        assertTrue(hold.holdingNow)
        bench.player.value = PlayerNow(playing = false, stopped = true, pieceId = 1L)
        runCurrent()
        assertFalse(hold.holdingNow)
        assertEquals(false, bench.wants.last())
        bench.held.value = false
        runCurrent()
        assertEquals("nothing resumed after a stop", listOf("pause"), bench.calls)
    }
}
