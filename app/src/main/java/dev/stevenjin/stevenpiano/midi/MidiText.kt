// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.midi

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/**
 * Text metas carry no declared encoding: UTF-8 when the bytes are valid UTF-8, else Latin-1. Only
 * the first [maxBytes] are read (a name is a line, not a megabyte); a cut that lands inside a UTF-8
 * sequence moves back to its start, so a long name in UTF-8 still reads as UTF-8.
 */
object MidiText {
    fun decode(bytes: ByteArray, offset: Int, length: Int, maxBytes: Int = Int.MAX_VALUE): String {
        var n = length
        if (n > maxBytes) {
            n = maxBytes
            var back = 0
            while (back < MAX_CONTINUATION && n > 0 && (bytes[offset + n].toInt() and 0xC0) == 0x80) {
                n--
                back++
            }
        }
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val text = try {
            decoder.decode(ByteBuffer.wrap(bytes, offset, n)).toString()
        } catch (e: CharacterCodingException) {
            String(bytes, offset, n, Charsets.ISO_8859_1)
        }
        return text.replace("\u0000", "").trim()
    }

    /** A UTF-8 sequence has at most three bytes after its first. */
    private const val MAX_CONTINUATION = 3
}
