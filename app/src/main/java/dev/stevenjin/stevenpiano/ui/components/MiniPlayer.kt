// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.stevenjin.stevenpiano.R
import dev.stevenjin.stevenpiano.data.art.ArtSize
import dev.stevenjin.stevenpiano.player.PlaybackStatus
import dev.stevenjin.stevenpiano.player.PlayerState
import dev.stevenjin.stevenpiano.ui.theme.LocalTertiary

/** The mini player's row: 64 dp, growing with large text rather than clipping it. */
private val RowHeight = 64.dp
private val ArtSizeDp = 48.dp

/**
 * The mini player (DESIGN.md › v1.5 — M16): on phones, a 64 dp row above the tab bar, in the bar's
 * glass, whenever a piece is loaded or loading ([miniPlayerShown]). The piece's art at 48 dp (its composer's
 * portrait, else its roll card, [PieceArt]), its title in Body over its composer as an eyebrow,
 * one line each with an ellipsis, then play/pause and next as 48 dp glyphs. The whole row opens
 * Now playing ([onOpen]); there are no swipes. On glass the composer line is the content colour
 * (text on glass is primary); on the solid surface, the tertiary eyebrow. While a first piece loads
 * the row says so and play/pause waits.
 */
@Composable
fun MiniPlayer(
    state: PlayerState,
    onOpen: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val piece = state.piece
    val playing = state.status == PlaybackStatus.Playing
    val ink = MaterialTheme.colorScheme.onSurface
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = RowHeight)
            .clickable(onClickLabel = "Open Now playing", onClick = onOpen)
            .padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (piece != null) PieceArt(piece.pieceId, piece.composerKey, ArtSize.Row, Modifier.size(ArtSizeDp), title = piece.title) else ArtFrame(Modifier.size(ArtSizeDp))
        Spacer(Modifier.width(12.dp))
        Column(
            Modifier
                .weight(1f)
                .padding(vertical = 8.dp),
        ) {
            Text(
                piece?.title ?: OPENING,
                style = MaterialTheme.typography.bodyLarge,
                color = ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (piece != null && piece.composer.isNotBlank()) {
                Eyebrow(piece.composer, color = if (LocalOnGlass.current) ink else LocalTertiary.current, maxLines = 1)
            }
        }
        GlyphButton(
            if (playing) R.drawable.ic_pause else R.drawable.ic_play,
            if (playing) "Pause" else "Play",
            enabled = piece != null,
            onClick = onPlayPause,
        )
        GlyphButton(R.drawable.ic_skip_next, "Next", enabled = state.queue.hasNext, onClick = onNext)
    }
}

/** Whether there is anything for the mini player to show: a piece is loaded, or one is loading. */
val PlayerState.miniPlayerShown: Boolean get() = piece != null || loading

private const val OPENING = "Opening the piece…"
