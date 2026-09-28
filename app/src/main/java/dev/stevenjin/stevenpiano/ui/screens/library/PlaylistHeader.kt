// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.ui.screens.library

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.stevenjin.stevenpiano.R
import dev.stevenjin.stevenpiano.data.db.PlaylistSummary
import dev.stevenjin.stevenpiano.ui.Format
import dev.stevenjin.stevenpiano.ui.components.Eyebrow
import dev.stevenjin.stevenpiano.ui.components.GlyphButton
import dev.stevenjin.stevenpiano.ui.components.Hairline
import dev.stevenjin.stevenpiano.ui.components.HairlineDivider
import dev.stevenjin.stevenpiano.ui.theme.LocalTertiary

/**
 * A playlist's page head: back and the playlist menu (Rename, Change photo, Delete; for a built-in
 * playlist Change photo only), the [cover] at 96 dp, the name in Title over "12 pieces · 41:20"
 * ("Built in · 12 pieces · 41:20" for a built-in one), then the outlined Shuffle button (not shown
 * for an empty playlist). Play is not here: it floats as a glass circle at the list's bottom end
 * (DESIGN.md › v1.5 — M16), where a thumb finds it wherever the list is scrolled.
 */
@Composable
fun PlaylistHeader(
    summary: PlaylistSummary,
    cover: @Composable (Modifier) -> Unit,
    onBack: () -> Unit,
    onShuffle: () -> Unit,
    onRename: () -> Unit,
    onChangePhoto: () -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Column {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            GlyphButton(R.drawable.ic_back, "Back to playlists", onClick = onBack)
            Spacer(Modifier.weight(1f))
            Box {
                GlyphButton(R.drawable.ic_more, "Playlist options") { menu = true }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    if (summary.builtIn) {
                        MenuItem("Change photo", { menu = false }, onChangePhoto)
                    } else {
                        MenuItem("Rename", { menu = false }, onRename)
                        MenuItem("Change photo", { menu = false }, onChangePhoto)
                        HairlineDivider()
                        MenuItem("Delete", { menu = false }, onDelete)
                    }
                }
            }
        }
        Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            cover(Modifier.size(COVER))
            Spacer(Modifier.width(16.dp))
            Column {
                Text(
                    summary.name,
                    modifier = Modifier.semantics { heading() },
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                val length = Format.piecesAndLength(summary.pieceCount, summary.durationMs)
                Eyebrow(if (summary.builtIn) "$BUILT_IN · $length" else length)
            }
        }
        if (summary.pieceCount > 0) {
            Row(
                Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(onClick = onShuffle, border = BorderStroke(Hairline, LocalTertiary.current)) {
                    Icon(painterResource(R.drawable.ic_shuffle), contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Shuffle")
                }
            }
        } else {
            Spacer(Modifier.size(16.dp))
        }
        HairlineDivider()
    }
}

private val COVER = 96.dp
