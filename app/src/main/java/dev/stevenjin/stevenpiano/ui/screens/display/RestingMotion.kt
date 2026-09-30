// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.ui.screens.display

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import dev.stevenjin.stevenpiano.ui.theme.Motion

/**
 * The resting screen's motion (DESIGN.md › v1.7.1): it comes over the app slowly, a cross-fade of
 * [ENTER_MS]; a touch sends it away in [LEAVE_MS], the app beneath live at once; while it rests, a
 * new piece takes the old one's place in a cross-fade of [PIECE_MS], the art and the words
 * together. The app's standard easing throughout; under reduced motion (Android's "Remove
 * animations") every one of them is a cut.
 */
object RestingMotion {
    /** From the app to the resting screen. */
    const val ENTER_MS = 1_500

    /** From the resting screen back to the app, on a touch. */
    const val LEAVE_MS = 600

    /** From one piece to the next while resting. */
    const val PIECE_MS = 1_200

    fun enter(reduced: Boolean): EnterTransition = if (reduced) EnterTransition.None else fadeIn(tween(ENTER_MS, easing = Motion.Standard))

    fun leave(reduced: Boolean): ExitTransition = if (reduced) ExitTransition.None else fadeOut(tween(LEAVE_MS, easing = Motion.Standard))

    /** The piece's art and words, old and new at once. */
    fun pieceChange(reduced: Boolean): ContentTransform =
        if (reduced) {
            EnterTransition.None togetherWith ExitTransition.None
        } else {
            fadeIn(tween(PIECE_MS, easing = Motion.Standard)) togetherWith fadeOut(tween(PIECE_MS, easing = Motion.Standard))
        }
}
