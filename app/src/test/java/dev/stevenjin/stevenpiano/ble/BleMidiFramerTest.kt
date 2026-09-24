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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class BleMidiFramerTest {

    private fun packed(vararg messages: Int) = messages

    @Test
    fun `a timestamp byte precedes every status byte`() {
        val messages = packed(
            MidiBatch.pack(0x90, 60, 64),
            MidiBatch.pack(0x80, 60, 0),
            MidiBatch.pack(0xB0, 64, 127),
        )
        val packet = BleMidiFramer.frame(messages, 0, 3, timestampMs = 0x1ABC)
        val bytes = packet.map { it.toInt() and 0xFF }
        assertEquals(1 + 3 * 4, bytes.size)
        assertEquals(0x80 or (0x1ABC shr 7 and 0x3F), bytes[0])
        val timestamp = 0x80 or (0x1ABC and 0x7F)
        assertEquals(
            listOf(timestamp, 0x90, 60, 64, timestamp, 0x80, 60, 0, timestamp, 0xB0, 64, 127),
            bytes.drop(1),
        )
    }

    @Test
    fun `packets never exceed 20 messages or MTU minus 3 bytes`() {
        for (mtu in 23..517) {
            val capacity = BleMidiFramer.capacity(mtu)
            assertTrue(capacity in 1..BleMidiFramer.MAX_MESSAGES)
            val packet = BleMidiFramer.frame(IntArray(capacity) { MidiBatch.pack(0x90, 60, 1) }, 0, capacity, 0)
            assertTrue("MTU $mtu", packet.size <= mtu - 3)
        }
        assertEquals(4, BleMidiFramer.capacity(23))
        assertEquals(20, BleMidiFramer.capacity(255))
    }

    @Test
    fun `the piano's own parser decodes every packet exactly`() {
        val random = Random(7)
        repeat(200) {
            val batch = MidiBatch()
            repeat(random.nextInt(1, 60)) {
                when (random.nextInt(3)) {
                    0 -> batch.add(0x90, random.nextInt(24, 108), random.nextInt(1, 128))
                    1 -> batch.add(0x80, random.nextInt(24, 108), 0)
                    else -> batch.add(0xB0, if (random.nextBoolean()) 64 else 123, random.nextInt(128))
                }
            }
            val mtu = listOf(23, 27, 64, 185, 255, 517).random(random)
            val writer = PacedWriter()
            writer.enqueue(batch)
            val decoded = mutableListOf<String>()
            var now = 0L
            while (writer.pending > 0) {
                val packet = writer.nextPacket(now, mtu, timestampMs = now / 1_000_000)
                if (packet != null) {
                    assertTrue(packet.size <= mtu - 3)
                    decoded += FirmwareBleMidiParser.messages(FirmwareBleMidiParser.receive(packet))
                }
                now += 1_000_000
            }
            assertEquals(batch.hex(), decoded)
        }
    }

    @Test
    fun `the firmware port reproduces the pitfall of a status without its timestamp`() {
        val naive = byteArrayOf(0x80.toByte(), 0x80.toByte(), 0x90.toByte(), 60, 64, 0xB0.toByte(), 64, 127)
        val decoded = FirmwareBleMidiParser.messages(FirmwareBleMidiParser.receive(naive))
        assertEquals(listOf("90 3C 40", "90 40 7F"), decoded)   // pedal-down became a key strike
        assertNotEquals(listOf("90 3C 40", "B0 40 7F"), decoded)
    }
}
