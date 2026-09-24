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

/** Text metas carry no declared encoding: UTF-8 when the bytes are valid UTF-8, else Latin-1. */
object MidiText {
    fun decode(bytes: ByteArray, offset: Int, length: Int): String {
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val text = try {
            decoder.decode(ByteBuffer.wrap(bytes, offset, length)).toString()
        } catch (e: CharacterCodingException) {
            String(bytes, offset, length, Charsets.ISO_8859_1)
        }
        return text.replace("\u0000", "").trim()
    }
}
