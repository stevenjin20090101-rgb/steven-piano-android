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
 * A line-by-line port of `BLEMIDI_Transport::receive()` (BLE-MIDI 2.2, RUNNING_ENABLE defined),
 * the packet parser running on the piano, so tests can check what the piano would decode.
 * Only the bounds check of the data-byte scan is reordered, to avoid reading past the end.
 */
object FirmwareBleMidiParser {
    private const val MIDI_TYPE = 0x80
    private const val INVALID_TYPE = 0x00
    private const val SYSTEM_EXCLUSIVE = 0xF0

    /** The bytes the library hands to the MIDI parser for one packet. */
    fun receive(packet: ByteArray): List<Int> {
        val buffer = IntArray(packet.size) { packet[it].toInt() and 0xFF }
        val length = buffer.size
        val out = mutableListOf<Int>()
        var lPtr = 0
        var rPtr: Int
        var lastStatus: Int
        var previousStatus = INVALID_TYPE
        lPtr++   // header byte
        val timestampByte = buffer[lPtr++]
        var sysExContinuation = false
        var runningStatusContinuation = false
        if (timestampByte < MIDI_TYPE) {
            sysExContinuation = true
            lPtr--
        }
        while (true) {
            lastStatus = buffer[lPtr]
            if (previousStatus == INVALID_TYPE) {
                if (lastStatus < MIDI_TYPE && !sysExContinuation) return out
            } else if (lastStatus < MIDI_TYPE) {
                lastStatus = previousStatus
                runningStatusContinuation = true
            }
            rPtr = lPtr
            while (rPtr < length - 1 && buffer[rPtr + 1] < MIDI_TYPE) rPtr++
            if (!runningStatusContinuation) {
                val midiType = if (sysExContinuation) SYSTEM_EXCLUSIVE else lastStatus and 0xF0
                when (midiType) {
                    0x80, 0x90, 0xA0, 0xB0, 0xE0 -> {
                        out += lastStatus
                        var i = lPtr
                        while (i < rPtr) {
                            out += buffer[i + 1]
                            out += buffer[i + 2]
                            i += 2
                        }
                    }
                    0xC0, 0xD0 -> {
                        out += lastStatus
                        for (i in lPtr until rPtr) out += buffer[i + 1]
                    }
                    SYSTEM_EXCLUSIVE -> {
                        out += lastStatus
                        for (i in lPtr until rPtr) out += buffer[i + 1]
                    }
                }
            } else {
                out += lastStatus
                for (i in lPtr..rPtr) out += buffer[i]
                runningStatusContinuation = false
            }
            if (++rPtr >= length) return out
            if (lastStatus < SYSTEM_EXCLUSIVE) previousStatus = lastStatus
            rPtr++   // the timestamp byte before the next status
            lPtr = rPtr
            if (lPtr >= length) return out
        }
    }

    /** Splits a MIDI byte stream (running status allowed) into 3-byte messages, as hex. */
    fun messages(stream: List<Int>): List<String> {
        val result = mutableListOf<String>()
        var status = 0
        var i = 0
        while (i < stream.size) {
            if (stream[i] >= MIDI_TYPE) status = stream[i++]
            result += "%02X %02X %02X".format(status, stream[i], stream[i + 1])
            i += 2
        }
        return result
    }
}
