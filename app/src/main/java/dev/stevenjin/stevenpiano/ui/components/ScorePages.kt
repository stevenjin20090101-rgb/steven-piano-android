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
import androidx.compose.ui.graphics.drawscope.DrawScope
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import dev.stevenjin.stevenpiano.R
import dev.stevenjin.stevenpiano.midi.KeyMap
import dev.stevenjin.stevenpiano.midi.KeySignature
import dev.stevenjin.stevenpiano.midi.NoteList
import dev.stevenjin.stevenpiano.midi.TempoMap
import dev.stevenjin.stevenpiano.midi.TimeSignature
import dev.stevenjin.stevenpiano.score.Accidental
import dev.stevenjin.stevenpiano.score.Head
import dev.stevenjin.stevenpiano.score.PageTurn
import dev.stevenjin.stevenpiano.score.ScoreLayout
import dev.stevenjin.stevenpiano.score.ScoreLayoutEngine
import dev.stevenjin.stevenpiano.score.ScoreMetrics
import dev.stevenjin.stevenpiano.score.ScoreSystem
import dev.stevenjin.stevenpiano.score.ScoreWidth
import dev.stevenjin.stevenpiano.score.Sign
import dev.stevenjin.stevenpiano.ui.theme.LocalHairline
import dev.stevenjin.stevenpiano.ui.theme.LocalTertiary
import dev.stevenjin.stevenpiano.ui.theme.Motion
import dev.stevenjin.stevenpiano.ui.theme.Tabular
import dev.stevenjin.stevenpiano.ui.theme.rememberReducedMotion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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

/** Bravura, the SMuFL reference music font (SIL Open Font Licence; see AUTHORS). */
private val Bravura = FontFamily(Font(R.font.bravura))

/**
 * The score (DESIGN.md › v1.2 › Score): the piece as systems of bars on pages, one page, or two
 * side by side when the panel is 840 dp wide or more, laid out by [ScoreLayoutEngine] from the
 * file's tempo map, bars and signatures ([keySignatures] are the file's; [transpose] moves them
 * with the notes). Staff lines and bar lines are the tertiary grey, clefs, signatures and notes the
 * secondary colour, bar numbers eyebrows above each system. A 2 dp cursor moves through the
 * current system, and sounding notes brighten with the roll's 120 ms flip (a cut when motion is
 * reduced). Pages turn by themselves so the cursor is always in sight ([PageTurn]).
 *
 * Agency: a horizontal swipe looks at other pages, and a Follow chip then waits at the top right;
 * tapping it, or the next turn the music makes, follows again. Tapping a bar seeks to it
 * ([onSeek], which silences the piano first like any seek).
 *
 * Work is kept off the frame: glyphs are measured once on the main thread; the layout is computed
 * on a background thread whenever the notes, transpose, folding or the panel change; each visible
 * page is drawn into its own cached layer that never reads the frame clock; only the overlay
 * (cursor and sounding notes) redraws per frame, without allocating.
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
) {
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val numberStyle = MaterialTheme.typography.labelSmall.merge(Tabular)
    val glyphs = remember(measurer, density) { ScoreGlyphs(measurer, density) }
    val numberHeight = remember(measurer, density, numberStyle) {
        measurer.measure("0", numberStyle, maxLines = 1, density = density).size.height.toFloat()
    }
    val colors = ScoreColors(
        upcoming = MaterialTheme.colorScheme.onSurfaceVariant,
        sounding = MaterialTheme.colorScheme.onSurface,
        line = LocalTertiary.current,
        glyph = MaterialTheme.colorScheme.onSurfaceVariant,
        number = LocalTertiary.current,
        spine = LocalHairline.current,
    )
    val reduced = rememberReducedMotion()
    BoxWithConstraints(modifier.clipToBounds()) {
        val panelWidth = constraints.maxWidth.toFloat()
        val panelHeight = constraints.maxHeight.toFloat()
        val metrics = remember(width, panelWidth, panelHeight, density, glyphs, numberHeight) {
            ScoreMetrics.forPanel(width, panelWidth, panelHeight, density.density, glyphs.headWidth, glyphs.clefWidth, numberHeight)
        }
        val laid by produceState<Laid?>(null, notes, tempo, bars, keySignatures, timeSignatures, transpose, fold, metrics) {
            if (value?.notes !== notes) value = null   // never another piece's pages under this one's cursor
            value = withContext(Dispatchers.Default) {
                val keys = IntArray(notes.size) { KeyMap.map(notes.note(it), transpose, fold) }
                val keysMoved = keySignatures.map { it.transposed(transpose) }
                Laid(notes, ScoreLayoutEngine.layout(notes, keys, tempo, bars, keysMoved, metrics, timeSignatures))
            }
        }
        laid?.let { ScoreView(it.layout, notes, glyphs, colors, measurer, numberStyle, frameNanos, clock, reduced, onSeek) }
    }
}

/** A layout and the notes it was made for. */
private class Laid(val notes: NoteList, val layout: ScoreLayout)

@Composable
private fun ScoreView(
    layout: ScoreLayout,
    notes: NoteList,
    glyphs: ScoreGlyphs,
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
    val painter = remember(glyphs, density) { ScorePainter(glyphs, density) }
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
                        with(painter) { overlay(layout, notes, now, shownNow.value, slotLeft, ramp, flipMicros, colors.sounding) }
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
                onDrawBehind {
                    with(painter) {
                        for ((k, s) in systems.withIndex()) system(layout, layout.systems[s], numbers[k], colors)
                    }
                }
            },
    )
}

/** The score's colours: staff and bar lines tertiary, glyphs and upcoming notes secondary, sounding ones primary. */
@Immutable
private data class ScoreColors(
    val upcoming: Color,
    val sounding: Color,
    val line: Color,
    val glyph: Color,
    val number: Color,
    val spine: Color,
)

/** Bravura's glyphs, measured once on the main thread at four staff spaces to the em. */
private class ScoreGlyphs(private val measurer: TextMeasurer, density: Density) {
    // In dp, not sp: the score is a drawing and keeps its size at any font scale, as the roll does.
    private val music = TextStyle(fontFamily = Bravura, fontSize = with(density) { (LineGap * 4).toSp() })

    private fun glyph(text: String) = measurer.measure(text, music, overflow = TextOverflow.Visible, softWrap = false, maxLines = 1)

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
}

/** Draws a laid-out score: whole systems for the page layers, and the per-frame overlay. Allocation-free. */
private class ScorePainter(private val glyphs: ScoreGlyphs, density: Density) {
    private val space = with(density) { LineGap.toPx() }
    val hair = with(density) { Hairline.toPx() }
    private val overhang = with(density) { LedgerOverhang.toPx() }
    private val cursor = with(density) { CursorWidth.toPx() }
    private val finalStroke = with(density) { FinalStroke.toPx() }
    private val finalGap = with(density) { FinalGap.toPx() }

    /** From the treble's top line up to a bar number's baseline: over the clef's top, never on the staff. */
    private val numberLift = G_CLEF_RISE * space + with(density) { NumberClearance.toPx() }

    /** One system: its staves, opening and bar lines, signs, bar number and notes, kept to its band. */
    fun DrawScope.system(layout: ScoreLayout, s: ScoreSystem, number: TextLayoutResult, colors: ScoreColors) {
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
            // A system holds a few bars; past MAX_NOTE_DRAWS notes in one it is a crafted file, not music.
            var drawn = 0
            for (i in s.firstNote until s.noteEnd) {
                if (drawn >= MAX_NOTE_DRAWS) break
                if (layout.system[i] == s.index) {
                    note(layout, s, i, colors.upcoming)
                    drawn++
                }
            }
        }
    }

    /**
     * The cursor in the system sounding at [now], and every note sounding (or easing across its
     * edges over [flipMicros]) brightened through [ramp]: only where their pages are [shown], at
     * [slotLeft] for each slot.
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
            val index = layout.system[i]
            if (index < 0) continue
            val s = layout.systems[index]
            val slot = slotOf(shown, s.page)
            if (slot < 0) continue
            translate(left = slotLeft[slot]) {
                clipRect(top = s.bandTop, bottom = s.bandBottom) { note(layout, s, i, ramp[level]) }
            }
            drawn++
        }
    }

    /** Note [i] of [s] in [color]: ledger lines, its length's hairline (performances), stem and flag, accidental, head, dot. */
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
