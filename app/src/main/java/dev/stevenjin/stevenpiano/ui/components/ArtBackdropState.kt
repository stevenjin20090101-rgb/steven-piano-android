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
import dev.stevenjin.stevenpiano.data.art.BackdropPicture
import dev.stevenjin.stevenpiano.data.db.ArtworkEntity
import dev.stevenjin.stevenpiano.graph

/**
 * The backdrop's picture for piece [pieceId] (v1.18 — M49): made of the art [PieceArt] shows first, its own cover,
 * else its composer's portrait ([composerKey]); a roll card or a monogram gives none. Made from the picture's row-size
 * decode off the main thread, once a picture ([dev.stevenjin.stevenpiano.data.art.ArtworkRepository.backdrop]), so a
 * piece played again has it at once. Null while it is made and for no art; a new piece's art keeps the last picture
 * until its own is made, so the backdrop cross-fades from one to the other.
 */
@Composable
fun rememberBackdropPicture(pieceId: Long, composerKey: String): BackdropPicture? {
    val artwork = LocalContext.current.graph.artwork
    val own = rememberArtworkRow(ArtworkEntity.forPiece(pieceId))
    val portrait = rememberArtworkRow(ArtworkEntity.forComposer(composerKey))
    val row = own?.takeIf { it.imagePath != null } ?: portrait?.takeIf { it.imagePath != null }
    val picture by produceState(row?.let(artwork::cachedBackdrop), row?.imagePath, row?.fetchedAt) {
        value = row?.let { artwork.backdrop(it) }
    }
    return picture
}
