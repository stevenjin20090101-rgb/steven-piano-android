// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.ui

import dev.stevenjin.stevenpiano.admin.KioskStatus
import dev.stevenjin.stevenpiano.ui.components.PinWait
import dev.stevenjin.stevenpiano.ui.screens.piano.pages.KioskPageCopy
import dev.stevenjin.stevenpiano.ui.screens.piano.pages.SET_A_PIN_FIRST
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Kiosk mode's way out and its page, in words (DESIGN.md › v1.6.1 — M20). */
class KioskExitTest {
    @Test
    fun `the byline is held three seconds, and its sheet offers both ways out unless already unlocked`() {
        assertEquals(3_000L, KIOSK_HOLD_MS)
        assertEquals(listOf("Unlock for now", "Turn kiosk off"), KioskExit.offered(unlocked = false).map { it.label })
        assertEquals(listOf("Turn kiosk off"), KioskExit.offered(unlocked = true).map { it.label })
    }

    @Test
    fun `a wait reads in seconds under a minute, then whole minutes, never less than it is`() {
        assertEquals("1 s", PinWait.text(1L))
        assertEquals("5 s", PinWait.text(5_000L))
        assertEquals("5 s", PinWait.text(4_001L))
        assertEquals("59 s", PinWait.text(59_000L))
        assertEquals("1 min", PinWait.text(60_000L))
        assertEquals("2 min", PinWait.text(80_000L))
        assertEquals("5 min", PinWait.text(300_000L))
        assertEquals("0 s", PinWait.text(-3L))
    }

    @Test
    fun `the switch says what is missing, and the page what kiosk mode does or what Android kept`() {
        val owner = KioskStatus(checked = true, owner = true)
        val notOwner = KioskStatus(checked = true)
        assertEquals(KioskPageCopy.MAKE_DEVICE_OWNER, KioskPageCopy.switchNote(on = false, notOwner, pinSet = true))
        assertNull(KioskPageCopy.explanation(on = false, notOwner, pinSet = true))
        assertEquals(SET_A_PIN_FIRST, KioskPageCopy.switchNote(on = false, owner, pinSet = false))
        assertNull(KioskPageCopy.switchNote(on = false, owner, pinSet = true))
        assertEquals("before it comes on, the way out", KioskPageCopy.WHAT_IT_DOES, KioskPageCopy.explanation(on = false, owner, pinSet = true))
        assertNull("once on, the page says no more about the way out", KioskPageCopy.explanation(on = true, owner, pinSet = true))
        assertNull(KioskPageCopy.switchNote(on = true, owner, pinSet = true))
        assertEquals(KioskPageCopy.SCREEN_LOCK_KEPT, KioskPageCopy.explanation(on = true, owner.copy(keyguardKept = true), pinSet = true))
        val refused = owner.copy(problem = "Android refused kiosk mode.")
        assertEquals("Android refused kiosk mode.", KioskPageCopy.switchNote(on = false, refused, pinSet = true))
        assertNull(KioskPageCopy.explanation(on = false, refused, pinSet = true))
    }
}
