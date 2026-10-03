// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/** How a fetch for one piece of artwork ended. Stored by name. */
enum class ArtworkStatus {
    /** Found: [ArtworkEntity.imagePath] and/or [ArtworkEntity.description] are set. */
    OK,

    /** Looked for and not there; asked again only when the person forces a fetch. */
    NOT_FOUND,

    /** The fetch failed (offline is not a failure); retried after a day. */
    FAILED,
}

/**
 * Artwork and notes fetched for a composer, a piece or a playlist, one row per [key]
 * ([forComposer], [forPiece], [forPlaylist]; a piece's album cover lookup, [forCover]). [imagePath] is relative to
 * the app's files directory; [description] is the short text shown with it; [sourceUrl] and [sourceTitle] name
 * where it came from. Created in schema v2 so v1.2 has one migration; the UI starts reading it
 * in v1.2's artwork run.
 */
@Entity(tableName = "artwork")
data class ArtworkEntity(
    @PrimaryKey val key: String,
    val imagePath: String? = null,
    val description: String? = null,
    val sourceUrl: String? = null,
    val sourceTitle: String? = null,
    val fetchedAt: Long,
    val status: ArtworkStatus,
) {
    companion object {
        /** A composer's portrait and blurb, by `composerKey`. */
        fun forComposer(composerKey: String): String = "composer:$composerKey"

        /** A piece's notes, by piece id. */
        fun forPiece(pieceId: Long): String = "piece:$pieceId"

        /** A playlist's cover photo, by playlist id. */
        fun forPlaylist(playlistId: Long): String = "playlist:$playlistId"

        /**
         * A piece's album cover lookup (v1.15 — M40), by piece id: its status and when, and where the cover came from
         * ([sourceUrl] the track on Apple Music, [sourceTitle] "album · artist", or [CHOSEN_HERE]). The picture itself is
         * the piece's own cover, on its [forPiece] row, whose source fields stay the notes' Wikipedia credit.
         */
        fun forCover(pieceId: Long): String = "cover:$pieceId"

        /** A cover row's [sourceTitle] when the person chose the cover ("Change cover"): no lookup ever replaces it. */
        const val CHOSEN_HERE = "Chosen on this tablet"

        /**
         * A cover row's [description] when the person chose the cover, or took it away, in the web panel's cover picker
         * (v1.18 — M48). Its [sourceTitle] and [sourceUrl] stay the album's credit, so the piece sheet's "Cover: …" line
         * reads as a found cover's; no lookup ever looks for that piece's cover again (`ArtworkPolicy.recordOf`).
         */
        const val CHOSEN_IN_PANEL = "Chosen in the web panel"
    }
}
