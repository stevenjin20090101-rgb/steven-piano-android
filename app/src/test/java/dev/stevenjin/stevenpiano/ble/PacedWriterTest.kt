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
import dev.stevenjin.stevenpiano.midi.NoteRouter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.random.Random

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

    // ---- The live lane (v1.11 — M29) ----------------------------------------------------------

    private fun batch(vararg messages: Triple<Int, Int, Int>) = MidiBatch().apply { messages.forEach { (s, d1, d2) -> add(s, d1, d2) } }

    private fun on(key: Int, velocity: Int = 64) = Triple(0x90, key, velocity)

    private fun off(key: Int) = Triple(0x80, key, 0)

    private fun cc(number: Int, value: Int) = Triple(0xB0, number, value)

    /** Every message the writer still holds, in the order it sends them, as "90 3C 40". */
    private fun drainAll(writer: PacedWriter): List<String> {
        val out = mutableListOf<String>()
        var now = 0L
        while (writer.pending > 0) {
            now += 100 * ms
            val packet = writer.nextPacket(now, 255, 0) ?: continue
            for (i in 0 until (packet.size - 1) / 4) {
                out += (0..2).joinToString(" ") { "%02X".format(packet[2 + i * 4 + it].toInt() and 0xFF) }
            }
        }
        return out
    }

    @Test
    fun `a live key goes ahead of a piece's backlog`() {
        val writer = PacedWriter()
        writer.enqueue(batchOf(50))   // keys 24-73
        assertEquals(20, messagesIn(writer.nextPacket(0, 255, 0)))
        writer.enqueueLive(batch(on(100, 90)))
        assertEquals(1, writer.pendingLive)
        val next = writer.nextPacket(ms, 255, 1)!!
        assertEquals(1, messagesIn(next))
        assertEquals(listOf(0x90, 100, 90), (2..4).map { next[it].toInt() and 0xFF })
        assertEquals(30, writer.pending)
    }

    @Test
    fun `a live message for a key the backlog still holds goes right behind its last message there`() {
        val writer = PacedWriter()
        writer.enqueue(batch(on(60), on(62), off(60), on(64)))
        writer.enqueueLive(batch(off(62), on(70, 90), cc(64, 127)))
        writer.enqueue(batch(on(62), cc(64, 0)))
        writer.enqueueLive(batch(off(62), cc(64, 127)))
        assertEquals(
            listOf(
                "90 46 5A", "B0 40 7F",   // the lane: nothing of key 70 or the pedal was waiting
                "90 3C 40", "90 3E 40", "80 3E 00",   // the live Off for 62 never overtakes the piece's On for 62
                "80 3C 00", "90 40 40",
                "90 3E 40", "80 3E 00",   // the second live Off, behind the second On
                "B0 40 00", "B0 40 7F",   // the pedal: the order it was changed in
            ),
            drainAll(writer),
        )
    }

    @Test
    fun `a live message for every key goes behind the whole backlog, and nothing live overtakes one waiting`() {
        val writer = PacedWriter()
        writer.enqueue(batch(on(60), on(62)))
        writer.enqueueLive(batch(cc(123, 0)))
        writer.enqueueLive(batch(on(70)))
        assertEquals(listOf("90 3C 40", "90 3E 40", "B0 7B 00", "90 46 40"), drainAll(writer))

        writer.enqueue(batch(on(60)))
        writer.enqueue(batch(cc(64, 0), cc(123, 0)), dropPending = true)   // the stop sequence
        writer.enqueueLive(batch(on(72)))
        assertEquals("never ahead of All Notes Off", listOf("B0 40 00", "B0 7B 00", "90 48 40"), drainAll(writer))
    }

    @Test
    fun `the stop sequence clears the live lane too, and the backlog's drop never touches the lane`() {
        val writer = PacedWriter(maxBacklog = 10)
        writer.enqueue(batchOf(5))
        writer.enqueueLive(batch(on(100)))
        assertEquals(15, writer.enqueue(batchOf(10)))   // past the cap: the backlog's Note Ons go
        assertEquals(1, writer.pending)
        assertEquals(1, writer.pendingLive)
        writer.enqueueLive(batch(on(101)))
        writer.enqueue(batch(cc(64, 0), cc(123, 0)), dropPending = true)
        assertEquals(0, writer.pendingLive)
        assertEquals(listOf("B0 40 00", "B0 7B 00"), drainAll(writer))
    }

    @Test
    fun `a message's slot is its key, its controller, or every key`() {
        assertEquals(60, PacedWriter.slotOf(MidiBatch.pack(0x90, 60, 1)))
        assertEquals(60, PacedWriter.slotOf(MidiBatch.pack(0x83, 60, 0)))
        assertEquals(60, PacedWriter.slotOf(MidiBatch.pack(0xA0, 60, 9)))
        assertEquals(128 + 64, PacedWriter.slotOf(MidiBatch.pack(0xB0, 64, 127)))
        val every = PacedWriter.slotOf(MidiBatch.pack(0xB0, 123, 0))
        assertEquals(every, PacedWriter.slotOf(MidiBatch.pack(0xB0, 120, 0)))
        assertEquals(every, PacedWriter.slotOf(MidiBatch.pack(0xC0, 5, 0)))
        assertEquals(every, PacedWriter.slotOf(MidiBatch.pack(0xE0, 0, 64)))
    }

    /**
     * The lane's promise, against the router itself: a piece's notes and pedal, the screen's keys and its
     * sustain, a MIDI keyboard's keys and pedal (v1.11 — M29), now and then a stop, queued at random and drained at random (four messages a packet). Each
     * slot's messages reach the wire in the order they were queued (less the backlog's dropped Note Ons),
     * and a key the router has let go is never left down on the wire; with nothing dropped, the wire leaves
     * down exactly the keys the router holds.
     */
    @Test
    fun `a random mix of a piece and live keys keeps each key's order, and never leaves a key down the router let go`() {
        repeat(300) { seed ->
            val random = Random(seed)
            val drops = seed % 4 == 0
            val writer = PacedWriter(maxBacklog = if (drops) 24 else PacedWriter.MAX_BACKLOG)
            val router = NoteRouter()
            val waiting = HashMap<Int, ArrayDeque<Int>>()   // per slot, what the writer holds, in the order queued
            val down = BooleanArray(128)   // what the wire has left down
            var nowMicros = 0L
            val batch = MidiBatch()

            fun queue(live: Boolean, dropPending: Boolean = false) {
                if (dropPending) waiting.clear()
                for (i in 0 until batch.size) waiting.getOrPut(PacedWriter.slotOf(batch.packedAt(i))) { ArrayDeque() }.addLast(batch.packedAt(i))
                if (live) writer.enqueueLive(batch) else writer.enqueue(batch, dropPending)
            }

            fun isNoteOn(m: Int) = (m ushr 16) and 0xF0 == 0x90 && m and 0x7F != 0

            fun drain(packets: Int) {
                repeat(packets) {
                    val packet = writer.nextPacket(nowMicros * 1_000, mtu = 23, timestampMs = 0) ?: return
                    for (i in 0 until (packet.size - 1) / 4) {
                        val m = MidiBatch.pack(packet[2 + i * 4].toInt() and 0xFF, packet[3 + i * 4].toInt(), packet[4 + i * 4].toInt())
                        val expected = waiting.getValue(PacedWriter.slotOf(m))
                        if (drops) while (expected.first() != m && isNoteOn(expected.first())) expected.removeFirst()
                        assertEquals("seed $seed: a slot's messages leave in the order queued", expected.removeFirst(), m)
                        val status = (m ushr 16) and 0xF0
                        val key = (m ushr 8) and 0x7F
                        when {
                            isNoteOn(m) -> down[key] = true
                            status == 0x80 || status == 0x90 -> down[key] = false
                            status == 0xB0 && (key == 120 || key == 123) -> down.fill(false)
                        }
                    }
                }
            }

            repeat(500) {
                batch.clear()
                val key = 48 + random.nextInt(8)   // few keys, so the piece and the fingers meet on them
                when (random.nextInt(15)) {
                    0, 1 -> router.liveNoteOn(key, 90, nowMicros, batch).also { queue(live = true) }
                    2, 3 -> router.liveNoteOff(key, batch).also { queue(live = true) }
                    4 -> router.liveSustain(random.nextBoolean(), batch).also { queue(live = true) }
                    5, 6 -> router.route(0x90 or random.nextInt(2), key, 70, nowMicros, batch).also { queue(live = false) }
                    7, 8 -> router.route(0x80 or random.nextInt(2), key, 0, nowMicros, batch).also { queue(live = false) }
                    9 -> router.route(0xB0, 64, random.nextInt(128), nowMicros, batch).also { queue(live = false) }
                    // A MIDI keyboard's keys and pedal (v1.11 — M29), as live as the screen's.
                    10, 11 -> router.externalNoteOn(key, 100, nowMicros, batch).also { queue(live = true) }
                    12 -> router.externalNoteOff(key, batch).also { queue(live = true) }
                    13 -> (if (random.nextInt(4) == 0) router.silenceExternal(batch) else router.externalPedal(64, random.nextInt(128), nowMicros, batch))
                        .also { queue(live = true) }
                    else -> if (random.nextInt(8) == 0) router.silence(batch).also { queue(live = false, dropPending = true) }
                }
                nowMicros += random.nextLong(0, 60_000)
                drain(random.nextInt(3))
            }
            while (writer.pending > 0) {
                nowMicros += 20_000
                drain(8)
            }
            for (key in 0..127) {
                if (!router.isSounding(key)) assertFalse("seed $seed: key $key let go by the router is down on the wire", down[key])
                if (!drops) assertEquals("seed $seed: key $key", router.isSounding(key), down[key])
            }
        }
    }
}
