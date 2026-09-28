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
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The link's side of a firmware update (BLE_OTA.md › 3–6): the version read before Connected, the
 * update service, a session's frames through the one-operation queue, and the restart after OK.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GattOtaTest {
    private val executor = FakeLinkExecutor()
    private val radio = FakeRadio()
    private val logged = mutableListOf<String>()
    private var autoConnect = true
    private val link = GattPianoLink(radio, executor, { _, _ -> }, { autoConnect }, log = { logged += it })
    private val address = "C8:2E:18:00:11:22"
    private val header = OtaBegin(4_000, ByteArray(32) { it.toByte() }, ByteArray(64) { (0x80 + it).toByte() }, "2.1.0", window = 16)

    /** Connects to a piano running firmware 2.0.0 (Device Information and the update service), up to the version read. */
    private fun connectUpTo(mtu: Int? = 255, version: Boolean = true, ota: Boolean = true): FakeGatt {
        link.connect(null)
        executor.runDue()
        radio.find(address, PianoBluetooth.NAME)
        executor.runDue()
        val gatt = radio.connections.last()
        gatt.hasDeviceInformation = version
        gatt.hasOtaService = ota
        gatt.connected()
        executor.runDue()
        if (mtu != null) gatt.mtu(mtu) else executor.advance(3_000)
        executor.runDue()
        gatt.discovered()
        executor.runDue()
        return gatt
    }

    private fun connect2(mtu: Int? = 255): FakeGatt = connectUpTo(mtu).also {
        it.versionRead("2.0.0+a1b2c3d")
        executor.runDue()
    }

    /** Starts a session whose answers land in [events]; the flow's own coroutine has run once it returns. */
    private fun TestScope.session(events: MutableList<OtaEvent>, begin: OtaBegin = header): Job {
        val job = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { link.ota!!.begin(begin).collect { events += it } }
        runCurrent()
        return job
    }

    /** Completes each write as it goes out, until nothing more is written. */
    private fun drain(gatt: FakeGatt) {
        repeat(100) {
            gatt.writeDone()
            executor.runDue()
        }
    }

    @Test
    fun `firmware 2 0 0's version is read before Connected, with the update service`() {
        val gatt = connectUpTo()
        assertEquals("still setting up while the version is read", LinkState.Connecting, link.state.value)
        assertEquals(1, gatt.versionReads)
        assertNull(link.ota)
        gatt.versionRead("2.0.0+a1b2c3d\u0000")
        executor.runDue()
        assertEquals(LinkState.Connected(PianoBluetooth.NAME, 255, epoch = 1), link.state.value)
        assertEquals("2.0.0+a1b2c3d", link.firmwareVersion.value)
        assertNotNull(link.ota)
        assertEquals(250, link.ota!!.maxChunk)
        assertEquals(16, link.ota!!.window)
        assertTrue(logged.contains("Firmware \"2.0.0+a1b2c3d\", update service yes"))
    }

    @Test
    fun `older firmware has neither, and connects as before`() {
        val gatt = connectUpTo(version = false, ota = false)
        assertEquals(0, gatt.versionReads)
        assertEquals(LinkState.Connected(PianoBluetooth.NAME, 255, epoch = 1), link.state.value)
        assertNull(link.firmwareVersion.value)
        assertNull(link.ota)
        assertTrue("nothing new in the log", logged.none { it.startsWith("Firmware") })
    }

    @Test
    fun `an unanswered version read gives up after 3 s, and the MTU sets the chunk`() {
        connectUpTo(mtu = null)
        executor.advance(2_999)
        assertEquals(LinkState.Connecting, link.state.value)
        executor.advance(1)
        assertTrue(link.state.value is LinkState.Connected)
        assertNull(link.firmwareVersion.value)
        assertEquals("ATT's default MTU of 23 carries 18 bytes a frame", 18, link.ota!!.maxChunk)
        assertTrue(logged.contains("Firmware version not read: no answer within 3 s, update service yes"))
    }

    @Test
    fun `the version and the service go with the connection`() {
        val gatt = connect2()
        gatt.dropped()
        executor.runDue()
        assertNull(link.firmwareVersion.value)
        assertNull(link.ota)
    }

    @Test
    fun `a session subscribes first, writes BEGIN byte for byte, and hands on the piano's answers`() = runTest {
        val gatt = connect2()
        val events = mutableListOf<OtaEvent>()
        session(events)
        executor.runDue()
        assertEquals(listOf("ota-subscribe"), gatt.ops)
        gatt.otaSubscribeDone()
        executor.runDue()
        assertEquals(listOf("ota-subscribe", "ota-control"), gatt.ops)
        assertArrayEquals(OtaFrames.begin(header), gatt.otaControlWrites.single())
        assertEquals(118, gatt.otaControlWrites.single().size)
        assertTrue("high priority for the transfer", gatt.highPriority)

        gatt.writeDone()
        gatt.otaNotify(byteArrayOf(0x81.toByte(), 0xFA.toByte(), 0x00, 0x10))
        executor.runDue()
        runCurrent()
        assertEquals(listOf<OtaEvent>(OtaEvent.Ready(250, 16)), events)

        val ota = link.ota!!
        for (seq in 0 until 16) assertTrue(ota.write(seq, ByteArray(250) { seq.toByte() }))
        drain(gatt)
        assertEquals(16, gatt.otaDataWrites.size)
        gatt.otaDataWrites.forEachIndexed { seq, frame ->
            assertEquals(252, frame.size)
            assertEquals(seq, (frame[0].toInt() and 0xFF) or ((frame[1].toInt() and 0xFF) shl 8))
            assertEquals(seq.toByte(), frame[2])
        }
        gatt.otaNotify(byteArrayOf(0x85.toByte(), 0xA0.toByte(), 0x0F, 0x00, 0x00))
        executor.runDue()
        ota.end()
        executor.runDue()
        drain(gatt)
        assertArrayEquals(byteArrayOf(0x02), gatt.otaControlWrites.last())
        assertFalse("nothing after END", ota.write(16, ByteArray(10)))
        gatt.otaNotify(byteArrayOf(0x82.toByte()))
        gatt.otaNotify(byteArrayOf(0x83.toByte(), 0xDC.toByte(), 0x05))
        executor.runDue()
        runCurrent()
        assertEquals(
            listOf(OtaEvent.Ready(250, 16), OtaEvent.Ack(4_000), OtaEvent.Verifying, OtaEvent.Ok(1_500)),
            events,
        )
        assertTrue(logged.contains("Update: Ok(restartInMs=1500)"))
    }

    @Test
    fun `MIDI goes before the update's frames, and console lines after them`() = runTest {
        val gatt = connect2()
        gatt.hasConsole = false
        val events = mutableListOf<OtaEvent>()
        session(events)
        executor.runDue()
        gatt.otaSubscribeDone()
        executor.runDue()
        gatt.writeDone()
        executor.runDue()
        gatt.ops.clear()
        val ota = link.ota!!
        repeat(3) { ota.write(it, ByteArray(20)) }
        link.send(MidiBatch().apply { add(0xB0, 123, 0) }, dropPending = false)
        drain(gatt)
        assertEquals(listOf("midi", "ota-data", "ota-data", "ota-data"), gatt.ops)
    }

    @Test
    fun `abort drops the frames still waiting and sends ABORT, and after END nothing`() = runTest {
        val gatt = connect2()
        val events = mutableListOf<OtaEvent>()
        session(events)
        executor.runDue()
        gatt.otaSubscribeDone()
        executor.runDue()
        gatt.writeDone()
        executor.runDue()
        val ota = link.ota!!
        repeat(16) { ota.write(it, ByteArray(250)) }
        executor.runDue()   // the first frame goes; fifteen wait
        ota.abort()
        executor.runDue()
        drain(gatt)
        assertEquals("only the frame already written", 1, gatt.otaDataWrites.size)
        assertArrayEquals(byteArrayOf(0x03), gatt.otaControlWrites.last())
        gatt.otaNotify(byteArrayOf(0x84.toByte()))
        executor.runDue()
        runCurrent()
        assertEquals(OtaEvent.Aborted, events.last())
        assertFalse("the session is over", ota.write(16, ByteArray(10)))
    }

    @Test
    fun `leaving a session before END sends ABORT`() = runTest {
        val gatt = connect2()
        val events = mutableListOf<OtaEvent>()
        val job = session(events)
        executor.runDue()
        gatt.otaSubscribeDone()
        executor.runDue()
        gatt.writeDone()
        executor.runDue()
        job.cancel()
        runCurrent()
        executor.runDue()
        drain(gatt)
        assertArrayEquals(byteArrayOf(0x03), gatt.otaControlWrites.last())
        assertEquals(2, gatt.otaControlWrites.size)
    }

    @Test
    fun `a drop ends the session, and its frames never go out on the next connection`() = runTest {
        val gatt = connect2()
        val events = mutableListOf<OtaEvent>()
        session(events)
        executor.runDue()
        gatt.otaSubscribeDone()
        executor.runDue()
        gatt.writeDone()
        executor.runDue()
        repeat(16) { link.ota!!.write(it, ByteArray(250)) }
        executor.runDue()
        gatt.dropped()
        executor.runDue()
        runCurrent()
        assertTrue(events.last() is OtaEvent.Lost)
        assertEquals(1, gatt.otaDataWrites.size)
        val next = radio.connections.last()
        next.hasDeviceInformation = true
        next.hasOtaService = true
        next.connected()
        executor.runDue()
        next.mtu(255)
        executor.runDue()
        next.discovered()
        executor.runDue()
        next.versionRead("2.0.0+a1b2c3d")
        executor.runDue()
        drain(next)
        assertTrue("nothing of the old session", next.otaDataWrites.isEmpty() && next.otaControlWrites.isEmpty())
    }

    @Test
    fun `one session at a time, and a BEGIN the piano refused to take ends it`() = runTest {
        val gatt = connect2()
        val first = mutableListOf<OtaEvent>()
        val second = mutableListOf<OtaEvent>()
        session(first)
        session(second)
        runCurrent()
        assertEquals(listOf<OtaEvent>(OtaEvent.Lost("an update session is already under way")), second)
        executor.runDue()
        gatt.otaSubscribeDone()
        executor.runDue()
        gatt.writeDone(success = false)   // the ATT write of BEGIN failed
        executor.runDue()
        runCurrent()
        assertEquals(listOf<OtaEvent>(OtaEvent.Lost("the piano did not take the BEGIN frame")), first)
    }

    @Test
    fun `frames of the wrong size are refused, and an unknown answer is ignored`() = runTest {
        val gatt = connect2()
        val events = mutableListOf<OtaEvent>()
        session(events)
        executor.runDue()
        val ota = link.ota!!
        assertFalse(ota.write(0, ByteArray(0)))
        assertFalse(ota.write(0, ByteArray(251)))
        assertTrue(ota.write(0, ByteArray(250)))
        gatt.otaNotify(byteArrayOf(0x90.toByte(), 1, 2))
        gatt.otaNotify(byteArrayOf(0x85.toByte(), 1))
        executor.runDue()
        runCurrent()
        assertEquals("the unknown opcode ignored, the short ACK malformed", listOf<OtaEvent>(OtaEvent.Malformed("85 01")), events)
    }

    @Test
    fun `a busy stack is waited out, never skipped, and a stack that keeps refusing ends the session`() = runTest {
        val gatt = connect2()
        val events = mutableListOf<OtaEvent>()
        session(events)
        executor.runDue()
        gatt.otaSubscribeDone()
        executor.runDue()
        gatt.writeDone()
        executor.runDue()
        val ota = link.ota!!
        ota.write(0, ByteArray(250))
        ota.write(1, ByteArray(250))
        gatt.nextWrite = WriteResult.Busy
        executor.advance(1_000)
        assertTrue("nothing given up after a second", gatt.otaDataWrites.isEmpty() && events.isEmpty())
        gatt.nextWrite = WriteResult.Sent
        executor.advance(50)
        drain(gatt)
        assertEquals(listOf(0, 1), gatt.otaDataWrites.map { it[0].toInt() })

        ota.write(2, ByteArray(250))
        gatt.nextWrite = WriteResult.Busy
        executor.advance(5_000)
        runCurrent()
        assertEquals(OtaEvent.Lost("Bluetooth would not take the update's frames"), events.last())
    }

    @Test
    fun `an expected restart is reconnected whatever auto-connect says, and scanned for sooner`() {
        autoConnect = false
        val gatt = connect2()
        link.expectRestart(60_000)
        executor.runDue()
        gatt.dropped(status = 19)
        executor.runDue()
        assertEquals(LinkState.Reconnecting(1), link.state.value)
        assertTrue("a background connection waits for it", radio.connections.last().autoConnect)
        executor.advance(3_000)
        assertTrue("the first scan at 3 s", radio.scanning)
        radio.find(address, PianoBluetooth.NAME)
        executor.runDue()
        val back = radio.connections.last()
        back.hasDeviceInformation = true
        back.hasOtaService = true
        back.connected()
        executor.runDue()
        back.mtu(255)
        executor.runDue()
        back.discovered()
        executor.runDue()
        back.versionRead("2.1.0+a1b2c3d")
        executor.runDue()
        assertTrue(link.state.value is LinkState.Connected)
        assertEquals("2.1.0+a1b2c3d", link.firmwareVersion.value)

        // Once back, a later drop is an ordinary one: auto-connect is off, so no reconnecting.
        back.dropped()
        executor.runDue()
        assertEquals(LinkState.Disconnected, link.state.value)
    }

    @Test
    fun `with auto-connect off, a piano that doesn't come back within the window is let go`() {
        autoConnect = false
        val gatt = connect2()
        link.expectRestart(60_000)
        executor.runDue()
        gatt.dropped()
        executor.runDue()
        executor.advance(59_000)
        assertTrue(link.state.value is LinkState.Reconnecting)
        executor.advance(1_000)
        assertEquals(LinkState.Disconnected, link.state.value)
        assertTrue(logged.contains("The piano did not come back within the restart's window; auto-connect is off: not reconnecting"))
    }
}
