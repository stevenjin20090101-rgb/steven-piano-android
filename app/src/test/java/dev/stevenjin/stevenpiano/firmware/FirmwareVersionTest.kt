// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.firmware

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The piano's version as it reports it (BLE_OTA.md › 2), and releases compared as semver. */
class FirmwareVersionTest {
    @Test
    fun `the piano's version string reads as release and build`() {
        val v = FirmwareVersion.parse("2.0.0+a1b2c3d")!!
        assertEquals(FirmwareVersion(2, 0, 0, build = "a1b2c3d"), v)
        assertEquals("2.0.0", v.release)
        assertEquals("2.0.0 · a1b2c3d", v.shown)
        assertEquals("2.0.0 · a1b2c3d-dirty", FirmwareVersion.parse("2.0.0+a1b2c3d-dirty")!!.shown)
        assertEquals("2.1.0", FirmwareVersion.parse("2.1.0")!!.shown)
        assertEquals("padded with NULs, as a GATT value may be", v, FirmwareVersion.parse("2.0.0+a1b2c3d\u0000\u0000"))
        assertEquals(FirmwareVersion(2, 1, 0, preRelease = "rc.1", build = "nogit"), FirmwareVersion.parse(" 2.1.0-rc.1+nogit "))
    }

    @Test
    fun `anything else is no version`() {
        for (text in listOf(null, "", "emulator", "a1b2c3d", "2.0", "v2.0.0", "2.0.0+", "2.0.0-", "2.0.0 beta", "2..0", "99999999999.0.0")) {
            assertNull(text, FirmwareVersion.parse(text))
        }
    }

    @Test
    fun `releases compare by their numbers, and a pre-release sits below its release`() {
        fun v(text: String) = FirmwareVersion.parse(text)!!
        assertTrue(v("2.1.0") > v("2.0.0"))
        assertTrue(v("2.0.10") > v("2.0.9"))
        assertTrue(v("3.0.0") > v("2.99.99"))
        assertTrue(v("2.1.0") > v("2.1.0-rc.1"))
        assertTrue(v("2.1.0-rc.2") > v("2.1.0-rc.1"))
        assertTrue(v("2.1.0-rc.10") > v("2.1.0-rc.9"))
        assertTrue(v("2.1.0-beta") > v("2.1.0-alpha"))
        assertTrue(v("2.1.0-alpha.1") > v("2.1.0-alpha"))
        assertEquals("the build never counts", 0, v("2.1.0+aaaaaaa").compareTo(v("2.1.0+bbbbbbb")))
        assertTrue(v("2.1.0+aaaaaaa").sameRelease(v("2.1.0")))
    }
}
