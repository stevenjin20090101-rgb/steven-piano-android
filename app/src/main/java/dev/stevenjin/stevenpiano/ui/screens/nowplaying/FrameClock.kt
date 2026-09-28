// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.ui.screens.nowplaying

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.LongState
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import dev.stevenjin.stevenpiano.ui.theme.rememberReducedMotion

/** After a change while paused (a seek), frames keep coming this long so the picture catches up. */
private const val SETTLE_NANOS = 400_000_000L

/**
 * The frame time the canvases, keyboard and scrubber draw at: every frame while playing (the
 * first one starts the roll's ease-in), and a short burst after anything that moves a paused
 * piece ([settle]), so the picture catches up with the scheduler thread.
 */
@Composable
internal fun rememberFrameNanos(playing: Boolean, pieceId: Long, settle: Int, roll: RollClock): LongState {
    val frame = remember { mutableLongStateOf(System.nanoTime()) }
    val reduced = rememberReducedMotion()
    LaunchedEffect(playing, pieceId, settle, reduced) {
        if (playing) {
            var first = true
            while (true) {
                withFrameNanos { t ->
                    if (first && !reduced) roll.easeIn(t)
                    first = false
                    frame.longValue = t
                }
            }
        } else {
            roll.cut()
            val until = System.nanoTime() + SETTLE_NANOS
            do {
                val t = withFrameNanos { it }
                frame.longValue = t
            } while (t < until)
        }
    }
    return frame
}
