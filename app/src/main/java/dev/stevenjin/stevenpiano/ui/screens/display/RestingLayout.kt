// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.ui.screens.display

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import dev.stevenjin.stevenpiano.ui.components.ReadingWidth

/**
 * Where the art and the words stand on the resting screen, Art and notes (DESIGN.md › v1.7.1). Pure.
 *
 * A wide frame lying on its side (a tablet on the piano, a phone on its side) sets the art at the
 * left, a square about [SIDE_ART_OF_HEIGHT] of the window's height, and the words to its right at
 * reading width; phones and tall windows set the art on top, about [TOP_ART_OF_WIDTH] of the
 * window's width, and the words under it. The art never takes more than the room the words leave.
 */
object RestingLayout {
    /** The art beside the words: this share of the window's height. */
    const val SIDE_ART_OF_HEIGHT = 0.55f

    /** ...and never more than this share of the room's width, so the words keep the rest. */
    const val SIDE_ART_OF_ROOM_WIDTH = 0.45f

    /** The art over the words: this share of the window's width. */
    const val TOP_ART_OF_WIDTH = 0.45f

    /** ...and never more than this share of the room's height, so the words fit under it. */
    const val TOP_ART_OF_ROOM_HEIGHT = 0.4f

    /** Between the art and the words beside it: a tenth of the art, within these bounds. */
    val SIDE_GAP_MIN: Dp = 32.dp
    val SIDE_GAP_MAX: Dp = 64.dp

    /** The art at the left of the words: a wide frame ([twoPane]) wider than it is tall. */
    fun sideBySide(twoPane: Boolean, window: DpSize): Boolean = twoPane && window.width > window.height

    /**
     * The art's side, for the [window] and the [room] the art and the words share (the window
     * inside its margins, the byline's band and the live dot's).
     */
    fun artSide(sideBySide: Boolean, window: DpSize, room: DpSize): Dp =
        if (sideBySide) {
            min(min(window.height * SIDE_ART_OF_HEIGHT, room.height), room.width * SIDE_ART_OF_ROOM_WIDTH)
        } else {
            min(min(window.width * TOP_ART_OF_WIDTH, room.width), room.height * TOP_ART_OF_ROOM_HEIGHT)
        }

    /** Between the art and the words beside it. */
    fun sideGap(art: Dp): Dp = (art * 0.1f).coerceIn(SIDE_GAP_MIN, SIDE_GAP_MAX)

    /** The words' width: beside the art, what the room leaves ([roomWidth] less the art and the gap); under it, the room; at most [ReadingWidth]. */
    fun wordsWidth(sideBySide: Boolean, roomWidth: Dp, art: Dp): Dp {
        val left = if (sideBySide) roomWidth - art - sideGap(art) else roomWidth
        return left.coerceIn(0.dp, ReadingWidth)
    }
}
