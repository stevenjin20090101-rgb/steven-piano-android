// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.ui.screens.nowplaying

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.stevenjin.stevenpiano.R
import dev.stevenjin.stevenpiano.ble.LinkState
import dev.stevenjin.stevenpiano.data.art.ArtSize
import dev.stevenjin.stevenpiano.graph
import dev.stevenjin.stevenpiano.player.NowPlaying
import dev.stevenjin.stevenpiano.player.PlaybackStatus
import dev.stevenjin.stevenpiano.player.Player
import dev.stevenjin.stevenpiano.player.PlayerState
import dev.stevenjin.stevenpiano.settings.NoteDisplay
import dev.stevenjin.stevenpiano.ui.ChannelCopy
import dev.stevenjin.stevenpiano.ui.NotesLayout
import dev.stevenjin.stevenpiano.ui.NotesPlan
import dev.stevenjin.stevenpiano.ui.LocalFloatingPadding
import dev.stevenjin.stevenpiano.ui.PlaybackStarter
import dev.stevenjin.stevenpiano.ui.components.ConnectionLine
import dev.stevenjin.stevenpiano.ui.components.Eyebrow
import dev.stevenjin.stevenpiano.ui.components.GlassHeaderPane
import dev.stevenjin.stevenpiano.ui.components.GlyphButton
import dev.stevenjin.stevenpiano.ui.components.KeyboardStripHeight
import dev.stevenjin.stevenpiano.ui.components.Hairline
import dev.stevenjin.stevenpiano.ui.components.OutlinedBanner
import dev.stevenjin.stevenpiano.ui.components.PieceArt
import dev.stevenjin.stevenpiano.ui.components.ProgressHairline
import dev.stevenjin.stevenpiano.ui.components.RollStrip
import dev.stevenjin.stevenpiano.ui.components.RollStripHeight
import dev.stevenjin.stevenpiano.ui.components.glassAvailable
import dev.stevenjin.stevenpiano.ui.components.screenHeaderHeight
import dev.stevenjin.stevenpiano.ui.rememberChannelName
import dev.stevenjin.stevenpiano.ui.screens.piece.PieceDetailSheet
import dev.stevenjin.stevenpiano.ui.screens.schedule.NextScheduleLine

/** The connection line's row at the panel's foot (declared before PANEL_FIXED, which counts it). */
private val CONNECTION_ROW = 40.dp

/** The panel's art: at most this, centred, and smaller where the pane is short. */
private val MAX_ART = 320.dp
private val MIN_ART = 96.dp

/** What the panel needs besides its art: the header row, the words, the strip at its smallest, the solid transport and the connection line. */
private val PANEL_FIXED = 48.dp + 104.dp + RollStripHeight + TransportHeight + 24.dp + CONNECTION_ROW

/** Below this height the panel scrolls rather than squeezing. */
private val PANEL_ROOMY = 560.dp

/** The panel's strip as the float rule sees it: a paper roll alone. */
private val StripPlan = NotesPlan(NotesLayout.ROLL, NoteDisplay.PAPER_ROLL)

/**
 * The now-playing panel beside the Library's list on wide frames (DESIGN.md › v1.5 — M16): the
 * queue glyph (Up next) at the top, the piece's art (its composer's portrait, else its roll card;
 * at most 320 dp, centred, smaller where the pane is short), the title in Title (it opens the
 * piece sheet), the composer as an eyebrow (and the channel while one plays) with "STARTING" under
 * it during the pause before the piece, the live [RollStrip], then the scrubber and the transport:
 * on glass over the strip's history where it can hold them (the composition Now playing uses),
 * solid under the strip where it cannot; and at its foot, in a 40 dp row at the end edge, the
 * connection line Now playing shows ("● Sent to piano", or not connected: it opens the Piano tab,
 * [onOpenPiano]). With nothing loaded: "Choose a piece from the library." Its NOW PLAYING row is the
 * pane's glass header ([GlassHeaderPane], DESIGN.md › v1.9), as tall as the list's beside it.
 * It reuses Now playing's clock ([RollClock], [rememberFrameNanos]) and plays through [playback].
 * Its sheets' state lives where the panel is composed, apart from Now playing's.
 */
@Composable
fun NowPlayingPanel(playback: PlaybackStarter, onOpenPiano: () -> Unit, modifier: Modifier = Modifier) {
    val graph = LocalContext.current.graph
    val player = graph.player
    val state by player.state.collectAsStateWithLifecycle()
    val link by graph.pianoLink.state.collectAsStateWithLifecycle()
    // Remembered where the panel is composed: apart from Now playing's sheets and from the phone's layout.
    var upNext by rememberSaveable { mutableStateOf(false) }
    var about by rememberSaveable { mutableStateOf<Long?>(null) }
    val piece = state.piece
    // The NOW PLAYING row is the pane's glass header (DESIGN.md › v1.9), level with the list's beside it.
    val header: @Composable () -> Unit = {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = screenHeaderHeight())
                .padding(start = 16.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Eyebrow("Now playing", Modifier.weight(1f), maxLines = 1)
            if (piece != null) GlyphButton(R.drawable.ic_queue, "Up next") { upNext = true }
        }
    }
    // Nothing scrolls beneath it: the panel's own column starts below it (and scrolls inside itself when short).
    GlassHeaderPane(scroll = null, modifier = modifier, header = header) { Column(Modifier.fillMaxSize().padding(top = LocalFloatingPadding.current.calculateTopPadding())) {
        if (state.loading) ProgressHairline(null)
        state.problem?.let { OutlinedBanner(it, Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) }
        StudioReviewBanner(Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        if (piece != null) {
            PanelPiece(piece, state, player, playback, link is LinkState.Connected, onOpenPiano, onAbout = { about = piece.pieceId })
        } else if (!state.loading) {
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                contentAlignment = Alignment.Center,
            ) {
                // The next schedule, when one is ahead, over the empty line (DESIGN.md › v1.6.2 — M19).
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    NextScheduleLine(Modifier.padding(bottom = 8.dp), centred = true)
                    Text(
                        "Choose a piece from the library.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    } }
    if (upNext) UpNextSheet { upNext = false }
    about?.let { PieceDetailSheet(it) { about = null } }
}

@Composable
private fun ColumnScope.PanelPiece(
    piece: NowPlaying,
    state: PlayerState,
    player: Player,
    playback: PlaybackStarter,
    connected: Boolean,
    onOpenPiano: () -> Unit,
    onAbout: () -> Unit,
) {
    val playing = state.status == PlaybackStatus.Playing
    val roll = remember(player) { RollClock(player) }
    var settle by remember { mutableIntStateOf(0) }
    val frame = rememberFrameNanos(playing, piece.pieceId, settle, roll)
    val starting by remember(roll, frame, playing) { derivedStateOf { playing && roll.positionAt(frame.longValue) < 0L } }
    val seek: (Long) -> Unit = {
        player.seek(it)
        if (!playing) settle++
    }
    val controls: @Composable ColumnScope.() -> Unit = {
        TransportControls(piece, state, frame, roll, player, playback, onSeek = seek, onMoved = { if (!playing) settle++ })
    }
    val strip: @Composable ColumnScope.() -> Unit = {
        RollStrip(
            piece.notes, state.transpose, state.fold, frame, roll,
            { player.activeKeysLow }, { player.activeKeysHigh },
            Modifier.fillMaxSize(),
        )
    }
    BoxWithConstraints(
        Modifier
            .weight(1f)
            .fillMaxWidth(),
    ) {
        val roomy = maxHeight >= PANEL_ROOMY
        val art = min(min(MAX_ART, maxWidth - 32.dp), if (roomy) maxHeight - PANEL_FIXED else MAX_ART).coerceAtLeast(MIN_ART)
        Column(
            (if (roomy) Modifier.fillMaxSize() else Modifier.fillMaxSize().verticalScroll(rememberScrollState()))
                .padding(horizontal = 16.dp),
        ) {
            PieceArt(
                piece.pieceId,
                piece.composerKey,
                ArtSize.Full,
                Modifier
                    .align(Alignment.CenterHorizontally)
                    .size(art),
            )
            Spacer(Modifier.height(16.dp))
            PanelTitle(piece, rememberChannelName(state.channel), starting, onAbout)
            Spacer(Modifier.height(12.dp))
            if (roomy) {
                // The strip takes what is left; the controls float on its history when it can hold them.
                BoxWithConstraints(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .heightIn(min = RollStripHeight),
                ) {
                    if (glassAvailable() && transportFloats(StripPlan, maxHeight)) {
                        GlassTransportPanel(Modifier.fillMaxSize(), stripHeight = KeyboardStripHeight + Hairline, panel = strip, controls = controls)
                    } else {
                        Column(Modifier.fillMaxSize()) {
                            Panel(Modifier.weight(1f).fillMaxWidth(), strip)
                            controls()
                        }
                    }
                }
            } else {
                Panel(Modifier.height(RollStripHeight).fillMaxWidth(), strip)
                controls()
            }
            // Where the piece goes, as Now playing says it at its bottom right: "● Sent to piano"; and at the
            // start the tablet's speaker (v1.8 — M25), as at the end of Now playing's tempo row.
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(CONNECTION_ROW),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TabletSoundSpeaker(Modifier.wrapContentHeight(unbounded = true))
                // Its 48 dp target (it opens the Piano tab when not connected) reaches past the 40 dp row.
                ConnectionLine(connected, state.status == PlaybackStatus.Playing, onOpenPiano, Modifier.wrapContentHeight(unbounded = true))
            }
            // The row is the panel's foot: it takes the place of the 8 dp that closed the column, so the
            // strip keeps the height that lets the transport float on its history.
        }
    }
}

/**
 * The title in Title (it opens the piece sheet), the composer eyebrow (with the channel while one
 * plays: "CLAUDE DEBUSSY · CALM · CHANNEL") and, during the pause before the piece, STARTING.
 */
@Composable
private fun PanelTitle(piece: NowPlaying, channel: String?, starting: Boolean, onAbout: () -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            piece.title,
            modifier = Modifier.clickable(onClickLabel = "About this piece", onClick = onAbout),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        val eyebrow = ChannelCopy.eyebrow(piece.composer, channel)
        if (eyebrow.isNotEmpty()) Eyebrow(eyebrow, maxLines = 1)
        StartingLine(starting)
    }
}
