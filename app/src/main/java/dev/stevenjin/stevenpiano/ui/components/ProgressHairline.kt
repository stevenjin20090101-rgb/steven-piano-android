// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import dev.stevenjin.stevenpiano.ui.theme.LocalHairline
import dev.stevenjin.stevenpiano.ui.theme.Motion
import dev.stevenjin.stevenpiano.ui.theme.rememberReducedMotion

private const val SWEEP_MS = 1_400
private const val SEGMENT = 0.3f

/**
 * Progress as a hairline, never a spinner. [progress] runs 0..1, and the line eases to each new value
 * over 200 ms (v1.14 — motion; it jumps when motion is reduced); null means indeterminate: a short
 * segment sweeps across, and holds still when motion is reduced (the copy beside it says what is
 * happening).
 */
@Composable
fun ProgressHairline(progress: Float?, modifier: Modifier = Modifier) {
    val track = LocalHairline.current
    val ink = MaterialTheme.colorScheme.onSurface
    val reduced = rememberReducedMotion()
    // From the first value it shows, not from nothing: a line that becomes determinate starts where it is.
    val eased = if (progress != null) {
        animateFloatAsState(progress.coerceIn(0f, 1f), Motion.timed(Motion.StandardMs, reduced), label = "progress")
    } else {
        null
    }
    val sweep = if (progress == null && !reduced) {
        rememberInfiniteTransition(label = "hairline")
            .animateFloat(0f, 1f, infiniteRepeatable(tween(SWEEP_MS, easing = LinearEasing)), label = "sweep")
    } else {
        null
    }
    Spacer(
        modifier
            .fillMaxWidth()
            .height(Hairline)
            .semantics {
                progressBarRangeInfo = if (progress == null) ProgressBarRangeInfo.Indeterminate else ProgressBarRangeInfo(progress, 0f..1f)
            }
            .drawBehind {
                drawRect(track)
                if (eased != null) {
                    drawRect(ink, size = Size(size.width * eased.value, size.height))
                } else if (sweep != null) {
                    val width = size.width * SEGMENT
                    drawRect(ink, topLeft = Offset((size.width + width) * sweep.value - width, 0f), size = Size(width, size.height))
                }
            },
    )
}
