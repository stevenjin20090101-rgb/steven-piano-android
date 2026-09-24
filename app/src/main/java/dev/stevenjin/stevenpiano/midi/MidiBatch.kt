// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.midi

/**
 * A reusable, growable run of 3-byte channel messages bound for the piano, in send order.
 * Receivers copy what they need before returning; the owner clears and refills it.
 */
class MidiBatch {
    private var packed = IntArray(32)

    var size: Int = 0
        private set

    fun isEmpty(): Boolean = size == 0

    fun add(status: Int, data1: Int, data2: Int) {
        if (size == packed.size) packed = packed.copyOf(size * 2)
        packed[size++] = pack(status, data1, data2)
    }

    /** The message as `status << 16 | data1 << 8 | data2`. */
    fun packedAt(i: Int): Int = packed[i]

    fun status(i: Int): Int = packed[i] ushr 16
    fun data1(i: Int): Int = (packed[i] ushr 8) and 0xFF
    fun data2(i: Int): Int = packed[i] and 0xFF

    fun clear() {
        size = 0
    }

    companion object {
        fun pack(status: Int, data1: Int, data2: Int): Int =
            ((status and 0xFF) shl 16) or ((data1 and 0x7F) shl 8) or (data2 and 0x7F)
    }
}
