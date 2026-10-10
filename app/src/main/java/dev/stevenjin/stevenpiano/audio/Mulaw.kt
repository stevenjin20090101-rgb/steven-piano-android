// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.audio

import java.util.Base64

/**
 * G.711 µ-law (v1.23 — M55): the microphone's 16-bit samples as 8 bits each, so 100 ms of the room (1,600 samples) is
 * 1,600 bytes on the wire. The web panel's `system.js` decodes it with the same table. Pure.
 */
object Mulaw {
    /** The largest magnitude encoded; louder samples are clipped to it (G.711's own). */
    private const val CLIP = 32_635

    /** G.711's bias, added before the segment is found. */
    private const val BIAS = 0x84

    /** One sample (a 16-bit value) as its µ-law byte. */
    fun encode(sample: Int): Byte {
        var magnitude = sample
        val sign = if (magnitude < 0) 0x80 else 0
        if (magnitude < 0) magnitude = -magnitude
        if (magnitude > CLIP) magnitude = CLIP
        magnitude += BIAS
        var exponent = 7
        var mask = 0x4000
        while (exponent > 0 && magnitude and mask == 0) {
            exponent--
            mask = mask shr 1
        }
        val mantissa = (magnitude shr (exponent + 3)) and 0x0F
        return (sign or (exponent shl 4) or mantissa).inv().toByte()
    }

    /** A µ-law byte as its 16-bit value (the middle of the span it stands for). */
    fun decode(byte: Byte): Int {
        val bits = byte.toInt().inv() and 0xFF
        val exponent = (bits shr 4) and 0x07
        val mantissa = bits and 0x0F
        val magnitude = (((mantissa shl 3) + BIAS) shl exponent) - BIAS
        return if (bits and 0x80 != 0) -magnitude else magnitude
    }

    /** Every sample of [pcm] as µ-law. */
    fun encode(pcm: ShortArray): ByteArray = ByteArray(pcm.size) { encode(pcm[it].toInt()) }
}

/**
 * What the panel's socket carries of the microphone (v1.23 — M55): one text message a frame, `{"type":"audio","seq":n,
 * "rate":16000,"codec":"mulaw","data":"<base64>"}`, about 2.2 KB for 100 ms, far under the relay's 64 KB frame. Built
 * by hand: base64 needs no escaping. Pure.
 */
object ListenWire {
    const val RATE = 16_000
    const val CODEC = "mulaw"

    /** Frame [seq] of a hearing, its µ-law bytes [ulaw]. */
    fun audio(seq: Long, ulaw: ByteArray): String = StringBuilder(ulaw.size * 4 / 3 + 96)
        .append("{\"type\":\"audio\",\"seq\":").append(seq)
        .append(",\"rate\":").append(RATE)
        .append(",\"codec\":\"").append(CODEC)
        .append("\",\"data\":\"").append(Base64.getEncoder().encodeToString(ulaw))
        .append("\"}")
        .toString()
}
