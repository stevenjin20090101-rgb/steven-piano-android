// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.ui.screens.library

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.stevenjin.stevenpiano.R
import dev.stevenjin.stevenpiano.channels.ChannelSummary
import dev.stevenjin.stevenpiano.ui.Format
import dev.stevenjin.stevenpiano.ui.components.Eyebrow
import dev.stevenjin.stevenpiano.ui.components.GlyphButton
import dev.stevenjin.stevenpiano.ui.components.HairlineDivider

/**
 * Every channel (See all, DESIGN.md › v1.5 — M17): a page of the Library as a playlist's is, with
 * back to the Playlists, the title "Channels" over their count, then the cards in a grid of 2, 3 or
 * 4 columns as the playlist tiles ([columns]), each card the row's card at the grid's width.
 */
@Composable
fun ChannelsHeader(count: Int, onBack: () -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            GlyphButton(R.drawable.ic_back, "Back to playlists", onClick = onBack)
        }
        Column(Modifier.padding(horizontal = 16.dp)) {
            Text(
                "Channels",
                modifier = Modifier.semantics { heading() },
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Eyebrow(Format.count(count, "channel", "channels"))
        }
        Spacer(Modifier.height(16.dp))
        HairlineDivider()
        Spacer(Modifier.height(8.dp))
    }
}

/** The channels' grid, as rows of [columns] cards in the Library's list. */
fun LazyListScope.channelsGrid(
    channels: List<ChannelSummary>,
    columns: Int,
    playing: String?,
    connected: Boolean,
    onPlay: (String) -> Unit,
    onSetVolume: (String) -> Unit,
    onSchedule: (String) -> Unit,
) {
    items(channels.chunked(columns), key = { row -> "channels-${row.first().key}" }) { row ->
        TileRow(columns, row.size) {
            row.forEach { channel ->
                ChannelCard(
                    channel,
                    playing = channel.key == playing,
                    connected = connected,
                    onPlay = { onPlay(channel.key) },
                    onSetVolume = { onSetVolume(channel.key) },
                    onSchedule = { onSchedule(channel.key) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}
