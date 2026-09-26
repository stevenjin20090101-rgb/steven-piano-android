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
    private val logged = mutableListOf<String>()
    private val link = GattPianoLink(radio, executor, { address, name -> remembered += address to name }, { autoConnect }, log = { logged += it })
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
        assertEquals(LinkState.Connected(PianoBluetooth.NAME, 247, epoch = 1), state())
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
    fun `nothing found in 12 s says the piano can't be found, and why it may hide`() {
        link.connect(null)
        executor.advance(11_999)
        assertEquals(LinkState.Scanning, state())
        executor.advance(1)
        assertEquals(LinkError.NotFound().toState(), state())
        assertFalse(radio.scanning)
        assertTrue((state() as LinkState.Error).message.contains("it hides while another device is connected"))
        assertEquals("Not connected: NotFound (scan ended after 12 s, 0 BLE-MIDI devices seen)", logged.last())
    }

    @Test
    fun `nothing found with Location off adds that some devices need it`() {
        radio.locationServices = false
        link.connect(null)
        executor.runDue()
        radio.find("11:22:33:44:55:66", "Someone's MIDI keyboard")
        executor.advance(12_000)
        val error = state() as LinkState.Error
        assertEquals(LinkError.NotFound(locationOff = true), error.reason)
        assertTrue(error.message.endsWith(" On some devices, Bluetooth scanning also needs Location turned on."))
        assertEquals("Not connected: NotFound (scan ended after 12 s, 1 BLE-MIDI device seen, Location is off)", logged.last())
    }

    @Test
    fun `a scan Android refuses is its own error, with the code`() {
        link.connect(null)
        executor.runDue()
        radio.scanFailed(2)
        executor.runDue()
        assertEquals(LinkError.ScanFailed(2).toState(), state())
        assertFalse(radio.scanning)
        assertTrue(logged.contains("Scan failed: code 2 (the app could not register the scan)"))

        link.connect(null)
        executor.runDue()
        radio.scanFailed(PianoScanner.NO_SCANNER)   // no scanner: Bluetooth went off
        executor.runDue()
        assertEquals(LinkError.BluetoothOff.toState(), state())
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
        assertEquals(LinkState.Connected(PianoBluetooth.NAME, 23, epoch = 1), state())
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
        assertEquals(LinkError.ConnectFailed(133).toState(), state())
        assertTrue((state() as LinkState.Error).message.startsWith("Found Steven Piano but the connection failed (code 133)."))
        val gattError = "133 (GATT_ERROR: Android's catch-all)"
        assertEquals(
            listOf(
                "GATT disconnected: $address, status $gattError",
                "Connecting to $address failed: status $gattError; one retry in 500 ms",
                "connectGatt $address, autoConnect=false (the one retry)",
                "GATT disconnected: $address, status $gattError",
                "Not connected: ConnectFailed, status $gattError",
            ),
            logged.takeLast(5),
        )
    }

    @Test
    fun `a connection that never answers ends as a timeout, after its one retry`() {
        link.connect(null)
        executor.runDue()
        radio.find(address, PianoBluetooth.NAME)
        executor.runDue()
        executor.advance(15_000)
        assertTrue(radio.connections.single().closed)
        assertEquals(LinkState.Connecting, state())
        executor.advance(500)
        assertEquals(2, radio.connections.size)
        executor.advance(15_000)
        assertEquals(LinkError.ConnectFailed(null).toState(), state())
        assertEquals(
            "Found Steven Piano but the connection failed (timeout). Tap Retry; if it keeps failing, restart the piano.",
            (state() as LinkState.Error).message,
        )
        assertEquals(listOf("No connection to $address within 15 s", "Not connected: ConnectFailed, timeout"), logged.takeLast(2))
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
        assertEquals(LinkState.Connected(PianoBluetooth.NAME, 255, epoch = 2), state())
    }

    @Test
    fun `the background connection can bring the piano back by itself`() {
        connectFully().dropped()
        executor.runDue()
        val background = radio.connections.last()
        finishConnecting(background)
        assertEquals(LinkState.Connected(PianoBluetooth.NAME, 255, epoch = 2), state())
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
    fun `Bluetooth coming back on clears "Bluetooth is off" and, with auto-connect on, looks for the piano`() {
        radio.blocker = LinkError.BluetoothOff
        link.connect(address)
        executor.runDue()
        assertEquals(LinkError.BluetoothOff.toState(), state())
        radio.blocker = null
        radio.adapter(on = true)
        executor.runDue()
        assertEquals(LinkState.Scanning, state())
        radio.find(address, null)
        executor.runDue()
        finishConnecting(radio.connections.single())
        assertEquals(LinkState.Connected(PianoBluetooth.NAME, 255, epoch = 1), state())
    }

    @Test
    fun `with auto-connect off, Bluetooth coming back on only clears "Bluetooth is off"`() {
        autoConnect = false
        radio.blocker = LinkError.BluetoothOff
        link.connect(null)
        executor.runDue()
        radio.blocker = null
        radio.adapter(on = true)
        executor.runDue()
        assertEquals(LinkState.Disconnected, state())
        assertEquals(0, radio.scans)
    }

    @Test
    fun `Bluetooth turning off in the middle of a call never crashes the link`() {
        radio.failure = IllegalStateException("BT Adapter is not turned ON")
        link.connect(null)
        executor.runDue()   // the scan throws
        radio.adapter(on = false)
        executor.runDue()   // and so does stopping it
        assertEquals(LinkError.BluetoothOff.toState(), state())
        radio.failure = null
        radio.adapter(on = true)
        executor.runDue()
        assertEquals(LinkState.Scanning, state())
        assertTrue(logged.contains("Bluetooth refused a call (BT Adapter is not turned ON): it is probably turning off"))

        radio.failure = IllegalStateException("BT Adapter is not turned ON")
        radio.find(address, PianoBluetooth.NAME)
        executor.runDue()   // connectGatt throws
        executor.advance(500)   // and throws again on the retry
        assertEquals(LinkError.ConnectFailed(-1).toState(), state())
        assertTrue(radio.connections.isEmpty())
    }

    @Test
    fun `a connection's milestones are logged, and each device once a scan`() {
        link.connect(null)
        executor.runDue()
        repeat(3) { radio.find("11:22:33:44:55:66", "Someone's MIDI keyboard", rssi = -71) }
        radio.find("11:22:33:44:55:77", "Line one\nNot connected: forged", rssi = -80)
        radio.find(address, PianoBluetooth.NAME, rssi = -58)
        executor.runDue()
        val gatt = radio.connections.single()
        gatt.hasConsole = true
        finishConnecting(gatt, mtu = 247)
        assertEquals(
            listOf(
                "Connect: no piano remembered yet",
                "Scan started (filter: MIDI service 03B80E5A-EDE8-4B33-A751-6CE34EC4C700, mode: low latency), looking for any Steven Piano",
                "Seen 11:22:33:44:55:66 \"Someone's MIDI keyboard\", RSSI -71 dBm, MIDI service yes: ignored: name is not Steven Piano",
                "Seen 11:22:33:44:55:77 \"Line one?Not connected: forged\", RSSI -80 dBm, MIDI service yes: ignored: name is not Steven Piano",
                "Seen $address \"Steven Piano\", RSSI -58 dBm, MIDI service yes: connecting",
                "connectGatt $address, autoConnect=false",
                "GATT connected: $address, status 0 (success)",
                "MTU 247 (asked for 255)",
                "Services discovered: MIDI yes, console yes",
                "Connected to Steven Piano ($address), MTU 247, with its console",
            ),
            logged,
        )

        gatt.dropped(status = 8)
        executor.runDue()
        executor.advance(20_000)   // the first reconnect scan
        radio.find("11:22:33:44:55:66", "Someone's MIDI keyboard", rssi = -71)
        executor.runDue()
        assertEquals("a new scan logs the device again", 2, logged.count { it.startsWith("Seen 11:22:33:44:55:66") })
        assertTrue(logged.contains("GATT disconnected: $address, status 8 (connection timeout: the piano went out of range or off)"))
        assertTrue(logged.contains("connectGatt $address, autoConnect=true (in the background, until the piano is back)"))
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
    fun `a packet that lets keys go is never dropped, however long the stack stays busy`() {
        val gatt = connectFully()
        gatt.nextWrite = WriteResult.Busy
        link.send(MidiBatch().apply { add(0x80, 60, 0); add(0x90, 62, 0) }, dropPending = false)
        executor.advance(5_000)   // far past the 40 retries (about 200 ms) after which a Note On would go
        assertTrue(gatt.writes.isEmpty())
        gatt.nextWrite = WriteResult.Sent
        executor.advance(20)
        val sent = gatt.writes.single().map { it.toInt() and 0xFF }
        assertEquals(listOf(0x80, 60, 0, 0x90, 62, 0), listOf(sent[2], sent[3], sent[4], sent[6], sent[7], sent[8]))
        assertEquals(1L, (state() as LinkState.Connected).epoch)
    }

    @Test
    fun `a Note On packet given up on is followed by the stop sequence and a new epoch`() {
        val gatt = connectFully()
        gatt.nextWrite = WriteResult.Busy
        link.send(notes(3), dropPending = false)
        executor.advance(5 * 41)   // 41 tries, 5 ms apart: dropped
        assertEquals(2L, (state() as LinkState.Connected).epoch)
        gatt.nextWrite = WriteResult.Sent
        executor.advance(5)
        val stop = gatt.writes.single().map { it.toInt() and 0xFF }
        assertEquals(listOf(0xB0, 64, 0, 0xB0, 123, 0), listOf(stop[2], stop[3], stop[4], stop[6], stop[7], stop[8]))
    }

    @Test
    fun `must-arrive packets are told apart from Note Ons`() {
        fun packet(vararg messages: Int) = BleMidiFramer.frame(messages, 0, messages.size, 0)
        assertTrue(BleMidiFramer.mustArrive(packet(MidiBatch.pack(0x80, 60, 0))))
        assertTrue(BleMidiFramer.mustArrive(packet(MidiBatch.pack(0x90, 60, 64), MidiBatch.pack(0x93, 61, 0))))
        assertTrue(BleMidiFramer.mustArrive(packet(MidiBatch.pack(0xB0, 64, 127))))
        for (controller in 120..123) assertTrue(BleMidiFramer.mustArrive(packet(MidiBatch.pack(0xB0, controller, 0))))
        assertFalse(BleMidiFramer.mustArrive(packet(MidiBatch.pack(0x90, 60, 64), MidiBatch.pack(0x90, 62, 1))))
        assertFalse(BleMidiFramer.mustArrive(packet(MidiBatch.pack(0xB0, 7, 100))))
    }

    @Test
    fun `each connection has a new epoch, so a quick drop and reconnect is never missed`() {
        val first = connectFully()
        assertEquals(1L, (state() as LinkState.Connected).epoch)
        first.dropped()
        executor.runDue()
        finishConnecting(radio.connections.last())
        assertEquals(2L, (state() as LinkState.Connected).epoch)
    }

    @Test
    fun `another Steven Piano is offered, never connected to, while the known one stays away`() {
        val other = "D4:D4:D4:00:00:01"
        link.connect(address)
        executor.runDue()
        radio.find(other, PianoBluetooth.NAME)
        executor.advance(2_999)
        assertTrue("not connected to it", radio.connections.isEmpty())
        assertEquals(LinkState.Scanning, state())
        executor.advance(1)
        assertEquals(LinkError.OtherPiano.toState(other), state())
        assertFalse(radio.scanning)

        link.connect(other)   // the person tapped Connect to it
        executor.runDue()
        radio.find(other, PianoBluetooth.NAME)
        executor.runDue()
        finishConnecting(radio.connections.single())
        assertEquals(listOf(other to PianoBluetooth.NAME), remembered)
    }

    @Test
    fun `the known piano answering within the grace wins over another one`() {
        link.connect(address)
        executor.runDue()
        radio.find("D4:D4:D4:00:00:01", PianoBluetooth.NAME)
        executor.advance(1_000)
        radio.find(address, PianoBluetooth.NAME)
        executor.runDue()
        assertEquals(address, radio.connections.single().address)
        executor.advance(5_000)
        assertEquals(LinkState.Connecting, state())
    }

    @Test
    fun `reconnecting never switches to another Steven Piano`() {
        connectFully().dropped()
        executor.runDue()
        executor.advance(20_000)   // the reconnect scan starts
        assertTrue(radio.scanning)
        val before = radio.connections.size
        radio.find("D4:D4:D4:00:00:01", PianoBluetooth.NAME)
        executor.advance(5_000)
        assertEquals(before, radio.connections.size)
        assertTrue(state() is LinkState.Reconnecting)
    }

    @Test
    fun `with no piano known yet, the first Steven Piano found is the one`() {
        link.connect(null)
        executor.runDue()
        radio.find("D4:D4:D4:00:00:01", PianoBluetooth.NAME)
        executor.runDue()
        assertEquals("D4:D4:D4:00:00:01", radio.connections.single().address)
    }

    @Test
    fun `a piano this device is paired with is refused with that reason, never connected to`() {
        radio.bonded = setOf(address)
        link.connect(null)
        executor.runDue()
        radio.find(address, PianoBluetooth.NAME)
        executor.runDue()
        assertEquals(LinkError.Paired.toState(), state())
        assertTrue(radio.connections.isEmpty())
        assertFalse(radio.scanning)
        assertEquals(
            listOf(
                "$address is paired with this device in Bluetooth settings (bonded): the piano refuses encryption, so connecting would fail",
                "Not connected: Paired",
            ),
            logged.takeLast(2),
        )
    }

    /** Connected, MTU and discovery done, for a candidate: its GAP Device Name is being read. */
    private fun connectCandidate(gatt: FakeGatt) {
        gatt.connected()
        executor.runDue()
        gatt.mtu(255)
        executor.runDue()
        gatt.discovered()
        executor.runDue()
    }

    @Test
    fun `with no piano known, a nameless BLE-MIDI device is connected to after a second and kept when its GAP name is Steven Piano`() {
        link.connect(null)
        executor.runDue()
        radio.find(address, null)
        executor.advance(999)
        assertTrue("a named Steven Piano gets a second to answer", radio.connections.isEmpty())
        assertTrue(radio.scanning)
        executor.advance(1)
        val gatt = radio.connections.single()
        assertFalse(radio.scanning)
        assertFalse(gatt.autoConnect)
        connectCandidate(gatt)
        assertEquals(1, gatt.nameReads)
        assertEquals("not connected until the name is read", LinkState.Connecting, state())
        assertTrue(remembered.isEmpty())

        gatt.nameRead(PianoBluetooth.NAME)
        executor.runDue()
        assertEquals(LinkState.Connected(PianoBluetooth.NAME, 255, epoch = 1), state())
        assertEquals(listOf(address to PianoBluetooth.NAME), remembered)
        assertTrue(logged.contains("Seen $address (no name), RSSI -60 dBm, MIDI service yes: no name: a candidate, connected to in 1 s to read its name unless a named Steven Piano answers"))
        assertTrue(logged.contains("GAP Device Name \"Steven Piano\": it is the piano"))
    }

    @Test
    fun `a nameless device whose GAP name is another is let go, and the scan goes on without it`() {
        val keyboard = "11:22:33:44:55:66"
        link.connect(null)
        executor.runDue()
        radio.find(keyboard, null)
        executor.advance(1_000)
        val first = radio.connections.single()
        connectCandidate(first)
        first.nameRead("Someone's MIDI keyboard")
        executor.runDue()
        assertTrue(first.disconnected)
        assertTrue(first.closed)
        assertEquals(LinkState.Scanning, state())
        assertTrue(radio.scanning)
        assertTrue(remembered.isEmpty())
        assertTrue(logged.contains("GAP Device Name \"Someone's MIDI keyboard\" is not Steven Piano: disconnecting, and the scan goes on without $keyboard"))

        radio.find(keyboard, null)
        executor.advance(2_000)
        assertEquals("passed over until the next Connect", 1, radio.connections.size)
        radio.find(address, PianoBluetooth.NAME)
        executor.runDue()
        val piano = radio.connections.last()
        assertEquals(address, piano.address)
        finishConnecting(piano)
        assertEquals(0, piano.nameReads)
        assertEquals(listOf(address to PianoBluetooth.NAME), remembered)
    }

    @Test
    fun `a nameless device whose GAP name can't be read is not taken for the piano`() {
        link.connect(null)
        executor.runDue()
        radio.find(address, null)
        executor.advance(1_000)
        val unread = radio.connections.single()
        connectCandidate(unread)
        unread.nameRead(null, status = 137)
        executor.runDue()
        assertTrue(unread.closed)
        assertEquals(LinkState.Scanning, state())
        assertTrue(
            logged.contains(
                "GAP Device Name not read: status 137 (authentication failed). Not taken for the piano; " +
                    "disconnecting, and the scan goes on without $address",
            ),
        )

        link.disconnect()
        executor.runDue()
        link.connect(null)   // a new Connect: the device may be a candidate again
        executor.runDue()
        radio.find(address, null)
        executor.advance(1_000)
        val silent = radio.connections.last()
        connectCandidate(silent)
        executor.advance(3_000)   // the name never comes
        assertTrue(silent.closed)
        assertEquals(LinkState.Scanning, state())
        assertTrue(remembered.isEmpty())
        assertTrue(logged.last().startsWith("Scan started"))
        assertTrue(logged.contains("GAP Device Name not read: no answer within 3 s. Not taken for the piano; disconnecting, and the scan goes on without $address"))
    }

    @Test
    fun `a nameless candidate without the MIDI characteristic is let go as well, not the end of the search`() {
        link.connect(null)
        executor.runDue()
        radio.find(address, null)
        executor.advance(1_000)
        val gatt = radio.connections.single()
        gatt.hasMidi = false
        connectCandidate(gatt)
        gatt.discovered()   // the second discovery finds no MIDI either
        executor.runDue()
        assertEquals(2, gatt.discoveries)
        assertEquals(0, gatt.nameReads)
        assertTrue(gatt.closed)
        assertEquals(LinkState.Scanning, state())
    }

    @Test
    fun `a named Steven Piano answering within the second wins over a nameless candidate`() {
        link.connect(null)
        executor.runDue()
        radio.find("11:22:33:44:55:66", null)
        executor.advance(500)
        radio.find(address, PianoBluetooth.NAME)
        executor.runDue()
        executor.advance(5_000)
        val gatt = radio.connections.single()
        assertEquals(address, gatt.address)
        finishConnecting(gatt)
        assertEquals(0, gatt.nameReads)
        assertEquals(LinkState.Connected(PianoBluetooth.NAME, 255, epoch = 1), state())
    }

    @Test
    fun `a candidate whose name turns out to be another is dropped, and one the scan's end finds is still tried`() {
        link.connect(null)
        executor.runDue()
        radio.find("11:22:33:44:55:66", null)
        radio.find("11:22:33:44:55:66", "Someone's MIDI keyboard")
        executor.advance(2_000)
        assertTrue(radio.connections.isEmpty())

        executor.advance(9_500)   // 11.5 s into the scan
        radio.find(address, null)
        executor.advance(500)   // the scan ends before the candidate's second is up
        assertEquals(address, radio.connections.single().address)
        assertEquals(LinkState.Connecting, state())
    }

    @Test
    fun `with a piano remembered, a nameless device is never a candidate`() {
        link.connect(address)
        executor.runDue()
        radio.find("11:22:33:44:55:66", null)
        executor.advance(5_000)
        assertTrue(radio.connections.isEmpty())
        assertTrue(logged.contains("Seen 11:22:33:44:55:66 (no name), RSSI -60 dBm, MIDI service yes: ignored: no name"))
    }

    @Test
    fun `a piano another app on this device holds is connected to directly, without a scan`() {
        radio.connectedElsewhere = listOf(FoundPiano(address, PianoBluetooth.NAME))
        link.connect(null)
        executor.runDue()
        assertEquals(0, radio.scans)
        val gatt = radio.connections.single()
        assertEquals(address, gatt.address)
        assertFalse(gatt.autoConnect)
        finishConnecting(gatt)
        assertEquals(LinkState.Connected(PianoBluetooth.NAME, 255, epoch = 1), state())
        assertTrue(logged.contains("$address \"Steven Piano\" is connected to this device already (another app holds it): connecting to it directly"))
    }

    @Test
    fun `with a piano remembered, only it is taken from another app, never another Steven Piano`() {
        radio.connectedElsewhere = listOf(FoundPiano("D4:D4:D4:00:00:01", PianoBluetooth.NAME))
        link.connect(address)
        executor.runDue()
        assertEquals(1, radio.scans)
        assertTrue(radio.connections.isEmpty())
        link.disconnect()
        executor.runDue()

        radio.connectedElsewhere = listOf(FoundPiano(address, null))
        link.connect(address)
        executor.runDue()
        assertEquals(1, radio.scans)
        assertEquals(address, radio.connections.single().address)
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
