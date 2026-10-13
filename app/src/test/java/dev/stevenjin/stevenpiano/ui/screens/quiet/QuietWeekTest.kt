// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.ui.screens.quiet

import dev.stevenjin.stevenpiano.schedule.QuietBlock
import dev.stevenjin.stevenpiano.schedule.QuietSection
import dev.stevenjin.stevenpiano.schedule.QuietTimes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The quiet times' week as a calendar (v1.25 — M59): which block a piece is, and which one a press opens. */
class QuietWeekTest {
    private val school = QuietSection("School days", QuietTimes.WEEKDAYS, listOf(QuietBlock(520, 570), QuietBlock(580, 630)))
    private val night = QuietSection("Night", QuietTimes.ALL_DAYS, listOf(QuietBlock(1260, 420)))

    @Test
    fun `a piece knows its section and its whole block, even where the window cuts it`() {
        val pieces = weekPieces(listOf(school, night))
        assertEquals(
            "Sunday: Saturday's night until 7:00 (from 6:00), and its own from 21:00 (to 22:00)",
            listOf(WeekPiece(6, 360, 420, 1, "Night", 1260, 420), WeekPiece(6, 1260, 1320, 1, "Night", 1260, 420)),
            pieces.filter { it.day == 6 }.sortedBy { it.from },
        )
        assertEquals("School days' two blocks on the five weekdays", 10, pieces.count { it.section == 0 })
    }

    @Test
    fun `a press opens the block under it, else the nearest within reach, else none`() {
        val monday = weekPieces(listOf(school)).filter { it.day == 0 }   // 8:40–9:30 and 9:40–10:30
        assertEquals(520, pieceAt(monday, 545f, 48f)?.from)
        assertEquals("30 minutes above the first", 520, pieceAt(monday, 490f, 48f)?.from)
        assertEquals("between two, the nearer", 580, pieceAt(monday, 577f, 48f)?.from)
        assertNull("beyond reach", pieceAt(monday, 400f, 48f))
    }
}
