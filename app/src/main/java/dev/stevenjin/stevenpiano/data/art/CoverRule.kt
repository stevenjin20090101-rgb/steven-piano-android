// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.data.art

import dev.stevenjin.stevenpiano.data.PlaylistNames
import dev.stevenjin.stevenpiano.data.db.PlaylistRef

/**
 * How a piece's art is chosen (v1.37 — M73). [Usual]: its own cover, else its composer's or artist's portrait, else its
 * roll card. [Plain] (a song of Steven's K-pop): its roll card, the plain generated cover, never the artist's photo nor
 * the album cover the lookup found (a cover the person chose by hand still shows). [Album] (a song of his House & Dance):
 * its own album cover, else its roll card, never the artist's photo.
 */
enum class CoverStyle { Usual, Plain, Album }

/** What a piece's art may be, tried in order: its own cover, its composer's portrait, its roll card. */
enum class ArtLayer { OwnCover, Portrait, RollCard }

/**
 * Which pieces' art is [CoverStyle.Plain] (in a playlist called K-pop) and which [CoverStyle.Album] (House & Dance), and
 * the artists of the plain ones ([plainArtists]: a channel's card shows no photo of them). [read] false until the library
 * has said: no portrait shows then, so none flashes before the rule is known.
 */
data class CoverStyles(
    val plain: Set<Long> = emptySet(),
    val album: Set<Long> = emptySet(),
    val plainArtists: Set<String> = emptySet(),
    val read: Boolean = false,
) {
    /** Piece [pieceId]'s style; K-pop's plain one wins for a song in both. */
    fun of(pieceId: Long): CoverStyle = when (pieceId) {
        in plain -> CoverStyle.Plain
        in album -> CoverStyle.Album
        else -> CoverStyle.Usual
    }

    companion object {
        val Unread = CoverStyles()
    }
}

/** The covers' rule (v1.37 — M73), pure: what a piece shows, and which playlists' pieces follow which style. */
object CoverRule {
    /**
     * The layers a piece of [style] tries, in order ([chosenByHand]: its cover was chosen by the person, on the tablet or
     * in the web panel, `ArtworkPolicy.chosenByHand`); [read] false: the styles aren't known yet, so no portrait.
     */
    fun layers(style: CoverStyle, chosenByHand: Boolean, read: Boolean = true): List<ArtLayer> = when (style) {
        CoverStyle.Usual -> if (read) listOf(ArtLayer.OwnCover, ArtLayer.Portrait, ArtLayer.RollCard) else listOf(ArtLayer.OwnCover, ArtLayer.RollCard)
        CoverStyle.Album -> listOf(ArtLayer.OwnCover, ArtLayer.RollCard)
        CoverStyle.Plain -> if (chosenByHand) listOf(ArtLayer.OwnCover, ArtLayer.RollCard) else listOf(ArtLayer.RollCard)
    }

    /** A playlist's own style, by its name as names compare ([PlaylistNames.fold]): K-pop plain, House & Dance album covers. */
    fun playlistStyle(name: String): CoverStyle = when (PlaylistNames.fold(name)) {
        PlaylistNames.KPOP -> CoverStyle.Plain
        PlaylistNames.HOUSE_AND_DANCE -> CoverStyle.Album
        else -> CoverStyle.Usual
    }

    /** Of [playlists], the ids of those whose pieces are plain, and of those whose pieces show album covers. */
    fun styled(playlists: List<PlaylistRef>): Pair<List<Long>, List<Long>> {
        val plain = playlists.filter { playlistStyle(it.name) == CoverStyle.Plain }.map { it.id }
        val album = playlists.filter { playlistStyle(it.name) == CoverStyle.Album }.map { it.id }
        return plain to album
    }
}
