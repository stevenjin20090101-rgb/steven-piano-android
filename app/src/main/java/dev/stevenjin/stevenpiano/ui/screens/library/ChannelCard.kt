// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.ui.screens.library

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.stevenjin.stevenpiano.channels.ChannelSummary
import dev.stevenjin.stevenpiano.data.art.ArtSize
import dev.stevenjin.stevenpiano.ui.ChannelCopy
import dev.stevenjin.stevenpiano.ui.components.ArtFrame
import dev.stevenjin.stevenpiano.ui.components.ComposerArt
import dev.stevenjin.stevenpiano.ui.components.Eyebrow
import dev.stevenjin.stevenpiano.ui.components.Hairline
import dev.stevenjin.stevenpiano.ui.components.LiveDot
import dev.stevenjin.stevenpiano.ui.components.Mosaic
import dev.stevenjin.stevenpiano.ui.components.MonogramTile
import dev.stevenjin.stevenpiano.ui.theme.GlassTokens
import dev.stevenjin.stevenpiano.ui.theme.LocalHairline

/** A channel's card in the Playlists row: 280 × 200 dp. */
val ChannelCardWidth: Dp = 280.dp

/** Its art's shape: 1.4 wide for 1 tall (280 × 200 dp in the row). */
const val CHANNEL_CARD_ASPECT = 1.4f

/** The band along the card's foot, at least this tall (it grows with large text). */
private val BandHeight = 56.dp

/**
 * A channel's card (DESIGN.md › v1.5 — M17): an [ArtFrame] 1.4 wide faced with a two-by-two
 * mosaic of the pool's four most frequent composers' art (their portraits, else their roll cards,
 * [ComposerArt]), and along its foot a static band, the surface at the glass's opacity with a
 * hairline top edge (a scrim, not glass: nothing behind it moves), holding the name in Title and
 * "12 PIECES", "ADD MORE PIECES" for a pool too small to play, or while it plays the live dot and
 * "PLAYING". On the band everything is the content colour, as on glass. Tap plays the channel
 * ([onPlay]); long-press offers Set volume ([onSetVolume]) and Schedule ([onSchedule], the schedule
 * editor with the channel chosen; v1.5.2). [connected] lights the dot red; otherwise it is the
 * hollow ring, as everywhere.
 */
@Composable
fun ChannelCard(
    summary: ChannelSummary,
    playing: Boolean,
    connected: Boolean,
    onPlay: () -> Unit,
    onSetVolume: () -> Unit,
    onSchedule: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var menu by remember { mutableStateOf(false) }
    val meta = ChannelCopy.cardMeta(summary, playing)
    Box(modifier) {
        Box(
            Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClickLabel = if (summary.playable) "Play ${summary.name}" else null,
                    onLongClickLabel = "Show options",
                    onLongClick = { menu = true },
                    hapticFeedbackEnabled = false,   // the app's only haptic is play/pause
                    onClick = { if (summary.playable) onPlay() },
                )
                .clearAndSetSemantics {
                    contentDescription = "${summary.name} ${ChannelCopy.CHANNEL.lowercase()}, ${meta.lowercase()}"
                    role = Role.Button
                },
        ) {
            ArtFrame(Modifier.fillMaxWidth(), aspect = CHANNEL_CARD_ASPECT) {
                val composers = summary.composers
                if (composers.isEmpty()) {
                    MonogramTile(summary.name, Modifier.fillMaxSize(), framed = false)
                } else {
                    Mosaic(composers.size, Modifier.fillMaxSize()) { i, cell ->
                        ComposerArt(composers[i].key, composers[i].name, ArtSize.Tile, cell, framed = false)
                    }
                }
                Band(summary.name, meta, playing, connected, Modifier.align(Alignment.BottomCenter))
            }
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            MenuItem("Set volume", { menu = false }, onSetVolume)
            MenuItem("Schedule", { menu = false }, onSchedule)
        }
    }
}

/** The card's foot: the surface at the glass's opacity, a hairline along its top, the name and the eyebrow. */
@Composable
private fun Band(name: String, meta: String, playing: Boolean, connected: Boolean, modifier: Modifier) {
    val ink = MaterialTheme.colorScheme.onSurface
    Column(
        modifier
            .fillMaxWidth()
            .heightIn(min = BandHeight)
            .background(MaterialTheme.colorScheme.surface.copy(alpha = GlassTokens.ContainerAlpha)),
    ) {
        Spacer(Modifier.fillMaxWidth().height(Hairline).background(LocalHairline.current))
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(min = BandHeight - Hairline)
                .padding(horizontal = 12.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(name, style = MaterialTheme.typography.titleLarge, color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (playing) {
                    LiveDot(live = connected, breathing = true)
                    Spacer(Modifier.width(6.dp))
                }
                Eyebrow(meta, color = ink, maxLines = 1)
            }
        }
    }
}
