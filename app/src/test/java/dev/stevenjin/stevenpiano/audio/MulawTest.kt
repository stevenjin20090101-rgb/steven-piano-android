// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.audio

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64
import kotlin.math.abs

/** The microphone's frames on the wire (v1.23 — M55): G.711 µ-law both ways, and the socket's audio message. */
class MulawTest {
    @Test
    fun `a sample comes back within half a step of its segment, and every byte decodes back to itself`() {
        for (sample in -32_768..32_767) {
            val clipped = sample.coerceIn(-32_635, 32_635)
            val back = Mulaw.decode(Mulaw.encode(sample))
            assertTrue("$sample came back as $back", abs(back - clipped) <= (abs(clipped) + 132) / 32)
        }
        for (b in 0..255) {
            val value = Mulaw.decode(b.toByte())
            assertEquals("byte $b", value, Mulaw.decode(Mulaw.encode(value)))
        }
        // G.711's own figures: silence is 0xFF, the loudest are 0x80 and 0x00 at ±32,124.
        assertEquals(0xFF.toByte(), Mulaw.encode(0))
        assertEquals(0x80.toByte(), Mulaw.encode(32_767))
        assertEquals(0x00.toByte(), Mulaw.encode(-32_768))
        assertEquals(32_124, Mulaw.decode(0x80.toByte()))
        assertEquals(-32_124, Mulaw.decode(0x00.toByte()))
    }

    @Test
    fun `100 ms of the room is one small socket message the panel decodes`() {
        val pcm = ShortArray(Microphone.FRAME) { (it * 37 % 20_000 - 10_000).toShort() }
        val ulaw = Mulaw.encode(pcm)
        val text = ListenWire.audio(12, ulaw)
        val json = JSONObject(text)
        assertEquals(setOf("type", "seq", "rate", "codec", "data"), json.keys().asSequence().toSet())
        assertEquals("audio", json.getString("type"))
        assertEquals(12, json.getLong("seq"))
        assertEquals(16_000, json.getInt("rate"))
        assertEquals("mulaw", json.getString("codec"))
        assertArrayEquals(ulaw, Base64.getDecoder().decode(json.getString("data")))
        assertEquals(1_600, ulaw.size)
        assertTrue("well under the relay's 64 KB frame: ${text.length}", text.length < 2_300)
    }
}
