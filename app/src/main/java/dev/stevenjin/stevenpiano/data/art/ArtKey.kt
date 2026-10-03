// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.data.art

import dev.stevenjin.stevenpiano.data.db.ArtworkEntity

/**
 * What can be fetched: from Wikipedia a composer's portrait and blurb, or a piece's notes; from Apple's catalogue a
 * piece's album cover (v1.15 — M40). A playlist's photo is the person's own and never fetched. [storageKey] is the
 * row's key in the artwork table; [label] names it in the fetch notification.
 */
sealed interface ArtKey {
    val storageKey: String
    val label: String

    /** A composer by [composerKey] (the folded surname the library groups by), shown as [display]. */
    data class Composer(val composerKey: String, val display: String) : ArtKey {
        override val storageKey: String get() = ArtworkEntity.forComposer(composerKey)
        override val label: String get() = display
    }

    /** A piece by [id], with its [title] and [composer] (the composer's name as the library shows it). */
    data class Piece(val id: Long, val title: String, val composer: String) : ArtKey {
        override val storageKey: String get() = ArtworkEntity.forPiece(id)
        override val label: String get() = title
    }

    /**
     * Piece [id]'s album cover (v1.15 — M40), looked up in Apple's catalogue by its [title] and [artist] (the name rows
     * show: an artist's whole name, a composer's surname); [classical]: a composer, whom the album or the track may name
     * instead of the artist. Stored as `cover:<id>`, the lookup's own record ([ArtworkEntity.forCover]); the picture
     * becomes the piece's own cover.
     */
    data class Cover(val id: Long, val title: String, val artist: String, val classical: Boolean) : ArtKey {
        override val storageKey: String get() = ArtworkEntity.forCover(id)
        override val label: String get() = title
    }
}
