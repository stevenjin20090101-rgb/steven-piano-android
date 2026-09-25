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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/** The crash handler (the v1.2 audit, F3): the piano's stop goes out first, then Android's handler gets the crash. */
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
    fun `no link, or no connection, is not silenced, and the crash still goes on`() {
        CrashSilencer({ null }, previous).uncaughtException(Thread.currentThread(), crash)
        assertSame(crash, delegated)
        val link = FakePianoLink().apply { disconnect() }
        delegated = null
        CrashSilencer({ link }, previous).uncaughtException(Thread.currentThread(), crash)
        assertEquals(emptyList<Long>(), link.emergencySilences)
        assertSame(crash, delegated)
    }

    @Test
    fun `a handler whose own lookup fails still hands the crash on`() {
        CrashSilencer({ throw OutOfMemoryError() }, previous).uncaughtException(Thread.currentThread(), crash)
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
