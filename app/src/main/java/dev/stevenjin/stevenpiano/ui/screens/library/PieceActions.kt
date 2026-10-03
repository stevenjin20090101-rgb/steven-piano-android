// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.ui.screens.library

import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import dev.stevenjin.stevenpiano.data.Genres
import dev.stevenjin.stevenpiano.data.db.PieceEntity
import dev.stevenjin.stevenpiano.ui.components.GlassDropdownMenu
import dev.stevenjin.stevenpiano.ui.components.HairlineDivider

/**
 * What a piece's row menu can do, wherever the row is: one holder instead of a callback per item.
 * Inside a playlist [removeFromPlaylist] and [move] are set too ([forPlaylist]).
 */
@Immutable
class PieceActions(
    val playNext: (PieceEntity) -> Unit,
    val addToQueue: (PieceEntity) -> Unit,
    /** The piece sheet: art, notes from Wikipedia and where they came from. */
    val about: (PieceEntity) -> Unit,
    val addToPlaylist: (PieceEntity) -> Unit,
    val setFavorite: (PieceEntity, Boolean) -> Unit,
    val rename: (PieceEntity) -> Unit,
    val delete: (PieceEntity) -> Unit,
    /** Move to Classical or Modern ([Genres.CLASSICAL], [Genres.MODERN]; v1.14 — M37). */
    val setGenre: (PieceEntity, Int) -> Unit,
    /** Change cover (v1.15 — M40): a photo chosen in the photo picker becomes the piece's own cover. */
    val changeCover: (PieceEntity) -> Unit,
    /** Inside a playlist: take the piece out of it (the piece stays in the library). */
    val removeFromPlaylist: ((PieceEntity) -> Unit)? = null,
    /** Inside a playlist: Move up (-1) or Move down (+1). */
    val move: ((PieceEntity, Int) -> Unit)? = null,
) {
    /** These actions, with the two a playlist adds. */
    fun forPlaylist(remove: (PieceEntity) -> Unit, move: (PieceEntity, Int) -> Unit): PieceActions =
        PieceActions(playNext, addToQueue, about, addToPlaylist, setFavorite, rename, delete, setGenre, changeCover, removeFromPlaylist = remove, move = move)
}

/** A row's place in a reorderable playlist: which of Move up and Move down it can offer. */
data class RowPlace(val index: Int, val count: Int) {
    val canMoveUp: Boolean get() = index > 0
    val canMoveDown: Boolean get() = index < count - 1
}

/**
 * A piece's menu, in three groups set apart by hairlines, destructive items last: Play next · Add
 * to queue · About this piece | Add to playlist · Favorite · Rename · Move to Modern or Move to
 * Classical (v1.14 — M37; the other genre's) · Change cover (v1.15 — M40) (· Move up · Move down,
 * inside a playlist that can be reordered) | Remove from playlist (inside a playlist) · Delete. A
 * move the row cannot make is left out rather than shown disabled: no Move to a genre for a piece
 * made here, which has none.
 */
@Composable
fun PieceMenu(piece: PieceEntity, actions: PieceActions, place: RowPlace?, expanded: Boolean, onDismiss: () -> Unit) {
    GlassDropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        MenuItem("Play next", onDismiss) { actions.playNext(piece) }
        MenuItem("Add to queue", onDismiss) { actions.addToQueue(piece) }
        MenuItem("About this piece", onDismiss) { actions.about(piece) }
        HairlineDivider()
        MenuItem("Add to playlist", onDismiss) { actions.addToPlaylist(piece) }
        MenuItem(if (piece.favorite) "Unfavorite" else "Favorite", onDismiss) { actions.setFavorite(piece, !piece.favorite) }
        MenuItem("Rename", onDismiss) { actions.rename(piece) }
        moveTarget(piece.genre)?.let { target -> MenuItem(moveLabel(target), onDismiss) { actions.setGenre(piece, target) } }
        MenuItem(CHANGE_COVER, onDismiss) { actions.changeCover(piece) }
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

/** Where Move takes a piece or a name of [genre] (v1.14 — M37): to the other genre; nowhere for none (made here) or a tie. */
fun moveTarget(genre: Int?): Int? = when (genre) {
    Genres.CLASSICAL -> Genres.MODERN
    Genres.MODERN -> Genres.CLASSICAL
    else -> null
}

/** The menu's item for a move to [target]: "Move to Modern", "Move to Classical". */
fun moveLabel(target: Int): String = "Move to ${genreWord(target)}"

/** What TalkBack hears once the move is made: "Moved to Modern." */
fun movedLine(target: Int): String = "Moved to ${genreWord(target)}."

private fun genreWord(genre: Int): String = if (genre == Genres.MODERN) "Modern" else "Classical"

/** The piece menu's item that opens the photo picker for its cover (v1.15 — M40). */
const val CHANGE_COVER = "Change cover"

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
