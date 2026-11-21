// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.piano

import dev.stevenjin.stevenpiano.ble.EmulatedConsole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A power board moved on this piano (M85, firmware 2.1.6): the dump's row, the facts, the lines and their replies. */
class BoardAddressTest {
    @Test
    fun `the dump's row, the moved boards, the strays and the missing`() {
        val addresses = BoardAddress.addresses("40,41,42,43,44,4d,46")!!
        assertEquals(listOf(0x40, 0x41, 0x42, 0x43, 0x44, 0x4D, 0x46), addresses)
        assertEquals(mapOf(5 to 0x4D), BoardAddress.moved(addresses))
        assertNull("not seven", BoardAddress.addresses("40,41"))
        assertNull("not hex", BoardAddress.addresses("40,41,42,43,44,zz,46"))
        assertEquals(listOf(0x4D), BoardAddress.strays("4d"))
        assertEquals(emptyList<Int>(), BoardAddress.strays(""))
        assertEquals(setOf(5), BoardAddress.missing("OK,OK,OK,OK,OK,MISSING,OK"))
        assertEquals("C6", BoardAddress.name(5))
        assertEquals("0x4D", BoardAddress.hex(0x4D))
    }

    @Test
    fun `the tick is offered only when it can't be wrong`() {
        val home = (0 until 7).map { BoardAddress.default(it) }
        val c6 = BoardAddress.Suggestion(5, 0x4D)
        assertEquals("the new piano's C6: one missing, one stray, one bit (A3)", c6, BoardAddress.suggest(setOf(5), listOf(0x4D), home))
        assertNull("two boards missing: which is which?", BoardAddress.suggest(setOf(5, 6), listOf(0x4D), home))
        assertNull("two strays", BoardAddress.suggest(setOf(5), listOf(0x4D, 0x4C), home))
        assertEquals("a reserved chip beside it isn't a candidate", c6, BoardAddress.suggest(setOf(5), listOf(0x4D, 0x71), home))
        assertNull("only a reserved stray", BoardAddress.suggest(setOf(5), listOf(0x71), home))
        assertNull("several bits away: some other chip", BoardAddress.suggest(setOf(5), listOf(0x5A), home))
        assertNull("nothing missing", BoardAddress.suggest(emptySet(), listOf(0x4D), home))
        val moved = home.toMutableList().apply { set(5, 0x4D) }
        assertEquals("the switch mended: offered back home", BoardAddress.Suggestion(5, 0x45), BoardAddress.suggest(setOf(5), listOf(0x45), moved))
    }

    @Test
    fun `the lines are the feature's own, and the piano's replies read word for word (the emulated 2_1_6)`() {
        assertEquals("boardaddr 5 4d", BoardAddress.move(5, 0x4D))
        assertEquals("boardaddr 5 default", BoardAddress.reset(5))
        assertTrue(BoardAddress.ours("boardaddr 5 4d"))
        assertFalse("never another command", BoardAddress.ours("boardaddr 5 4d; fire 60"))
        val piano = EmulatedConsole().apply { stuck = mapOf(5 to 0x4D) }
        assertTrue(piano.handle("dump").contains("!strays=4d"))
        assertEquals("OK,OK,OK,OK,OK,MISSING,OK", piano.valueOf("!boards"))
        val moved = piano.handle(BoardAddress.move(5, 0x4D)).single()
        assertTrue(BoardAddress.answers(BoardAddress.move(5, 0x4D), moved))
        assertEquals(BoardAddress.Reply.Done(answering = true), BoardAddress.read(moved))
        assertEquals("OK,OK,OK,OK,OK,OK,OK", piano.valueOf("!boards"))
        assertEquals("40,41,42,43,44,4d,46", piano.valueOf("boardaddr"))
        val clash = piano.handle(BoardAddress.move(4, 0x4D)).single()
        assertTrue(BoardAddress.read(clash) is BoardAddress.Reply.Refused)
        val back = piano.handle(BoardAddress.reset(5)).single()
        assertEquals(BoardAddress.Reply.Done(answering = false), BoardAddress.read(back))
    }
}
