// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.ui.screens.piece

import dev.stevenjin.stevenpiano.data.db.ArtworkEntity
import dev.stevenjin.stevenpiano.data.db.ArtworkStatus
import org.junit.Assert.assertEquals
import org.junit.Test

/** What the piece sheet says, including with automatic fetching off (the v1.2 audit, F17). */
class PieceNotesChoiceTest {
    private val bach = "https://en.wikipedia.org/wiki/Johann_Sebastian_Bach"
    private val composer = ArtworkEntity("composer:bach", description = "Johann Sebastian Bach was a German composer.", sourceUrl = bach, fetchedAt = 1, status = ArtworkStatus.OK)
    private val piece = ArtworkEntity("piece:7", description = "The Air is a movement.", sourceUrl = null, fetchedAt = 1, status = ArtworkStatus.OK)

    @Test
    fun `with fetching on, the sheet waits for the piece, then falls back to the composer`() {
        assertEquals(PieceNotesChoice.Waiting, PieceNotesChoice.of(null, composer, online = true, waiting = true))
        assertEquals(PieceNotesChoice.Text(composer.description!!, bach), PieceNotesChoice.of(null, composer, online = true, waiting = false))
        assertEquals(PieceNotesChoice.Text(piece.description!!, null), PieceNotesChoice.of(piece, composer, online = true, waiting = true))
    }

    @Test
    fun `with fetching off, nothing is asked for, and a Fetch notes button shows under the composer's text when kept`() {
        assertEquals(
            PieceNotesChoice.Ask(PieceNotesChoice.Text(composer.description!!, bach)),
            PieceNotesChoice.of(null, composer, online = true, waiting = true, fetching = false),
        )
        assertEquals(PieceNotesChoice.Ask(null), PieceNotesChoice.of(null, null, online = true, waiting = true, fetching = false))
        assertEquals(PieceNotesChoice.Offline, PieceNotesChoice.of(null, null, online = false, waiting = true, fetching = false))
        // Notes kept from before are shown, fetching or not.
        assertEquals(PieceNotesChoice.Text(piece.description!!, null), PieceNotesChoice.of(piece, null, online = true, waiting = true, fetching = false))
    }
}
