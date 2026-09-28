// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.firmware

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/**
 * The emulator's fake firmware update (`debug.stevenpiano.fakeota`): each scenario's release reads
 * and verifies against RFC 8032's test key and never the author's, and the fake server serves the
 * worked example's image.
 */
class FakeOtaTest {
    @Test
    fun `scenarios are named by the property's value, and nothing else is one`() {
        assertEquals(FakeOta.Happy, FakeOta.named("happy"))
        assertEquals(FakeOta.NotQuiet, FakeOta.named(" err1 "))
        assertEquals(FakeOta.Old, FakeOta.named("old"))
        for (value in listOf(null, "", "HAPPY", "none", "1")) assertNull(value, FakeOta.named(value))
        assertEquals(FakeOta.entries.size, FakeOta.entries.map { it.key }.toSet().size)
    }

    @Test
    fun `every scenario's release reads, and only the test key trusts it`() {
        for (scenario in FakeOta.entries) {
            val manifest = FirmwareManifest.parse(scenario.manifestJson())
            assertTrue(scenario.key, manifest.verify(FirmwareKeys.rfc8032Test))
            assertFalse(scenario.key, manifest.verify(FirmwareKeys.author))
        }
        assertTrue(FirmwareManifest.parse(FakeOta.UsbOnly.manifestJson()).usbOnly)
        assertTrue(FirmwareManifest.parse(FakeOta.NewerApp.manifestJson()).needsNewerApp(appVersionCode = 13))
        assertFalse("any build takes the other scenarios' release", FirmwareManifest.parse(FakeOta.Happy.manifestJson()).needsNewerApp(appVersionCode = 1))
        assertNull("firmware older than 2.0.0 reports no version", FakeOta.Old.running)
        assertEquals("2.1.0+a1b2c3d", FakeOta.UpToDate.running)
        assertEquals(OtaExample.RUNNING to "none", FakeOta.Rollback.afterRestart)
        assertNull(FakeOta.NoComeBack.afterRestart)
    }

    @Test
    fun `the fake server serves the scenario's manifest and the worked example's image`() = runTest {
        val server = FakeFirmwareServer { FakeOta.Happy }
        assertEquals(FakeOta.Happy.manifestJson(), server.manifest())
        val out = ByteArrayOutputStream()
        server.download(OtaExample.BIN_URL, OtaExample.SIZE.toLong()) { buffer, n -> out.write(buffer, 0, n) }
        assertArrayEquals(OtaExample.image(), out.toByteArray())
    }
}
