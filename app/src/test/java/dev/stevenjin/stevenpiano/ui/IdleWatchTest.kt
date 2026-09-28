// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.ui

import dev.stevenjin.stevenpiano.settings.Appearance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Display mode's clock (DESIGN.md › v1.5 — M17): idle once no touch came for the timeout; a touch
 * remembered at most once a second while the screen is in use, and always when it ends idleness;
 * a new timeout keeps the last touch. And the appearance the theme is drawn in.
 */
class IdleWatchTest {
    @Test
    fun `idle once the timeout passes without a touch`() {
        val timer = IdleTimer(now = 1_000, timeoutMs = 60_000)
        assertFalse(timer.isIdle(60_999))
        assertEquals(1L, timer.remaining(60_999))
        assertTrue(timer.isIdle(61_000))
        assertEquals(0L, timer.remaining(90_000))
    }

    @Test
    fun `a touch is remembered at most once a second while the screen is in use`() {
        val timer = IdleTimer(now = 0, timeoutMs = 60_000)
        assertFalse(timer.touch(400))   // a finger moving: dozens of events a second
        assertFalse(timer.touch(999))
        assertEquals(0L, timer.lastTouchAt)
        assertTrue(timer.touch(1_000))
        assertEquals(1_000L, timer.lastTouchAt)
        assertFalse(timer.touch(1_500))
        assertTrue(timer.touch(2_100))
        assertEquals(62_100L, 2_100L + timer.remaining(2_100))
    }

    @Test
    fun `the first touch after idleness always counts, however soon`() {
        val timer = IdleTimer(now = 0, timeoutMs = 5_000)
        assertTrue(timer.isIdle(5_000))
        assertTrue(timer.touch(5_000))
        assertFalse(timer.isIdle(5_000))
        assertEquals(5_000L, timer.remaining(5_000))
    }

    @Test
    fun `a new timeout keeps the last touch`() {
        val timer = IdleTimer(now = 10_000, timeoutMs = 60_000).retimed(5_000)
        assertEquals(10_000L, timer.lastTouchAt)
        assertTrue(timer.isIdle(15_000))
        assertFalse(IdleTimer(now = 10_000, timeoutMs = 60_000).isIdle(15_000))
    }

    @Test
    fun `display mode waits a minute`() {
        assertEquals(60_000L, DisplayModeTimeout.DEFAULT_MS)
    }

    @Test
    fun `the app appears as the system says, or light, or dark`() {
        assertTrue(Appearance.SYSTEM.dark(systemDark = true))
        assertFalse(Appearance.SYSTEM.dark(systemDark = false))
        assertFalse(Appearance.LIGHT.dark(systemDark = true))
        assertTrue(Appearance.DARK.dark(systemDark = false))
    }
}
