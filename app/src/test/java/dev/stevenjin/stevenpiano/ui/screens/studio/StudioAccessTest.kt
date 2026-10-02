// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.ui.screens.studio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Studio tab in kiosk mode (v1.12 — M30): typing ideas is free (Steven's choice), what changes the library or
 * the tablet still asks for the PIN, and the guards hold: three ideas waiting at most, unused words as a count, the
 * idea's own words hidden in the history while the settings are locked.
 */
class StudioAccessTest {
    @Test
    fun `typing, sending, another like it, listening and cancelling are free in kiosk mode`() {
        for (action in listOf(StudioAction.Type, StudioAction.Send, StudioAction.Again, StudioAction.Listen, StudioAction.Cancel)) {
            assertFalse(action.name, StudioAccess.needsPin(action, locked = true))
        }
    }

    @Test
    fun `attach, models, keep, discard and deleting a turn ask for the PIN while locked, and nothing does when not`() {
        for (action in listOf(StudioAction.Attach, StudioAction.Models, StudioAction.Keep, StudioAction.Discard, StudioAction.DeleteTurn)) {
            assertTrue(action.name, StudioAccess.needsPin(action, locked = true))
        }
        assertTrue(StudioAction.entries.none { StudioAccess.needsPin(it, locked = false) })
    }

    @Test
    fun `at most three ideas wait, and an empty box sends nothing`() {
        assertTrue(StudioAccess.canSend("calm", waiting = 2))
        assertFalse(StudioAccess.canSend("calm", waiting = 3))
        assertFalse(StudioAccess.canSend("   ", waiting = 0))
        assertTrue(StudioAccess.canAgain(2))
        assertFalse(StudioAccess.canAgain(3))
    }

    @Test
    fun `while locked the history hides the idea's words and counts the unused ones`() {
        assertFalse(StudioAccess.showsTypedText(locked = true))
        assertTrue(StudioAccess.showsTypedText(locked = false))
        assertEquals("Not used: 2 words", StudioAccess.unusedLine(listOf("unicorn", "rainbow"), locked = true))
        assertEquals("Not used: 1 word", StudioAccess.unusedLine(listOf("unicorn"), locked = true))
        assertEquals("Not used: unicorn, rainbow", StudioAccess.unusedLine(listOf("unicorn", "rainbow"), locked = false))
        assertNull(StudioAccess.unusedLine(emptyList(), locked = true))
    }
}
