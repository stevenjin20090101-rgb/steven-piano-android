// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.ui.screens.quiet

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import dev.stevenjin.stevenpiano.schedule.Occurrences
import dev.stevenjin.stevenpiano.schedule.QuietCopy
import dev.stevenjin.stevenpiano.schedule.QuietSection
import dev.stevenjin.stevenpiano.schedule.ScheduleCopy
import dev.stevenjin.stevenpiano.ui.theme.LocalHairline
import dev.stevenjin.stevenpiano.ui.theme.LocalTertiary
import java.time.DayOfWeek

/** The week strip's window: from 6:00 to 22:00, minutes after midnight. */
const val WEEK_FROM = 6 * 60
const val WEEK_TO = 22 * 60

/** One hatched piece of the strip: on [day] (0 Monday … 6 Sunday), from [from] to [to], minutes after midnight, inside the window. */
data class WeekPiece(val day: Int, val from: Int, val to: Int)

/**
 * Every section's blocks as the week strip draws them (v1.20 — M54): a piece on each day a block runs, and one crossing
 * midnight on the next day too (Sunday's on Monday), each cut to [WEEK_FROM]–[WEEK_TO]; what lies outside the window
 * is left out. Pure.
 */
fun weekPieces(sections: List<QuietSection>): List<WeekPiece> {
    val pieces = ArrayList<WeekPiece>()
    fun add(day: Int, from: Int, to: Int) {
        val a = maxOf(from, WEEK_FROM)
        val b = minOf(to, WEEK_TO)
        if (b > a) pieces += WeekPiece(day, a, b)
    }
    for (section in sections) {
        for (block in section.blocks) {
            if (block.minutes == 0) continue
            for (day in 0 until DAYS) {
                if (section.days and (1 shl day) == 0) continue
                if (block.end > block.start) {
                    add(day, block.start, block.end)
                } else {
                    add(day, block.start, Occurrences.MINUTES_PER_DAY)
                    add((day + 1) % DAYS, 0, block.end)
                }
            }
        }
    }
    return pieces
}

/**
 * The week at a glance (v1.20 — M54): Monday to Sunday across, 6:00 to 22:00 down, the hours 6, 12, 18 and 22 ruled in
 * hairlines, and every block hatched in the content colour inside a tertiary edge. A picture: the sections below it say
 * the same in words, so TalkBack reads one line for it.
 */
@Composable
fun QuietWeek(sections: List<QuietSection>, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer()
    val tertiary = LocalTertiary.current
    val hairline = LocalHairline.current
    val ink = MaterialTheme.colorScheme.onSurface
    val style = MaterialTheme.typography.labelSmall.copy(color = tertiary)
    val days = remember(measurer, style) { DayOfWeek.entries.map { measurer.measure(ScheduleCopy.short(it), style) } }
    val hours = remember(measurer, style) { RULED.map { measurer.measure(QuietCopy.clock(it * Occurrences.MINUTES_PER_HOUR), style) } }
    val pieces = remember(sections) { weekPieces(sections) }
    Canvas(
        modifier
            .fillMaxWidth()
            .height(STRIP_HEIGHT)
            .clearAndSetSemantics { contentDescription = "The week's quiet times, Monday to Sunday, 6:00 to 22:00" },
    ) {
        val gutter = GUTTER.toPx()
        val head = HEAD.toPx()
        val line = 1.dp.toPx()
        val column = (size.width - gutter) / DAYS
        val body = size.height - head - line
        fun y(minute: Int): Float = head + body * (minute - WEEK_FROM) / (WEEK_TO - WEEK_FROM).toFloat()
        days.forEachIndexed { day, label ->
            drawText(label, topLeft = Offset(gutter + column * day + (column - label.size.width) / 2f, 0f))
        }
        RULED.forEachIndexed { i, hour ->
            val at = y(hour * Occurrences.MINUTES_PER_HOUR)
            drawLine(hairline, Offset(gutter, at), Offset(size.width, at), strokeWidth = line)
            val label = hours[i]
            drawText(label, topLeft = Offset(0f, (at - label.size.height / 2f).coerceIn(head, size.height - label.size.height)))
        }
        for (day in 1 until DAYS) {
            val x = gutter + column * day
            drawLine(hairline, Offset(x, head), Offset(x, size.height), strokeWidth = line)
        }
        val inset = INSET.toPx()
        val step = HATCH.toPx()
        for (piece in pieces) {
            val left = gutter + column * piece.day + inset
            val right = left + column - inset * 2
            val top = y(piece.from)
            val bottom = y(piece.to)
            if (right <= left || bottom <= top) continue
            drawRect(ink.copy(alpha = FILL_ALPHA), Offset(left, top), Size(right - left, bottom - top))
            clipRect(left, top, right, bottom) {
                val rise = bottom - top
                var x = left - rise
                while (x < right) {
                    drawLine(ink.copy(alpha = HATCH_ALPHA), Offset(x, bottom), Offset(x + rise, top), strokeWidth = line)
                    x += step
                }
            }
            drawRect(tertiary, Offset(left, top), Size(right - left, bottom - top), style = Stroke(line))
        }
    }
}

private const val DAYS = 7

/** The hours ruled across the strip. */
private val RULED = listOf(6, 12, 18, 22)

private val STRIP_HEIGHT = 200.dp
private val GUTTER = 40.dp
private val HEAD = 20.dp
private val INSET = 3.dp
private val HATCH = 6.dp
private const val FILL_ALPHA = 0.06f
private const val HATCH_ALPHA = 0.35f
