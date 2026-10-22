// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.ui.screens.quiet

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import androidx.compose.ui.unit.sp
import dev.stevenjin.stevenpiano.quiet.QuietBlock
import dev.stevenjin.stevenpiano.quiet.QuietCopy
import dev.stevenjin.stevenpiano.quiet.QuietSection
import dev.stevenjin.stevenpiano.quiet.QuietTimes
import dev.stevenjin.stevenpiano.ui.Format
import dev.stevenjin.stevenpiano.ui.components.Card
import dev.stevenjin.stevenpiano.ui.components.Hairline
import dev.stevenjin.stevenpiano.ui.theme.LocalHairline
import dev.stevenjin.stevenpiano.ui.theme.LocalTertiary
import dev.stevenjin.stevenpiano.ui.theme.SelectedAlpha
import dev.stevenjin.stevenpiano.ui.theme.Tabular
import kotlinx.coroutines.delay
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import kotlin.math.roundToInt

/** The week's window: from 6:00 to 22:00, minutes after midnight. */
const val WEEK_FROM = 6 * 60
const val WEEK_TO = 22 * 60

/**
 * One block as the week draws it: on [day] (0 Monday … 6 Sunday), from [from] to [to], minutes after midnight, inside
 * the window; the [section] it belongs to (its place among the sections) with its [name]; and the whole block, [start]
 * to [end], which its times read even where the window cuts it (the night's "21:00–7:00", at 6:00 and at 21:00).
 */
data class WeekPiece(val day: Int, val from: Int, val to: Int, val section: Int = 0, val name: String = "", val start: Int = from, val end: Int = to) {
    /** How long it shows in the window, in minutes. */
    val minutes: Int get() = to - from
}

/**
 * Every section's blocks as the week draws them (v1.20 — M54): a piece on each day a block runs, and one crossing
 * midnight on the next day too (Sunday's on Monday), each cut to [WEEK_FROM]–[WEEK_TO]; what lies outside the window
 * is left out. Pure.
 */
fun weekPieces(sections: List<QuietSection>): List<WeekPiece> {
    val pieces = ArrayList<WeekPiece>()
    sections.forEachIndexed { index, section ->
        for (block in section.blocks) {
            if (block.minutes == 0) continue
            fun add(day: Int, from: Int, to: Int) {
                val a = maxOf(from, WEEK_FROM)
                val b = minOf(to, WEEK_TO)
                if (b > a) pieces += WeekPiece(day, a, b, index, section.name, block.start, block.end)
            }
            for (day in 0 until DAYS) {
                if (section.days and (1 shl day) == 0) continue
                if (block.end > block.start) {
                    add(day, block.start, block.end)
                } else {
                    add(day, block.start, QuietTimes.MINUTES_PER_DAY)
                    add((day + 1) % DAYS, 0, block.end)
                }
            }
        }
    }
    return pieces
}

/**
 * The piece a press at [minute] of a day's column opens (v1.25 — M59): the one under it, else the nearest within [reach]
 * minutes above or below it, so even a short block keeps a 48 dp target; null when none is that near. Pure.
 */
fun pieceAt(pieces: List<WeekPiece>, minute: Float, reach: Float): WeekPiece? {
    fun distance(piece: WeekPiece): Float = when {
        minute < piece.from -> piece.from - minute
        minute > piece.to -> minute - piece.to
        else -> 0f
    }
    return pieces.filter { distance(it) <= reach }.minByOrNull(::distance)
}

/**
 * The week as a calendar (DESIGN.md › v1.25 — M59, as the web panel's since v1.21), on a [Card]: Monday to Sunday across,
 * 6:00 to 22:00 down a 480 dp body, every hour ruled (the odd ones fainter) and every other one labelled in a gutter
 * measured so "22:00" never touches Monday at any text size. Today's name sits on a pill and its column is washed,
 * with a line across it at the time now (a dot at its start). Each block is a card of the content colour with a 3 dp
 * edge: its section's name from 70 minutes, its times from 30, wrapping after the dash when the column is narrow, as
 * many lines as fit and clipped. Pressing a block, or near one (within 24 dp), opens its section's editor ([onEdit]
 * with the section's place). TalkBack hears the week's name, then each block with its day and times, and Edit.
 */
@Composable
fun QuietWeek(sections: List<QuietSection>, onEdit: (Int) -> Unit, modifier: Modifier = Modifier) {
    val now = rememberMinute()
    val today = now.dayOfWeek.value - 1
    val minute = now.hour * QuietTimes.MINUTES_PER_HOUR + now.minute
    val pieces = remember(sections) { weekPieces(sections) }
    val byDay = remember(pieces) { List(DAYS) { day -> pieces.filter { it.day == day } } }
    val hourStyle = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Normal, letterSpacing = 0.sp).merge(Tabular)
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    // The gutter: the widest label as it is drawn now ("22:00", at the person's text size) and 8 dp, at least 48 dp.
    val gutter = remember(measurer, hourStyle, density) {
        max(GUTTER_MIN, with(density) { measurer.measure(Format.timeOfDay(WEEK_TO), hourStyle).size.width.toDp() } + LABEL_GAP)
    }
    val edit by rememberUpdatedState(onEdit)
    Card(modifier.semantics { contentDescription = WEEK_WORDS }) {
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 18.dp)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = HEAD)
                    .clearAndSetSemantics { },
            ) {
                Spacer(Modifier.width(gutter))
                for (day in 0 until DAYS) DayName(day, day == today, Modifier.weight(1f))
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(BODY),
            ) {
                HourLabels(
                    hourStyle,
                    Modifier
                        .width(gutter)
                        .fillMaxHeight()
                        .clearAndSetSemantics { },
                )
                for (day in 0 until DAYS) {
                    DayColumn(
                        byDay[day],
                        today = day == today,
                        now = minute.takeIf { day == today },
                        last = day == DAYS - 1,
                        onEdit = { edit(it) },
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                    )
                }
            }
        }
    }
}

/**
 * A day's short name over its column, the secondary grey, centred in the 28 dp row; today's in the content colour on
 * a pill as tall as the row.
 */
@Composable
private fun DayName(day: Int, today: Boolean, modifier: Modifier) {
    val ink = MaterialTheme.colorScheme.onSurface
    Box(modifier.heightIn(min = HEAD), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .then(if (today) Modifier.background(ink.copy(alpha = SelectedAlpha), CircleShape) else Modifier)
                .heightIn(min = HEAD)
                .padding(horizontal = if (today) 10.dp else 0.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                QuietCopy.short(DayOfWeek.of(day + 1)),
                style = MaterialTheme.typography.labelSmall.copy(lineHeight = 18.sp, letterSpacing = 0.4.sp),
                color = if (today) ink else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Clip,
            )
        }
    }
}

/** The labelled hours down the gutter, tertiary, each at its rule's height and 8 dp before the first column. */
@Composable
private fun HourLabels(style: TextStyle, modifier: Modifier) {
    val tertiary = LocalTertiary.current
    Layout(
        content = {
            for (hour in LABELLED) Text(Format.timeOfDay(hour * QuietTimes.MINUTES_PER_HOUR), style = style, color = tertiary, maxLines = 1, softWrap = false)
        },
        modifier = modifier,
    ) { measurables, constraints ->
        val labels = measurables.map { it.measure(Constraints()) }
        val end = constraints.maxWidth - LABEL_GAP.roundToPx()
        layout(constraints.maxWidth, constraints.maxHeight) {
            labels.forEachIndexed { i, label ->
                label.place(end - label.width, at(LABELLED[i] * QuietTimes.MINUTES_PER_HOUR, constraints.maxHeight) - label.height / 2)
            }
        }
    }
}

/**
 * A day's column: its rule at its start (and at its end, the last), the hours ruled across it, its blocks inset 3 dp,
 * today's wash under them and, over them, the line at the time [now]. A press anywhere in it goes to the block under
 * it or the nearest within 24 dp ([pieceAt]).
 */
@Composable
private fun DayColumn(pieces: List<WeekPiece>, today: Boolean, now: Int?, last: Boolean, onEdit: (Int) -> Unit, modifier: Modifier) {
    val ink = MaterialTheme.colorScheme.onSurface
    val hairline = LocalHairline.current
    Layout(
        content = { for (piece in pieces) WeekBlock(piece, onEdit) },
        modifier = modifier
            .then(if (today) Modifier.background(ink.copy(alpha = TODAY_WASH)) else Modifier)
            .drawBehind {
                val line = Hairline.toPx()
                drawRect(hairline, Offset.Zero, Size(line, size.height))
                if (last) drawRect(hairline, Offset(size.width - line, 0f), Size(line, size.height))
                for (hour in WEEK_FROM / QuietTimes.MINUTES_PER_HOUR..WEEK_TO / QuietTimes.MINUTES_PER_HOUR) {
                    val y = (at(hour * QuietTimes.MINUTES_PER_HOUR, size.height.roundToInt()) - line / 2).coerceIn(0f, size.height - line)
                    drawRect(hairline, Offset(0f, y), Size(size.width, line), alpha = if (hour % 2 == 0) 1f else MINOR_RULE)
                }
            }
            .drawWithContent {
                drawContent()
                if (now != null && now > WEEK_FROM && now < WEEK_TO) {
                    val y = at(now, size.height.roundToInt()).toFloat()
                    val thick = NOW_LINE.toPx()
                    drawRect(ink, Offset(0f, y - thick / 2), Size(size.width, thick))
                    drawCircle(ink, radius = NOW_DOT.toPx() / 2, center = Offset(0f, y))
                }
            }
            .pointerInput(pieces) {
                detectTapGestures { press ->
                    val perMinute = size.height / (WEEK_TO - WEEK_FROM).toFloat()
                    pieceAt(pieces, WEEK_FROM + press.y / perMinute, TAP_REACH.toPx() / perMinute)?.let { onEdit(it.section) }
                }
            },
    ) { measurables, constraints ->
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val inset = BLOCK_INSET.roundToPx()
        val blocks = measurables.mapIndexed { i, block ->
            val piece = pieces[i]
            block.measure(Constraints.fixed((width - inset * 2).coerceAtLeast(0), (at(piece.to, height) - at(piece.from, height)).coerceAtLeast(1)))
        }
        layout(width, height) {
            blocks.forEachIndexed { i, block -> block.place(inset, at(pieces[i].from, height)) }
        }
    }
}

/**
 * A block: the content colour at 14 % behind a 3 dp edge of it, in corners square at its start and 6 dp at its end;
 * inside, 1 dp down and 4 dp in, its section's name (from 70 minutes, semibold, one line) over its times (from 30
 * minutes, secondary, tabular), as many 14 sp lines as its height holds, clipped. To TalkBack a button: "School days,
 * Monday, 8:40–9:30", Edit.
 */
@Composable
private fun WeekBlock(piece: WeekPiece, onEdit: (Int) -> Unit) {
    val ink = MaterialTheme.colorScheme.onSurface
    val times = QuietCopy.range(QuietBlock(piece.start, piece.end))
    val line = with(LocalDensity.current) { LINE.toDp() }
    // The body is 480 dp for 960 minutes: half a dp a minute, whatever the screen.
    val lines = ((BODY * (piece.minutes / (WEEK_TO - WEEK_FROM).toFloat()) - 1.dp) / line).toInt()
    val named = piece.minutes >= NAMED_MINUTES && lines >= 1
    val timed = piece.minutes >= TIMED_MINUTES && lines >= (if (named) 2 else 1)
    Box(
        Modifier
            .clip(BlockShape)
            .background(ink.copy(alpha = BLOCK_FILL))
            .drawBehind { drawRect(ink, Offset.Zero, Size(BLOCK_EDGE.toPx(), size.height)) }
            .clearAndSetSemantics {
                contentDescription = "${piece.name}, ${QuietCopy.full(DayOfWeek.of(piece.day + 1))}, $times"
                role = Role.Button
                onClick(label = "Edit") {
                    onEdit(piece.section)
                    true
                }
            },
    ) {
        if (named || timed) {
            Column(Modifier.padding(start = BLOCK_EDGE + 4.dp, end = 4.dp, top = 1.dp)) {
                val style = MaterialTheme.typography.labelSmall.copy(lineHeight = LINE, letterSpacing = 0.sp)
                if (named) Text(piece.name, style = style.copy(fontWeight = FontWeight.SemiBold), color = ink, maxLines = 1, softWrap = false, overflow = TextOverflow.Clip)
                if (timed) {
                    Text(
                        // A break after the dash: Android's line breaker keeps "10:40–11:30" whole, the panel's browser doesn't.
                        times.replace("–", "–\u200B"),
                        style = style.copy(fontWeight = FontWeight.Normal).merge(Tabular),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = lines - if (named) 1 else 0,
                        overflow = TextOverflow.Clip,
                    )
                }
            }
        }
    }
}

/** The local time, to the minute, moving on at each minute's turn while the week shows. */
@Composable
private fun rememberMinute(): LocalDateTime {
    var now by remember { mutableStateOf(LocalDateTime.now().truncatedTo(ChronoUnit.MINUTES)) }
    LaunchedEffect(Unit) {
        while (true) {
            val next = LocalDateTime.now().truncatedTo(ChronoUnit.MINUTES).plusMinutes(1)
            delay(Duration.between(LocalDateTime.now(), next).toMillis().coerceAtLeast(0) + TICK_SLACK_MS)
            now = LocalDateTime.now().truncatedTo(ChronoUnit.MINUTES)
        }
    }
    return now
}

/** Where [minute] of the window falls down a body [height] pixels tall. */
private fun at(minute: Int, height: Int): Int = ((minute - WEEK_FROM) * height.toLong() / (WEEK_TO - WEEK_FROM)).toInt()

/** What TalkBack hears of the week before its blocks. */
private const val WEEK_WORDS = "The week's quiet times, Monday to Sunday, 6:00 to 22:00"

private const val DAYS = 7

/** The hours labelled down the gutter; every hour between 6:00 and 22:00 is ruled. */
private val LABELLED = listOf(6, 8, 10, 12, 14, 16, 18, 20, 22)

/** A block names its section from this long, and reads its times from this long, in minutes. */
private const val NAMED_MINUTES = 70
private const val TIMED_MINUTES = 30

private val HEAD = 28.dp
private val BODY = 480.dp
private val GUTTER_MIN = 48.dp
private val LABEL_GAP = 8.dp
private val BLOCK_INSET = 3.dp
private val BLOCK_EDGE = 3.dp
private val BlockShape = RoundedCornerShape(topStart = 0.dp, topEnd = 6.dp, bottomEnd = 6.dp, bottomStart = 0.dp)
private val LINE = 14.sp
private val NOW_LINE = 2.dp
private val NOW_DOT = 8.dp

/** A press this far from a block still opens it (half a 48 dp target). */
private val TAP_REACH = 24.dp

/** The content colour's share in a block's fill and in today's wash; the odd hours' rules at this share of the hairline. */
private const val BLOCK_FILL = 0.14f
private const val TODAY_WASH = 0.04f
private const val MINOR_RULE = 0.45f

/** How long after a minute's turn the clock looks, so it never reads the minute before. */
private const val TICK_SLACK_MS = 50L
