// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.data.art

import dev.stevenjin.stevenpiano.data.db.PieceHead
import dev.stevenjin.stevenpiano.data.db.PlaylistRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The covers' rule (v1.37 — M73): K-pop plain, House & Dance its album covers, never an artist's photo for either. */
class CoverRuleTest {
    private val playlists = listOf(
        PlaylistRef(3, "K-pop", builtIn = false, builtInKey = null),
        PlaylistRef(4, "House & Dance", builtIn = false, builtInKey = null),
        PlaylistRef(5, "Road trip", builtIn = false, builtInKey = null),
        PlaylistRef(9, "K-Pop", builtIn = true, builtInKey = "genre:kpop"),
    )
    private val styles = CoverStyles(plain = setOf(1L, 2L), album = setOf(2L, 3L), plainArtists = setOf("bts"), read = true)

    @Test
    fun `K-pop pieces never get the artist photo`() {
        assertEquals(CoverStyle.Plain, styles.of(1))
        assertEquals("K-pop's rule wins for a song in both", CoverStyle.Plain, styles.of(2))
        for (chosen in listOf(false, true)) for (read in listOf(false, true)) {
            assertFalse("chosen $chosen, read $read", ArtLayer.Portrait in CoverRule.layers(CoverStyle.Plain, chosen, read))
        }
        assertEquals("the plain generated cover, not the album cover the lookup found", listOf(ArtLayer.RollCard), CoverRule.layers(CoverStyle.Plain, chosenByHand = false))
        assertEquals("a cover the person chose still shows", listOf(ArtLayer.OwnCover, ArtLayer.RollCard), CoverRule.layers(CoverStyle.Plain, chosenByHand = true))
        assertEquals(CoverStyle.Plain, CoverRule.playlistStyle("KPOP"))
    }

    @Test
    fun `a House & Dance piece prefers its album cover, then the plain one`() {
        assertEquals(CoverStyle.Album, styles.of(3))
        assertEquals(listOf(ArtLayer.OwnCover, ArtLayer.RollCard), CoverRule.layers(CoverStyle.Album, chosenByHand = false))
        assertEquals(CoverStyle.Album, CoverRule.playlistStyle("house and dance"))
    }

    @Test
    fun `any other piece keeps the usual rule, without a portrait until the styles are read`() {
        assertEquals(CoverStyle.Usual, styles.of(4))
        assertEquals(listOf(ArtLayer.OwnCover, ArtLayer.Portrait, ArtLayer.RollCard), CoverRule.layers(CoverStyle.Usual, chosenByHand = false))
        assertEquals(listOf(ArtLayer.OwnCover, ArtLayer.RollCard), CoverRule.layers(CoverStyle.Usual, chosenByHand = false, read = false))
        assertEquals(CoverStyle.Usual, CoverStyles.Unread.of(1))
        assertEquals(CoverStyle.Usual, CoverRule.playlistStyle("Road trip"))
    }

    @Test
    fun `the playlists named K-pop are plain and House & Dance album, Apple's own K-Pop list too`() {
        assertEquals(listOf(3L, 9L) to listOf(4L), CoverRule.styled(playlists))
    }

    @Test
    fun `an artist whose pieces are all or mostly K-pop's shows the plain art, one K-pop song among many keeps the photo`() {
        val inKpop = listOf(PieceHead(1, "bts"), PieceHead(2, "bts"), PieceHead(3, "twice"), PieceHead(4, "coldplay"), PieceHead(5, ""))
        val counts = mapOf("bts" to 2, "twice" to 2, "coldplay" to 6, "" to 3)
        val kpop = CoverRule.kpopArtists(inKpop, counts)
        assertEquals("all, or half and more", setOf("bts", "twice"), kpop)
        val read = CoverStyles(read = true, kpopArtists = kpop)
        assertFalse(read.portrait("bts"))
        assertTrue(read.portrait("coldplay"))
        assertFalse("no photo before the styles are read", CoverStyles.Unread.portrait("coldplay"))
    }
}
