// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.ui.screens.library

import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import dev.stevenjin.stevenpiano.data.db.PieceEntity
import dev.stevenjin.stevenpiano.ui.components.HairlineDivider

/**
 * What a piece's row menu can do, wherever the row is: one holder instead of a callback per item.
 * Inside a playlist [removeFromPlaylist] and [move] are set too ([forPlaylist]).
 */
@Immutable
class PieceActions(
    val playNext: (PieceEntity) -> Unit,
    val addToQueue: (PieceEntity) -> Unit,
    val addToPlaylist: (PieceEntity) -> Unit,
    val setFavorite: (PieceEntity, Boolean) -> Unit,
    val rename: (PieceEntity) -> Unit,
    val delete: (PieceEntity) -> Unit,
    /** Inside a playlist: take the piece out of it (the piece stays in the library). */
    val removeFromPlaylist: ((PieceEntity) -> Unit)? = null,
    /** Inside a playlist: Move up (-1) or Move down (+1). */
    val move: ((PieceEntity, Int) -> Unit)? = null,
) {
    /** These actions, with the two a playlist adds. */
    fun forPlaylist(remove: (PieceEntity) -> Unit, move: (PieceEntity, Int) -> Unit): PieceActions =
        PieceActions(playNext, addToQueue, addToPlaylist, setFavorite, rename, delete, remove, move)
}

/** A row's place in a reorderable playlist: which of Move up and Move down it can offer. */
data class RowPlace(val index: Int, val count: Int) {
    val canMoveUp: Boolean get() = index > 0
    val canMoveDown: Boolean get() = index < count - 1
}

/**
 * A piece's menu, in three groups set apart by hairlines, destructive items last: Play next · Add
 * to queue | Add to playlist · Favorite · Rename (· Move up · Move down, inside a playlist that can
 * be reordered) | Remove from playlist (inside a playlist) · Delete. A move the row cannot make is
 * left out rather than shown disabled.
 */
@Composable
fun PieceMenu(piece: PieceEntity, actions: PieceActions, place: RowPlace?, expanded: Boolean, onDismiss: () -> Unit) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        MenuItem("Play next", onDismiss) { actions.playNext(piece) }
        MenuItem("Add to queue", onDismiss) { actions.addToQueue(piece) }
        HairlineDivider()
        MenuItem("Add to playlist", onDismiss) { actions.addToPlaylist(piece) }
        MenuItem(if (piece.favorite) "Unfavorite" else "Favorite", onDismiss) { actions.setFavorite(piece, !piece.favorite) }
        MenuItem("Rename", onDismiss) { actions.rename(piece) }
        val move = actions.move
        if (move != null && place != null) {
            if (place.canMoveUp) MenuItem("Move up", onDismiss) { move(piece, -1) }
            if (place.canMoveDown) MenuItem("Move down", onDismiss) { move(piece, 1) }
        }
        HairlineDivider()
        actions.removeFromPlaylist?.let { remove -> MenuItem("Remove from playlist", onDismiss) { remove(piece) } }
        MenuItem("Delete", onDismiss) { actions.delete(piece) }
    }
}

/** A menu item that closes the menu, then acts. */
@Composable
fun MenuItem(label: String, onDismiss: () -> Unit, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(label) },
        onClick = {
            onDismiss()
            onClick()
        },
    )
}
