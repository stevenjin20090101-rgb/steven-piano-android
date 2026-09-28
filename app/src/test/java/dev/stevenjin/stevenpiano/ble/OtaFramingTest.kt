// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.ble

import dev.stevenjin.stevenpiano.firmware.FirmwareManifest
import dev.stevenjin.stevenpiano.firmware.OtaExample
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/** The update's frames against BLE_OTA.md › 5's worked example, byte for byte. */
class OtaFramingTest {
    private val manifest = FirmwareManifest.parse(OtaExample.manifestJson())
    private val begin = OtaBegin(OtaExample.SIZE.toLong(), manifest.digest(), manifest.signature(), OtaExample.VERSION, OtaFrames.WINDOW)

    /** § 5's BEGIN dump, 118 bytes. */
    private val beginHex = """
        01 00 20 0F 00 6D 87 2B 96 45 D7 19 95 87 1A 34
        6D 90 6E 64 C1 B4 EA 4D 92 DD 7F 88 CC CE A7 5F
        27 92 B2 12 C6 27 78 01 1C 44 97 C2 CC 29 C4 08
        5B 46 68 99 0D 2E 58 F1 86 CB 93 5E B3 C6 04 41
        26 F0 9A 79 93 BB 38 EC 4F 3C 63 E6 06 3B 81 92
        88 74 BF 7C AD 29 2F F0 F4 F7 93 0C 93 41 8E 87
        B8 CA 2D E6 0A 32 2E 31 2E 30 00 00 00 00 00 00
        00 00 00 00 00 10
    """

    @Test
    fun `BEGIN is the spec's 118 bytes`() {
        val frame = OtaFrames.begin(begin)
        assertEquals(118, frame.size)
        assertArrayEquals(bytes(beginHex), frame)
    }

    @Test
    fun `END, ABORT and the Data frames are the spec's`() {
        assertArrayEquals(bytes("02"), OtaFrames.end())
        assertArrayEquals(bytes("03"), OtaFrames.abort())
        val image = OtaExample.image()
        val first = OtaFrames.data(0, image, 0, 250)
        assertEquals(252, first.size)
        assertArrayEquals(bytes("00 00 E9 F0 F7 FE 05 0C"), first.copyOf(8))
        assertArrayEquals(bytes("B1 B8"), first.copyOfRange(250, 252))
        assertArrayEquals(bytes("01 00 BF C6"), OtaFrames.data(1, image, 250, 250).copyOf(4))
        val last = OtaFrames.data(3964, image, 3964 * 250, image.size - 3964 * 250)
        assertEquals(234, last.size)
        assertArrayEquals(bytes("7C 0F 91 98 9F A6 AD B4"), last.copyOf(8))
        assertArrayEquals(bytes("DB E2"), last.copyOfRange(232, 234))
        assertArrayEquals("the sequence number wraps at 65,536", bytes("01 00 AA"), OtaFrames.data(65_537, byteArrayOf(0xAA.toByte())))
    }

    @Test
    fun `the image is 3,965 frames of 250 bytes at MTU 255, the last carrying 232`() {
        assertEquals(250, OtaFrames.maxChunk(255))
        assertEquals("never more than the piano's 250", 250, OtaFrames.maxChunk(517))
        assertEquals(18, OtaFrames.maxChunk(23))
        val frames = (OtaExample.SIZE + 249) / 250
        assertEquals(3_965, frames)
        assertEquals(247, frames / 16)
        assertEquals(13, frames % 16)
        assertEquals(232, OtaExample.SIZE - 3_964 * 250)
        assertEquals(OtaExample.SHA256, MessageDigest.getInstance("SHA-256").digest(OtaExample.image()).joinToString("") { "%02x".format(it) })
    }

    @Test
    fun `the piano's answers read as the spec writes them`() {
        assertEquals(OtaEvent.Ready(250, 16), OtaFrames.parse(bytes("81 FA 00 10")))
        assertEquals(OtaEvent.Ack(4_000), OtaFrames.parse(bytes("85 A0 0F 00 00")))
        assertEquals(OtaEvent.Ack(988_000), OtaFrames.parse(bytes("85 60 13 0F 00")))
        assertEquals(OtaEvent.Ack(991_232), OtaFrames.parse(bytes("85 00 20 0F 00")))
        assertEquals(OtaEvent.Ack(0xFFFF_FFFFL), OtaFrames.parse(bytes("85 FF FF FF FF")))
        assertEquals(OtaEvent.Verifying, OtaFrames.parse(bytes("82")))
        assertEquals(OtaEvent.Ok(1_500), OtaFrames.parse(bytes("83 DC 05")))
        assertEquals(OtaEvent.Aborted, OtaFrames.parse(bytes("84")))
        assertEquals(OtaEvent.Error(5), OtaFrames.parse(bytes("E1 05")))
        assertEquals(OtaEvent.Error(6), OtaFrames.parse(bytes("E1 06")))
        assertEquals("hash mismatch", OtaFrames.errorName(5))
        assertEquals("refused by safety", OtaFrames.errorName(10))
    }

    @Test
    fun `a known answer of the wrong length is malformed, an unknown one is ignored`() {
        assertEquals(OtaEvent.Malformed("81 FA 00"), OtaFrames.parse(bytes("81 FA 00")))
        assertEquals(OtaEvent.Malformed("85 A0 0F 00 00 00"), OtaFrames.parse(bytes("85 A0 0F 00 00 00")))
        assertEquals(OtaEvent.Malformed("82 00"), OtaFrames.parse(bytes("82 00")))
        assertEquals(OtaEvent.Malformed("E1"), OtaFrames.parse(bytes("E1")))
        assertNull(OtaFrames.parse(bytes("86 01")))
        assertNull(OtaFrames.parse(ByteArray(0)))
    }

    @Test
    fun `BEGIN's fields are checked as the piano checks them`() {
        val sha = ByteArray(32)
        val sig = ByteArray(64)
        fun refused(why: String, make: () -> Unit) {
            try {
                make()
                fail("accepted: $why")
            } catch (e: IllegalArgumentException) {
                // expected
            }
        }
        refused("size 0") { OtaBegin(0, sha, sig, "2.1.0", 16) }
        refused("a short hash") { OtaBegin(1, ByteArray(31), sig, "2.1.0", 16) }
        refused("a long signature") { OtaBegin(1, sha, ByteArray(65), "2.1.0", 16) }
        refused("no version") { OtaBegin(1, sha, sig, "", 16) }
        refused("17 bytes") { OtaBegin(1, sha, sig, "2.1.0-abcdefghijk", 16) }
        refused("a space") { OtaBegin(1, sha, sig, "2.1.0 rc", 16) }
        refused("window 0") { OtaBegin(1, sha, sig, "2.1.0", 0) }
        refused("window 33") { OtaBegin(1, sha, sig, "2.1.0", 33) }
        OtaBegin(0xFFFF_FFFFL, sha, sig, "2.1.0-rc.1+abcd", 32)   // the limits themselves are fine
    }

    @Test
    fun `the spec's own dump, when the firmware folder is beside, matches too`() {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        var spec: File? = null
        while (dir != null && spec == null) {
            spec = File(dir, "firmware/docs/BLE_OTA.md").takeIf { it.isFile }
            dir = dir.parentFile
        }
        assumeTrue("firmware/docs/BLE_OTA.md is not beside android/", spec != null)
        val block = spec!!.readText().substringAfter("BEGIN, 118 B:").substringBefore("READY")
        val dumped = block.lines().drop(1)
            .map { it.substringAfter(':', "").trim() }
            .filter { it.isNotEmpty() }
            .joinToString(" ")
        assertArrayEquals(bytes(dumped), OtaFrames.begin(begin))
    }

    private fun bytes(hex: String): ByteArray =
        hex.split(Regex("\\s+")).filter { it.isNotEmpty() }.map { it.toInt(16).toByte() }.toByteArray()
}
