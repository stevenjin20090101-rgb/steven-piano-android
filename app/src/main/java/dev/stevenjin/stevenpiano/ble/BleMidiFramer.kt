// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.ble

/**
 * BLE-MIDI packets the piano's parser (BLEMIDI_Transport::receive) reads correctly:
 * a header byte, then a timestamp byte before EVERY status byte, and never running status,
 * so every packet stands alone. `ts st d1 d2 st d1 d2` would make the parser read the second
 * status as a timestamp and replay the first status over the second message's data.
 */
object BleMidiFramer {
    /** The piano's 64-byte receive queue holds about 21 three-byte messages. */
    const val MAX_MESSAGES = 20
    private const val ATT_HEADER = 3
    private const val BYTES_PER_MESSAGE = 4   // timestamp, status, two data bytes

    /** Messages per packet at this MTU: the payload is at most MTU - 3 bytes, header included. */
    fun capacity(mtu: Int): Int = ((mtu - ATT_HEADER - 1) / BYTES_PER_MESSAGE).coerceIn(1, MAX_MESSAGES)

    /**
     * Frames [count] packed messages (`status << 16 | data1 << 8 | data2`) from [messages],
     * starting at [offset], stamped with the low 13 bits of [timestampMs]. The piano ignores
     * timestamps; they are only there to keep the format valid.
     */
    fun frame(messages: IntArray, offset: Int, count: Int, timestampMs: Long): ByteArray {
        require(count in 1..MAX_MESSAGES) { "A packet carries 1 to $MAX_MESSAGES messages, not $count" }
        val time = (timestampMs and 0x1FFF).toInt()
        val timestamp = (0x80 or (time and 0x7F)).toByte()
        val packet = ByteArray(1 + count * BYTES_PER_MESSAGE)
        packet[0] = (0x80 or (time ushr 7)).toByte()
        for (i in 0 until count) {
            val message = messages[offset + i]
            val at = 1 + i * BYTES_PER_MESSAGE
            packet[at] = timestamp
            packet[at + 1] = (message ushr 16).toByte()
            packet[at + 2] = ((message ushr 8) and 0x7F).toByte()
            packet[at + 3] = (message and 0x7F).toByte()
        }
        return packet
    }
}
