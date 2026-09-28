// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.ui.screens.display

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.stevenjin.stevenpiano.Provenance
import dev.stevenjin.stevenpiano.ble.LinkState
import dev.stevenjin.stevenpiano.data.art.ArtSize
import dev.stevenjin.stevenpiano.graph
import dev.stevenjin.stevenpiano.player.NowPlaying
import dev.stevenjin.stevenpiano.player.PlaybackStatus
import dev.stevenjin.stevenpiano.player.PlayerState
import dev.stevenjin.stevenpiano.settings.NoteDisplay
import dev.stevenjin.stevenpiano.settings.StandbyCanvas
import dev.stevenjin.stevenpiano.ui.ChannelCopy
import dev.stevenjin.stevenpiano.ui.LocalAppFrame
import dev.stevenjin.stevenpiano.ui.components.Eyebrow
import dev.stevenjin.stevenpiano.ui.components.HairlineDivider
import dev.stevenjin.stevenpiano.ui.components.KeyboardStrip
import dev.stevenjin.stevenpiano.ui.components.LiveDot
import dev.stevenjin.stevenpiano.ui.components.NoteCanvas
import dev.stevenjin.stevenpiano.ui.components.PieceArt
import dev.stevenjin.stevenpiano.ui.components.QrTile
import dev.stevenjin.stevenpiano.ui.rememberChannelName
import dev.stevenjin.stevenpiano.ui.screens.nowplaying.RollClock
import dev.stevenjin.stevenpiano.ui.screens.nowplaying.rememberFrameNanos
import dev.stevenjin.stevenpiano.ui.theme.DisplayTheme
import dev.stevenjin.stevenpiano.ui.theme.EyebrowLarge
import dev.stevenjin.stevenpiano.ui.theme.Tabular
import kotlinx.coroutines.delay

/** The portrait behind display mode: this faint. */
private const val BACKDROP_ALPHA = 0.25f

/** Where the backdrop has faded fully into the canvas, as a share of the screen's height. */
private const val BACKDROP_FADED = 0.72f

/**
 * Display mode (DESIGN.md › v1.5 — M17): for passers-by, after a minute without a touch while a
 * piece is loaded. A full-window overlay above everything, the glass bars and the mini player
 * included, never a route: the tabs underneath keep their state. Its canvas is true black in
 * either appearance (the only pure black in the app, through [DisplayTheme]), or with Standby
 * canvas "Same as the app" the app's own surface, ink or paper as the app appears. On it: the
 * piece's art as a faint backdrop fading into the canvas (the composer's portrait, else the
 * piece's roll card; black and white when the person chose that), the title in Display (Display
 * Large on wide screens, a tablet on the piano, with its eyebrow at 16 sp to match), the
 * composer and, while one plays, the channel as an eyebrow, the paper roll and its keyboard strip
 * across the whole width, the live dot with "Sent to piano", and the byline at the foot. No
 * controls: any touch, or back, leaves ([onLeave]); the touch goes no further. The screen stays
 * on and the system bars step aside while it shows. In kiosk mode (DESIGN.md › v1.6 — M20) it is
 * also the resting state with nothing loaded ([DisplayRest]): the byline, and while guests may
 * request, the request page's code; the screen then stays on only as Android's "stay on while
 * plugged in" says (kiosk mode sets it).
 */
@Composable
fun DisplayScreen(onLeave: () -> Unit) {
    val graph = LocalContext.current.graph
    val player = graph.player
    val state by player.state.collectAsStateWithLifecycle()
    val settings by graph.settings.collectAsStateWithLifecycle()
    val link by graph.pianoLink.state.collectAsStateWithLifecycle()
    val piece = state.piece
    val dark = settings.appearance.dark(isSystemInDarkTheme())
    val channel = rememberChannelName(state.channel)

    BackHandler(onBack = onLeave)
    val view = LocalView.current
    val window = LocalActivity.current?.window
    val loaded = piece != null
    DisposableEffect(view, loaded) {
        view.keepScreenOn = loaded   // at rest (kiosk mode), Android's own "stay on while plugged in" decides
        onDispose { view.keepScreenOn = false }
    }
    DisposableEffect(view, window) {
        // The whole screen for the display: the status and navigation bars (a tablet's taskbar
        // among them) step aside while it shows, and come back with a swipe or when it leaves.
        val bars = window?.let { WindowCompat.getInsetsController(it, view) }
        bars?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        bars?.hide(WindowInsetsCompat.Type.systemBars())
        onDispose { bars?.show(WindowInsetsCompat.Type.systemBars()) }
    }
    DisplayTheme(black = settings.standbyCanvas == StandbyCanvas.BLACK, darkTheme = dark) {
        val canvas = MaterialTheme.colorScheme.surface
        Box(
            Modifier
                .fillMaxSize()
                .background(canvas)
                .semantics {
                    contentDescription = if (piece != null) "Display mode: ${piece.title}" else "Display mode"
                    onClick(label = "Leave display mode") {
                        onLeave()
                        true
                    }
                }
                .pointerInput(Unit) {
                    // Any touch leaves, and nothing beneath sees it: the whole gesture is consumed.
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false).consume()
                        onLeave()
                        do {
                            val event = awaitPointerEvent()
                            event.changes.forEach { it.consume() }
                        } while (event.changes.any { it.pressed })
                    }
                },
        ) {
            if (piece == null) {
                DisplayRest()
                return@Box
            }
            PieceArt(
                piece.pieceId,
                piece.composerKey,
                ArtSize.Full,
                Modifier
                    .fillMaxSize()
                    .alpha(BACKDROP_ALPHA),
                framed = false,
            )
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Brush.verticalGradient(0f to Color.Transparent, BACKDROP_FADED to canvas, 1f to canvas)),
            )
            DisplayContent(piece, state, link is LinkState.Connected, channel)
        }
    }
}

/**
 * Kiosk mode's resting state, nothing loaded (DESIGN.md › v1.6 — M20): the canvas, the byline at
 * the foot where display mode has it, and while Web control is on and guests may request, the
 * request page's code in the middle, as the poster has it: "Ask the piano" in Display, "Scan to
 * pick a piece for the piano", the code on its paper card (dark on light, as cameras read best) and
 * its address under it. Nothing moves but the whole, a few dp once a minute ([rememberDrift]), so
 * hours of rest burn nothing into the screen.
 */
@Composable
private fun DisplayRest() {
    val graph = LocalContext.current.graph
    val settings by graph.settings.collectAsStateWithLifecycle()
    val web by graph.web.status.collectAsStateWithLifecycle()
    val url = web.guestUrl?.takeIf { settings.webEnabled && settings.webGuests }
    val wide = LocalAppFrame.current.twoPane
    val drift = rememberDrift()
    Column(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.displayCutout))
            .padding(horizontal = 24.dp, vertical = 16.dp)
            .offset { drift.value },
    ) {
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            if (url != null) RequestCode(url, wide)
        }
        Eyebrow(Provenance.byline, Modifier.align(Alignment.End), maxLines = 1)
    }
}

/** The request page's code at rest: the words, the code as large as the screen allows (at most 280 dp on phones, 360 dp on tablets), the address. */
@Composable
private fun RequestCode(url: String, wide: Boolean) {
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val side = min(min(maxWidth * CODE_WIDTH, maxHeight * CODE_HEIGHT), if (wide) 360.dp else 280.dp)
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "Ask the piano",
                style = if (wide) MaterialTheme.typography.displayLarge else MaterialTheme.typography.displayMedium,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
            Text(
                "Scan to pick a piece for the piano",
                Modifier.padding(top = 8.dp),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(24.dp))
            QrTile(url, side, "QR code for the request page, $url")
            Text(
                url,
                Modifier.padding(top = 16.dp),
                style = MaterialTheme.typography.bodyLarge.merge(Tabular),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** At most this share of the rest's width, and of its height, for the code. */
private const val CODE_WIDTH = 0.7f
private const val CODE_HEIGHT = 0.45f

/** The resting state's shift: a step round a small square, [DRIFT] apart, once every [DRIFT_EVERY_MS]. */
private val DRIFT = 4.dp
private const val DRIFT_EVERY_MS = 60_000L
private val DRIFT_PATH = listOf(0 to 0, 1 to 0, 1 to 1, 0 to 1, -1 to 1, -1 to 0, -1 to -1, 0 to -1)

/** Where the resting state stands now, in pixels: it moves a step of [DRIFT_PATH] each minute, and never animates. */
@Composable
private fun rememberDrift(): State<IntOffset> {
    var step by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(DRIFT_EVERY_MS)
            step = (step + 1) % DRIFT_PATH.size
        }
    }
    val unit = with(LocalDensity.current) { DRIFT.roundToPx() }
    return remember(unit) { derivedStateOf { DRIFT_PATH[step].let { (x, y) -> IntOffset(x * unit, y * unit) } } }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DisplayContent(piece: NowPlaying, state: PlayerState, connected: Boolean, channel: String?) {
    val player = LocalContext.current.graph.player
    val playing = state.status == PlaybackStatus.Playing
    val roll = remember(player) { RollClock(player) }
    val frame = rememberFrameNanos(playing, piece.pieceId, settle = 0, roll = roll)
    // Wide screens (a tablet on the piano, read from a step away) take the larger title, and its eyebrow to match.
    val wide = LocalAppFrame.current.twoPane
    Column(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.displayCutout))
            .padding(horizontal = 24.dp, vertical = 16.dp),
    ) {
        Text(
            piece.title,
            style = if (wide) MaterialTheme.typography.displayLarge else MaterialTheme.typography.displayMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
        val eyebrow = ChannelCopy.eyebrow(piece.composer, channel)
        if (eyebrow.isNotEmpty()) {
            Eyebrow(
                eyebrow,
                Modifier.padding(top = if (wide) 8.dp else 4.dp),
                maxLines = 1,
                style = if (wide) EyebrowLarge else MaterialTheme.typography.labelSmall,
            )
        }
        Spacer(Modifier.height(24.dp))
        NoteCanvas(
            piece.notes,
            state.transpose,
            state.fold,
            NoteDisplay.PAPER_ROLL,
            frame,
            roll,
            Modifier
                .weight(1f)
                .fillMaxWidth(),
            blackKeyLanes = false,   // the notes stand on the portrait, not on bars of black
        )
        HairlineDivider()
        KeyboardStrip(frame, { player.activeKeysLow }, { player.activeKeysHigh }, clock = roll)
        Spacer(Modifier.height(16.dp))
        FlowRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                LiveDot(live = connected, breathing = playing)
                Spacer(Modifier.width(8.dp))
                Text(
                    if (connected) "Sent to piano" else "Not connected",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Eyebrow(Provenance.byline, maxLines = 1)
        }
    }
}
