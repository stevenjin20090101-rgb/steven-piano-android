// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.ble

import dev.stevenjin.stevenpiano.midi.MidiBatch
import dev.stevenjin.stevenpiano.midi.hex
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The gate in front of the player (v1.25 — M58): what the piano's Bluetooth lock lets through while it is decided or
 * refuses this tablet (only releasing), what the player sees (connecting, or why not), and that the stop is never held.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LockGateTest {
    private val link = FakePianoLink()

    private fun batch(vararg bytes: Int): MidiBatch = MidiBatch().apply { for (i in bytes.indices step 3) add(bytes[i], bytes[i + 1], bytes[i + 2]) }

    @Test
    fun `only what releases passes an untrusted connection - Note Off, Note On at 0, CC 120 121 123, the pedal up`() {
        val all = batch(
            0x90, 60, 80, 0x91, 61, 0, 0x80, 62, 64, 0xB0, 64, 127, 0xB0, 64, 63, 0xB0, 64, 64, 0xB0, 66, 127, 0xB0, 67, 0, 0xB0, 7, 100,
            0xB0, 120, 0, 0xB0, 121, 0, 0xB0, 123, 0, 0xC0, 1, 0, 0xE0, 0, 64,
        )
        assertEquals(
            listOf("91 3D 00", "80 3E 40", "B0 40 3F", "B0 78 00", "B0 79 00", "B0 7B 00"),
            LockGate.releasing(all)?.hex(),
        )
        assertNull("nothing that releases: nothing to send", LockGate.releasing(batch(0x90, 60, 80, 0xB0, 64, 127)))
    }

    @Test
    fun `a piano whose firmware may have the lock is held until the lock says, older firmware is not`() {
        assertTrue(LockGate.mayLock("2.1.0+a1b2c3d"))
        assertTrue(LockGate.mayLock("2.1.0-rc.1"))
        assertTrue(LockGate.mayLock("3.0.0"))
        assertFalse(LockGate.mayLock("2.0.9+a1b2c3d"))
        assertFalse(LockGate.mayLock("1.9.0"))
        assertFalse(LockGate.mayLock(null))
        assertFalse(LockGate.mayLock("emulator"))
    }

    @Test
    fun `what the player sees - connecting while it is decided, why not when refused, connected when open`() = runTest {
        val trust = MutableStateFlow(LinkTrust())
        link.firmwareVersionOnConnect = "2.1.0+a1b2c3d"
        link.connect(null)
        val gate = LockGate(link, trust, backgroundScope)
        runCurrent()
        assertEquals("2.1.0, the dump not read yet: held", LinkState.Connecting, gate.state.value)
        gate.send(batch(0x90, 60, 80), dropPending = false)
        assertTrue(link.messages.isEmpty())
        trust.value = LinkTrust(0, Trust.Pending)
        runCurrent()
        assertEquals(LinkState.Connecting, gate.state.value)
        trust.value = LinkTrust(0, Trust.Refused(LinkError.NotAccepted))
        runCurrent()
        assertEquals(LinkError.NotAccepted.toState(), gate.state.value)
        trust.value = LinkTrust(0, Trust.Open)
        runCurrent()
        assertEquals(link.state.value, gate.state.value)
        gate.sendLive(batch(0x90, 60, 80))
        assertEquals(listOf("90 3C 50"), link.messages)
        assertTrue(link.sent.single().live)

        trust.value = LinkTrust(7, Trust.Open)   // about another connection: this one is undecided again
        runCurrent()
        assertEquals(LinkState.Connecting, gate.state.value)
        link.firmwareVersionOnConnect = "2.0.1+a1b2c3d"
        link.disconnect()
        link.connect(null)
        runCurrent()
        assertEquals("firmware 2.0.x: nothing held, as before the lock", link.state.value, gate.state.value)
        link.disconnect()
        runCurrent()
        assertEquals(LinkState.Disconnected, gate.state.value)
    }

    @Test
    fun `refused, Retry reconnects, the stop and the crash silence always go through`() = runTest {
        val trust = MutableStateFlow(LinkTrust(0, Trust.Refused(LinkError.LockedOut)))
        val gate = LockGate(link, trust, backgroundScope)
        runCurrent()
        assertEquals(LinkError.LockedOut.toState(), gate.state.value)
        gate.send(batch(0xB0, 64, 0, 0xB0, 123, 0), dropPending = true)
        assertEquals(listOf("B0 40 00", "B0 7B 00"), link.messages)
        assertTrue(gate.emergencySilence(200))
        assertEquals(listOf(200L), link.emergencySilences)
        val states = mutableListOf<LinkState>()
        val recording = object : PianoLink by link {
            override fun disconnect() {
                states += LinkState.Disconnected
                link.disconnect()
            }

            override fun connect(address: String?) {
                states += LinkState.Connecting
                link.connect(address)
            }
        }
        LockGate(recording, trust, backgroundScope).connect("AA:BB:CC:DD:EE:01")
        assertEquals("a new connection, a new claim or proof", listOf(LinkState.Disconnected, LinkState.Connecting), states)
    }
}
