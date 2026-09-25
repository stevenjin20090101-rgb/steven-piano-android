// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.ble

import android.Manifest
import dev.stevenjin.stevenpiano.midi.MidiBatch
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The link's state machine against the contract: scan, connect, MTU, discovery, writes, drops. */
class GattPianoLinkTest {
    private val executor = FakeLinkExecutor()
    private val radio = FakeRadio()
    private val remembered = mutableListOf<Pair<String, String>>()
    private var autoConnect = true
    private val link = GattPianoLink(radio, executor, { address, name -> remembered += address to name }, { autoConnect }, log = {})
    private val address = "C8:2E:18:00:11:22"

    private fun state() = link.state.value

    private fun connectFully(mtu: Int = 255): FakeGatt {
        link.connect(null)
        executor.runDue()
        radio.find(address, PianoBluetooth.NAME)
        executor.runDue()
        return radio.connections.last().also { finishConnecting(it, mtu) }
    }

    private fun finishConnecting(gatt: FakeGatt, mtu: Int = 255) {
        gatt.connected()
        executor.runDue()
        gatt.mtu(mtu)
        executor.runDue()
        gatt.discovered()
        executor.runDue()
    }

    private fun notes(count: Int) = MidiBatch().apply { repeat(count) { add(0x90, 24 + it, 64) } }

    @Test
    fun `connect scans, connects, negotiates the MTU, discovers, then is connected`() {
        link.connect(null)
        executor.runDue()
        assertEquals(LinkState.Scanning, state())
        assertTrue(radio.scanning)
        radio.find("11:22:33:44:55:66", "Someone's MIDI keyboard")
        executor.runDue()
        assertTrue("another BLE-MIDI device is not the piano", radio.connections.isEmpty())

        radio.find(address, PianoBluetooth.NAME)
        executor.runDue()
        assertFalse("scanning stops before connecting", radio.scanning)
        val gatt = radio.connections.single()
        assertFalse(gatt.autoConnect)
        assertEquals(LinkState.Connecting, state())

        gatt.connected()
        executor.runDue()
        assertEquals(255, gatt.requestedMtu)
        assertEquals(0, gatt.discoveries)
        gatt.mtu(247)
        executor.runDue()
        assertEquals(1, gatt.discoveries)
        gatt.discovered()
        executor.runDue()
        assertEquals(LinkState.Connected(PianoBluetooth.NAME, 247), state())
        assertTrue(gatt.highPriority)
        assertEquals(listOf(address to PianoBluetooth.NAME), remembered)
    }

    @Test
    fun `the remembered address is enough when the scan response has no name`() {
        link.connect(address)
        executor.runDue()
        radio.find(address, null)
        executor.runDue()
        assertEquals(1, radio.connections.size)
    }

    @Test
    fun `nothing found in 12 s says the piano can't be reached`() {
        link.connect(null)
        executor.advance(11_999)
        assertEquals(LinkState.Scanning, state())
        executor.advance(1)
        assertEquals(LinkError.NotFound.toState(), state())
        assertFalse(radio.scanning)
    }

    @Test
    fun `a blocked radio says why and does not scan`() {
        for (reason in listOf(LinkError.BluetoothOff, LinkError.PermissionMissing, LinkError.LocationOff, LinkError.Unsupported)) {
            radio.blocker = reason
            link.connect(null)
            executor.runDue()
            assertEquals(reason.toState(), state())
        }
        assertEquals(0, radio.scans)
    }

    @Test
    fun `the MTU request timing out falls back to 23 and carries on`() {
        link.connect(null)
        executor.runDue()
        radio.find(address, PianoBluetooth.NAME)
        executor.runDue()
        val gatt = radio.connections.single()
        gatt.connected()
        executor.advance(3_000)
        assertEquals(1, gatt.discoveries)
        gatt.discovered()
        executor.runDue()
        assertEquals(LinkState.Connected(PianoBluetooth.NAME, 23), state())
    }

    @Test
    fun `discovery is retried once, then the link gives up`() {
        link.connect(null)
        executor.runDue()
        radio.find(address, PianoBluetooth.NAME)
        executor.runDue()
        val gatt = radio.connections.single()
        gatt.hasMidi = false
        gatt.connected()
        gatt.mtu(255)
        gatt.discovered()
        executor.runDue()
        assertEquals(2, gatt.discoveries)
        gatt.discovered()
        executor.runDue()
        assertEquals(LinkError.Failed.toState(), state())
        assertTrue(gatt.closed)
    }

    @Test
    fun `a first connection that fails is retried once on a fresh gatt`() {
        link.connect(null)
        executor.runDue()
        radio.find(address, PianoBluetooth.NAME)
        executor.runDue()
        val first = radio.connections.single()
        first.dropped(status = 133)
        executor.runDue()
        assertTrue(first.closed)
        assertEquals(LinkState.Connecting, state())
        executor.advance(500)
        val second = radio.connections.last()
        assertEquals(2, radio.connections.size)
        first.connected()   // a late callback from the closed gatt
        executor.runDue()
        assertEquals(0, first.requestedMtu)
        second.dropped(status = 133)
        executor.runDue()
        assertEquals(LinkError.NotFound.toState(), state())
    }

    @Test
    fun `one write in flight, the next packet waits for the callback and the pace`() {
        val gatt = connectFully()
        link.send(notes(30), dropPending = false)
        executor.runDue()
        assertEquals(1, gatt.writes.size)
        assertEquals(1 + 20 * 4, gatt.writes[0].size)
        executor.advance(50)
        assertEquals("no second write before onCharacteristicWrite", 1, gatt.writes.size)
        gatt.writeDone()
        executor.runDue()
        assertEquals(2, gatt.writes.size)
        assertEquals(1 + 10 * 4, gatt.writes[1].size)
    }

    @Test
    fun `a busy stack gets the same packet again`() {
        val gatt = connectFully()
        gatt.nextWrite = WriteResult.Busy
        link.send(notes(1), dropPending = false)
        executor.runDue()
        assertTrue(gatt.writes.isEmpty())
        gatt.nextWrite = WriteResult.Sent
        executor.advance(5)
        assertEquals(1, gatt.writes.size)
        assertArrayEquals(byteArrayOf(0x90.toByte(), 24, 64), gatt.writes[0].copyOfRange(2, 5))
    }

    @Test
    fun `a write callback that never comes does not stall the queue`() {
        val gatt = connectFully()
        link.send(notes(25), dropPending = false)
        executor.runDue()
        executor.advance(1_000)
        assertEquals(2, gatt.writes.size)
    }

    @Test
    fun `the stop sequence replaces whatever is still queued`() {
        val gatt = connectFully()
        link.send(notes(30), dropPending = false)
        executor.runDue()
        link.send(MidiBatch().apply { add(0xB0, 64, 0); add(0xB0, 123, 0) }, dropPending = true)
        executor.advance(20)
        gatt.writeDone()
        executor.runDue()
        val stop = gatt.writes.last().map { it.toInt() and 0xFF }
        assertEquals(listOf(0xB0, 64, 0, 0xB0, 123, 0), listOf(stop[2], stop[3], stop[4], stop[6], stop[7], stop[8]))
        assertEquals(9, stop.size)
    }

    @Test
    fun `nothing is queued while disconnected, and flush then returns at once`() {
        link.send(notes(3), dropPending = false)
        assertTrue(link.flush(10))
        val gatt = connectFully()
        executor.runDue()
        assertTrue(gatt.writes.isEmpty())
        link.send(notes(3), dropPending = false)
        assertFalse("still queued: the executor has not run", link.flush(20))
    }

    @Test
    fun `a drop closes the gatt, reconnects in the background, and scans with backoff after 20 s`() {
        val first = connectFully()
        first.dropped()
        executor.runDue()
        assertTrue(first.closed)
        assertEquals(LinkState.Reconnecting(1), state())
        val background = radio.connections.last()
        assertTrue(background.autoConnect)
        assertEquals(address, background.address)
        assertFalse(radio.scanning)

        executor.advance(20_000)
        assertTrue(radio.scanning)
        executor.advance(12_000)
        assertFalse(radio.scanning)
        assertEquals(LinkState.Reconnecting(2), state())
        executor.advance(999)
        assertFalse(radio.scanning)
        executor.advance(1)
        assertTrue("backoff 1 s", radio.scanning)
        executor.advance(12_000 + 2_000)
        assertTrue("backoff 2 s", radio.scanning)

        radio.find(address, PianoBluetooth.NAME)
        executor.runDue()
        assertTrue(background.closed)
        val direct = radio.connections.last()
        assertFalse(direct.autoConnect)
        finishConnecting(direct)
        assertEquals(LinkState.Connected(PianoBluetooth.NAME, 255), state())
    }

    @Test
    fun `the background connection can bring the piano back by itself`() {
        connectFully().dropped()
        executor.runDue()
        val background = radio.connections.last()
        finishConnecting(background)
        assertEquals(LinkState.Connected(PianoBluetooth.NAME, 255), state())
        val scans = radio.scans
        executor.advance(120_000)
        assertEquals("no reconnect scans once connected", scans, radio.scans)
    }

    @Test
    fun `with auto-connect off a drop just disconnects`() {
        autoConnect = false
        connectFully().dropped()
        executor.runDue()
        assertEquals(LinkState.Disconnected, state())
        assertEquals(1, radio.connections.size)
    }

    @Test
    fun `disconnect closes everything and late callbacks change nothing`() {
        val gatt = connectFully()
        link.disconnect()
        executor.runDue()
        assertTrue(gatt.disconnected)
        assertTrue(gatt.closed)
        assertEquals(LinkState.Disconnected, state())
        gatt.dropped()
        executor.advance(60_000)
        assertEquals(LinkState.Disconnected, state())
        assertEquals(1, radio.connections.size)
    }

    @Test
    fun `Bluetooth turning off then on brings the piano back`() {
        val gatt = connectFully()
        radio.adapter(on = false)
        executor.runDue()
        assertTrue(gatt.closed)
        assertEquals(LinkError.BluetoothOff.toState(), state())
        radio.adapter(on = true)
        executor.runDue()
        assertEquals(LinkState.Reconnecting(1), state())
        assertTrue(radio.connections.last().autoConnect)
    }

    @Test
    fun `a revoked permission ends the link with that reason`() {
        val gatt = connectFully()
        gatt.revoked = true
        link.send(notes(1), dropPending = false)
        executor.runDue()
        assertEquals(LinkError.PermissionMissing.toState(), state())
        assertTrue(gatt.closed)
    }

    @Test
    fun `the emergency stop goes straight onto the connection, past a queue and a write in flight`() {
        val gatt = connectFully()
        link.send(notes(30), dropPending = false)
        executor.runDue()   // one packet in flight, ten messages still queued behind it
        assertTrue(link.emergencySilence(200))
        val stop = gatt.writes.last().map { it.toInt() and 0xFF }
        assertEquals(9, stop.size)
        assertEquals(listOf(0xB0, 64, 0, 0xB0, 123, 0), listOf(stop[2], stop[3], stop[4], stop[6], stop[7], stop[8]))
    }

    @Test
    fun `the emergency stop gives up when not connected, when the stack stays busy, or when it throws`() {
        assertFalse(link.emergencySilence(200))
        val gatt = connectFully()
        gatt.nextWrite = WriteResult.Busy
        val started = System.nanoTime()
        assertFalse(link.emergencySilence(60))
        val tookMs = (System.nanoTime() - started) / 1_000_000
        assertTrue("retried for $tookMs ms", tookMs in 40L..1_000L)
        gatt.nextWrite = WriteResult.Sent
        gatt.revoked = true
        assertFalse(link.emergencySilence(200))
        link.disconnect()
        executor.runDue()
        assertFalse(link.emergencySilence(200))
    }

    @Test
    fun `scans stay under five in thirty seconds`() {
        val throttle = ScanThrottle()
        repeat(5) {
            assertEquals(0L, throttle.delayBeforeNextScan(it * 1_000L))
            throttle.record(it * 1_000L)
        }
        assertEquals(25_000L, throttle.delayBeforeNextScan(5_000L))
        assertEquals(0L, throttle.delayBeforeNextScan(30_000L))
    }

    @Test
    fun `permissions follow the API level`() {
        assertEquals(listOf(Manifest.permission.ACCESS_FINE_LOCATION), BlePermissions.required(26))
        assertEquals(listOf(Manifest.permission.ACCESS_FINE_LOCATION), BlePermissions.required(30))
        assertEquals(
            listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT),
            BlePermissions.required(34),
        )
        assertEquals(listOf(Manifest.permission.BLUETOOTH_CONNECT), BlePermissions.missing(31) { it == Manifest.permission.BLUETOOTH_SCAN })
        assertTrue(BlePermissions.needsLocationServices(30))
        assertFalse(BlePermissions.needsLocationServices(31))
    }
}
