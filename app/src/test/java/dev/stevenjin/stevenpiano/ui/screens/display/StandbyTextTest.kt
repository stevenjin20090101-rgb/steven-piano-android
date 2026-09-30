// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.ui.screens.display

import dev.stevenjin.stevenpiano.data.db.ArtworkEntity
import dev.stevenjin.stevenpiano.data.db.ArtworkStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The lines about the piece on the resting screen (DESIGN.md › v1.7.1): whose notes, as one paragraph, and how many lines. */
class StandbyTextTest {
    private val debussy = ArtworkEntity(
        "composer:debussy",
        description = "Claude Debussy was a French composer.",
        sourceUrl = "https://en.wikipedia.org/wiki/Claude_Debussy",
        sourceTitle = "Claude Debussy",
        fetchedAt = 1,
        status = ArtworkStatus.OK,
    )
    private val clairDeLune = ArtworkEntity(
        "piece:7",
        description = "\"Clair de lune\" is the third and most famous movement of the Suite bergamasque.",
        sourceTitle = "Suite bergamasque",
        fetchedAt = 1,
        status = ArtworkStatus.OK,
    )

    @Test
    fun `the piece's own notes come first`() {
        assertEquals(clairDeLune.description, StandbyText.description(clairDeLune, debussy))
        assertEquals(clairDeLune.description, StandbyText.description(clairDeLune, null))
    }

    @Test
    fun `without notes of its own the piece takes its composer's`() {
        assertEquals(debussy.description, StandbyText.description(null, debussy))
        val lookedUp = listOf(
            clairDeLune.copy(description = null, status = ArtworkStatus.NOT_FOUND),
            clairDeLune.copy(description = null, status = ArtworkStatus.FAILED),
            clairDeLune.copy(description = "  \n "),
            clairDeLune.copy(status = ArtworkStatus.FAILED),   // a failed row's old text is not shown
        )
        for (row in lookedUp) assertEquals(debussy.description, StandbyText.description(row, debussy))
    }

    @Test
    fun `with no notes anywhere nothing is said, never a placeholder`() {
        assertNull(StandbyText.description(null, null))
        assertNull(StandbyText.description(clairDeLune.copy(status = ArtworkStatus.NOT_FOUND), debussy.copy(description = null)))
        assertNull(StandbyText.description(null, debussy.copy(description = "", status = ArtworkStatus.OK)))
    }

    @Test
    fun `a piece made in Studio shows its own line`() {
        val studio = ArtworkEntity(
            "piece:8",
            description = "Made in Studio · in the manner of Clair de lune (Claude Debussy)",
            fetchedAt = 1,
            status = ArtworkStatus.OK,
        )
        assertEquals("Made in Studio · in the manner of Clair de lune (Claude Debussy)", StandbyText.description(studio, null))
        assertEquals("Made in Studio · in the manner of Clair de lune (Claude Debussy)", StandbyText.description(studio, debussy))
    }

    @Test
    fun `Wikipedia's text carries its credit, one line, and the app's own line none`() {
        assertEquals("From Wikipedia · CC BY-SA 4.0", StandbyText.WIKIPEDIA_CREDIT)
        val withPage = clairDeLune.copy(sourceUrl = "https://en.wikipedia.org/wiki/Suite_bergamasque")
        assertEquals(StandbyText.Notes(clairDeLune.description!!, StandbyText.WIKIPEDIA_CREDIT), StandbyText.notes(withPage, debussy))
        assertEquals("a page whose address was refused keeps its title: still Wikipedia's", StandbyText.WIKIPEDIA_CREDIT, StandbyText.notes(clairDeLune, null)?.credit)
        assertEquals("the composer's blurb, when the piece has none", StandbyText.WIKIPEDIA_CREDIT, StandbyText.notes(null, debussy)?.credit)
        val studio = ArtworkEntity("piece:8", description = "Made in Studio · Sep 28, 2026", fetchedAt = 1, status = ArtworkStatus.OK)
        assertEquals(StandbyText.Notes("Made in Studio · Sep 28, 2026", credit = null), StandbyText.notes(studio, debussy))
        assertNull("no text, no credit", StandbyText.notes(null, null))
        assertNull(StandbyText.notes(clairDeLune.copy(status = ArtworkStatus.NOT_FOUND), debussy.copy(description = null)))
    }

    @Test
    fun `the notes are one paragraph, so no line is spent on a break`() {
        val two = debussy.copy(description = "  Claude Debussy was a French composer.\n\nHe is seen as the first Impressionist\tcomposer.  ")
        assertEquals("Claude Debussy was a French composer. He is seen as the first Impressionist composer.", StandbyText.description(null, two))
        assertEquals("Suite bergamasque, L. 75", StandbyText.oneParagraph("Suite bergamasque,\r\nL. 75").replace(' ', ' '))
        assertEquals("a b", StandbyText.oneParagraph("a b"))   // a no-break space stays one
    }

    @Test
    fun `six lines at most on wide frames, four on phones`() {
        assertEquals(6, StandbyText.WIDE_LINES)
        assertEquals(4, StandbyText.PHONE_LINES)
        assertEquals(6, StandbyText.maxLines(wide = true))
        assertEquals(4, StandbyText.maxLines(wide = false))
    }
}
