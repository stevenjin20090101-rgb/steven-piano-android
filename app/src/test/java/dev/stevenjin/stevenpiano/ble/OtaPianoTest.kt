// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.ble

import dev.stevenjin.stevenpiano.firmware.FirmwareKeys
import dev.stevenjin.stevenpiano.firmware.FirmwareManifest
import dev.stevenjin.stevenpiano.firmware.OtaExample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

/**
 * The model of the piano's side (the emulator's stand-in and the tests' fake) answers the worked
 * example as BLE_OTA.md › 5 says the firmware does.
 */
class OtaPianoTest {
    private val manifest = FirmwareManifest.parse(OtaExample.manifestJson())
    private val image = OtaExample.image()
    private fun begin(window: Int = 16) = OtaFrames.begin(OtaBegin(image.size.toLong(), manifest.digest(), manifest.signature(), "2.1.0", window))

    private fun List<OtaPiano.Out>.hex(): List<String> = map { it.toString() }

    @Test
    fun `the worked example goes through with the spec's answers`() {
        val piano = OtaPiano(mtu = 255, script = OtaPiano.Script(publicKey = FirmwareKeys.rfc8032Test))
        assertEquals(listOf("81 FA 00 10"), piano.control(begin()).hex())
        val acks = mutableListOf<String>()
        var seq = 0
        var at = 0
        while (at < image.size) {
            val length = minOf(250, image.size - at)
            acks += piano.data(OtaFrames.data(seq++, image, at, length)).hex()
            at += length
        }
        assertEquals(3_965, seq)
        assertEquals(248, acks.size)
        assertEquals("85 A0 0F 00 00", acks.first())
        assertEquals("85 60 13 0F 00", acks[246])
        assertEquals("85 00 20 0F 00", acks.last())
        val end = piano.control(OtaFrames.end())
        assertEquals(listOf("82", "83 DC 05", "Restart(inMs=1500)"), end.hex())
        assertEquals(listOf<String>(), piano.control(OtaFrames.abort()).hex())
    }

    @Test
    fun `a gap, a byte changed, a wrong key and an abort are answered as the firmware answers them`() {
        val gap = OtaPiano()
        gap.control(begin())
        assertEquals(listOf("E1 04"), gap.data(OtaFrames.data(1, image, 0, 250)).hex())

        val changed = OtaPiano()
        changed.control(begin(window = 32))
        val bent = image.copyOf().also { it[500_000] = (it[500_000].toInt() xor 1).toByte() }
        var seq = 0
        var at = 0
        while (at < bent.size) {
            val length = minOf(250, bent.size - at)
            changed.data(OtaFrames.data(seq++, bent, at, length))
            at += length
        }
        assertEquals(listOf("82", "E1 05"), changed.control(OtaFrames.end()).hex())

        val author = OtaPiano(script = OtaPiano.Script(publicKey = FirmwareKeys.author))
        assertEquals("a test image against the author's key", listOf("E1 06"), author.control(begin()).hex())

        val aborted = OtaPiano()
        aborted.control(begin())
        assertEquals(listOf("84"), aborted.control(OtaFrames.abort()).hex())
        assertTrue("a stale frame gets no reply", aborted.data(OtaFrames.data(0, image, 0, 250)).isEmpty())
        assertEquals("END before every byte", listOf("E1 04"), OtaPiano().apply { control(begin()) }.control(OtaFrames.end()).hex())
    }

    /** Every frame of [bytes] at 250 a frame, then END: what the piano answered END with. */
    private fun send(piano: OtaPiano, bytes: ByteArray): List<String> {
        var seq = 0
        var at = 0
        while (at < bytes.size) {
            val length = minOf(250, bytes.size - at)
            piano.data(OtaFrames.data(seq++, bytes, at, length))
            at += length
        }
        return piano.control(OtaFrames.end()).hex()
    }

    @Test
    fun `firmware 2_1_0 - the worked example has no version tag, so 82 E1 0C, tagged, 82 83 DC 05 (v1_25 M58)`() {
        val checking = OtaPiano.Script(publicKey = FirmwareKeys.rfc8032Test, runningVersion = "2.0.2")
        val untagged = OtaPiano(script = checking)
        assertEquals(listOf("81 FA 00 10"), untagged.control(begin()).hex())
        assertEquals("the hash and the signature pass; the tag is missing", listOf("82", "E1 0C"), send(untagged, image))

        val tagged = OtaExample.taggedImage()
        assertEquals("the same size, so the same frames and ACKs", image.size, tagged.size)
        assertEquals("2.1.0", OtaPiano.tagOf(tagged))
        val sha = MessageDigest.getInstance("SHA-256").digest(tagged)
        val piano = OtaPiano(script = OtaPiano.Script(runningVersion = "2.0.2"))   // the tagged twin isn't signed with the test key
        assertEquals(listOf("81 FA 00 10"), piano.control(OtaFrames.begin(OtaBegin(tagged.size.toLong(), sha, ByteArray(64), "2.1.0", 16))).hex())
        assertEquals(listOf("82", "83 DC 05", "Restart(inMs=1500)"), send(piano, tagged))

        val already = OtaPiano(script = OtaPiano.Script(runningVersion = "2.1.0"))
        assertEquals("BEGIN not newer: refused at once", listOf("E1 0C"), already.control(OtaFrames.begin(OtaBegin(tagged.size.toLong(), sha, ByteArray(64), "2.1.0", 16))).hex())
        assertEquals("a 2.0.x piano weighs no tag: § 5 goes through", listOf("82", "83 DC 05", "Restart(inMs=1500)"), send(OtaPiano().apply { control(begin()) }, image))
    }

    @Test
    fun `a piano whose lock doesn't trust the connection refuses BEGIN with ERR 11`() {
        assertEquals(listOf("E1 0B"), OtaPiano(script = OtaPiano.Script(authorised = { false })).control(begin()).hex())
        assertEquals("not authorised", OtaFrames.errorName(11))
        assertEquals("not newer", OtaFrames.errorName(12))
    }

    @Test
    fun `the version tag - the first well-formed one counts, as the firmware scans for it`() {
        fun image(vararg parts: ByteArray) = parts.fold(ByteArray(8)) { acc, part -> acc + part + ByteArray(8) }
        val marker = "SJPV1:".toByteArray()
        assertEquals("2.1.0", OtaPiano.tagOf(image(OtaPiano.tag("2.1.0"))))
        assertEquals("a decoy before it", "2.1.0", OtaPiano.tagOf(image(marker + "2.1.0".toByteArray() + 0x20, OtaPiano.tag("2.1.0"))))
        assertEquals("a marker inside another", "2.1.0", OtaPiano.tagOf(image("SJPV".toByteArray() + OtaPiano.tag("2.1.0"))))
        assertNull("17 characters is too long", OtaPiano.tagOf(image(OtaPiano.tag("1".repeat(17)))))
        assertNull("no NUL, no tag", OtaPiano.tagOf(marker + "2.1.0".toByteArray()))
        assertNull(OtaPiano.tagOf(image(marker + 0)))
        assertTrue(OtaPiano.newer("2.1.0", "2.0.2"))
        assertFalse("equal is not newer", OtaPiano.newer("2.1.0", "2.1.0"))
        assertFalse("a suffix is ignored", OtaPiano.newer("2.1.0-rc.1", "2.1.0"))
        assertFalse("unreadable is not newer", OtaPiano.newer("2.1", "2.0.0"))
    }

    @Test
    fun `its script makes it refuse, drop or fail on purpose`() {
        assertEquals(listOf("E1 01"), OtaPiano(script = OtaPiano.Script(errorAtBegin = 1)).control(begin()).hex())
        val dropping = OtaPiano(script = OtaPiano.Script(dropAfterAcks = 2))
        dropping.control(begin())
        val outs = (0 until 32).flatMap { dropping.data(OtaFrames.data(it, image, it * 250, 250)).hex() }
        assertEquals(listOf("85 A0 0F 00 00", "85 40 1F 00 00", "Drop"), outs)
        val busy = OtaPiano()
        busy.control(begin())
        assertEquals("a second BEGIN", listOf("E1 03"), busy.control(begin()).hex())
    }
}
