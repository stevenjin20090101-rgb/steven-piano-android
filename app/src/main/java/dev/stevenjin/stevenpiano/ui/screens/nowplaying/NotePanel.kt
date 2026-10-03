// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.ui.screens.nowplaying

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LongState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.stevenjin.stevenpiano.player.NowPlaying
import dev.stevenjin.stevenpiano.player.PlaybackStatus
import dev.stevenjin.stevenpiano.player.Player
import dev.stevenjin.stevenpiano.player.PlayerState
import dev.stevenjin.stevenpiano.settings.NoteDisplay
import dev.stevenjin.stevenpiano.ui.NotesLayout
import dev.stevenjin.stevenpiano.ui.NotesPlan
import dev.stevenjin.stevenpiano.ui.PlaybackStarter
import dev.stevenjin.stevenpiano.ui.components.GlassSurface
import dev.stevenjin.stevenpiano.ui.components.Hairline
import dev.stevenjin.stevenpiano.ui.components.KeyboardStripHeight
import dev.stevenjin.stevenpiano.ui.components.LocalHazeState
import dev.stevenjin.stevenpiano.ui.components.LocalYieldBlur
import dev.stevenjin.stevenpiano.ui.components.Scrubber
import dev.stevenjin.stevenpiano.ui.components.TRACKER_FROM_BOTTOM
import dev.stevenjin.stevenpiano.ui.components.TransportBar
import dev.stevenjin.stevenpiano.ui.components.TransportMinWidth
import dev.stevenjin.stevenpiano.ui.components.hazeSource
import dev.stevenjin.stevenpiano.ui.components.rememberHazeState

/**
 * The scrubber and the transport together, as they stand (the scrubber's 48 dp row with 4 dp above
 * and below, then the 72 dp play control): what the glass over the roll must hold.
 */
internal val TransportHeight: Dp = 56.dp + 72.dp

/** Room kept between the tracker bar and the glass, so a note is seen crossing into the history before it passes under the glass. */
private val UNDER_THE_BAR = 12.dp

/** The note views' card: the elevated surface with the card corners. */
@Composable
internal fun Panel(modifier: Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceVariant),
        content = content,
    )
}

/**
 * A roll's card with the transport floating on glass over its history (DESIGN.md › v1.5 — M16):
 * the card ([panel]: the canvas, the hairline, the keyboard strip) is the glass's source, and the
 * glass is its sibling, laid across the card's width with its bottom on the strip's top edge
 * ([stripHeight] above the card's bottom), holding [controls]. So the controls never cover the
 * tracker bar or the keyboard strip; the notes just played pass under them, blurred. The play
 * control is the filled circle on that band (DESIGN.md › v1.9). Beside the Library's list, the band
 * yields its blur while the list scrolls ([LocalYieldBlur]), so only the list's header blurs on every
 * frame of a scroll.
 */
@Composable
internal fun GlassTransportPanel(
    modifier: Modifier,
    stripHeight: Dp,
    panel: @Composable ColumnScope.() -> Unit,
    controls: @Composable ColumnScope.() -> Unit,
) {
    val source = rememberHazeState()
    Box(modifier) {
        Panel(Modifier.matchParentSize().hazeSource(source), panel)
        CompositionLocalProvider(LocalHazeState provides source) {
            GlassSurface(
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = stripHeight)
                    .fillMaxWidth(),
                source = source,
                // Beside a list scrolling under its own glass (the Library's panel), this glass yields its blur for that while.
                paused = LocalYieldBlur.current.value,
            ) {
                Column(content = controls)
            }
        }
    }
}

/**
 * Whether the transport can float on glass over the roll when the note views have [height] (and
 * [width]): only the paper roll has a history (the third below the tracker bar; falling notes end at
 * the keys), and it must hold the controls with a little room under the bar. Otherwise the transport
 * stands below the views as a solid row, as before (a phone on its side, the score alone). Stacked,
 * the roll has what the split leaves it below the score ([NotesPlan.split], v1.12 — M31a); side by
 * side, the whole height, but it must also be as wide as the controls ([TransportMinWidth]). The
 * plan's split is the committed one, so the transport does not jump while a finger drags the divider.
 */
internal fun transportFloats(plan: NotesPlan, height: Dp, width: Dp = Dp.Infinity): Boolean {
    if (plan.rollStyle != NoteDisplay.PAPER_ROLL) return false
    val rollCard = when (plan.layout) {
        NotesLayout.ROLL -> height
        NotesLayout.SIDE_BY_SIDE -> if ((width - PANEL_GAP) * (1f - plan.split) < TransportMinWidth) return false else height
        NotesLayout.STACKED -> (height - PANEL_GAP) * (1f - plan.split)
        NotesLayout.SCORE -> return false
    }
    val canvas = rollCard - Hairline - KeyboardStripHeight
    return canvas * TRACKER_FROM_BOTTOM >= TransportHeight + UNDER_THE_BAR
}

/** Between two note views (the score and the roll), stacked or side by side. */
internal val PANEL_GAP = 8.dp

/**
 * The scrubber over the transport, as Now playing and the now-playing panel show them, on glass or
 * solid: through [playback] (which keeps the playback service running), Shuffle and Repeat on the
 * player. [onSeek] is the scrubber's seek; [onMoved] follows anything else that moves a paused
 * piece (Previous), so the picture catches up. Without [scrubber] the transport alone (the score's
 * strip, v1.18 — M49); [inset] is the scrubber's room at its sides.
 */
@Composable
internal fun ColumnScope.TransportControls(
    piece: NowPlaying,
    state: PlayerState,
    frame: LongState,
    roll: RollClock,
    player: Player,
    playback: PlaybackStarter,
    onSeek: (Long) -> Unit,
    onMoved: () -> Unit,
    scrubber: Boolean = true,
    inset: Dp = 16.dp,
) {
    if (scrubber) {
        Scrubber(
            piece.durationMicros,
            frame,
            roll,
            onSeek = onSeek,
            modifier = Modifier.padding(horizontal = inset, vertical = 4.dp),
        )
    }
    TransportBar(
        playing = state.status == PlaybackStatus.Playing,
        hasNext = state.queue.hasNext,
        shuffle = state.queue.shuffle,
        repeat = state.queue.repeat,
        onShuffle = { player.setShuffle(!state.queue.shuffle) },
        onRepeat = { player.setRepeat(state.queue.repeat.cycled()) },
        onPrevious = {
            playback.previous()
            onMoved()
        },
        onPlayPause = playback::togglePlayPause,
        onNext = playback::next,
        modifier = Modifier.fillMaxWidth(),
    )
}
