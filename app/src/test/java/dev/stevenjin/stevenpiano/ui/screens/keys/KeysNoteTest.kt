// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.ui.screens.keys

import dev.stevenjin.stevenpiano.instruments.LiveState
import dev.stevenjin.stevenpiano.instruments.LiveTrip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The Keys tab's one line under the pills (v1.11 — M29). */
class KeysNoteTest {
    @Test
    fun `why Live is off comes first, then the 2 s rule while Live plays Steven Piano`() {
        assertEquals(
            "Live turned off: the keyboard sent more than 200 notes in a second. Turn it on to play again.",
            keysNote(LiveState(tripped = LiveTrip.TooManyNotes), keyboardConnected = true, steven = true),
        )
        assertEquals(
            "Live turned off: the keyboard held 32 keys at once. Turn it on to play again.",
            keysNote(LiveState(tripped = LiveTrip.TooManyKeys), keyboardConnected = false, steven = false),
        )
        assertEquals(
            "Live stays off: this keyboard is the instrument too, so it already plays its own keys.",
            keysNote(LiveState(wanted = true, looped = true), keyboardConnected = true, steven = false),
        )
        assertEquals(
            "The piano lets a held key go after 2 seconds to keep its coils cool.",
            keysNote(LiveState(wanted = true, open = true), keyboardConnected = true, steven = true),
        )
        assertNull("the tablet's own sound has no coils", keysNote(LiveState(wanted = true, open = true), keyboardConnected = true, steven = false))
        assertNull(keysNote(LiveState(wanted = true), keyboardConnected = true, steven = true))
    }
}
