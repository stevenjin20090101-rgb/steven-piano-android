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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PacedWriterTest {
    private val ms = 1_000_000L

    private fun batchOf(count: Int) = MidiBatch().apply { repeat(count) { add(0x90, 24 + it % 84, 64) } }

    private fun messagesIn(packet: ByteArray?) = packet?.let { (it.size - 1) / 4 } ?: 0

    @Test
    fun `a burst of 20, then one message per millisecond`() {
        val writer = PacedWriter()
        writer.enqueue(batchOf(50))
        assertEquals(20, messagesIn(writer.nextPacket(0, mtu = 255, timestampMs = 0)))
        assertNull(writer.nextPacket(0, mtu = 255, timestampMs = 0))
        assertEquals(ms, writer.nanosUntilReady(0))
        assertEquals(ms / 2, writer.nanosUntilReady(ms / 2))
        assertEquals(1, messagesIn(writer.nextPacket(ms, 255, 1)))
        assertNull(writer.nextPacket(ms, 255, 1))
        assertEquals(5, messagesIn(writer.nextPacket(6 * ms, 255, 6)))
        assertEquals(24, writer.pending)
        assertEquals(20, messagesIn(writer.nextPacket(100 * ms, 255, 100)))   // refill caps at the burst
        assertEquals(4, messagesIn(writer.nextPacket(104 * ms, 255, 104)))
        assertEquals(0, writer.pending)
        assertEquals(Long.MAX_VALUE, writer.nanosUntilReady(200 * ms))
    }

    @Test
    fun `a small MTU splits a burst into packets of four`() {
        val writer = PacedWriter()
        writer.enqueue(batchOf(10))
        assertEquals(4, messagesIn(writer.nextPacket(0, mtu = 23, timestampMs = 0)))
        assertEquals(4, messagesIn(writer.nextPacket(0, mtu = 23, timestampMs = 0)))
        assertEquals(2, messagesIn(writer.nextPacket(0, mtu = 23, timestampMs = 0)))
    }

    @Test
    fun `the stop sequence replaces whatever is still queued`() {
        val writer = PacedWriter()
        writer.enqueue(batchOf(300))
        writer.enqueue(MidiBatch().apply { add(0xB0, 64, 0); add(0xB0, 123, 0) }, dropPending = true)
        assertEquals(2, writer.pending)
        val packet = writer.nextPacket(0, 255, 0)!!.map { it.toInt() and 0xFF }
        assertEquals(listOf(0xB0, 64, 0, 0xB0, 123, 0), listOf(packet[2], packet[3], packet[4], packet[6], packet[7], packet[8]))
    }

    @Test
    fun `past 2,000 waiting messages the Note Ons are dropped, releases and the pedal kept in order`() {
        val writer = PacedWriter()
        val notes = MidiBatch().apply {
            repeat(1_000) { i ->
                add(0x90, 24 + i % 84, 64)
                add(0x80, 24 + i % 84, 0)
            }
        }
        assertEquals(0, writer.enqueue(notes))   // 2,000 waiting: at the cap, not past it
        assertEquals(2_000, writer.pending)
        val more = MidiBatch().apply {
            add(0xB0, 64, 127)
            add(0x90, 60, 64)
            add(0x90, 62, 0)   // a Note On at velocity 0 is a release: kept
        }
        assertEquals(1_001, writer.enqueue(more))   // past the cap: every waiting Note On goes, the new one too
        assertEquals(1_002, writer.pending)
        val kept = mutableListOf<Int>()
        var now = 0L
        while (writer.pending > 0) {
            now += 20 * ms
            val packet = writer.nextPacket(now, 255, 0) ?: continue
            for (i in 0 until (packet.size - 1) / 4) kept += packet[2 + i * 4].toInt() and 0xF0
        }
        assertEquals(List(1_000) { 0x80 } + 0xB0 + 0x90, kept)
    }

    @Test
    fun `order survives the queue growing and wrapping`() {
        val writer = PacedWriter(burst = 7)
        val sent = MidiBatch()
        val received = mutableListOf<Int>()
        var now = 0L
        repeat(40) { round ->
            val batch = MidiBatch().apply { repeat(round % 23) { add(0x90, (round * 7 + it) % 128, 1) } }
            for (i in 0 until batch.size) sent.add(batch.status(i), batch.data1(i), batch.data2(i))
            writer.enqueue(batch)
            now += 3 * ms
            writer.nextPacket(now, 255, 0)?.let { packet ->
                for (i in 0 until (packet.size - 1) / 4) received += packet[2 + i * 4 + 1].toInt()
            }
        }
        while (writer.pending > 0) {
            now += ms
            writer.nextPacket(now, 255, 0)?.let { packet ->
                for (i in 0 until (packet.size - 1) / 4) received += packet[2 + i * 4 + 1].toInt()
            }
        }
        assertEquals((0 until sent.size).map { sent.data1(it) }, received)
    }
}
