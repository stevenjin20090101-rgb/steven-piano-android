// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.ui.screens.library

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.stevenjin.stevenpiano.data.art.ArtSize
import dev.stevenjin.stevenpiano.data.db.ComposerGroup
import dev.stevenjin.stevenpiano.data.db.PieceEntity
import dev.stevenjin.stevenpiano.data.db.PlaylistSummary
import dev.stevenjin.stevenpiano.ui.Format
import dev.stevenjin.stevenpiano.ui.components.ComposerArt
import dev.stevenjin.stevenpiano.ui.components.Eyebrow
import dev.stevenjin.stevenpiano.ui.components.HairlineDivider
import dev.stevenjin.stevenpiano.ui.components.PlaylistCover

/**
 * A piece: its composer's 40 dp portrait (else the mosaic of their roll cards, else a monogram),
 * then the title over "Surname · m:ss". Tap plays; long-press opens its menu ([PieceMenu] with
 * [actions]; [place] offers Move up and Move down inside a reorderable playlist). [trailing] is
 * the drag handle inside a playlist.
 */
@Composable
fun PieceRow(
    piece: PieceEntity,
    actions: PieceActions,
    onPlay: () -> Unit,
    modifier: Modifier = Modifier,
    place: RowPlace? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    var menu by remember { mutableStateOf(false) }
    Box(modifier) {
        TextRow(
            title = piece.title,
            meta = listOf(piece.composerShort, Format.clockMillis(piece.durationMs)).filter { it.isNotBlank() }.joinToString(" · "),
            onClickLabel = "Play",
            onClick = onPlay,
            onLongClick = { menu = true },
            leading = { ComposerArt(piece.composerKey, piece.composerShort.ifBlank { piece.title }, ArtSize.Row, Modifier.size(PORTRAIT)) },
            trailing = trailing,
        )
        PieceMenu(piece, actions, place, expanded = menu) { menu = false }
    }
}

/**
 * A playlist's tile: its cover (the person's photo, else its first composer's portrait, else a
 * monogram), name and size. Tap opens it; long-press offers Rename, Change photo and Delete, or
 * for a built-in playlist (its eyebrow "BUILT IN · 12 PIECES") Change photo only.
 */
@Composable
fun PlaylistTile(
    playlist: PlaylistSummary,
    onOpen: () -> Unit,
    onChangePhoto: () -> Unit,
    onDialog: (LibraryDialog) -> Unit,
    modifier: Modifier = Modifier,
) {
    var menu by remember { mutableStateOf(false) }
    Tile(
        name = playlist.name,
        meta = playlistMeta(playlist),
        art = { PlaylistCover(playlist.id, playlist.name, ArtSize.Tile, it) },
        onOpen = onOpen,
        onLongPress = { menu = true },
        modifier = modifier,
    ) {
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            if (playlist.builtIn) {
                MenuItem("Change photo", { menu = false }, onChangePhoto)
            } else {
                MenuItem("Rename", { menu = false }) { onDialog(LibraryDialog.RenamePlaylist(playlist)) }
                MenuItem("Change photo", { menu = false }, onChangePhoto)
                HairlineDivider()
                MenuItem("Delete", { menu = false }) { onDialog(LibraryDialog.DeletePlaylist(playlist)) }
            }
        }
    }
}

/** A playlist tile's eyebrow: "12 pieces", or for a built-in one "Built in · 12 pieces" (set in capitals). */
fun playlistMeta(playlist: PlaylistSummary): String {
    val count = Format.count(playlist.pieceCount, "piece", "pieces")
    return if (playlist.builtIn) "$BUILT_IN · $count" else count
}

/** What marks a built-in playlist in its eyebrows. */
const val BUILT_IN = "Built in"

/**
 * A composer's tile, by full name: their portrait, else the mosaic of their pieces' roll cards.
 * Tap opens their pieces; long-press plays them all or shuffled.
 */
@Composable
fun ComposerTile(composer: ComposerGroup, onOpen: () -> Unit, onPlayAll: (shuffle: Boolean) -> Unit, modifier: Modifier = Modifier) {
    var menu by remember { mutableStateOf(false) }
    Tile(
        name = composerName(composer),
        meta = Format.count(composer.pieceCount, "piece", "pieces"),
        art = { ComposerArt(composer.composerKey, composer.shortName.ifBlank { composer.name }, ArtSize.Tile, it) },
        onOpen = onOpen,
        onLongPress = { menu = true },
        modifier = modifier,
    ) {
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            MenuItem("Play all", { menu = false }) { onPlayAll(false) }
            MenuItem("Shuffle", { menu = false }) { onPlayAll(true) }
        }
    }
}

fun composerName(composer: ComposerGroup): String = composer.name.ifBlank { "Unknown composer" }

/** One row of a grid of [columns] tiles with 8 dp gutters; a short last row keeps the tiles' width. */
@Composable
fun TileRow(columns: Int, count: Int, content: @Composable RowScope.() -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        content()
        repeat(columns - count) { Spacer(Modifier.weight(1f)) }
    }
}

/** A tile: square [art], the name in Body, the count as an eyebrow. [menu] is its long-press menu. */
@Composable
private fun Tile(
    name: String,
    meta: String,
    art: @Composable (Modifier) -> Unit,
    onOpen: () -> Unit,
    onLongPress: () -> Unit,
    modifier: Modifier,
    menu: @Composable () -> Unit,
) {
    Box(modifier) {
        Column(
            Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClickLabel = "Open",
                    onLongClickLabel = "Show options",
                    onLongClick = onLongPress,
                    hapticFeedbackEnabled = false,   // the app's only haptic is play/pause
                    onClick = onOpen,
                )
                .padding(bottom = 8.dp),
        ) {
            art(Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            Text(
                name,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Eyebrow(meta, maxLines = 1)
        }
        menu()
    }
}

/**
 * The library's row: [leading] art, then the text, 56 dp or more, a hairline divider inset to the
 * text; [trailing] holds a row's glyphs.
 */
@Composable
private fun TextRow(
    title: String,
    meta: String,
    onClickLabel: String,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    Column {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier
                    .weight(1f)
                    .heightIn(min = 56.dp)
                    .combinedClickable(
                        onClickLabel = onClickLabel,
                        onLongClickLabel = if (onLongClick != null) "Show options" else null,
                        onLongClick = onLongClick,
                        hapticFeedbackEnabled = false,   // the app's only haptic is play/pause
                        onClick = onClick,
                    )
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (leading != null) {
                    leading()
                    Spacer(Modifier.width(LEADING_GAP))
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                    Text(
                        title,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (meta.isNotEmpty()) Eyebrow(meta, color = MaterialTheme.colorScheme.onSurfaceVariant, uppercase = false, maxLines = 1)
                }
            }
            trailing?.invoke(this)
        }
        HairlineDivider(startInset = if (leading != null) TEXT_INSET_WITH_ART else 16.dp)
    }
}

/** The composer portrait beside a piece row. */
private val PORTRAIT: Dp = 40.dp
private val LEADING_GAP: Dp = 16.dp
private val TEXT_INSET_WITH_ART: Dp = 16.dp + PORTRAIT + LEADING_GAP
