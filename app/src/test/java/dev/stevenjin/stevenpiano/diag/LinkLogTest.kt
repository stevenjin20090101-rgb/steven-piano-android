// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.diag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset

/** The link's trail for diagnostics (v1.4): the last 500 lines, stamped, oldest first. */
class LinkLogTest {
    private var now = 1_790_000_000_000L   // 2026-09-21 14:13:20 UTC
    private val log = LinkLog(clock = { now++ }, zone = ZoneOffset.UTC)

    @Test
    fun `each line carries its time`() {
        log.add("Scan started")
        assertEquals(listOf("2026-09-21 14:13:20.000 Scan started"), log.lines())
        assertEquals("2026-09-21 14:13:20.000 Scan started\n", log.text())
    }

    @Test
    fun `only the last 500 lines are kept, in order`() {
        repeat(620) { log.add("line $it") }
        val lines = log.lines()
        assertEquals(500, lines.size)
        assertTrue(lines.first().endsWith(" line 120"))
        assertTrue(lines.last().endsWith(" line 619"))
        assertEquals(listOf("line 617", "line 618", "line 619"), log.tail(3).map { it.substringAfter(".", "").drop(4) })
        assertEquals(500, LinkLog.CAPACITY)
    }

    @Test
    fun `a line stays one line and is cut at its limit`() {
        log.add("Seen AA:BB \"Steven\nPiano\"\r" + "x".repeat(1_000))
        val line = log.lines().single()
        assertTrue('\n' !in line && '\r' !in line)
        assertEquals(24 + LinkLog.MAX_LINE, line.length)
    }

    @Test
    fun `nothing yet reads as empty`() {
        assertEquals("", log.text())
        assertEquals(emptyList<String>(), log.tail(50))
    }
}
