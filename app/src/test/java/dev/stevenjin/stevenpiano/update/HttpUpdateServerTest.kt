// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.update

import dev.stevenjin.stevenpiano.net.FakeTransport
import dev.stevenjin.stevenpiano.net.RefusedRequestException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException

/** The two requests over HTTP (v1.4): the manifest's address and cap, a release's redirect, and what a failure is. */
class HttpUpdateServerTest {
    private val transport = FakeTransport()
    private val server = HttpUpdateServer(UpdateSource.production, transport, Dispatchers.Unconfined)
    private val asset = "https://release-assets.githubusercontent.com/github-production-release-asset/1?sp=r&sig=x"

    @Test
    fun `the manifest comes from the repository, as text`() = runBlocking {
        transport.ok(UpdateSource.MANIFEST_URL, Manifests.json().toByteArray())
        assertEquals(Manifests.json(), server.manifest())
        assertEquals("application/json", transport.opened.single().second.headers["Accept"])
    }

    @Test
    fun `a private repository's 404, and a manifest over 64 KB, are failures`() = runBlocking {
        failsWithIo { server.manifest() }
        transport.ok(UpdateSource.MANIFEST_URL, ByteArray(64 * 1024 + 1))
        failsWithIo { server.manifest() }
        transport.ok(UpdateSource.MANIFEST_URL, ByteArray(10), declared = 64L * 1024 + 1)
        failsWithIo { server.manifest() }
    }

    @Test
    fun `a release's file follows the redirect and arrives whole`() = runBlocking {
        val bytes = ByteArray(200_000) { it.toByte() }
        transport.redirect(Manifests.APK, asset).ok(asset, bytes)
        val out = ByteArrayOutputStream()
        server.download(Manifests.APK, 1_000_000) { buffer, n -> out.write(buffer, 0, n) }
        assertArrayEquals(bytes, out.toByteArray())
    }

    @Test
    fun `a file declared past the cap is refused before it is read`() = runBlocking {
        transport.redirect(Manifests.APK, asset).ok(asset, ByteArray(10), declared = 2_000_000)
        try {
            server.download(Manifests.APK, 1_000_000) { _, _ -> fail("read") }
            fail()
        } catch (e: UpdateFailure) {
            assertEquals(UpdateFailures.MISMATCH, e.message)
        }
    }

    @Test
    fun `a redirect off GitHub is refused`() = runBlocking {
        transport.redirect(Manifests.APK, "https://evil.example/steven-piano-1.4.apk")
        try {
            server.download(Manifests.APK, 1_000_000) { _, _ -> fail("read") }
            fail()
        } catch (e: RefusedRequestException) {
            assertEquals(1, transport.opened.size)
        }
    }

    private suspend fun failsWithIo(block: suspend () -> Unit) {
        try {
            block()
            fail("no failure")
        } catch (e: IOException) {
            // as expected
        }
    }
}
