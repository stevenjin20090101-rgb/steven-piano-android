// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalContext
import dev.stevenjin.stevenpiano.data.art.ArtPalette
import dev.stevenjin.stevenpiano.data.db.ArtworkEntity
import dev.stevenjin.stevenpiano.graph

/**
 * The album-colour backdrop's palette for piece [pieceId] (v1.15 — M41): the colours of the art [PieceArt] shows first,
 * its own cover, else its composer's portrait ([composerKey]); a roll card or a monogram has none. Read from the
 * picture's row-size decode off the main thread, once a picture ([dev.stevenjin.stevenpiano.data.art.ArtworkRepository.palette]),
 * so a piece played again has its colours at once. Null while it is read, for grey art and for no art; a new piece's
 * art keeps the last colours until its own are read, so the backdrop cross-fades from one to the other.
 */
@Composable
fun rememberArtPalette(pieceId: Long, composerKey: String): ArtPalette? {
    val artwork = LocalContext.current.graph.artwork
    val own = rememberArtworkRow(ArtworkEntity.forPiece(pieceId))
    val portrait = rememberArtworkRow(ArtworkEntity.forComposer(composerKey))
    val row = own?.takeIf { it.imagePath != null } ?: portrait?.takeIf { it.imagePath != null }
    val palette by produceState(row?.let(artwork::cachedPalette), row?.imagePath, row?.fetchedAt) {
        value = row?.let { artwork.palette(it) }
    }
    return palette
}
