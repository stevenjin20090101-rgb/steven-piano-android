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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.stevenjin.stevenpiano.ble.LinkState
import dev.stevenjin.stevenpiano.graph
import dev.stevenjin.stevenpiano.player.NowPlaying
import dev.stevenjin.stevenpiano.player.PlaybackLimits
import dev.stevenjin.stevenpiano.player.PlaybackStatus
import dev.stevenjin.stevenpiano.player.Player
import dev.stevenjin.stevenpiano.player.PlayerState
import dev.stevenjin.stevenpiano.ui.Format
import dev.stevenjin.stevenpiano.ui.LocalAppFrame
import dev.stevenjin.stevenpiano.ui.NotesLayout
import dev.stevenjin.stevenpiano.ui.NotesPlan
import dev.stevenjin.stevenpiano.ui.PlaybackStarter
import dev.stevenjin.stevenpiano.ui.components.ConnectionLine
import dev.stevenjin.stevenpiano.ui.components.Eyebrow
import dev.stevenjin.stevenpiano.ui.components.HairlineDivider
import dev.stevenjin.stevenpiano.ui.components.KeyboardStrip
import dev.stevenjin.stevenpiano.ui.components.NoteCanvas
import dev.stevenjin.stevenpiano.ui.components.OutlinedBanner
import dev.stevenjin.stevenpiano.ui.components.ProgressHairline
import dev.stevenjin.stevenpiano.ui.components.ScreenHeader
import dev.stevenjin.stevenpiano.ui.components.Scrubber
import dev.stevenjin.stevenpiano.ui.components.StaffCanvas
import dev.stevenjin.stevenpiano.ui.components.StepperControl
import dev.stevenjin.stevenpiano.ui.components.TransportBar
import dev.stevenjin.stevenpiano.ui.theme.rememberReducedMotion

/** Below this height the screen scrolls, and the note views get fixed heights; the stacked views need more. */
private val SHORT_BELOW = 520.dp
private val SHORT_BELOW_STACKED = 780.dp
private val SHORT_ROLL = 240.dp
private val SHORT_STAFF = 200.dp
private val PANEL_GAP = 8.dp

/** After a change while paused (a seek), frames keep coming this long so the picture catches up. */
private const val SETTLE_NANOS = 400_000_000L

/**
 * The signature screen: the title, the composer, the note views, the scrubber, the transport,
 * tempo and the connection line. The note views follow the window's width class: on a phone one
 * canvas (paper roll, falling notes or the staff, as Note display says); on wider screens the
 * staff and the notes together (stacked on medium widths, side by side on expanded ones), or
 * either alone, as Wide layout says. The roll always keeps its keyboard strip beneath it, lane
 * for key. The transport goes through [playback], which keeps the playback service running;
 * [onOpenPiano] shows the Piano tab.
 */
@Composable
fun NowPlayingScreen(playback: PlaybackStarter, onOpenPiano: () -> Unit) {
    val graph = LocalContext.current.graph
    val player = graph.player
    val frame = LocalAppFrame.current
    val state by player.state.collectAsStateWithLifecycle()
    val settings by graph.settings.collectAsStateWithLifecycle()
    val link by graph.pianoLink.state.collectAsStateWithLifecycle()
    val piece = state.piece
    val plan = frame.notesPlan(settings.noteDisplay, settings.wideLayout)
    BoxWithConstraints(Modifier.fillMaxSize()) {
        // Too short for the note views to share the height (landscape, a small phone at a large
        // font): the screen scrolls and the views keep fixed heights instead of collapsing.
        val short = piece != null && maxHeight < if (plan.layout == NotesLayout.STACKED) SHORT_BELOW_STACKED else SHORT_BELOW
        Column(if (short) Modifier.fillMaxSize().verticalScroll(rememberScrollState()) else Modifier.fillMaxSize()) {
            NowPlayingContent(state, piece, plan, link is LinkState.Connected, player, playback, onOpenPiano, short)
        }
    }
}

@Composable
private fun ColumnScope.NowPlayingContent(
    state: PlayerState,
    piece: NowPlaying?,
    plan: NotesPlan,
    connected: Boolean,
    player: Player,
    playback: PlaybackStarter,
    onOpenPiano: () -> Unit,
    short: Boolean,
) {
    ScreenHeader("Now playing")
    if (state.loading) ProgressHairline(null)
    state.problem?.let { OutlinedBanner(it, Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) }
    if (piece != null) {
        PieceView(piece, state, plan, connected, player, playback, onOpenPiano, short)
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ColumnScope.PieceView(
    piece: NowPlaying,
    state: PlayerState,
    plan: NotesPlan,
    connected: Boolean,
    player: Player,
    playback: PlaybackStarter,
    onOpenPiano: () -> Unit,
    short: Boolean,
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
    NoteViews(
        plan,
        piece,
        state,
        frame,
        roll,
        player,
        short,
        (if (short) Modifier.height(shortHeight(plan.layout)) else Modifier.weight(1f))
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
    )
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
        hasNext = state.queue.hasNext,
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

/** The fixed height the note views take when the screen scrolls. */
private fun shortHeight(layout: NotesLayout): Dp = when (layout) {
    NotesLayout.STACKED -> SHORT_STAFF + PANEL_GAP + SHORT_ROLL
    else -> SHORT_ROLL
}

/**
 * The note views of [plan] in [modifier]'s room: the roll over its keyboard strip, the staff,
 * or both, each on the elevated surface with the card corners.
 */
@Composable
private fun NoteViews(
    plan: NotesPlan,
    piece: NowPlaying,
    state: PlayerState,
    frame: LongState,
    roll: RollClock,
    player: Player,
    short: Boolean,
    modifier: Modifier,
) {
    val notes: @Composable (Modifier) -> Unit = { panel ->
        Panel(panel) {
            NoteCanvas(piece.notes, state.transpose, state.fold, plan.rollStyle, frame, roll, Modifier.weight(1f).fillMaxWidth())
            HairlineDivider()
            KeyboardStrip(frame, { player.activeKeysLow }, { player.activeKeysHigh })
        }
    }
    val staff: @Composable (Modifier, Boolean) -> Unit = { panel, strip ->
        Panel(panel) {
            StaffCanvas(piece.notes, state.transpose, state.fold, frame, roll, Modifier.weight(1f).fillMaxWidth())
            if (strip) {
                HairlineDivider()
                KeyboardStrip(frame, { player.activeKeysLow }, { player.activeKeysHigh })
            }
        }
    }
    when (plan.layout) {
        NotesLayout.ROLL -> notes(modifier)
        NotesLayout.STAFF -> staff(modifier, true)
        NotesLayout.STACKED -> Column(modifier) {
            staff(if (short) Modifier.height(SHORT_STAFF).fillMaxWidth() else Modifier.weight(1f).fillMaxWidth(), false)
            Spacer(Modifier.height(PANEL_GAP))
            notes(if (short) Modifier.height(SHORT_ROLL).fillMaxWidth() else Modifier.weight(2f).fillMaxWidth())
        }
        // The keyboard strip stays under the roll, not across both views, so every lane still
        // meets its key.
        NotesLayout.SIDE_BY_SIDE -> Row(modifier) {
            staff(Modifier.weight(1f).fillMaxHeight(), false)
            Spacer(Modifier.width(PANEL_GAP))
            notes(Modifier.weight(1f).fillMaxHeight())
        }
    }
}

@Composable
private fun Panel(modifier: Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceVariant),
        content = content,
    )
}

/**
 * The frame time the canvases, keyboard and scrubber draw at: every frame while playing (the
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
