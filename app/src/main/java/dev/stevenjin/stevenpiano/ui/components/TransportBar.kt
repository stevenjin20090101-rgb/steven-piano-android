// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.ui.components

import android.view.HapticFeedbackConstants
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.stevenjin.stevenpiano.R
import dev.stevenjin.stevenpiano.ui.theme.Motion
import dev.stevenjin.stevenpiano.ui.theme.rememberReducedMotion

/**
 * previous · play/pause · next. Play/pause is the 72 dp circle in the content colour with a
 * surface glyph; its glyph crossfades over 320 ms (a cut when motion is reduced) and it gives
 * the app's one haptic, a light tick, on play and on pause.
 */
@Composable
fun TransportBar(
    playing: Boolean,
    hasNext: Boolean,
    onPrevious: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier,
        horizontalArrangement = Arrangement.spacedBy(32.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GlyphButton(R.drawable.ic_skip_previous, "Previous", Modifier.size(56.dp), glyphSize = 32.dp, onClick = onPrevious)
        PlayPauseButton(playing, onPlayPause)
        GlyphButton(R.drawable.ic_skip_next, "Next", Modifier.size(56.dp), enabled = hasNext, glyphSize = 32.dp, onClick = onNext)
    }
}

@Composable
private fun PlayPauseButton(playing: Boolean, onClick: () -> Unit) {
    val view = LocalView.current
    val reduced = rememberReducedMotion()
    Surface(
        onClick = {
            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            onClick()
        },
        modifier = Modifier
            .size(72.dp)
            .semantics { contentDescription = if (playing) "Pause" else "Play" },
        shape = CircleShape,
        color = MaterialTheme.colorScheme.onSurface,
        contentColor = MaterialTheme.colorScheme.surface,
    ) {
        Crossfade(
            targetState = playing,
            animationSpec = if (reduced) snap() else tween(Motion.RollStartMs, easing = Motion.EaseOut),
            label = "play-pause",
        ) { showPause ->
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Icon(
                    painterResource(if (showPause) R.drawable.ic_pause else R.drawable.ic_play),
                    contentDescription = null,
                    modifier = Modifier.size(32.dp),
                )
            }
        }
    }
}
