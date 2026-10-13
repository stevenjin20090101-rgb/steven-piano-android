// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano

import dev.stevenjin.stevenpiano.ble.FakePianoLink
import dev.stevenjin.stevenpiano.ble.LinkError
import dev.stevenjin.stevenpiano.ble.LinkState
import dev.stevenjin.stevenpiano.ble.LinkTrust
import dev.stevenjin.stevenpiano.ble.LockGate
import dev.stevenjin.stevenpiano.ble.Trust
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/** The crash handler (the v1.2 audit, F3): the piano's stop goes out first, then Android's handler gets the crash. */
@OptIn(ExperimentalCoroutinesApi::class)
class CrashSilencerTest {
    private var delegated: Throwable? = null
    private val previous = Thread.UncaughtExceptionHandler { _, e -> delegated = e }
    private val crash = IllegalStateException("crash")

    @Test
    fun `a crash while connected silences the piano within 200 ms, then goes on to Android's handler`() {
        val link = FakePianoLink()
        CrashSilencer({ link }, previous).uncaughtException(Thread.currentThread(), crash)
        assertEquals(listOf(200L), link.emergencySilences)
        assertSame(crash, delegated)
    }

    @Test
    fun `no link is not silenced, a link is always asked, and its own connection decides, the crash still goes on`() {
        CrashSilencer({ null }, previous).uncaughtException(Thread.currentThread(), crash)
        assertSame(crash, delegated)
        val link = FakePianoLink().apply { disconnect() }
        delegated = null
        CrashSilencer({ link }, previous).uncaughtException(Thread.currentThread(), crash)
        assertEquals("asked: a link without a connection has nothing to send (its own rule)", listOf(200L), link.emergencySilences)
        assertSame(crash, delegated)
    }

    @Test
    fun `the stop goes out while the piano's lock holds the link back, untrusted (v1_25 M58)`() = runTest {
        val link = FakePianoLink()   // connected
        val trust = MutableStateFlow(LinkTrust(connection = 0, trust = Trust.Pending))
        val gate = LockGate(link, trust, backgroundScope)
        runCurrent()
        assertEquals("the player sees it not connected yet", LinkState.Connecting, gate.state.value)
        CrashSilencer({ gate }, previous).uncaughtException(Thread.currentThread(), crash)
        assertEquals(listOf(200L), link.emergencySilences)
        trust.value = LinkTrust(connection = 0, trust = Trust.Refused(LinkError.LockedOut))
        CrashSilencer({ gate }, previous).uncaughtException(Thread.currentThread(), crash)
        assertEquals("read only, too", listOf(200L, 200L), link.emergencySilences)
        assertSame(crash, delegated)
    }

    @Test
    fun `a handler whose own lookup fails still hands the crash on`() {
        CrashSilencer({ throw OutOfMemoryError() }, previous).uncaughtException(Thread.currentThread(), crash)
        assertSame(crash, delegated)
    }

    @Test
    fun `the crash is written down after the piano is silenced, then goes on`() {
        val link = FakePianoLink()
        val order = mutableListOf<String>()
        CrashSilencer({ link.also { order += "silence" } }, previous) { thread, error ->
            assertSame(crash, error)
            assertSame(Thread.currentThread(), thread)
            assertEquals(listOf(200L), link.emergencySilences)
            order += "report"
        }.uncaughtException(Thread.currentThread(), crash)
        assertEquals(listOf("silence", "report"), order)
        assertSame(crash, delegated)
    }

    @Test
    fun `a report that fails, or a silence that fails, still hands the crash on`() {
        CrashSilencer({ FakePianoLink() }, previous) { _, _ -> throw OutOfMemoryError() }.uncaughtException(Thread.currentThread(), crash)
        assertSame(crash, delegated)
        delegated = null
        var reported = false
        CrashSilencer({ throw IllegalStateException("no link") }, previous) { _, _ -> reported = true }.uncaughtException(Thread.currentThread(), crash)
        assertEquals(true, reported)
        assertSame(crash, delegated)
    }

    @Test
    fun `install puts it in front of the handler there before`() {
        val before = Thread.getDefaultUncaughtExceptionHandler()
        try {
            Thread.setDefaultUncaughtExceptionHandler(previous)
            val link = FakePianoLink()
            CrashSilencer.install { link }
            Thread.getDefaultUncaughtExceptionHandler()!!.uncaughtException(Thread.currentThread(), crash)
            assertEquals(listOf(200L), link.emergencySilences)
            assertSame(crash, delegated)
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(before)
        }
    }
}
