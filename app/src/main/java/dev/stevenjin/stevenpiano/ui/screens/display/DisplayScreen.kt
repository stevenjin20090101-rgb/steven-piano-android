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
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.stevenjin.stevenpiano.Provenance
import dev.stevenjin.stevenpiano.R
import dev.stevenjin.stevenpiano.ble.LinkState
import dev.stevenjin.stevenpiano.data.art.ArtKey
import dev.stevenjin.stevenpiano.data.art.ArtSize
import dev.stevenjin.stevenpiano.data.db.ArtworkEntity
import dev.stevenjin.stevenpiano.graph
import dev.stevenjin.stevenpiano.player.NowPlaying
import dev.stevenjin.stevenpiano.player.PlaybackStatus
import dev.stevenjin.stevenpiano.player.PlayerState
import dev.stevenjin.stevenpiano.schedule.QuietCopy
import dev.stevenjin.stevenpiano.settings.NoteDisplay
import dev.stevenjin.stevenpiano.settings.StandbyCanvas
import dev.stevenjin.stevenpiano.settings.StandbyShows
import dev.stevenjin.stevenpiano.ui.ChannelCopy
import dev.stevenjin.stevenpiano.ui.LocalAppFrame
import dev.stevenjin.stevenpiano.ui.components.ArtBackdrop
import dev.stevenjin.stevenpiano.ui.components.Eyebrow
import dev.stevenjin.stevenpiano.ui.components.Hairline
import dev.stevenjin.stevenpiano.ui.components.HairlineDivider
import dev.stevenjin.stevenpiano.ui.components.KeyboardStrip
import dev.stevenjin.stevenpiano.ui.components.LiveDot
import dev.stevenjin.stevenpiano.ui.components.NoteCanvas
import dev.stevenjin.stevenpiano.ui.components.OnBackdrop
import dev.stevenjin.stevenpiano.ui.components.PieceArt
import dev.stevenjin.stevenpiano.ui.components.QrTile
import dev.stevenjin.stevenpiano.ui.components.rememberArtworkRow
import dev.stevenjin.stevenpiano.ui.components.rememberBackdrop
import dev.stevenjin.stevenpiano.ui.components.secondaryText
import dev.stevenjin.stevenpiano.ui.rememberChannelName
import dev.stevenjin.stevenpiano.ui.screens.nowplaying.RollClock
import dev.stevenjin.stevenpiano.ui.screens.nowplaying.rememberFrameNanos
import dev.stevenjin.stevenpiano.ui.screens.quiet.LocalPlayAnyway
import dev.stevenjin.stevenpiano.ui.screens.quiet.quietUntil
import dev.stevenjin.stevenpiano.ui.screens.quiet.rememberQuiet
import dev.stevenjin.stevenpiano.ui.theme.Backdrop
import dev.stevenjin.stevenpiano.ui.theme.DisplayTheme
import dev.stevenjin.stevenpiano.ui.theme.EyebrowLarge
import dev.stevenjin.stevenpiano.ui.theme.LocalTertiary
import dev.stevenjin.stevenpiano.ui.theme.Tabular
import dev.stevenjin.stevenpiano.ui.theme.rememberReducedMotion
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first

/** The portrait behind the paper roll: this faint. */
private const val BACKDROP_ALPHA = 0.25f

/** Where the backdrop has faded fully into the canvas, as a share of the screen's height. */
private const val BACKDROP_FADED = 0.72f

/** The resting screen's margins inside the display's cutout, at the sides and at the top and the foot: the title's, and the byline's opposite it. */
private val MARGIN_SIDE = 24.dp
private val MARGIN_TOP_AND_FOOT = 16.dp

/**
 * Display mode, the resting screen (DESIGN.md › v1.5 — M17, v1.7.1): for passers-by, after a minute
 * without a touch while a piece is loaded. A full-window overlay above everything, the glass bars
 * and the mini player included, never a route: the tabs underneath keep their state. Its canvas is
 * true black in either appearance (the only pure black in the app, through [DisplayTheme]), or with
 * Standby canvas "Same as the app" the app's own surface, ink or paper as the app appears.
 *
 * Piano › Display › What it shows chooses what is on it. **Art and notes** (the default,
 * [ArtAndNotes]): the piece's art large and sharp (the composer's portrait, else the piece's roll
 * card; black and white when the person chose that), its title, the composer (and the channel) as
 * an eyebrow, and a few lines about it ([StandbyText]); a new piece cross-fades in; behind them, with
 * Album colours on, the art's colours drift while the piece plays ([ArtBackdrop], v1.15 — M41). **Paper roll**
 * ([PaperRoll], v1.5's): the portrait faint behind the title and the roll over its keyboard. On
 * both, the byline at the top right ([RestingByline]) and the live dot at the foot on the left; no
 * controls. The whole screen steps a few dp once a minute ([rememberDrift]), so hours of the same
 * words burn nothing into the screen.
 *
 * While [resting], any touch, or back, leaves ([onLeave]) and the touch goes no further; the screen
 * stays on and the system bars step aside. Once it starts to fade away ([resting] false; the nav
 * host fades it, [RestingMotion]) it holds what it showed and lets everything through, so the app
 * beneath is live at once. In kiosk mode (DESIGN.md › v1.6.1 — M20) it is also the resting state
 * with nothing loaded: the byline, and while guests may request, the request page's code; the
 * screen then stays on only as Android's "stay on while plugged in" says (kiosk mode sets it).
 *
 * During a quiet time (v1.20 — M54), at the foot opposite the live dot: the moon, "Quiet until 9:30" and Play anyway.
 * A touch there leaves the resting screen as any touch does, and then plays anyway ([LocalPlayAnyway]: behind the
 * kiosk PIN while the kiosk is on).
 */
@Composable
fun DisplayScreen(onLeave: () -> Unit, resting: Boolean = true) {
    val graph = LocalContext.current.graph
    val player = graph.player
    val state by player.state.collectAsStateWithLifecycle()
    val settings by graph.settings.collectAsStateWithLifecycle()
    val link by graph.pianoLink.state.collectAsStateWithLifecycle()
    // While it fades away nothing on it changes: the piece it showed stays until it has gone.
    val piece = heldWhile(!resting, state.piece)
    val dark = settings.appearance.dark(isSystemInDarkTheme())
    val channel = rememberChannelName(state.channel)
    val connected = link is LinkState.Connected
    val playing = state.status == PlaybackStatus.Playing
    // Quiet times (v1.20 — M54): what the foot says while one holds, held as the screen fades away.
    val quiet = heldWhile(!resting, rememberQuiet())
    val until = if (quiet.holds) quietUntil(quiet) else null
    val anyway = remember { AnywayPlace() }
    val playAnyway = LocalPlayAnyway.current
    SideEffect {
        anyway.shown = until != null
        anyway.action = playAnyway
    }

    BackHandler(enabled = resting, onBack = onLeave)
    val view = LocalView.current
    val window = LocalActivity.current?.window
    val loaded = piece != null
    DisposableEffect(view, loaded, resting) {
        view.keepScreenOn = loaded && resting   // at rest (kiosk mode), Android's own "stay on while plugged in" decides
        onDispose { view.keepScreenOn = false }
    }
    DisposableEffect(view, window, resting) {
        // The whole screen for the display: the status and navigation bars (a tablet's taskbar
        // among them) step aside while it rests, and come back with a swipe or as it leaves.
        val bars = window?.takeIf { resting }?.let { WindowCompat.getInsetsController(it, view) }
        bars?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        bars?.hide(WindowInsetsCompat.Type.systemBars())
        onDispose { bars?.show(WindowInsetsCompat.Type.systemBars()) }
    }
    val black = settings.standbyCanvas == StandbyCanvas.BLACK
    val words = remember { WordsPlace() }
    DisplayTheme(black = black, darkTheme = dark) {
        Box(
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surface)
                .onPlaced {
                    words.canvas = it
                    anyway.canvas = it
                }
                .then(if (resting) Modifier.leaveOnTouch(piece?.title, onLeave, anyway) else Modifier),
        ) {
            val drift = rememberDrift()
            val insets = restingInsets(resting)
            val quietLine: (@Composable (Modifier) -> Unit)? = until?.let { shown ->
                { modifier -> RestingQuiet(shown, onLeave, anyway, modifier) }
            }
            if (piece != null && settings.standbyShows == StandbyShows.PAPER_ROLL) {
                PaperRoll(piece, state, connected, playing, channel, insets, drift, quietLine)
            } else {
                // No album colours at rest (v1.19, Steven's choice): the canvas stays plain, black by default. Now playing
                // keeps them (Album colours on Piano › Display).
                val backdrop = rememberBackdrop(piece?.pieceId, piece?.composerKey, on = false)
                ArtBackdrop(
                    backdrop,
                    playing = playing && resting,
                    modifier = Modifier.matchParentSize(),
                    veil = if (black) Backdrop.BlackWordsVeil else Backdrop.veil(dark),
                    resting = true,
                    fadeMs = RestingMotion.PIECE_MS,
                    veilArea = if (black) words.side else null,
                )
                OnBackdrop(backdrop != null) {
                    ArtAndNotes(piece, connected, playing, channel, insets, drift, words, quietLine)
                }
            }
        }
    }
}

/**
 * Where the words stand on the canvas (v1.15 — M41): on the black canvas the backdrop's veil covers their side alone,
 * from their column to the screen's edge (beside the art), or from their top down (under it). Placed as they are laid
 * out, the burn-in step and all.
 */
private class WordsPlace {
    var canvas: LayoutCoordinates? = null
    val side = mutableStateOf(Rect.Zero)

    fun report(words: LayoutCoordinates) {
        val canvas = canvas?.takeIf { it.isAttached && words.isAttached } ?: return
        val box = canvas.localBoundingBoxOf(words)
        val width = canvas.size.width.toFloat()
        val height = canvas.size.height.toFloat()
        side.value = when {
            box.center.x > width * BESIDE_SHARE -> Rect(box.left, 0f, width, height)          // beside the art, after it
            box.center.x < width * (1f - BESIDE_SHARE) -> Rect(0f, 0f, box.right, height)   // beside it, right to left
            else -> Rect(0f, box.top, width, height)                                        // under it
        }
    }
}

/** Words whose middle stands past this share of the width stand beside the art. */
private const val BESIDE_SHARE = 0.55f

/**
 * While resting: any touch leaves, and nothing beneath sees it (the whole gesture is consumed);
 * TalkBack reads the piece and offers the same as a click. A touch on Play anyway, while a quiet
 * time shows it ([anyway]), plays anyway once it has left (v1.20 — M54).
 */
private fun Modifier.leaveOnTouch(title: String?, onLeave: () -> Unit, anyway: AnywayPlace): Modifier = this
    .semantics {
        contentDescription = if (title != null) "Resting screen: $title" else "Resting screen"
        onClick(label = "Leave the resting screen") {
            onLeave()
            true
        }
    }
    .pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            down.consume()
            onLeave()
            if (anyway.hit(down.position)) anyway.action()
            do {
                val event = awaitPointerEvent()
                event.changes.forEach { it.consume() }
            } while (event.changes.any { it.pressed })
        }
    }

/**
 * What the resting screen keeps clear of, as padding: the display's cutout. The system bars step
 * aside while it rests (a swipe brings them over it for a moment), so it never follows them, and
 * nothing on it moves as they slide away when it comes or back when it goes; once it fades away
 * ([resting] false) it holds what it had.
 */
@Composable
private fun restingInsets(resting: Boolean): PaddingValues {
    val insets = WindowInsets.displayCutout
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    val now = with(density) {
        PaddingValues.Absolute(
            left = insets.getLeft(density, direction).toDp(),
            top = insets.getTop(density).toDp(),
            right = insets.getRight(density, direction).toDp(),
            bottom = insets.getBottom(density).toDp(),
        )
    }
    return heldWhile(!resting, now)
}

/** [value], or while [hold] the value it had when the hold began. */
@Composable
private fun <T> heldWhile(hold: Boolean, value: T): T {
    val held = remember { Held(value) }
    if (!hold) held.value = value
    return held.value
}

private class Held<T>(var value: T)

/**
 * Art and notes (DESIGN.md › v1.7.1): the frame, inside the margins and drifting as one: the
 * byline at the top right, the live dot alone at the foot on the left, and between them the piece
 * ([PieceAtRest]), which a new piece replaces in a cross-fade ([RestingMotion.pieceChange]); in
 * kiosk mode with nothing loaded, the request page's code there instead, or nothing.
 */
@Composable
private fun ArtAndNotes(
    piece: NowPlaying?,
    connected: Boolean,
    playing: Boolean,
    channel: String?,
    insets: PaddingValues,
    drift: State<IntOffset>,
    words: WordsPlace,
    quiet: (@Composable (Modifier) -> Unit)?,
) {
    val reduced = rememberReducedMotion()
    val twoPane = LocalAppFrame.current.twoPane
    // The byline's two lines and a gap, kept clear above the piece and, so it stands in the middle, below it.
    val band = with(LocalDensity.current) { (MaterialTheme.typography.labelSmall.lineHeight * 2).toDp() } + MARGIN_TOP_AND_FOOT
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val window = DpSize(maxWidth, maxHeight)
        Box(
            Modifier
                .fillMaxSize()
                .padding(insets)
                .padding(horizontal = MARGIN_SIDE, vertical = MARGIN_TOP_AND_FOOT)
                .offset { drift.value },
        ) {
            AnimatedContent(
                targetState = piece,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(vertical = band),
                transitionSpec = { RestingMotion.pieceChange(reduced) },
                contentAlignment = Alignment.Center,
                label = "resting piece",
                contentKey = { it?.pieceId },
            ) { shown ->
                BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    val room = DpSize(maxWidth, maxHeight)
                    if (shown != null) PieceAtRest(shown, channel, twoPane, window, room, words) else RequestAtRest(twoPane)
                }
            }
            RestingByline(Modifier.align(Alignment.TopEnd))
            AnimatedVisibility(
                visible = piece != null,
                modifier = Modifier.align(Alignment.BottomStart),
                enter = RestingMotion.pieceChange(reduced).targetContentEnter,
                exit = RestingMotion.pieceChange(reduced).initialContentExit,
                label = "live dot",
            ) {
                // The dot alone, without its words (DESIGN.md › v1.7.1); TalkBack still says them.
                LiveDot(
                    live = connected,
                    breathing = playing,
                    modifier = Modifier.semantics { contentDescription = if (connected) "Sent to piano" else "Not connected" },
                )
            }
            quiet?.invoke(Modifier.align(Alignment.BottomEnd))
        }
    }
}

/**
 * The piece at rest: the art (the composer's portrait, else the piece's roll card) at the left of
 * the words in a window wider than it is tall, over them in one taller than it is wide
 * ([RestingLayout]); the title (Display Large on wide frames, [twoPane], Display on phones), the
 * composer and the channel as an eyebrow, and the description ([StandbyText]) in Body and the
 * secondary ink, six lines at most on wide frames and four on phones, with "From Wikipedia · CC BY-SA
 * 4.0" one line under it when the text is Wikipedia's. A piece whose own notes were never looked up
 * has them asked for once, at low priority, while artwork is fetched by itself ([StandbyText.asksForOwnNotes]):
 * the composer's show meanwhile, and the piece's take their place when they come. Its album cover (v1.15 —
 * M40) is asked for first in line, when Album covers is on and it has none.
 */
@Composable
private fun PieceAtRest(piece: NowPlaying, channel: String?, twoPane: Boolean, window: DpSize, room: DpSize, place: WordsPlace) {
    val graph = LocalContext.current.graph
    val settings by graph.settings.collectAsStateWithLifecycle()
    val fetchAutomatically = settings.fetchArtworkAutomatically
    LaunchedEffect(piece.pieceId, fetchAutomatically) {
        if (!fetchAutomatically) return@LaunchedEffect
        graph.artwork.requestCover(piece.pieceId)   // the playing piece's cover first (v1.15 — M40)
        // The table as it is read (not the first frame's empty guess), so a piece already looked up is never asked for.
        val row = graph.artwork.artwork(ArtworkEntity.forPiece(piece.pieceId)).first()
        if (StandbyText.asksForOwnNotes(row, fetchAutomatically)) {
            graph.artwork.request(ArtKey.Piece(piece.pieceId, piece.title, piece.composer), priority = false)
        }
    }
    val own = rememberArtworkRow(ArtworkEntity.forPiece(piece.pieceId))
    val composer = rememberArtworkRow(ArtworkEntity.forComposer(piece.composerKey))
    val notes = StandbyText.notes(own, composer)
    val eyebrow = ChannelCopy.eyebrow(piece.composer, channel)
    val beside = RestingLayout.sideBySide(window)
    val art = RestingLayout.artSide(beside, window, room)
    val words = RestingLayout.wordsWidth(beside, room.width, art)
    if (beside) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PieceArt(piece.pieceId, piece.composerKey, ArtSize.Full, Modifier.size(art), title = piece.title)
            Spacer(Modifier.width(RestingLayout.sideGap(art)))
            Column(Modifier.width(words).onGloballyPositioned(place::report)) {
                Words(piece.title, eyebrow, notes, twoPane, TextAlign.Start)
            }
        }
    } else {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            PieceArt(piece.pieceId, piece.composerKey, ArtSize.Full, Modifier.size(art), title = piece.title)
            Spacer(Modifier.height(if (twoPane) 32.dp else 24.dp))
            Column(
                Modifier
                    .width(words)
                    .weight(1f, fill = false)
                    .onGloballyPositioned(place::report),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Words(piece.title, eyebrow, notes, twoPane, TextAlign.Center)
            }
        }
    }
}

/**
 * The title, the eyebrow, the description and its credit (one line, kept); the description gives way
 * first when the room runs short (a large font).
 */
@Composable
private fun ColumnScope.Words(title: String, eyebrow: String, notes: StandbyText.Notes?, wide: Boolean, align: TextAlign) {
    Text(
        title,
        style = if (wide) MaterialTheme.typography.displayLarge else MaterialTheme.typography.displayMedium,
        color = MaterialTheme.colorScheme.onSurface,
        maxLines = 3,
        overflow = TextOverflow.Ellipsis,
        textAlign = align,
    )
    if (eyebrow.isNotEmpty()) {
        Eyebrow(
            eyebrow,
            Modifier.padding(top = if (wide) 8.dp else 4.dp),
            maxLines = 1,
            style = if (wide) EyebrowLarge else MaterialTheme.typography.labelSmall,
        )
    }
    if (notes != null) {
        Text(
            notes.text,
            Modifier
                .padding(top = if (wide) 16.dp else 12.dp)
                .weight(1f, fill = false),
            style = MaterialTheme.typography.bodyLarge,
            color = secondaryText(),   // the primary colour over the album-colour backdrop (v1.15 — M41)
            maxLines = StandbyText.maxLines(wide),
            overflow = TextOverflow.Ellipsis,
            textAlign = align,
        )
        notes.credit?.let { credit ->
            Eyebrow(
                credit,
                Modifier.padding(top = if (wide) 12.dp else 8.dp),
                uppercase = false,
                maxLines = 1,
                style = MaterialTheme.typography.labelSmall.merge(textAlign = align),
            )
        }
    }
}

/**
 * The resting screen's byline (DESIGN.md › v1.7.1): "PLAYER PIANO" over "MADE BY STEVEN JIN" in the
 * eyebrow, set to the right, at the top right inside the margins the title keeps at the top left.
 */
@Composable
private fun RestingByline(modifier: Modifier = Modifier) {
    Column(modifier.semantics(mergeDescendants = true) {}, horizontalAlignment = Alignment.End) {
        Provenance.restingByline.forEach { line -> Eyebrow(line, maxLines = 1) }
    }
}

/**
 * Kiosk mode's resting state, nothing loaded (DESIGN.md › v1.6.1 — M20): with Web control on and
 * guests allowed to request, the request page's code, as the poster has it: "Ask the piano" in
 * Display, "Scan to pick a piece for the piano", the code on its paper card (dark on light, as
 * cameras read best) and its address under it; otherwise nothing but the byline.
 */
@Composable
private fun RequestAtRest(wide: Boolean) {
    val graph = LocalContext.current.graph
    val settings by graph.settings.collectAsStateWithLifecycle()
    val web by graph.web.status.collectAsStateWithLifecycle()
    val url = web.guestUrl?.takeIf { settings.webEnabled && settings.webGuests } ?: return
    RequestCode(url, wide)
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

/** The resting screen's shift: a step round a small square, [DRIFT] apart, once every [DRIFT_EVERY_MS]. */
private val DRIFT = 4.dp
private const val DRIFT_EVERY_MS = 60_000L
private val DRIFT_PATH = listOf(0 to 0, 1 to 0, 1 to 1, 0 to 1, -1 to 1, -1 to 0, -1 to -1, 0 to -1)

/** Where the resting screen stands now, in pixels: it moves a step of [DRIFT_PATH] each minute, and never animates. */
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

/**
 * Paper roll (v1.5 — M17's display, Standby shows "Paper roll"): the composer's portrait faint
 * behind everything, fading into the canvas towards the foot; the title in Display (Display Large
 * on wide screens, a tablet on the piano, with its eyebrow at 16 sp to match) with the byline at the
 * top right opposite it; the composer and the channel as an eyebrow; the paper roll across the
 * whole width over its keyboard strip; the live dot with "Sent to piano" at the foot.
 */
@Composable
private fun PaperRoll(
    piece: NowPlaying,
    state: PlayerState,
    connected: Boolean,
    playing: Boolean,
    channel: String?,
    insets: PaddingValues,
    drift: State<IntOffset>,
    quiet: (@Composable (Modifier) -> Unit)?,
) {
    val canvas = MaterialTheme.colorScheme.surface
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
    val player = LocalContext.current.graph.player
    val roll = remember(player) { RollClock(player) }
    val frame = rememberFrameNanos(playing, piece.pieceId, settle = 0, roll = roll)
    // Wide screens (a tablet on the piano, read from a step away) take the larger title, and its eyebrow to match.
    val wide = LocalAppFrame.current.twoPane
    Column(
        Modifier
            .fillMaxSize()
            .padding(insets)
            .padding(horizontal = MARGIN_SIDE, vertical = MARGIN_TOP_AND_FOOT)
            .offset { drift.value },
    ) {
        Row(Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
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
            }
            Spacer(Modifier.width(BYLINE_GAP))
            RestingByline()
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
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            LiveDot(live = connected, breathing = playing)
            Spacer(Modifier.width(8.dp))
            Text(
                if (connected) "Sent to piano" else "Not connected",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (quiet != null) {
                Spacer(Modifier.weight(1f))
                quiet(Modifier)
            }
        }
    }
}

/**
 * Where Play anyway stands on the resting screen (v1.20 — M54), in the canvas's own coordinates, as it is laid out;
 * whether it [shown] now, and what it does ([action]). Read by the touch that leaves.
 */
private class AnywayPlace {
    var canvas: LayoutCoordinates? = null
    var shown = false
    var action: () -> Unit = {}
    private var bounds = Rect.Zero

    fun report(pill: LayoutCoordinates) {
        val canvas = canvas?.takeIf { it.isAttached && pill.isAttached } ?: return
        bounds = canvas.localBoundingBoxOf(pill)
    }

    /** Whether a touch at [at] (the canvas's coordinates) landed on Play anyway while it shows. */
    fun hit(at: Offset): Boolean = shown && bounds.contains(at)
}

/**
 * The resting screen's quiet time (v1.20 — M54): the moon and "Quiet until 9:30" in the secondary ink, and Play anyway
 * as an outlined pill. Its touch is the resting screen's ([leaveOnTouch]); TalkBack offers Play anyway as an action.
 */
@Composable
private fun RestingQuiet(until: String, onLeave: () -> Unit, anyway: AnywayPlace, modifier: Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Icon(painterResource(R.drawable.ic_moon), contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(8.dp))
        Text(QuietCopy.capsule(until), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        Spacer(Modifier.width(12.dp))
        Box(
            Modifier
                .heightIn(min = 48.dp)
                .onGloballyPositioned(anyway::report)
                .semantics {
                    role = Role.Button
                    onClick(label = QuietCopy.PLAY_ANYWAY) {
                        onLeave()
                        anyway.action()
                        true
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                QuietCopy.PLAY_ANYWAY,
                Modifier
                    .border(Hairline, LocalTertiary.current, CircleShape)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
        }
    }
}

/** Between the paper roll's title and the byline opposite it. */
private val BYLINE_GAP: Dp = 16.dp
