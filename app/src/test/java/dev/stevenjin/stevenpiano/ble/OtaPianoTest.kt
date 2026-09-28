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
import org.junit.Assert.assertTrue
import org.junit.Test

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
