// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LongState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.stevenjin.stevenpiano.midi.NoteList
import dev.stevenjin.stevenpiano.settings.NoteDisplay

/** The strip's roll travels slower than Now playing's, so its short window still shows what is coming. */
const val ROLL_STRIP_DP_PER_SECOND = 48f

/** The strip's roll: 120 dp at least (2.5 s of music at the strip's pace, the last 0.8 s below the tracker bar). */
val RollStripCanvasHeight: Dp = 120.dp

/** The whole strip at its smallest: the roll, the hairline and the keyboard strip. */
val RollStripHeight: Dp = RollStripCanvasHeight + Hairline + KeyboardStripHeight

/**
 * The now-playing panel's live roll (DESIGN.md › v1.5 — M16): the paper roll at
 * [ROLL_STRIP_DP_PER_SECOND] over its keyboard strip, lane for key; no hands, fingering or chord
 * names (a glance, not a study; the Now playing tab has them). The roll takes the height [modifier]
 * gives beyond [RollStripCanvasHeight]; like Now playing's, it is content: it moves under reduced
 * motion too. Over the cover's backdrop ([LocalImmersive], v1.18 — M49) the roll's light line is where its notes land,
 * and no hairline stands between it and the keys.
 */
@Composable
fun RollStrip(
    notes: NoteList,
    transpose: Int,
    fold: Boolean,
    frameNanos: LongState,
    clock: SongClock,
    activeLow: () -> Long,
    activeHigh: () -> Long,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        NoteCanvas(
            notes, transpose, fold, NoteDisplay.PAPER_ROLL, frameNanos, clock,
            Modifier
                .weight(1f)
                .heightIn(min = RollStripCanvasHeight)
                .fillMaxWidth(),
            dpPerSecond = ROLL_STRIP_DP_PER_SECOND,
        )
        if (!LocalImmersive.current) HairlineDivider()
        KeyboardStrip(frameNanos, activeLow, activeHigh)
    }
}
