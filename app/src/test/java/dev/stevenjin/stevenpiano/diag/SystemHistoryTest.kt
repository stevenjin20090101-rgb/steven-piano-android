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
import org.junit.Assert.assertNull
import org.junit.Test

/** The System page's day (v1.18 — M46): a ring of a minute's samples, oldest first. */
class SystemHistoryTest {
    @Test
    fun `it fills oldest first, and a day holds 1,440 minutes, the oldest going first`() {
        val history = SystemHistory()
        assertEquals(emptyList<SystemSample>(), history.snapshot())
        repeat(3) { history.add(SystemSample(at = it * SystemHistory.EVERY_MS)) }
        assertEquals(listOf(0L, 60_000L, 120_000L), history.snapshot().map { it.at })

        repeat(1_500) { history.add(SystemSample(at = 1_000L + it)) }
        val day = history.snapshot()
        assertEquals(SystemHistory.CAPACITY, day.size)
        assertEquals("the last 1,440, oldest first", (1_060L until 2_500L).toList(), day.map { it.at })

        val small = SystemHistory(capacity = 3)
        (1..7).forEach { small.add(SystemSample(at = it.toLong())) }
        assertEquals("wrapped twice, still oldest first", listOf(5L, 6L, 7L), small.snapshot().map { it.at })
    }

    @Test
    fun `a sample takes the reading's figures, and the piano's temperature only while its facts are fresh`() {
        val reading = SystemReading(
            battery = BatteryReading(percent = 76, tempC = 30.4),
            memory = MemoryReading(total = 4_000, available = 1_000),
            thermal = ThermalReading(status = 1),
        )
        val at = 10_000_000L
        assertEquals(SystemSample(at, 76, 304, 25, 1, 415), SystemHistory.sampleOf(at, reading, pianoTemp = "41.5", factsAt = at - 30_000))
        assertNull("read ten minutes ago: no reading now", SystemHistory.sampleOf(at, reading, "41.5", at - 600_000).pianoTenthsC)
        assertNull("2.0.0 reports no temperature", SystemHistory.sampleOf(at, reading, null, at).pianoTenthsC)
        assertEquals("nothing known: nothing but the time", SystemSample(at), SystemHistory.sampleOf(at, SystemReading(), "hot", at))
    }
}
