// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.data.db

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The one-off repair of over-long library text (the v1.2 audit, F1). The SQL itself was run on SQLite with 2.json's schema. */
class TextRepairTest {
    private val log = mutableListOf<String>()

    @Test
    fun `the repair runs once, only while due, and is marked done only after it succeeds`() {
        var flag = false
        var applied = 0
        repeat(3) { TextRepair.runOnce(due = { !flag }, done = { flag = true }, log = { log += it }) { applied++ } }
        assertEquals(1, applied)
        assertTrue(flag)
    }

    @Test
    fun `a repair that fails stays due and never stops the database opening`() {
        var flag = false
        TextRepair.runOnce(due = { !flag }, done = { flag = true }, log = { log += it }) { throw IllegalStateException("disk full") }
        assertEquals(false, flag)
        assertEquals(listOf("Library text repair failed: disk full"), log)
    }

    @Test
    fun `every capped column is cut, and only rows over a cap are touched`() {
        val pieces = TextRepair.STATEMENTS[0]
        for ((column, cap) in listOf(
            "title" to 200, "composer" to 120, "collection" to 120, "sourceName" to 512, "searchText" to 400, "titleKey" to 200,
            "composerShort" to 120, "composerKey" to 120,
        )) {
            assertTrue(column, "$column = substr($column, 1, $cap)" in pieces)
            assertTrue(column, "length($column) > $cap" in pieces)
        }
        assertTrue(TextRepair.STATEMENTS.drop(1).all { it.startsWith("UPDATE OR IGNORE collections") })
    }
}
