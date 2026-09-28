// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================


package dev.stevenjin.stevenpiano.ui.components

import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.SuggestionChipDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.LongState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.stevenjin.stevenpiano.R
import dev.stevenjin.stevenpiano.midi.KeyMap
import dev.stevenjin.stevenpiano.midi.KeySignature
import dev.stevenjin.stevenpiano.midi.NoteList
import dev.stevenjin.stevenpiano.midi.TempoMap
import dev.stevenjin.stevenpiano.midi.TimeSignature
import dev.stevenjin.stevenpiano.score.Accidental
import dev.stevenjin.stevenpiano.score.Beams
import dev.stevenjin.stevenpiano.score.ChordTrack
import dev.stevenjin.stevenpiano.score.Dynamics
import dev.stevenjin.stevenpiano.score.Head
import dev.stevenjin.stevenpiano.score.PageTurn
import dev.stevenjin.stevenpiano.score.Rests
import dev.stevenjin.stevenpiano.score.ScoreLayout
import dev.stevenjin.stevenpiano.score.ScoreLayoutEngine
import dev.stevenjin.stevenpiano.score.ScoreMetrics
import dev.stevenjin.stevenpiano.score.ScoreSystem
import dev.stevenjin.stevenpiano.score.ScoreWidth
import dev.stevenjin.stevenpiano.score.Sign
import dev.stevenjin.stevenpiano.score.TempoMark
import dev.stevenjin.stevenpiano.ui.theme.LocalHairline
import dev.stevenjin.stevenpiano.ui.theme.LocalNoteSounding
import dev.stevenjin.stevenpiano.ui.theme.LocalTertiary
import dev.stevenjin.stevenpiano.ui.theme.Motion
import dev.stevenjin.stevenpiano.ui.theme.Tabular
import dev.stevenjin.stevenpiano.ui.theme.rememberReducedMotion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Staff line spacing: the score's unit. SMuFL fonts are drawn at four spaces to the em. */
private val LineGap = 6.dp
private val CursorWidth = 2.dp

/** A ledger line runs this far past each side of its head. */
private val LedgerOverhang = 2.dp

/** A final bar line's thick stroke, and the gap before it. */
private val FinalStroke = 3.dp
private val FinalGap = 2.dp

/** The score's fingering numerals follow the font scale up to this, then keep their size with the heads. */
private const val NUMERAL_MAX_SCALE = 1.3f

/** A chord name keeps this far above the bar number, and from the name before it. */
private val ChordGap = 2.dp
private val ChordSpacing = 6.dp

/** A horizontal drag this long turns the page. */
private val SwipeDistance = 40.dp

/** Bravura's G clef rises this many spaces above the treble staff's top line; a bar number sits clear of it. */
private const val G_CLEF_RISE = 1.4f
private val NumberClearance = 1.dp

/** Bravura (SMuFL) glyphs. */
private const val G_CLEF = ""
private const val F_CLEF = ""
private const val SHARP = ""
private const val FLAT = ""
private const val NATURAL = ""
private const val WHOLE_HEAD = ""
private const val HALF_HEAD = ""
private const val BLACK_HEAD = ""
private const val FLAG_8TH_UP = ""
private const val FLAG_8TH_DOWN = ""
private const val FLAG_16TH_UP = ""
private const val FLAG_16TH_DOWN = ""
private const val DOT = ""
private const val TIME_DIGIT_0 = 0xE080
private const val REST_WHOLE = "\uE4E3"
private const val REST_HALF = "\uE4E4"
private const val REST_QUARTER = "\uE4E5"
private const val REST_8TH = "\uE4E6"
private const val REST_16TH = "\uE4E7"

/** The tempo mark's note: Bravura's quarter note, stem up (U+E1D5), dotted with U+E1E7 in a compound metre. */
private const val TEMPO_NOTE = "\uE1D5"

/**
 * The tempo mark's note is set in Bravura at this multiple of the bar number's (eyebrow) size, so it
 * grows with the text; its head sits on the text's baseline (the head reaches 0.564 of its space
 * below its own baseline), and the glyph is 1.328 spaces wide, the dot 0.4.
 */
private const val TEMPO_NOTE_SCALE = 1.5f
private const val TEMPO_HEAD_BELOW = 0.564f
private const val TEMPO_NOTE_RISE = 3.5f
private const val TEMPO_NOTE_WIDTH = 1.328f
private const val TEMPO_DOT_GAP = 0.3f
private const val TEMPO_DOT_WIDTH = 0.4f
private const val TEMPO_TEXT_GAP = 0.6f

/**
 * A tempo mark (and a chord name) keeps this far (in staff spaces) above the notes under it: stems,
 * flags, beams, heads and their accidentals, as the layout's skyline has them.
 */
private const val TEMPO_CLEARANCE = 0.5f

/** A tie rises this share of its length, held to 0.3 to 0.9 of a staff space. */
private const val TIE_RISE = 0.15f
private const val TIE_LOWEST = 0.3f
private const val TIE_HIGHEST = 0.9f

/** Bravura, the SMuFL reference music font (SIL Open Font Licence; see AUTHORS). */
private val Bravura = FontFamily(Font(R.font.bravura))

/**
 * The score (DESIGN.md › v1.2 › Score): the piece as systems of bars on pages, one page, or two
 * side by side when the panel is 840 dp wide or more, laid out by [ScoreLayoutEngine] from the
 * file's tempo map, bars and signatures ([keySignatures] are the file's; [transpose] moves them
 * with the notes), each note on its hand's staff when the [hands] are known, the suggested
 * [fingers] as small numerals above the right hand's heads and below the left's, and the [chords]'
 * names on a line of their own above each system, where each chord begins (DESIGN.md › v1.3).
 * Staff lines and bar lines are the tertiary grey; clefs, signatures, notes and their beams, rests,
 * ties, tempo marks and dynamics the secondary colour (DESIGN.md › v1.3 › Score fidelity); bar
 * numbers eyebrows above each system. A 2 dp cursor in the content colour moves through the current
 * system, and sounding notes (and the heads tied to them, as the cursor reaches each) turn the
 * sounding yellow ([LocalNoteSounding], DESIGN.md › v1.5 — M16) with the roll's 120 ms flip (a cut
 * when motion is reduced), then settle back to the secondary grey; beams, ties and rests stay grey.
 * Pages turn by themselves so the cursor is always in sight ([PageTurn]).
 *
 * Agency: a horizontal swipe looks at other pages, and a Follow chip then waits at the top right;
 * tapping it, or the next turn the music makes, follows again. Tapping a bar seeks to it
 * ([onSeek], which silences the piano first like any seek).
 *
 * Work is kept off the frame: glyphs are measured once on the main thread; the layout is computed
 * on a background thread whenever the notes, transpose, folding or the panel change; each visible
 * page is drawn into its own cached layer that never reads the frame clock (its beams and ties built
 * as paths once, with the layer); only the overlay (cursor and sounding notes) redraws per frame,
 * without allocating. A piece too large to lay out (the heap runs out, or the engine fails) leaves
 * the panel saying "This score is too large to show." instead of taking the app down, and a layout
 * is only ever drawn with the notes it was made for. A layout moments after another for the same
 * piece (transpose tapped up and up, the fingering following it, the panel resizing) waits
 * [RELAYOUT_SETTLE_MS] first, and a layout replaced while it runs stops at its next pass; a page's
 * build reads the layout's skylines for the tempo mark and chord names and draws at most
 * [MAX_NOTE_DRAWS] heads, rests, numerals, beams and ties a system, so the main thread's work per
 * page is bounded whatever the file (the v1.3 delta audit, L1 and L2).
 */
@Composable
fun ScorePages(
    notes: NoteList,
    tempo: TempoMap,
    bars: LongArray,
    keySignatures: List<KeySignature>,
    timeSignatures: List<TimeSignature>,
    transpose: Int,
    fold: Boolean,
    width: ScoreWidth,
    frameNanos: LongState,
    clock: SongClock,
    onSeek: (micros: Long) -> Unit,
    modifier: Modifier = Modifier,
    hands: ByteArray? = null,
    fingers: ByteArray? = null,
    chords: ChordTrack? = null,
) {
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val numberStyle = MaterialTheme.typography.labelSmall.merge(Tabular)
    val glyphs = remember(measurer, density, numberStyle) { ScoreGlyphs(measurer, density, numberStyle) }
    val numberHeight = remember(measurer, density, numberStyle) {
        measurer.measure("0", numberStyle, maxLines = 1, density = density).size.height.toFloat()
    }
    // Fingering numerals: the eyebrow's size, tabular, untracked (one digit each). They belong to the
    // heads, which keep their size at any font scale, so they grow with the text only up to
    // NUMERAL_MAX_SCALE: at twice the size a sixteenth's numeral ran into the next one's.
    val numeralScale = min(density.fontScale, NUMERAL_MAX_SCALE) / density.fontScale
    val numeralStyle = numberStyle.merge(TextStyle(letterSpacing = 0.sp, fontSize = numberStyle.fontSize * numeralScale))
    val numerals = remember(measurer, density, numeralStyle) { Numerals(measurer, density, numeralStyle) }
    // Chord names: body text on a line of their own, reserved above each system's bar-number line.
    val chordStyle = MaterialTheme.typography.bodyMedium
    val shownChords = chords?.takeIf { it.size > 0 }
    val chordNames = remember(shownChords, transpose) { shownChords?.let { c -> Array(c.size) { c.name(it, transpose) } } }
    val chordHeight = remember(measurer, density, chordStyle, numberStyle, numberHeight, shownChords != null) {
        if (shownChords == null) {
            0f
        } else {
            val line = measurer.measure("C", chordStyle, maxLines = 1, density = density).size.height.toFloat()
            val numberBaseline = measurer.measure("0", numberStyle, maxLines = 1, density = density).firstBaseline
            // The bar number rises past its own line (over the G clef): the chord line sits clear above it.
            val numberTop = with(density) { G_CLEF_RISE * LineGap.toPx() + NumberClearance.toPx() } + numberBaseline
            line + max(0f, numberTop - numberHeight) + with(density) { ChordGap.toPx() }
        }
    }
    val colors = ScoreColors(
        upcoming = MaterialTheme.colorScheme.onSurfaceVariant,
        sounding = LocalNoteSounding.current,
        cursor = MaterialTheme.colorScheme.onSurface,
        line = LocalTertiary.current,
        glyph = MaterialTheme.colorScheme.onSurfaceVariant,
        number = LocalTertiary.current,
        spine = LocalHairline.current,
    )
    val reduced = rememberReducedMotion()
    BoxWithConstraints(modifier.clipToBounds()) {
        val panelWidth = constraints.maxWidth.toFloat()
        val panelHeight = constraints.maxHeight.toFloat()
        val metrics = remember(width, panelWidth, panelHeight, density, glyphs, numberHeight, numerals, chordHeight) {
            ScoreMetrics.forPanel(
                width, panelWidth, panelHeight, density.density, glyphs.headWidth, glyphs.clefWidth, numberHeight,
                numeralHeight = numerals.height, numeralWidth = numerals.width, chordHeight = chordHeight,
            )
        }
        val laid by produceState<Laid?>(null, notes, tempo, bars, keySignatures, timeSignatures, transpose, fold, metrics, hands, fingers) {
            if (value?.notes !== notes) value = null   // never another piece's pages under this one's cursor
            // Another layout of the piece shown: wait for a burst of changes to end (a newer one cancels this one here).
            if (value != null) delay(RELAYOUT_SETTLE_MS)
            value = layOut(notes, Dispatchers.Default) { checkpoint ->
                val keys = IntArray(notes.size) { KeyMap.map(notes.note(it), transpose, fold) }
                val keysMoved = keySignatures.map { it.transposed(transpose) }
                val handsHere = hands?.takeIf { it.size == notes.size }
                val fingersHere = fingers?.takeIf { it.size == notes.size && handsHere != null }
                ScoreLayoutEngine.layout(notes, keys, tempo, bars, keysMoved, metrics, timeSignatures, handsHere, fingersHere, checkpoint)
            }
        }
        val chordText = remember(shownChords, chordNames, chordStyle) { if (shownChords != null && chordNames != null) ChordText(shownChords, chordNames, chordStyle) else null }
        // Only a layout made for these notes: for a frame after a change of piece the state still holds the last one's.
        val shown = laid.madeFor(notes)
        if (shown != null) {
            val layout = shown.layout
            if (layout != null) {
                ScoreView(layout, shown.notes, glyphs, numerals, chordText, colors, measurer, numberStyle, frameNanos, clock, reduced, onSeek)
            } else {
                TooLarge()
            }
        }
    }
}

/** The panel when the piece is too large to lay out: one line of Body text, centred, in the secondary colour. */
@Composable
private fun TooLarge() {
    Box(
        Modifier
            .fillMaxSize()
            .padding(16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            SCORE_TOO_LARGE,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun ScoreView(
    layout: ScoreLayout,
    notes: NoteList,
    glyphs: ScoreGlyphs,
    numerals: Numerals,
    chordText: ChordText?,
    colors: ScoreColors,
    measurer: TextMeasurer,
    numberStyle: TextStyle,
    frameNanos: LongState,
    clock: SongClock,
    reduced: Boolean,
    onSeek: (Long) -> Unit,
) {
    val metrics = layout.metrics
    val density = LocalDensity.current
    val painter = remember(glyphs, numerals, density) { ScorePainter(glyphs, numerals, density) }
    val slotLeft = remember(metrics) { FloatArray(metrics.pages) { metrics.slotLeft(it).roundToInt().toFloat() } }
    // The system sounding, read per frame; everything below it changes only when it does.
    val cursorSystem by remember(layout, clock) { derivedStateOf { layout.systemAt(clock.positionAt(frameNanos.longValue)) } }
    val follow = remember(layout, cursorSystem) {
        PageTurn.pagesShown(cursorSystem, layout.systems.size, metrics.systemsPerPage, metrics.pages).toList()
    }
    // A page looked at by hand (its first page), until Follow or the music's next turn.
    var browsing by remember(layout) { mutableStateOf<Int?>(null) }
    LaunchedEffect(follow) { browsing = null }
    val shown = browsing?.let { PageTurn.browsing(it, layout.pageCount, metrics.pages).toList() } ?: follow
    val shownNow = rememberUpdatedState(shown)
    val cursorNow = rememberUpdatedState(cursorSystem)
    val seek by rememberUpdatedState(onSeek)

    /** The first page of the spread [delta] spreads from what is shown, or null when there is none that way. */
    fun turned(delta: Int): Int? {
        val pages = metrics.pages
        val from = browsing ?: (cursorNow.value / metrics.systemsPerPage).let { it - it % pages }
        val target = (from + delta * pages).coerceIn(0, layout.pageCount - 1).let { it - it % pages }
        return target.takeIf { PageTurn.browsing(it, layout.pageCount, pages).toList() != shownNow.value }
    }

    val description = shown.filter { it >= 0 }.map { it + 1 }.let { visible ->
        val pages = if (visible.size == 2) "Pages ${visible[0].coerceAtMost(visible[1])} and ${visible[0].coerceAtLeast(visible[1])}" else "Page ${visible.firstOrNull() ?: 1}"
        "$pages of ${layout.pageCount}" + if (browsing != null) ", not following" else ""
    }
    Box(
        Modifier
            .fillMaxSize()
            .semantics {
                contentDescription = "Score"
                stateDescription = description
                customActions = listOfNotNull(
                    turned(1)?.let { CustomAccessibilityAction("Next page") { browsing = it; true } },
                    turned(-1)?.let { CustomAccessibilityAction("Previous page") { browsing = it; true } },
                    if (browsing != null) CustomAccessibilityAction("Follow the music") { browsing = null; true } else null,
                )
            }
            .pointerInput(layout) {
                val threshold = SwipeDistance.toPx()
                var total = 0f
                detectHorizontalDragGestures(
                    onDragStart = { total = 0f },
                    onDragEnd = {
                        if (abs(total) >= threshold) turned(if (total < 0) 1 else -1)?.let { browsing = it }
                    },
                ) { change, dx ->
                    change.consume()
                    total += dx
                }
            }
            .pointerInput(layout) {
                detectTapGestures { tap ->
                    val slot = if (metrics.pages == 2 && tap.x >= slotLeft[1]) 1 else 0
                    val page = shownNow.value.getOrElse(slot) { -1 }
                    if (page < 0) return@detectTapGestures
                    val system = layout.systemsOn(page).minByOrNull { distance(tap.y, layout.systems[it], metrics.numberHeight) }
                        ?: return@detectTapGestures
                    seek(layout.bars.startMicros[layout.systems[system].barAtX(tap.x - slotLeft[slot])])
                }
            },
    ) {
        val pageSize = with(density) { Modifier.size(metrics.pageWidth.toDp(), metrics.pageHeight.toDp()) }
        for (slot in 0 until metrics.pages) {
            PageLayer(
                layout,
                shown.getOrElse(slot) { -1 },
                painter,
                chordText,
                colors,
                measurer,
                numberStyle,
                Modifier
                    .offset { IntOffset(slotLeft[slot].toInt(), 0) }
                    .then(pageSize),
            )
        }
        if (metrics.pages == 2) {
            // The spine between two pages: a hairline down the middle of the gap.
            Spacer(
                Modifier
                    .fillMaxSize()
                    .drawBehind {
                        val x = slotLeft[1] - metrics.pageGap / 2
                        val inset = metrics.firstSystemTop
                        drawRect(colors.spine, Offset(x, inset), Size(painter.hair, size.height - 2 * inset))
                    },
            )
        }
        // The overlay: the only layer that reads the frame clock.
        Spacer(
            Modifier
                .fillMaxSize()
                .graphicsLayer()
                .drawWithCache {
                    val ramp = colorRamp(colors.upcoming, colors.sounding)
                    val flipMicros = if (reduced) 0L else Motion.FastMs * 1_000L
                    onDrawBehind {
                        val now = clock.positionAt(frameNanos.longValue)
                        with(painter) { overlay(layout, notes, now, shownNow.value, slotLeft, ramp, flipMicros, colors.cursor) }
                    }
                },
        )
        if (browsing != null) {
            SuggestionChip(
                onClick = { browsing = null },
                label = { Text("Follow") },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(end = 8.dp),
                colors = SuggestionChipDefaults.suggestionChipColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    labelColor = MaterialTheme.colorScheme.onSurface,
                ),
                border = SuggestionChipDefaults.suggestionChipBorder(enabled = true, borderColor = LocalTertiary.current),
            )
        }
    }
}

/** How far [y] is from [system]'s staves and bar-number line: 0 on them. */
private fun distance(y: Float, system: ScoreSystem, numberHeight: Float): Float {
    val top = system.trebleTop - numberHeight
    return when {
        y < top -> top - y
        y > system.bassBottom -> y - system.bassBottom
        else -> 0f
    }
}

/** One page's staves, signs, bar numbers and notes: drawn once into a cached layer, never per frame. */
@Composable
private fun PageLayer(
    layout: ScoreLayout,
    page: Int,
    painter: ScorePainter,
    chordText: ChordText?,
    colors: ScoreColors,
    measurer: TextMeasurer,
    numberStyle: TextStyle,
    modifier: Modifier,
) {
    Spacer(
        modifier
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithCache {
                val systems = layout.systemsOn(page)
                val numbers = systems.map { s -> measurer.measure((layout.systems[s].firstBar + 1).toString(), numberStyle, maxLines = 1) }
                val marks = systems.mapIndexed { k, s -> painter.marks(layout, layout.systems[s], numbers[k], measurer, numberStyle) }
                val chordLabels = systems.mapIndexed { k, s ->
                    chordText?.let { painter.chordLabels(layout, layout.systems[s], numbers[k], marks[k], it, measurer) }
                }
                onDrawBehind {
                    with(painter) {
                        for ((k, s) in systems.withIndex()) system(layout, layout.systems[s], numbers[k], marks[k], chordLabels[k], colors)
                    }
                }
            },
    )
}

/** A piece's chords and their names (transposed as shown), to be set on the score's chord lines in [style]. */
private class ChordText(val chords: ChordTrack, val names: Array<String>, val style: TextStyle)

/** One system's chord names as placed: each name's layout and its box's top-left corner. */
private class ChordLabels(val text: List<TextLayoutResult>, val x: FloatArray, val top: FloatArray)

/**
 * The score's colours: staff and bar lines tertiary, glyphs and upcoming notes secondary, sounding
 * notes the sounding yellow (the overlay's ramp only), the cursor the content colour.
 */
@Immutable
private data class ScoreColors(
    val upcoming: Color,
    val sounding: Color,
    val cursor: Color,
    val line: Color,
    val glyph: Color,
    val number: Color,
    val spine: Color,
)

/**
 * Bravura's glyphs, measured once on the main thread at four staff spaces to the em; the tempo
 * mark's note at [TEMPO_NOTE_SCALE] times the [eyebrow]'s size.
 */
private class ScoreGlyphs(private val measurer: TextMeasurer, density: Density, eyebrow: TextStyle) {
    // In dp, not sp: the score is a drawing and keeps its size at any font scale, as the roll does.
    private val music = TextStyle(fontFamily = Bravura, fontSize = with(density) { (LineGap * 4).toSp() })

    // The tempo mark's note is text-sized instead, in sp, so it keeps its size beside "= 80".
    private val tempoMusic = TextStyle(fontFamily = Bravura, fontSize = eyebrow.fontSize * TEMPO_NOTE_SCALE)

    private fun glyph(text: String, style: TextStyle = music) =
        measurer.measure(text, style, overflow = TextOverflow.Visible, softWrap = false, maxLines = 1)

    private val gClef = glyph(G_CLEF)
    private val fClef = glyph(F_CLEF)
    private val sharp = glyph(SHARP)
    private val flat = glyph(FLAT)
    private val natural = glyph(NATURAL)
    private val blackHead = glyph(BLACK_HEAD)
    private val halfHead = glyph(HALF_HEAD)
    private val wholeHead = glyph(WHOLE_HEAD)
    private val flag8thUp = glyph(FLAG_8TH_UP)
    private val flag8thDown = glyph(FLAG_8TH_DOWN)
    private val flag16thUp = glyph(FLAG_16TH_UP)
    private val flag16thDown = glyph(FLAG_16TH_DOWN)
    private val digits = Array(10) { glyph(String(Character.toChars(TIME_DIGIT_0 + it))) }
    private val restWhole = glyph(REST_WHOLE)
    private val restHalf = glyph(REST_HALF)
    private val restQuarter = glyph(REST_QUARTER)
    private val rest8th = glyph(REST_8TH)
    private val rest16th = glyph(REST_16TH)
    private val dynamics = Array(Dynamics.FF + 1) { glyph(Dynamics.glyphs(it)) }
    val tempoNote = glyph(TEMPO_NOTE, tempoMusic)
    val tempoDot = glyph(DOT, tempoMusic)

    /** The tempo glyphs' staff space: a quarter of their em. */
    val tempoSpace: Float = with(density) { tempoMusic.fontSize.toPx() } / 4
    val dot = glyph(DOT)

    val headWidth: Float = blackHead.size.width.toFloat()
    val clefWidth: Float = max(gClef.size.width, fClef.size.width).toFloat()

    fun sign(kind: Int): TextLayoutResult = when (kind) {
        Sign.G_CLEF -> gClef
        Sign.F_CLEF -> fClef
        Sign.SHARP -> sharp
        Sign.FLAT -> flat
        Sign.NATURAL -> natural
        else -> digits[(kind - Sign.DIGIT).coerceIn(0, 9)]
    }

    fun accidental(kind: Int): TextLayoutResult = when (kind) {
        Accidental.SHARP -> sharp
        Accidental.FLAT -> flat
        else -> natural
    }

    fun head(kind: Int): TextLayoutResult = when (kind) {
        Head.WHOLE -> wholeHead
        Head.HALF -> halfHead
        else -> blackHead
    }

    fun flag(count: Int, up: Boolean): TextLayoutResult = when {
        count >= 2 -> if (up) flag16thUp else flag16thDown
        else -> if (up) flag8thUp else flag8thDown
    }

    /** The rest of [value] sixteenths ([Rests]). */
    fun rest(value: Int): TextLayoutResult = when {
        value >= Rests.WHOLE -> restWhole
        value >= Rests.HALF -> restHalf
        value >= Rests.QUARTER -> restQuarter
        value >= Rests.EIGHTH -> rest8th
        else -> rest16th
    }

    fun dynamic(band: Int): TextLayoutResult = dynamics[band.coerceIn(0, dynamics.size - 1)]
}

/**
 * The fingering numerals 1 to 5, measured once on the main thread in the eyebrow's size (they grow
 * with the font scale, as text does): each digit's layout, a digit's cap [height] and [width].
 */
internal class Numerals(measurer: TextMeasurer, density: Density, style: TextStyle) {
    val digits: Array<TextLayoutResult> = Array(6) { measurer.measure(it.toString(), style, maxLines = 1, density = density) }
    val height: Float = with(density) { style.fontSize.toPx() } * DIGIT_CAP
    val width: Float = digits.maxOf { it.size.width }.toFloat()

    private companion object {
        /** Roboto's figures stand this share of the em above the baseline. */
        const val DIGIT_CAP = 0.71f
    }
}

/**
 * A system's beams and ties as paths, and its tempo mark measured and placed (its left edge and its
 * text's baseline): built with its page's cached layer, never per frame.
 */
private class SystemMarks(
    val beams: Path,
    val ties: Path,
    val tempo: TempoMark?,
    val tempoText: TextLayoutResult?,
    val tempoX: Float,
    val tempoBaseline: Float,
    /** The tempo mark's right edge and top, for the chord names above it. */
    val tempoRight: Float = 0f,
    val tempoTop: Float = 0f,
)

/** Draws a laid-out score: whole systems for the page layers, and the per-frame overlay. Allocation-free. */
private class ScorePainter(private val glyphs: ScoreGlyphs, private val numerals: Numerals, density: Density) {
    private val space = with(density) { LineGap.toPx() }
    val hair = with(density) { Hairline.toPx() }
    private val overhang = with(density) { LedgerOverhang.toPx() }
    private val cursor = with(density) { CursorWidth.toPx() }
    private val finalStroke = with(density) { FinalStroke.toPx() }
    private val finalGap = with(density) { FinalGap.toPx() }
    private val tieStroke = Stroke(width = hair)
    private val chordGap = with(density) { ChordGap.toPx() }
    private val chordSpacing = with(density) { ChordSpacing.toPx() }

    /** From the treble's top line up to a bar number's baseline: over the clef's top, never on the staff. */
    private val numberLift = G_CLEF_RISE * space + with(density) { NumberClearance.toPx() }

    /**
     * System [system]'s beams (half-space bands, the thickness toward the heads) and ties (1 dp arcs
     * rising a share of their length) as paths, at most [MAX_NOTE_DRAWS] of each, and its tempo mark
     * with its text measured in [style]: after the bar [number] on the numbers' line, lifted clear of
     * any note that reaches up under it (the layout's skyline).
     */
    fun marks(layout: ScoreLayout, system: ScoreSystem, number: TextLayoutResult, measurer: TextMeasurer, style: TextStyle): SystemMarks {
        val s = system.index
        val beams = Path()
        val thickness = Beams.THICKNESS * space
        val b = layout.beams
        for (k in b.inSystem(s).capped()) {
            val inward = if (b.up[k]) thickness else -thickness
            beams.moveTo(b.x1[k], b.y1[k])
            beams.lineTo(b.x2[k], b.y2[k])
            beams.lineTo(b.x2[k], b.y2[k] + inward)
            beams.lineTo(b.x1[k], b.y1[k] + inward)
            beams.close()
        }
        val ties = Path()
        val t = layout.ties
        for (k in t.inSystem(s).capped()) {
            val length = t.x2[k] - t.x1[k]
            if (length <= 0f) continue
            val rise = (length * TIE_RISE).coerceIn(TIE_LOWEST * space, TIE_HIGHEST * space)
            val bow = if (t.above[k]) -2 * rise else 2 * rise   // a quadratic's middle reaches half its control point's offset
            ties.moveTo(t.x1[k], t.y1[k])
            ties.quadraticTo((t.x1[k] + t.x2[k]) / 2, (t.y1[k] + t.y2[k]) / 2 + bow, t.x2[k], t.y2[k])
        }
        val tempo = layout.tempoMarkIn(s) ?: return SystemMarks(beams, ties, null, null, 0f, 0f)
        val text = measurer.measure(tempo.text, style, maxLines = 1)
        val unit = glyphs.tempoSpace
        val x = max(tempo.x, system.left + number.size.width + space)
        val glyphWidth = TEMPO_NOTE_WIDTH * unit + if (tempo.dotted) (TEMPO_DOT_GAP + TEMPO_DOT_WIDTH) * unit else 0f
        val right = x + glyphWidth + TEMPO_TEXT_GAP * unit + text.size.width
        val height = max((TEMPO_HEAD_BELOW + TEMPO_NOTE_RISE) * unit, text.firstBaseline)
        val onLine = system.trebleTop - numberLift
        val lifted = min(onLine, layout.skyline.top(s, x, right) - TEMPO_CLEARANCE * space)
        val baseline = max(lifted, min(onLine, system.bandTop + height))   // never above its band
        return SystemMarks(beams, ties, tempo, text, x, baseline, right, baseline - height)
    }

    /**
     * System [system]'s chord names ([chordText]): each where its chord begins in the system, its box
     * on the chord line just above the bar [number] (lifted clear of notes, numerals and the tempo mark
     * that reach up into it, never above its band, moved left to stay on the page); a name that would
     * run into the one before it is left out on the score (the waterfall still shows it).
     */
    fun chordLabels(
        layout: ScoreLayout,
        system: ScoreSystem,
        number: TextLayoutResult,
        marks: SystemMarks,
        chordText: ChordText,
        measurer: TextMeasurer,
    ): ChordLabels {
        val chords = chordText.chords
        val bars = layout.bars
        val from = chords.firstAtOrAfter(bars.startMicros[system.firstBar])
        val until = if (system.lastBar + 1 < bars.count) chords.firstAtOrAfter(bars.startMicros[system.lastBar + 1]) else chords.size
        val numberTop = system.trebleTop - numberLift - number.firstBaseline
        val texts = ArrayList<TextLayoutResult>()
        val xs = ArrayList<Float>()
        val tops = ArrayList<Float>()
        var lastRight = Float.NEGATIVE_INFINITY
        for (i in from until until) {
            val at = system.xAt(chords.startMicros[i])
            if (at < lastRight + chordSpacing) continue
            val text = measurer.measure(chordText.names[i], chordText.style, maxLines = 1)
            // A name near the system's end is moved left to stay on the page, not cut at its edge.
            val x = max(system.left, min(at, layout.metrics.pageWidth - text.size.width))
            if (x < lastRight + chordSpacing) continue
            val right = x + text.size.width
            var bottom = min(numberTop - chordGap, layout.skyline.top(system.index, x, right) - TEMPO_CLEARANCE * space)
            if (marks.tempo != null && right >= marks.tempoX && x <= marks.tempoRight) bottom = min(bottom, marks.tempoTop - chordGap)
            texts += text
            xs += x
            tops += max(bottom - text.size.height, system.bandTop)
            lastRight = right
        }
        return ChordLabels(texts, xs.toFloatArray(), tops.toFloatArray())
    }

    /**
     * One system: its staves, opening and bar lines, signs, bar number, tempo mark and chord names, then
     * its rests, ties, beams, notes and tied heads, its dynamics and its fingering, kept to its band.
     * Heads, rests and numerals stop at [MAX_NOTE_DRAWS] each (a system holds a few bars; past that it
     * is a crafted file, not music).
     */
    fun DrawScope.system(layout: ScoreLayout, s: ScoreSystem, number: TextLayoutResult, marks: SystemMarks, chords: ChordLabels?, colors: ScoreColors) {
        clipRect(top = s.bandTop, bottom = s.bandBottom) {
            val length = s.right - s.left
            for (n in 0..4) {
                drawRect(colors.line, Offset(s.left, s.trebleTop + n * space - hair / 2), Size(length, hair))
                drawRect(colors.line, Offset(s.left, s.bassTop + n * space - hair / 2), Size(length, hair))
            }
            val span = s.bassBottom - s.trebleTop
            drawRect(colors.line, Offset(s.left - hair / 2, s.trebleTop), Size(hair, span))   // the system's opening line
            for (bar in s.firstBar..s.lastBar) {
                val x = layout.bars.right[bar]
                if (s.final && bar == s.lastBar) {
                    drawRect(colors.line, Offset(x - finalStroke - finalGap - hair, s.trebleTop), Size(hair, span))
                    drawRect(colors.line, Offset(x - finalStroke, s.trebleTop), Size(finalStroke, span))
                } else {
                    drawRect(colors.line, Offset(x - hair / 2, s.trebleTop), Size(hair, span))
                }
            }
            for (k in s.signKind.indices) glyph(glyphs.sign(s.signKind[k]), s.signX[k], s.signY[k], colors.glyph)
            drawText(number, color = colors.number, topLeft = Offset(s.left, s.trebleTop - numberLift - number.firstBaseline))
            if (marks.tempo != null && marks.tempoText != null) tempoMark(marks.tempo, marks.tempoText, marks.tempoX, marks.tempoBaseline, colors.glyph)
            if (chords != null) for (k in chords.text.indices) drawText(chords.text[k], color = colors.glyph, topLeft = Offset(chords.x[k], chords.top[k]))
            val rests = layout.rests
            for (k in rests.inSystem(s.index).capped()) glyph(glyphs.rest(rests.value[k].toInt()), rests.x[k], rests.y[k], colors.upcoming)
            drawPath(marks.ties, colors.upcoming, style = tieStroke)
            drawPath(marks.beams, colors.upcoming)
            // A system holds a few bars; past MAX_NOTE_DRAWS heads in one it is a crafted file, not music.
            var drawn = 0
            for (i in s.firstNote until s.noteEnd) {
                if (drawn >= MAX_NOTE_DRAWS) break
                if (layout.system[i] == s.index) {
                    note(layout, s, i, colors.upcoming)
                    drawn++
                }
            }
            for (h in s.firstTied until s.tiedEnd) {
                if (drawn >= MAX_NOTE_DRAWS) break
                note(layout, s, h, colors.upcoming)
                drawn++
            }
            for (k in layout.dynamicsIn(s.index)) {
                val mark = layout.dynamics[k]
                glyph(glyphs.dynamic(mark.band), mark.x, mark.y, colors.glyph)
            }
            val f = layout.fingers
            for (k in f.inSystem(s.index).capped()) {
                val digit = numerals.digits[f.finger[k].toInt().coerceIn(0, 5)]
                drawText(digit, color = colors.glyph, topLeft = Offset(f.x[k] - digit.size.width / 2f, f.baseline[k] - digit.firstBaseline))
            }
        }
    }

    /**
     * A tempo mark from [left] on [baseline] (see [marks]): the note glyph, its head on the baseline
     * (dotted in a compound metre), then "= N" in the eyebrow style.
     */
    private fun DrawScope.tempoMark(mark: TempoMark, text: TextLayoutResult, left: Float, baseline: Float, color: Color) {
        val unit = glyphs.tempoSpace
        var x = left
        val noteBaseline = baseline - TEMPO_HEAD_BELOW * unit
        glyph(glyphs.tempoNote, x, noteBaseline, color)
        x += TEMPO_NOTE_WIDTH * unit
        if (mark.dotted) {
            glyph(glyphs.tempoDot, x + TEMPO_DOT_GAP * unit, noteBaseline, color)
            x += (TEMPO_DOT_GAP + TEMPO_DOT_WIDTH) * unit
        }
        drawText(text, color = color, topLeft = Offset(x + TEMPO_TEXT_GAP * unit, baseline - text.firstBaseline))
    }

    /**
     * The cursor ([cursorColor]) in the system sounding at [now], and every note sounding (or easing
     * across its edges over [flipMicros]) coloured through [ramp], from the upcoming grey to the
     * sounding yellow: only where their pages are [shown], at [slotLeft] for each slot.
     */
    fun DrawScope.overlay(
        layout: ScoreLayout,
        notes: NoteList,
        now: Long,
        shown: List<Int>,
        slotLeft: FloatArray,
        ramp: Array<Color>,
        flipMicros: Long,
        cursorColor: Color,
    ) {
        val current = layout.systems[layout.systemAt(now)]
        val cursorSlot = slotOf(shown, current.page)
        if (cursorSlot >= 0) {
            val x = slotLeft[cursorSlot] + current.xAt(now)
            drawRect(cursorColor, Offset(x - cursor / 2, current.trebleTop - space), Size(cursor, current.bassBottom - current.trebleTop + 2 * space))
        }
        val starts = notes.startMicros
        val ends = notes.endMicros
        var drawn = 0
        for (i in notes.scanStart(now - flipMicros) until notes.size) {
            if (drawn >= MAX_NOTE_DRAWS) break
            val start = starts[i]
            if (start > now) break
            val end = ends[i]
            if (end + flipMicros < now) continue
            val level = rampLevel(now, start, end, flipMicros)
            if (level == 0) continue
            if (layout.system[i] < 0) continue
            if (lit(layout, i, ramp[level], shown, slotLeft)) drawn++
            // The heads tied to it light as the cursor reaches each of them (they are in time order).
            for (k in 0 until layout.tiedHeadCount(i)) {
                val h = layout.tiedHead(i, k)
                val tiedStart = layout.tiedStartMicros[h - layout.noteCount]
                if (tiedStart > now) break
                val tiedLevel = rampLevel(now, tiedStart, end, flipMicros)
                if (tiedLevel > 0 && lit(layout, h, ramp[tiedLevel], shown, slotLeft)) drawn++
            }
        }
    }

    /** Draws head [h] in [color] over its page when that page is [shown]; whether it was. */
    private fun DrawScope.lit(layout: ScoreLayout, h: Int, color: Color, shown: List<Int>, slotLeft: FloatArray): Boolean {
        val s = layout.systems[layout.system[h]]
        val slot = slotOf(shown, s.page)
        if (slot < 0) return false
        translate(left = slotLeft[slot]) {
            clipRect(top = s.bandTop, bottom = s.bandBottom) { note(layout, s, h, color) }
        }
        return true
    }

    /** Head [i] of [s] in [color]: ledger lines, its length's hairline (performances), stem and flag, accidental, head, dot. */
    private fun DrawScope.note(layout: ScoreLayout, s: ScoreSystem, i: Int, color: Color) {
        val x = layout.x[i]
        val y = layout.y[i]
        val headWidth = layout.headWidth(i)
        val top = if (layout.treble[i]) s.trebleTop else s.bassTop
        val ledgers = layout.ledgers[i].toInt()
        // Ledger lines belong to their note, in its colour: a short hairline in the staff's grey all but disappears.
        for (k in 1..abs(ledgers)) {
            val lineY = if (ledgers < 0) top + s.staffHeight + k * space else top - k * space
            drawRect(color, Offset(x - overhang, lineY - hair / 2), Size(headWidth + 2 * overhang, hair))
        }
        val end = layout.durationEnd[i]
        if (!end.isNaN()) drawRect(color, Offset(x + headWidth, y - hair / 2), Size(end - x - headWidth, hair))
        val stemX = layout.stemX[i]
        if (!stemX.isNaN()) {
            val from = layout.stemFrom[i]
            val to = layout.stemTo[i]
            drawRect(color, Offset(stemX, min(from, to)), Size(hair, abs(to - from)))
            val flags = layout.flags[i].toInt()
            if (flags > 0) glyph(glyphs.flag(flags, layout.stemUp[i]), stemX, to, color)
        }
        val accidental = layout.accidental[i].toInt()
        if (accidental != Accidental.NONE) glyph(glyphs.accidental(accidental), layout.accidentalX[i], y, color)
        glyph(glyphs.head(layout.head[i].toInt()), x, y, color)
        if (layout.dotted[i]) glyph(glyphs.dot, layout.dotX[i], layout.dotY[i], color)
    }

    /** Draws [glyph] with its SMuFL origin (left edge, on the baseline) at ([x], [baseline]). */
    private fun DrawScope.glyph(glyph: TextLayoutResult, x: Float, baseline: Float, color: Color) {
        drawText(glyph, color = color, topLeft = Offset(x, baseline - glyph.firstBaseline))
    }

    /** The slot showing [page], or -1. */
    private fun slotOf(shown: List<Int>, page: Int): Int {
        for (slot in shown.indices) if (shown[slot] == page) return slot
        return -1
    }
}
