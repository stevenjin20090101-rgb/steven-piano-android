// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.ui.screens.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.stevenjin.stevenpiano.channels.ChannelSummary
import dev.stevenjin.stevenpiano.ui.components.Eyebrow

/**
 * The channels at the top of the Playlists listing (DESIGN.md › v1.5 — M17): the eyebrow
 * "CHANNELS" with See all at its end ([onSeeAll], the grid of every channel), then a row of
 * 280 × 200 dp cards, 12 dp apart, 16 dp in from both edges, scrolling sideways. [playing] is the
 * channel playing, whose card shows it. The cards come from the pools as last worked out, keyed by
 * channel, so a refresh that leaves them alone does not lay the row out again.
 */
@Composable
fun ChannelRow(
    channels: List<ChannelSummary>,
    playing: String?,
    connected: Boolean,
    onPlay: (String) -> Unit,
    onSetVolume: (String) -> Unit,
    onSeeAll: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .padding(start = 16.dp, end = 4.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Eyebrow("Channels", Modifier.weight(1f).semantics { heading() }, maxLines = 1)
            TextButton(onClick = onSeeAll) { Text("See all") }
        }
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(channels, key = { it.key }) { channel ->
                ChannelCard(
                    channel,
                    playing = channel.key == playing,
                    connected = connected,
                    onPlay = { onPlay(channel.key) },
                    onSetVolume = { onSetVolume(channel.key) },
                    modifier = Modifier.width(ChannelCardWidth),
                )
            }
        }
    }
}
