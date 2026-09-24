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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.stevenjin.stevenpiano.data.db.CollectionSummary
import dev.stevenjin.stevenpiano.data.db.ComposerGroup
import dev.stevenjin.stevenpiano.data.db.PieceEntity
import dev.stevenjin.stevenpiano.ui.Format
import dev.stevenjin.stevenpiano.ui.components.Eyebrow
import dev.stevenjin.stevenpiano.ui.components.HairlineDivider

/** A piece: title over "Surname · m:ss". Tap plays; long-press opens its menu. */
@Composable
fun PieceRow(piece: PieceEntity, onPlay: () -> Unit, onFavorite: (Boolean) -> Unit, onDialog: (LibraryDialog) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Box {
        TextRow(
            title = piece.title,
            meta = listOf(piece.composerShort, Format.clockMillis(piece.durationMs)).filter { it.isNotBlank() }.joinToString(" · "),
            onClickLabel = "Play",
            onClick = onPlay,
            onLongClick = { menu = true },
        )
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            MenuItem("Add to collection") {
                menu = false
                onDialog(LibraryDialog.AddToCollection(piece))
            }
            MenuItem(if (piece.favorite) "Unfavorite" else "Favorite") {
                menu = false
                onFavorite(!piece.favorite)
            }
            MenuItem("Rename") {
                menu = false
                onDialog(LibraryDialog.Rename(piece))
            }
            MenuItem("Delete") {
                menu = false
                onDialog(LibraryDialog.Delete(piece))
            }
        }
    }
}

/** A collection: its name over its size. Tap opens it; long-press renames or deletes it. */
@Composable
fun CollectionRow(collection: CollectionSummary, onOpen: () -> Unit, onDialog: (LibraryDialog) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Box {
        TextRow(
            title = collection.name,
            meta = Format.count(collection.pieceCount, "piece", "pieces"),
            onClickLabel = "Open",
            onClick = onOpen,
            onLongClick = { menu = true },
        )
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            MenuItem("Rename") {
                menu = false
                onDialog(LibraryDialog.RenameCollection(collection))
            }
            MenuItem("Delete collection") {
                menu = false
                onDialog(LibraryDialog.DeleteCollection(collection))
            }
        }
    }
}

/** A composer, by full name, over how many pieces. Tap opens their pieces. */
@Composable
fun ComposerRow(composer: ComposerGroup, onOpen: () -> Unit) {
    TextRow(
        title = composerName(composer),
        meta = Format.count(composer.pieceCount, "piece", "pieces"),
        onClickLabel = "Open",
        onClick = onOpen,
    )
}

fun composerName(composer: ComposerGroup): String = composer.name.ifBlank { "Unknown composer" }

/** The library's row: text only, 56 dp, a hairline divider inset to the text. */
@Composable
private fun TextRow(title: String, meta: String, onClickLabel: String, onClick: () -> Unit, onLongClick: (() -> Unit)? = null) {
    Column {
        Column(
            Modifier
                .fillMaxWidth()
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
        HairlineDivider(startInset = 16.dp)
    }
}

@Composable
private fun MenuItem(label: String, onClick: () -> Unit) {
    DropdownMenuItem(text = { Text(label) }, onClick = onClick)
}
