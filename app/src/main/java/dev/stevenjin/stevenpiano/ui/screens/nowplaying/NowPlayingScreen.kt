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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.LongState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.stevenjin.stevenpiano.ble.LinkState
import dev.stevenjin.stevenpiano.graph
import dev.stevenjin.stevenpiano.player.NowPlaying
import dev.stevenjin.stevenpiano.player.PlaybackLimits
import dev.stevenjin.stevenpiano.player.PlaybackStatus
import dev.stevenjin.stevenpiano.player.Player
import dev.stevenjin.stevenpiano.player.PlayerState
import dev.stevenjin.stevenpiano.settings.NoteDisplay
import dev.stevenjin.stevenpiano.ui.Format
import dev.stevenjin.stevenpiano.ui.PlaybackStarter
import dev.stevenjin.stevenpiano.ui.components.Eyebrow
import dev.stevenjin.stevenpiano.ui.components.HairlineDivider
import dev.stevenjin.stevenpiano.ui.components.KeyboardStrip
import dev.stevenjin.stevenpiano.ui.components.LiveDot
import dev.stevenjin.stevenpiano.ui.components.NoteCanvas
import dev.stevenjin.stevenpiano.ui.components.OutlinedBanner
import dev.stevenjin.stevenpiano.ui.components.ProgressHairline
import dev.stevenjin.stevenpiano.ui.components.ScreenHeader
import dev.stevenjin.stevenpiano.ui.components.Scrubber
import dev.stevenjin.stevenpiano.ui.components.StepperControl
import dev.stevenjin.stevenpiano.ui.components.TransportBar
import dev.stevenjin.stevenpiano.ui.theme.rememberReducedMotion

/** After a change while paused (a seek), frames keep coming this long so the picture catches up. */
private const val SETTLE_NANOS = 400_000_000L

/**
 * The signature screen: the title, the composer, the note canvas over the keyboard strip, the
 * scrubber, the transport, tempo and the connection line. The transport goes through
 * [playback], which keeps the playback service running; [onOpenPiano] shows the Piano tab.
 */
@Composable
fun NowPlayingScreen(playback: PlaybackStarter, onOpenPiano: () -> Unit) {
    val graph = LocalContext.current.graph
    val player = graph.player
    val state by player.state.collectAsStateWithLifecycle()
    val settings by graph.settings.collectAsStateWithLifecycle()
    val link by graph.pianoLink.state.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Now playing")
        if (state.loading) ProgressHairline(null)
        state.problem?.let { OutlinedBanner(it, Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) }
        val piece = state.piece
        if (piece != null) {
            PieceView(piece, state, settings.noteDisplay, link is LinkState.Connected, player, playback, onOpenPiano)
        } else if (!state.loading) {
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "Choose a piece from the library.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ColumnScope.PieceView(
    piece: NowPlaying,
    state: PlayerState,
    display: NoteDisplay,
    connected: Boolean,
    player: Player,
    playback: PlaybackStarter,
    onOpenPiano: () -> Unit,
) {
    val playing = state.status == PlaybackStatus.Playing
    val roll = remember(player) { RollClock(player) }
    var settle by remember { mutableIntStateOf(0) }
    val frame = rememberFrameNanos(playing, piece.pieceId, settle, roll)

    Column(Modifier.padding(horizontal = 16.dp)) {
        Text(
            piece.title,
            style = MaterialTheme.typography.displayMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
        if (piece.composer.isNotBlank()) Eyebrow(piece.composer, Modifier.padding(top = 4.dp), maxLines = 1)
    }
    Spacer(Modifier.height(16.dp))
    Column(
        Modifier
            .weight(1f)
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        NoteCanvas(
            piece.notes,
            state.transpose,
            state.fold,
            display,
            frame,
            roll,
            Modifier
                .weight(1f)
                .fillMaxWidth(),
        )
        HairlineDivider()
        KeyboardStrip(frame, { player.activeKeysLow }, { player.activeKeysHigh })
    }
    Scrubber(
        piece.durationMicros,
        frame,
        roll,
        onSeek = {
            player.seek(it)
            if (!playing) settle++
        },
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    )
    TransportBar(
        playing = playing,
        hasNext = state.queueIndex + 1 < state.queueSize,
        onPrevious = {
            playback.previous()
            if (!playing) settle++
        },
        onPlayPause = playback::togglePlayPause,
        onNext = playback::next,
        modifier = Modifier.fillMaxWidth(),
    )
    FlowRow(
        Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Eyebrow("Tempo")
            Spacer(Modifier.width(4.dp))
            StepperControl(state.tempoPct, PlaybackLimits.TempoPct, TEMPO_STEP, Format::percent, "Slower", "Faster", player::setTempo)
        }
        ConnectionLine(connected, playing, onOpenPiano)
    }
}

/** "● Sent to piano" with the live dot, or "○ Not connected", which opens the Piano tab. */
@Composable
private fun ConnectionLine(connected: Boolean, playing: Boolean, onOpenPiano: () -> Unit) {
    val tap = if (connected) Modifier else Modifier.clickable(onClickLabel = "Open the Piano tab", role = Role.Button, onClick = onOpenPiano)
    Row(
        tap
            .heightIn(min = 48.dp)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LiveDot(live = connected, breathing = playing)
        Spacer(Modifier.width(8.dp))
        Text(
            if (connected) "Sent to piano" else "Not connected",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The frame time the canvas, keyboard and scrubber draw at: every frame while playing (the
 * first one starts the roll's ease-in), and a short burst after anything that moves a paused
 * piece ([settle]), so the picture catches up with the scheduler thread.
 */
@Composable
private fun rememberFrameNanos(playing: Boolean, pieceId: Long, settle: Int, roll: RollClock): LongState {
    val frame = remember { mutableLongStateOf(System.nanoTime()) }
    val reduced = rememberReducedMotion()
    LaunchedEffect(playing, pieceId, settle, reduced) {
        if (playing) {
            var first = true
            while (true) {
                withFrameNanos { t ->
                    if (first && !reduced) roll.easeIn(t)
                    first = false
                    frame.longValue = t
                }
            }
        } else {
            roll.cut()
            val until = System.nanoTime() + SETTLE_NANOS
            do {
                val t = withFrameNanos { it }
                frame.longValue = t
            } while (t < until)
        }
    }
    return frame
}

private const val TEMPO_STEP = 5
