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
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The SwitchBot Plug Mini (v1.36 — M72), piano safety: its adverts read as Steven's real plug sent them, the draw's formula
 * with bit 15 masked, and the rail's ARM, CUT and status as frames and replies, a plug that doesn't answer read as such.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SwitchBotTest {
    private fun hex(text: String): ByteArray = text.replace(" ", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test
    fun `the real plug's advert - its MAC, on, 0_0 W, and the Plug Mini's service data`() {
        val advert = SwitchBot.advert(hex("68ee8f2847ca 01 80 00 00 00 00"))!!
        assertEquals("68:EE:8F:28:47:CA", advert.mac)
        assertEquals(1, advert.seq)
        assertTrue("byte 7 bit 7: on", advert.on)
        assertEquals(0.0, advert.watts, 0.0)
        assertEquals("47CA", SwitchBot.lastFour(advert.mac))
        assertTrue(SwitchBot.isPlugMini(hex("67 00 64")))
        assertEquals("Plug Mini (JP)", SwitchBot.model(hex("6a 00 64")))
        assertFalse("another SwitchBot", SwitchBot.isPlugMini(hex("48 00 64")))
        assertNull("too short", SwitchBot.advert(hex("68ee8f2847ca 01 80")))
    }

    @Test
    fun `the draw in tenths of a watt from bytes 10 and 11, bit 15 masked`() {
        val made = SwitchBot.advert(hex("68ee8f2847ca 01 80 00 c4 00 7b"))!!
        assertTrue(made.on)
        assertEquals(12.3, made.watts, 1e-9)
        assertEquals(-196, made.wifiRssi)
        val masked = SwitchBot.advert(hex("68ee8f2847ca 02 00 00 c4 80 7b"))!!
        assertFalse(masked.on)
        assertEquals("bit 15 is no part of the draw", 12.3, masked.watts, 1e-9)
        assertEquals(3276.7, SwitchBot.watts(0xFF, 0xFF), 1e-9)
    }

    @Test
    fun `the rail's ARM, CUT and status - the frames and their replies, and a plug that doesn't answer reads false or null`() = runTest {
        val plug = EmulatedSwitchBot({ currentTime }, on = false)
        val transport = FakePlugTransport(plug)
        val client = PlugClient(transport, backgroundScope, address = { EmulatedSwitchBot.ADDRESS }, io = StandardTestDispatcher(testScheduler))
        val adverts = PlugAdverts({ currentTime })
        val rail = SwitchBotRail(client, adverts)

        assertTrue("ARM: on, its reply 01 80", rail.arm(RailKeeper.TOGGLE_AFTER_S))
        assertTrue(plug.output())
        adverts.heard(SwitchBot.advert(plug.manufacturer())!!)
        assertEquals(PlugStatus(output = true, timerS = null, watts = EmulatedSwitchBot.IDLE_WATTS), rail.status())
        assertTrue("CUT: off, its reply 01 00", rail.cut())
        assertFalse(plug.output())
        assertEquals(listOf("570f50010180", "570f5101", "570f50010100"), transport.frames)
        assertEquals("one connection for the three", 1, transport.connects)

        advanceTimeBy(PlugAdverts.FRESH_MS + 1)
        assertEquals("the advert over 3 s old: the draw unread", PlugStatus(output = false, watts = null), rail.status())
        assertFalse("nothing to put back", rail.putBack()!!)

        plug.silent = true
        assertFalse(rail.arm(RailKeeper.TOGGLE_AFTER_S))
        assertFalse(rail.cut())
        assertNull(rail.status())
        assertEquals("a reply it didn't take", null, SwitchBot.reply(byteArrayOf(0x05)))
        assertEquals(null, SwitchBot.reply(byteArrayOf(0x01)))
    }
}
