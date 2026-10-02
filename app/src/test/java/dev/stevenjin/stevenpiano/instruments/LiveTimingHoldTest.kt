// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.instruments

import dev.stevenjin.stevenpiano.piano.PianoState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The piano's timing scatter held at 0 while Live plays it (v1.11 — M29). */
class LiveTimingHoldTest {
    private val calls = mutableListOf<String>()
    private val hold = LiveTimingHold(hold = { name, value -> calls += "hold $name $value" }, release = { name, value -> calls += "release $name $value" })

    private fun piano(scatter: String?) = PianoState.Ready(if (scatter == null) emptyMap() else mapOf("humantime" to scatter), emptyMap())

    @Test
    fun `held at 0 while Live plays, and put back as it was`() {
        hold.update(liveOpen = true, piano = piano("12"))
        hold.update(liveOpen = true, piano = piano("0"))   // the piano reads back the held value: nothing more
        assertEquals(12, hold.before)
        hold.update(liveOpen = false, piano = piano("0"))
        assertEquals(listOf("hold humantime 0", "release humantime 12"), calls)
        assertNull(hold.before)
    }

    @Test
    fun `nothing is held when the piano says 0, or before it has answered, which it is held as soon as it does`() {
        hold.update(liveOpen = true, piano = piano("0"))
        hold.update(liveOpen = true, piano = piano(null))
        hold.update(liveOpen = true, piano = PianoState.Unknown)
        assertEquals(emptyList<String>(), calls)
        hold.update(liveOpen = true, piano = piano("7.0"))
        assertEquals(listOf("hold humantime 0"), calls)
    }

    @Test
    fun `a release while the piano is away waits until it answers again`() {
        hold.update(liveOpen = true, piano = piano("20"))
        hold.update(liveOpen = false, piano = PianoState.Unknown)
        assertEquals(listOf("hold humantime 0"), calls)
        hold.update(liveOpen = false, piano = piano("0"))
        assertEquals(listOf("hold humantime 0", "release humantime 20"), calls)
    }
}
