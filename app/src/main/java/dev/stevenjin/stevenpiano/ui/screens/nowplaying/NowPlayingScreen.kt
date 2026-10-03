// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.ui.screens.nowplaying

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.LongState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import androidx.compose.ui.unit.min
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.stevenjin.stevenpiano.R
import dev.stevenjin.stevenpiano.ble.LinkState
import dev.stevenjin.stevenpiano.data.art.ArtSize
import dev.stevenjin.stevenpiano.graph
import dev.stevenjin.stevenpiano.player.NowPlaying
import dev.stevenjin.stevenpiano.player.PlaybackLimits
import dev.stevenjin.stevenpiano.player.PlaybackStatus
import dev.stevenjin.stevenpiano.player.Player
import dev.stevenjin.stevenpiano.player.PlayerState
import dev.stevenjin.stevenpiano.ui.ChannelCopy
import dev.stevenjin.stevenpiano.ui.Format
import dev.stevenjin.stevenpiano.ui.LocalAppFrame
import dev.stevenjin.stevenpiano.ui.LocalFloatingPadding
import dev.stevenjin.stevenpiano.ui.NotesLayout
import dev.stevenjin.stevenpiano.ui.NotesPlan
import dev.stevenjin.stevenpiano.ui.PlaybackStarter
import dev.stevenjin.stevenpiano.ui.rememberChannelName
import dev.stevenjin.stevenpiano.ui.components.ArtBackdrop
import dev.stevenjin.stevenpiano.ui.components.ConnectionLine
import dev.stevenjin.stevenpiano.ui.components.Eyebrow
import dev.stevenjin.stevenpiano.ui.components.GlassEdge
import dev.stevenjin.stevenpiano.ui.components.GlassHeaderPane
import dev.stevenjin.stevenpiano.ui.components.GlassSurface
import dev.stevenjin.stevenpiano.ui.components.GlyphButton
import dev.stevenjin.stevenpiano.ui.components.HairlineDivider
import dev.stevenjin.stevenpiano.ui.components.Hairline
import dev.stevenjin.stevenpiano.ui.components.Immersive
import dev.stevenjin.stevenpiano.ui.components.ImmersiveBars
import dev.stevenjin.stevenpiano.ui.components.KeyHands
import dev.stevenjin.stevenpiano.ui.components.KeyboardStrip
import dev.stevenjin.stevenpiano.ui.components.KeyboardStripHeight
import dev.stevenjin.stevenpiano.ui.components.LocalImmersive
import dev.stevenjin.stevenpiano.ui.components.NoteCanvas
import dev.stevenjin.stevenpiano.ui.components.OutlinedBanner
import dev.stevenjin.stevenpiano.ui.components.PieceArt
import dev.stevenjin.stevenpiano.ui.components.ProgressHairline
import dev.stevenjin.stevenpiano.ui.components.ScreenHeader
import dev.stevenjin.stevenpiano.ui.components.ScorePages
import dev.stevenjin.stevenpiano.ui.components.SplitAxis
import dev.stevenjin.stevenpiano.ui.components.SplitPane
import dev.stevenjin.stevenpiano.ui.components.StepperControl
import dev.stevenjin.stevenpiano.ui.components.rememberBackdrop
import dev.stevenjin.stevenpiano.ui.components.scrollEdges
import dev.stevenjin.stevenpiano.ui.components.secondaryText
import dev.stevenjin.stevenpiano.ui.screens.piece.PieceDetailSheet
import dev.stevenjin.stevenpiano.ui.screens.schedule.NextScheduleLine
import dev.stevenjin.stevenpiano.ui.theme.Motion
import dev.stevenjin.stevenpiano.ui.theme.NowPlayingComposer
import dev.stevenjin.stevenpiano.ui.theme.NowPlayingStripTitle
import dev.stevenjin.stevenpiano.ui.theme.NowPlayingTitle
import dev.stevenjin.stevenpiano.ui.theme.rememberReducedMotion
import kotlinx.coroutines.launch

/**
 * Below this height the screen scrolls, and the note views get fixed heights; the stacked views
 * need more. The score's fixed height holds one system.
 */
private val SHORT_BELOW = 520.dp
private val SHORT_BELOW_STACKED = 780.dp
private val SHORT_ROLL = 240.dp
private val SHORT_SCORE = 200.dp

/**
 * The cover (v1.18 — M49): beside the roll, a column this wide (the cover its width, less where the screen is short or
 * narrow) this far from the roll; in the score's strip, this side; alone (Art only), at most this and this share of
 * the height; in the scroll (phones, short screens), at most this, and this share of the screen's height.
 */
private val COLUMN = 400.dp
private val COLUMN_GAP = 28.dp
private val STRIP_COVER = 84.dp
private val ART_ONLY_MAX = 520.dp
private const val ART_ONLY_SHARE = 0.56f
private val SCROLL_COVER_MAX = 360.dp
private const val SCROLL_COVER_SHARE = 0.8f

/** The cover's corners (the strip's smaller) and the deep soft shadow it stands on. */
private val COVER_CORNERS = 24.dp
private val STRIP_CORNERS = 14.dp
private val COVER_SHADOW = 24.dp

/** The score's strip: the transport at its end this wide, or under the words where the strip is narrower than [STRIP_BESIDE]. */
private val STRIP_TRANSPORT = 400.dp
private val STRIP_BESIDE = 720.dp

/** In the scroll the views take the screen's height less the foot's row, never less than their fixed heights. */
private val FOOT_ROOM = 64.dp

/**
 * The signature screen, over the cover (DESIGN.md › v1.18 — M49): the piece's art large, its title and composer, the
 * scrubber and the transport, the note views as the View menu says ([ViewMenu]: the score, the notes, both, or neither),
 * and at its foot the tempo, the tablet's speaker and the connection line in glass capsules. Behind it all, edge to
 * edge, under the header and the rail or the tab bar too, the cover itself blurred and slowly turning ([ArtBackdrop]);
 * while it shows the screen is immersive ([Immersive]): light words, black glass, the roll without its card over the
 * backdrop, the score on its opaque sheet. Without it (Album colours off, a roll card for art, high contrast) the same
 * layouts on the plain surface.
 *
 * On wide frames, the notes without the score: a column 400 dp wide (the cover at its width, smaller where the height
 * is short), the title (40 sp, two lines), the composer, the scrubber and the transport, and the roll filling the rest
 * at the right over its keyboard strip. The score with the notes (or alone): a strip at the top (the cover at 84 dp,
 * the title, the composer, the transport at its end) over the score's sheet and the roll, sharing the room as the
 * divider says (DESIGN.md › v1.12: [SplitPane]). Art only: the cover as large as fits (at most 56 % of the height and
 * 520 dp), centred, the words and the controls beneath it. On phones, and wherever the screen is too short for those,
 * one scroll: the cover as wide as the screen less its margins (at most 360 dp), the words, the scrubber and the
 * transport, then the views. Tapping a bar of the score seeks there, as the scrubber does. The transport goes through
 * [playback], which keeps the playback service running; the queue glyph in the header opens the Up next sheet, and the
 * title and the cover open the piece sheet. [onOpenPiano] shows the Piano tab. The screen stops above the tab bar
 * ([LocalFloatingPadding]) and below its header, a glass navigation bar ([GlassHeaderPane], DESIGN.md › v1.9) that only
 * a short screen's scroll passes beneath, and never over the backdrop.
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
    // The playing piece's album cover first in line (v1.15 — M40), for the mini player and every screen that shows it.
    LaunchedEffect(piece?.pieceId) { piece?.let { graph.artwork.requestCover(it.pieceId) } }
    val plan = frame.notesPlan(settings.noteDisplay, settings.notesSplitStacked, settings.notesSplitSide, settings.notesArtOnly)
    var upNext by rememberSaveable { mutableStateOf(false) }
    var about by rememberSaveable { mutableStateOf<Long?>(null) }
    // The status bar, before the header's glass takes the top: the short rule measures what is below it, as before.
    val outer = LocalFloatingPadding.current
    val statusBar = outer.calculateTopPadding()
    val column = rememberScrollState()
    // The cover behind everything, edge to edge (v1.18 — M49); while it shows, what stands on it is immersive.
    val backdrop = rememberBackdrop(piece?.pieceId, piece?.composerKey, settings.albumBackdrop)
    val immersive = backdrop != null
    DisposableEffect(immersive) {
        ImmersiveBars.on = immersive
        onDispose { ImmersiveBars.on = false }
    }
    Box(Modifier.fillMaxSize()) {
        ArtBackdrop(backdrop, state.status == PlaybackStatus.Playing, Modifier.matchParentSize())
        Immersive(immersive) {
            // The header is a glass navigation bar (DESIGN.md › v1.9); only a short screen's column scrolls beneath it,
            // and over the backdrop not even that. The whole pane keeps clear of the rail, its header too.
            GlassHeaderPane(
                scroll = column,
                modifier = Modifier.padding(outer.sides()),
                header = {
                    ScreenHeader("Now playing") {
                        if (piece != null) {
                            ViewMenu(settings, plan, frame)   // v1.12 — M31a
                            GlyphButton(R.drawable.ic_queue, "Up next", onClick = { upNext = true })
                        }
                    }
                },
            ) {
                val floating = LocalFloatingPadding.current
                val top = floating.calculateTopPadding()
                BoxWithConstraints(
                    Modifier
                        .fillMaxSize()
                        .padding(bottom = floating.calculateBottomPadding()),
                ) {
                    // Too short for the wide layouts (landscape, a small tablet at a large font), and on every phone: one
                    // scroll, the cover first, the views below it at fixed heights.
                    val available = maxHeight - statusBar
                    val short = piece != null && available < if (plan.layout == NotesLayout.STACKED && !plan.artOnly) SHORT_BELOW_STACKED else SHORT_BELOW
                    val scrolls = piece != null && (short || !frame.wide)
                    // The divider (and a hidden pane's grabber at its edge) only where both views would fit unscrolled (v1.12 — M31a).
                    val divided = !scrolls && when (plan.axis) {
                        SplitAxis.Stacked -> available >= SHORT_BELOW_STACKED
                        SplitAxis.SideBySide -> true
                        null -> false
                    }
                    // Over the backdrop the scroll starts below the header and never passes beneath it.
                    val body = when {
                        !scrolls -> Modifier.fillMaxSize().padding(top = top)
                        immersive -> Modifier.fillMaxSize().padding(top = top).clipToBounds().verticalScroll(column)
                        else -> Modifier.fillMaxSize().scrollEdges(column).verticalScroll(column)
                    }
                    val view = PieceLayout(plan, scrolls, divided, viewport = maxHeight - top)
                    Column(body) {
                        if (scrolls && !immersive) Spacer(Modifier.height(top))
                        val marks = Marks(fingering = settings.fingering, chordNames = settings.chordNames)
                        NowPlayingContent(state, piece, view, marks, link is LinkState.Connected, player, playback, onOpenPiano) { about = it }
                    }
                }
            }
        }
    }
    if (upNext) UpNextSheet { upNext = false }
    about?.let { PieceDetailSheet(it) { about = null } }
}

/** How the piece is laid out: its [plan], whether the screen [scrolls], whether the views share a [divided] room, and the scroll's visible height. */
@Immutable
private class PieceLayout(val plan: NotesPlan, val scrolls: Boolean, val divided: Boolean, val viewport: Dp)

@Composable
private fun ColumnScope.NowPlayingContent(
    state: PlayerState,
    piece: NowPlaying?,
    view: PieceLayout,
    marks: Marks,
    connected: Boolean,
    player: Player,
    playback: PlaybackStarter,
    onOpenPiano: () -> Unit,
    onAbout: (Long) -> Unit,
) {
    if (state.loading) ProgressHairline(null)
    state.problem?.let { OutlinedBanner(it, Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) }
    StudioReviewBanner(Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
    if (piece != null) {
        PieceView(piece, state, view, marks, connected, player, playback, onOpenPiano) { onAbout(piece.pieceId) }
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
}

/** The piece in the layout [view] says (v1.18 — M49), then the foot's capsules and the tablet sound's download line. */
@Composable
private fun ColumnScope.PieceView(
    piece: NowPlaying,
    state: PlayerState,
    view: PieceLayout,
    marks: Marks,
    connected: Boolean,
    player: Player,
    playback: PlaybackStarter,
    onOpenPiano: () -> Unit,
    onAbout: () -> Unit,
) {
    val playing = state.status == PlaybackStatus.Playing
    val roll = remember(player) { RollClock(player) }
    var settle by remember { mutableIntStateOf(0) }
    val frame = rememberFrameNanos(playing, piece.pieceId, settle, roll)
    // The pause before the piece: playing, and the music not reached yet.
    val starting by remember(roll, frame, playing) { derivedStateOf { playing && roll.positionAt(frame.longValue) < 0L } }
    val channel = rememberChannelName(state.channel)
    val plan = view.plan
    // Every seek (the scrubber, a bar of the score) silences the piano first; paused, the picture catches up.
    val seek: (Long) -> Unit = {
        player.seek(it)
        if (!playing) settle++
    }
    val cover: @Composable (Modifier) -> Unit = { modifier -> Cover(piece, onAbout, modifier) }
    val words: @Composable (TextStyle) -> Unit = { title -> PieceWords(piece, channel, starting, title, onAbout) }
    val controls: @Composable ColumnScope.(Boolean) -> Unit = { scrubber ->
        TransportControls(piece, state, frame, roll, player, playback, onSeek = seek, onMoved = { if (!playing) settle++ }, scrubber = scrubber, inset = 0.dp)
    }
    val views: @Composable (Modifier) -> Unit = { modifier ->
        NoteViews(plan, piece, state, marks, frame, roll, player, view.scrolls, view.divided, seek, modifier)
    }
    when {
        view.scrolls -> InTheScroll(piece, plan, view.viewport, cover, words, controls, views)
        plan.artOnly -> ArtOnly(piece, cover, words, controls)
        plan.layout == NotesLayout.ROLL -> BesideTheRoll(piece, cover, words, controls, views)
        else -> UnderTheStrip(piece, onAbout, words, controls, views)
    }
    FootRow(state, connected, playing, player, onOpenPiano)
    TabletSoundDownloadNote(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp))
}

/**
 * The notes without the score (wide frames): a column at the start, [COLUMN] wide at most ([CoverStack]: the cover, as
 * large as the height leaves it, over the title, the composer, the scrubber and the transport, all as wide as the
 * cover), and the roll filling the rest at the end, the hidden score's grabber at its edge.
 */
@Composable
private fun ColumnScope.BesideTheRoll(
    piece: NowPlaying,
    cover: @Composable (Modifier) -> Unit,
    words: @Composable (TextStyle) -> Unit,
    controls: @Composable ColumnScope.(Boolean) -> Unit,
    views: @Composable (Modifier) -> Unit,
) {
    BoxWithConstraints(
        Modifier
            .weight(1f)
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
    ) {
        val most = min(COLUMN, (maxWidth - COLUMN_GAP) / 2)
        val height = maxHeight
        Row(Modifier.fillMaxSize()) {
            CoverStack(piece, most, height, NowPlayingTitle, cover, words, controls)
            Spacer(Modifier.width(COLUMN_GAP))
            views(
                Modifier
                    .weight(1f)
                    .fillMaxHeight(),
            )
        }
    }
}

/**
 * The score with the notes, or alone (wide frames): a strip at the top (the cover at [STRIP_COVER], the title, the
 * composer; the transport at its end, or under them where the strip is narrow) over the views, sharing the room.
 */
@Composable
private fun ColumnScope.UnderTheStrip(
    piece: NowPlaying,
    onAbout: () -> Unit,
    words: @Composable (TextStyle) -> Unit,
    controls: @Composable ColumnScope.(Boolean) -> Unit,
    views: @Composable (Modifier) -> Unit,
) {
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
    ) {
        val beside = maxWidth >= STRIP_BESIDE
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Cover(piece, onAbout, Modifier.size(STRIP_COVER), corners = STRIP_CORNERS, art = ArtSize.Tile)
                Spacer(Modifier.width(18.dp))
                Box(Modifier.weight(1f)) { words(NowPlayingStripTitle) }
                if (beside) {
                    Spacer(Modifier.width(18.dp))
                    Column(Modifier.width(STRIP_TRANSPORT)) { controls(false) }
                }
            }
            if (!beside) controls(false)
        }
    }
    Spacer(Modifier.height(14.dp))
    views(
        Modifier
            .weight(1f)
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
    )
}

/**
 * Neither view (Art only, wide frames): the cover as large as fits, at most [ART_ONLY_MAX] and [ART_ONLY_SHARE] of the
 * height (and what the words and the controls beneath it leave), centred, the words and the controls as wide as it.
 */
@Composable
private fun ColumnScope.ArtOnly(
    piece: NowPlaying,
    cover: @Composable (Modifier) -> Unit,
    words: @Composable (TextStyle) -> Unit,
    controls: @Composable ColumnScope.(Boolean) -> Unit,
) {
    BoxWithConstraints(
        Modifier
            .weight(1f)
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        CoverStack(piece, min(min(ART_ONLY_MAX, maxHeight * ART_ONLY_SHARE), maxWidth), maxHeight, NowPlayingTitle, cover, words, controls)
    }
}

/**
 * Phones, and screens too short for the wide layouts: one scroll, the cover as wide as the screen less its margins (at
 * most [SCROLL_COVER_MAX], and [SCROLL_COVER_SHARE] of the screen's height), the words, the scrubber and the transport,
 * then the views below it (none under Art only).
 */
@Composable
private fun ColumnScope.InTheScroll(
    piece: NowPlaying,
    plan: NotesPlan,
    viewport: Dp,
    cover: @Composable (Modifier) -> Unit,
    words: @Composable (TextStyle) -> Unit,
    controls: @Composable ColumnScope.(Boolean) -> Unit,
    views: @Composable (Modifier) -> Unit,
) {
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.TopCenter,
    ) {
        CoverStack(piece, min(min(SCROLL_COVER_MAX, maxWidth), viewport * SCROLL_COVER_SHARE), Dp.Infinity, NowPlayingStripTitle, cover, words, controls)
    }
    if (!plan.artOnly) {
        Spacer(Modifier.height(16.dp))
        views(
            Modifier
                .height(scrollHeight(plan.layout, viewport))
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
        )
    }
}

/**
 * The cover over its words and controls, all one width: the cover's side, at most [most] and what [height] leaves above
 * the words in [title]'s style and the controls ([coverSide]), then the title, the composer, the scrubber and the
 * transport. Top-aligned: what the height leaves over is below it.
 */
@Composable
private fun CoverStack(
    piece: NowPlaying,
    most: Dp,
    height: Dp,
    title: TextStyle,
    cover: @Composable (Modifier) -> Unit,
    words: @Composable (TextStyle) -> Unit,
    controls: @Composable ColumnScope.(Boolean) -> Unit,
) {
    val side = coverSide(piece.title, title, most, height)
    Column(Modifier.width(side)) {
        cover(Modifier.size(side))
        Spacer(Modifier.height(WORDS_GAP))
        words(title)
        Spacer(Modifier.height(CONTROLS_GAP))
        controls(true)
    }
}

/**
 * The cover's side over the words and the controls in [height]: at most [most], and what the height leaves above them
 * (the gaps, the title's lines in [style], the composer's, STARTING's and [TransportHeight]); the title is given two
 * lines unless it fits in one at that side, measured.
 */
@Composable
private fun coverSide(title: String, style: TextStyle, most: Dp, height: Dp): Dp {
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val eyebrow = MaterialTheme.typography.labelSmall
    val line = with(density) { style.lineHeight.toDp() }
    val rest = with(density) { WORDS_GAP + 4.dp + NowPlayingComposer.lineHeight.toDp() + 2.dp + eyebrow.lineHeight.toDp() + CONTROLS_GAP + TransportHeight }
    val oneLine = min(most, height - rest - line).coerceAtLeast(0.dp)
    val fits = remember(title, style, oneLine, density, measurer) {
        val width = with(density) { oneLine.roundToPx() }
        width > 0 && measurer.measure(title, style, constraints = Constraints(maxWidth = width), density = density).lineCount <= 1
    }
    return if (fits) oneLine else min(most, height - rest - line * 2).coerceAtLeast(0.dp)
}

/** Between the cover and the title, and between the words and the scrubber. */
private val WORDS_GAP = 22.dp
private val CONTROLS_GAP = 14.dp

/**
 * The piece's art (its own cover, else its composer's portrait, else its roll card) in [modifier]'s square, with
 * [corners] and a deep soft shadow; a tap opens the piece sheet. Screen readers skip it and reach the sheet through the title.
 */
@Composable
private fun Cover(piece: NowPlaying, onAbout: () -> Unit, modifier: Modifier, corners: Dp = COVER_CORNERS, art: ArtSize = ArtSize.Full) {
    val shape = RoundedCornerShape(corners)
    // The art's frame draws in the theme's card shape: these corners for it.
    MaterialTheme(colorScheme = MaterialTheme.colorScheme, typography = MaterialTheme.typography, shapes = MaterialTheme.shapes.copy(medium = shape)) {
        PieceArt(
            piece.pieceId,
            piece.composerKey,
            art,
            modifier
                .shadow(COVER_SHADOW, shape)
                .clearAndSetSemantics { }
                .clickable(onClick = onAbout),
            title = piece.title,
        )
    }
}

/**
 * The title in [title]'s style, two lines at most (it opens the piece sheet), the composer under it (and while a
 * channel plays its name: "Claude Debussy · Calm · Channel"), and STARTING during the pause before the piece.
 */
@Composable
private fun PieceWords(piece: NowPlaying, channel: String?, starting: Boolean, title: TextStyle, onAbout: () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Text(
            piece.title,
            modifier = Modifier.clickable(onClickLabel = "About this piece", onClick = onAbout),
            style = title,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        val line = ChannelCopy.eyebrow(piece.composer, channel)
        if (line.isNotEmpty()) {
            Text(
                line,
                modifier = Modifier.padding(top = 4.dp),
                style = NowPlayingComposer,
                color = secondaryText(),   // the light words over the backdrop
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        StartingLine(starting, Modifier.padding(top = 2.dp))
    }
}

/**
 * The foot's row in glass capsules (v1.18 — M49): Tempo with its stepper and the tablet's speaker (v1.8 — M25) at the
 * start, "Sent to piano" at the end; over the backdrop black glass, otherwise the bars' glass without a blur.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FootRow(state: PlayerState, connected: Boolean, playing: Boolean, player: Player, onOpenPiano: () -> Unit) {
    FlowRow(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(CAPSULE_GAP), verticalAlignment = Alignment.CenterVertically) {
            Capsule {
                Row(Modifier.padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Eyebrow("Tempo")
                    Spacer(Modifier.width(4.dp))
                    StepperControl(state.tempoPct, PlaybackLimits.TempoPct, TEMPO_STEP, Format::percent, "Slower", "Faster", player::setTempo, rolling = true)
                }
            }
            Capsule { TabletSoundSpeaker() }
        }
        Capsule { ConnectionLine(connected, playing, onOpenPiano, Modifier.padding(horizontal = 8.dp)) }
    }
}

/** A capsule of glass round [content], which sets its height (48 dp targets). */
@Composable
private fun Capsule(content: @Composable () -> Unit) {
    GlassSurface(shape = CircleShape, edge = GlassEdge.Outline, blur = false) { content() }
}

private val CAPSULE_GAP = 12.dp

/** The height the views take in the scroll: a stacked pair their fixed heights; one view the screen's height less the foot's row. */
private fun scrollHeight(layout: NotesLayout, viewport: Dp): Dp = when (layout) {
    NotesLayout.STACKED -> SHORT_SCORE + PANEL_GAP + SHORT_ROLL
    NotesLayout.SCORE -> max(SHORT_SCORE + Hairline + KeyboardStripHeight, viewport - FOOT_ROOM)
    else -> max(SHORT_ROLL, viewport - FOOT_ROOM)
}

/**
 * The note views of [plan] in [modifier]'s room: the roll over its keyboard strip on its card ([RollPanel]: none over
 * the backdrop), the score on its sheet ([ScoreSheet], always opaque), or both. [onSeek] is a tap on a bar. [divided]
 * (wide frames with room, v1.12 — M31a): the two share a [SplitPane] whose divider the person drags, a hidden pane's
 * grabber waiting at its edge; the share it settles on is remembered for the arrangement. Otherwise (in the scroll)
 * they keep fixed heights.
 */
@Composable
private fun NoteViews(
    plan: NotesPlan,
    piece: NowPlaying,
    state: PlayerState,
    marks: Marks,
    frame: LongState,
    roll: RollClock,
    player: Player,
    scrolls: Boolean,
    divided: Boolean,
    onSeek: (Long) -> Unit,
    modifier: Modifier,
) {
    val graph = LocalContext.current.graph
    val hands = piece.handsOrNull
    val fingers = if (marks.fingering) piece.fingersFor(state.transpose, state.fold) else null
    val chords = if (marks.chordNames) piece.chords else null
    // Which hand each sounding key belongs to, for the strip's outlined left-hand keys.
    val keyHands = remember(piece.notes, hands, state.transpose, state.fold) {
        hands?.let { KeyHands(piece.notes, it, state.transpose, state.fold) }
    }
    val rollCard: @Composable ColumnScope.() -> Unit = {
        NoteCanvas(
            piece.notes, state.transpose, state.fold, plan.rollStyle, frame, roll, Modifier.weight(1f).fillMaxWidth(),
            hands = hands, fingers = fingers, chords = chords,
        )
        // Over the backdrop the roll's own light line is where its notes land: no hairline before the keys.
        if (!LocalImmersive.current) HairlineDivider()
        KeyboardStrip(frame, { player.activeKeysLow }, { player.activeKeysHigh }, hands = keyHands, clock = roll)
    }
    val notes: @Composable (Modifier) -> Unit = { panel -> RollPanel(panel, rollCard) }
    val score: @Composable (Modifier, Boolean) -> Unit = { panel, strip ->
        ScoreSheet(panel) {
            ScorePages(
                notes = piece.notes,
                tempo = piece.tempoMap,
                bars = piece.barStartsMicros,
                keySignatures = piece.keySignatures,
                timeSignatures = piece.timeSignatures,
                transpose = state.transpose,
                fold = state.fold,
                frameNanos = frame,
                clock = roll,
                onSeek = onSeek,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                hands = hands,
                fingers = fingers,
                chords = chords,
            )
            if (strip) {
                HairlineDivider()
                KeyboardStrip(frame, { player.activeKeysLow }, { player.activeKeysHigh }, hands = keyHands, clock = roll)
            }
        }
    }
    val axis = plan.axis
    if (divided && axis != null) {
        // The keyboard strip stays under the roll, not across both views, so every lane still meets its key; with
        // the roll hidden it comes under the score.
        SplitPane(
            axis = axis,
            share = plan.split,
            onShare = { share -> graph.appScope.launch { graph.settingsRepository.setNotesSplit(axis == SplitAxis.Stacked, share) } },
            first = { panel, alone -> score(panel, alone) },
            second = { panel, _ -> notes(panel) },
            modifier = modifier,
        )
        return
    }
    when (plan.layout) {
        NotesLayout.ROLL -> notes(modifier)
        NotesLayout.SCORE -> score(modifier, true)
        NotesLayout.STACKED -> Column(modifier) {
            score(if (scrolls) Modifier.height(SHORT_SCORE).fillMaxWidth() else Modifier.weight(plan.split).fillMaxWidth(), false)
            Spacer(Modifier.height(PANEL_GAP))
            notes(if (scrolls) Modifier.height(SHORT_ROLL).fillMaxWidth() else Modifier.weight(1f - plan.split).fillMaxWidth())
        }
        NotesLayout.SIDE_BY_SIDE -> Row(modifier) {
            score(Modifier.weight(plan.split).fillMaxHeight(), false)
            Spacer(Modifier.width(PANEL_GAP))
            notes(Modifier.weight(1f - plan.split).fillMaxHeight())
        }
    }
}

private const val TEMPO_STEP = 5

/**
 * "STARTING" while the pause before a piece runs (DESIGN.md › v1.5 — M16): an eyebrow in the
 * tertiary grey under the composer, fading in and out over 120 ms (a cut when motion is reduced).
 * Its line is always kept, so nothing below it moves as it comes and goes.
 */
@Composable
internal fun StartingLine(starting: Boolean, modifier: Modifier = Modifier) {
    val reduced = rememberReducedMotion()
    Box(modifier) {
        Eyebrow(STARTING, Modifier.alpha(0f).clearAndSetSemantics { }, maxLines = 1)
        AnimatedVisibility(
            visible = starting,
            enter = if (reduced) EnterTransition.None else fadeIn(tween(Motion.FastMs)),
            exit = if (reduced) ExitTransition.None else fadeOut(tween(Motion.FastMs)),
        ) {
            Eyebrow(STARTING, maxLines = 1)
        }
    }
}

private const val STARTING = "Starting"

/** The floating padding at the sides alone (the rail's, the cutout's): the pane keeps clear of them. */
private fun PaddingValues.sides(): PaddingValues = PaddingValues(
    start = calculateStartPadding(LayoutDirection.Ltr),
    end = calculateEndPadding(LayoutDirection.Ltr),
)

/** What the note views show beside the notes, as the View menu's switches say. */
private data class Marks(val fingering: Boolean, val chordNames: Boolean)
