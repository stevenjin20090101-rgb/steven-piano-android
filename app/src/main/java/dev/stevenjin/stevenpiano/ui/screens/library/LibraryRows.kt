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
import androidx.compose.ui.unit.dp
import dev.stevenjin.stevenpiano.data.db.ComposerGroup
import dev.stevenjin.stevenpiano.data.db.PieceEntity
import dev.stevenjin.stevenpiano.data.db.PlaylistSummary
import dev.stevenjin.stevenpiano.ui.Format
import dev.stevenjin.stevenpiano.ui.components.Eyebrow
import dev.stevenjin.stevenpiano.ui.components.HairlineDivider
import dev.stevenjin.stevenpiano.ui.components.MonogramTile

/**
 * A piece: title over "Surname · m:ss". Tap plays; long-press opens its menu ([PieceMenu] with
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
            trailing = trailing,
        )
        PieceMenu(piece, actions, place, expanded = menu) { menu = false }
    }
}

/** A playlist's tile: its art, name and size. Tap opens it; long-press offers Rename and Delete. */
@Composable
fun PlaylistTile(playlist: PlaylistSummary, onOpen: () -> Unit, onDialog: (LibraryDialog) -> Unit, modifier: Modifier = Modifier) {
    var menu by remember { mutableStateOf(false) }
    Tile(
        name = playlist.name,
        monogram = playlist.name,
        meta = Format.count(playlist.pieceCount, "piece", "pieces"),
        onOpen = onOpen,
        onLongPress = { menu = true },
        modifier = modifier,
    ) {
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            MenuItem("Rename", { menu = false }) { onDialog(LibraryDialog.RenamePlaylist(playlist)) }
            HairlineDivider()
            MenuItem("Delete", { menu = false }) { onDialog(LibraryDialog.DeletePlaylist(playlist)) }
        }
    }
}

/** A composer's tile, by full name (the surname's initial as its art). Tap opens their pieces; long-press plays them all or shuffled. */
@Composable
fun ComposerTile(composer: ComposerGroup, onOpen: () -> Unit, onPlayAll: (shuffle: Boolean) -> Unit, modifier: Modifier = Modifier) {
    var menu by remember { mutableStateOf(false) }
    Tile(
        name = composerName(composer),
        monogram = composer.shortName.ifBlank { composer.name },
        meta = Format.count(composer.pieceCount, "piece", "pieces"),
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

/** A tile: square art, the name in Body, the count as an eyebrow. [menu] is its long-press menu. */
@Composable
private fun Tile(
    name: String,
    monogram: String,
    meta: String,
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
            MonogramTile(monogram, Modifier.fillMaxWidth())
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

/** The library's row: text, 56 dp or more, a hairline divider inset to the text; [trailing] holds a row's glyphs. */
@Composable
private fun TextRow(
    title: String,
    meta: String,
    onClickLabel: String,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    Column {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(
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
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (meta.isNotEmpty()) Eyebrow(meta, color = MaterialTheme.colorScheme.onSurfaceVariant, uppercase = false, maxLines = 1)
            }
            trailing?.invoke(this)
        }
        HairlineDivider(startInset = 16.dp)
    }
}
