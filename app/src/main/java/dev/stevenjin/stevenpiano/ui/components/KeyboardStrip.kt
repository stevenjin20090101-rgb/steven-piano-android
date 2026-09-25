// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.ui.components

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LongState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import dev.stevenjin.stevenpiano.midi.KeyMap
import dev.stevenjin.stevenpiano.midi.NoteList
import dev.stevenjin.stevenpiano.score.Hands
import dev.stevenjin.stevenpiano.ui.theme.LocalHairline
import dev.stevenjin.stevenpiano.ui.theme.LocalTertiary

val KeyboardStripHeight = 44.dp
private const val BLACK_KEY_HEIGHT = 0.62f

/** A key the left hand plays is outlined this thick (keys are narrow; a 1 dp line would vanish). */
private val KeyOutline = 1.5.dp

/**
 * The piano's 84 keys under the roll, monochrome: white keys on the elevated surface split by
 * hairlines, black keys in the tertiary grey. A key the piano is playing inverts to the content
 * colour; with [hands], a key only the left hand is playing is outlined in it instead, as the
 * waterfall outlines the left hand's bars (DESIGN.md › v1.3). It redraws each frame [frameNanos]
 * changes, reading the player's key bitsets ([activeLow]: bit `key - 24`, keys 24-87; [activeHigh]:
 * bit `key - 88`) and, with [hands], which hand the notes sounding at [clock]'s position belong to,
 * without allocating.
 */
@Composable
fun KeyboardStrip(
    frameNanos: LongState,
    activeLow: () -> Long,
    activeHigh: () -> Long,
    modifier: Modifier = Modifier,
    hands: KeyHands? = null,
    clock: SongClock? = null,
) {
    val body = MaterialTheme.colorScheme.surfaceVariant
    val pressed = MaterialTheme.colorScheme.onSurface
    val blackKey = LocalTertiary.current
    val line = LocalHairline.current
    Spacer(
        modifier
            .fillMaxWidth()
            .height(KeyboardStripHeight)
            .drawWithCache {
                val keys = KeyLayout(size.width)
                val sep = Hairline.toPx()
                val blackHeight = size.height * BLACK_KEY_HEIGHT
                val outline = Stroke(width = KeyOutline.toPx())
                val half = outline.width / 2
                onDrawBehind {
                    val frame = frameNanos.longValue   // a new frame: the keys may have changed
                    val low = activeLow()
                    val high = activeHigh()
                    if (hands != null && clock != null) hands.update(clock.positionAt(frame))
                    drawRect(body)
                    for (i in 0 until KeyMap.KEY_COUNT) {
                        if (!keys.isBlack(i) && isActive(i, low, high) && hands?.leftOnly(i) != true) {
                            drawRect(pressed, Offset(keys.left(i), 0f), Size(keys.width(i), size.height))
                        }
                    }
                    for (w in 1 until KeyLayout.WHITE_KEYS) {
                        drawRect(line, Offset(w * keys.whiteWidth - sep / 2, 0f), Size(sep, size.height))
                    }
                    for (i in 0 until KeyMap.KEY_COUNT) {
                        if (keys.isBlack(i) || !isActive(i, low, high) || hands?.leftOnly(i) != true) continue
                        drawRect(pressed, Offset(keys.left(i) + half, half), Size(keys.width(i) - outline.width, size.height - outline.width), style = outline)
                    }
                    for (i in 0 until KeyMap.KEY_COUNT) {
                        if (!keys.isBlack(i)) continue
                        drawRect(body, Offset(keys.left(i) - sep, 0f), Size(keys.width(i) + 2 * sep, blackHeight + sep))
                        val active = isActive(i, low, high)
                        val left = active && hands?.leftOnly(i) == true
                        drawRect(if (active && !left) pressed else blackKey, Offset(keys.left(i), 0f), Size(keys.width(i), blackHeight))
                        if (left) {
                            drawRect(pressed, Offset(keys.left(i) + half, half), Size(keys.width(i) - outline.width, blackHeight - outline.width), style = outline)
                        }
                    }
                }
            },
    )
}

private fun isActive(i: Int, low: Long, high: Long): Boolean =
    if (i < 64) (low ushr i) and 1L == 1L else (high ushr (i - 64)) and 1L == 1L

/**
 * Which hand plays each of the 84 keys at a moment: the notes of [notes] sounding then (within
 * [EDGE_MICROS], so a key the player has just struck or not yet let go still finds its note),
 * mapped to keys with [transpose] and [fold] as the piano plays them, by their [hands]. Updated once
 * a frame from the draw phase ([update]), without allocating.
 */
class KeyHands(private val notes: NoteList, private val hands: ByteArray, private val transpose: Int, private val fold: Boolean) {
    private var leftLow = 0L
    private var leftHigh = 0L
    private var rightLow = 0L
    private var rightHigh = 0L

    fun update(now: Long) {
        var ll = 0L
        var lh = 0L
        var rl = 0L
        var rh = 0L
        val starts = notes.startMicros
        val ends = notes.endMicros
        for (i in notes.scanStart(now - EDGE_MICROS) until notes.size) {
            if (starts[i] > now + EDGE_MICROS) break
            if (ends[i] < now - EDGE_MICROS) continue
            val key = KeyMap.map(notes.note(i), transpose, fold)
            if (key == KeyMap.UNPLAYABLE) continue
            val bit = key - KeyMap.LOWEST
            val left = i < hands.size && hands[i] == Hands.LEFT
            if (bit < 64) {
                if (left) ll = ll or (1L shl bit) else rl = rl or (1L shl bit)
            } else {
                if (left) lh = lh or (1L shl (bit - 64)) else rh = rh or (1L shl (bit - 64))
            }
        }
        leftLow = ll
        leftHigh = lh
        rightLow = rl
        rightHigh = rh
    }

    /** Key [i] (0 = C1) is sounding in the left hand only, as of the last [update]. */
    fun leftOnly(i: Int): Boolean = isActive(i, leftLow, leftHigh) && !isActive(i, rightLow, rightHigh)

    private companion object {
        /** A note counts this long before it starts and after it ends: the player's and the frame's clocks differ by a little. */
        const val EDGE_MICROS = 30_000L
    }
}
