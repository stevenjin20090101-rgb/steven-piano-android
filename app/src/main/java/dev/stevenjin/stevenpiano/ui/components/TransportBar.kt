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
import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.stevenjin.stevenpiano.R
import dev.stevenjin.stevenpiano.player.RepeatMode
import dev.stevenjin.stevenpiano.ui.theme.LocalTertiary
import dev.stevenjin.stevenpiano.ui.theme.Motion
import dev.stevenjin.stevenpiano.ui.theme.rememberReducedMotion

/** Shuffle 48 + previous 56 + play 72 + next 56 + repeat 48. */
private val CONTROLS_WIDTH = 280.dp
private val MIN_GAP = 8.dp
private val MAX_GAP = 32.dp
private val SIDE_ROOM = 16.dp

/** The narrowest the bar fits in with its smallest gaps and room at both ends: what a pane must give it (v1.12 — M31a). */
internal val TransportMinWidth = CONTROLS_WIDTH + MIN_GAP * 4 + SIDE_ROOM * 2

/**
 * shuffle · previous · play/pause · next · repeat. Play/pause is the 72 dp circle in the content
 * colour with a surface glyph; its glyph cross-fades with a small scale over 200 ms (v1.14; a cut when motion is reduced) and
 * it gives the app's one haptic, a light tick, on play and on pause. Shuffle and Repeat sit at the
 * two ends: the tertiary grey when off, the content colour with a 4 dp dot beneath when on; Repeat
 * cycles off, all, one (a small "1" in its glyph). The gaps shrink to fit a narrow phone. On glass
 * ([LocalOnGlass], DESIGN.md › v1.5 — M16) Shuffle and Repeat when off take the secondary grey
 * (nothing tertiary sits on glass); play/pause stays the filled circle, the primary action sitting on
 * the glass band, never glass on glass (DESIGN.md › v1.9).
 */
@Composable
fun TransportBar(
    playing: Boolean,
    hasNext: Boolean,
    shuffle: Boolean,
    repeat: RepeatMode,
    onShuffle: () -> Unit,
    onRepeat: () -> Unit,
    onPrevious: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier) {
        val gap = ((maxWidth - CONTROLS_WIDTH - SIDE_ROOM * 2) / 4).coerceIn(MIN_GAP, MAX_GAP)
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(gap, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ModeToggle(R.drawable.ic_shuffle, on = shuffle, description = if (shuffle) "Shuffle on" else "Shuffle off", switch = true, onClick = onShuffle)
            GlyphButton(R.drawable.ic_skip_previous, "Previous", Modifier.size(56.dp), glyphSize = 32.dp, onClick = onPrevious)
            PlayPauseButton(playing, onPlayPause)
            GlyphButton(R.drawable.ic_skip_next, "Next", Modifier.size(56.dp), enabled = hasNext, glyphSize = 32.dp, onClick = onNext)
            ModeToggle(
                if (repeat == RepeatMode.ONE) R.drawable.ic_repeat_one else R.drawable.ic_repeat,
                on = repeat != RepeatMode.OFF,
                description = when (repeat) {
                    RepeatMode.OFF -> "Repeat off"
                    RepeatMode.ALL -> "Repeat all"
                    RepeatMode.ONE -> "Repeat one"
                },
                switch = false,
                onClick = onRepeat,
            )
        }
    }
}

/**
 * Shuffle or Repeat: a 48 dp target, its glyph tertiary when off and in the content colour with a
 * 4 dp dot beneath when on. The description carries the state ("Shuffle on"); Shuffle is a switch
 * to accessibility services, Repeat a button that cycles.
 */
@Composable
private fun ModeToggle(@DrawableRes glyph: Int, on: Boolean, description: String, switch: Boolean, onClick: () -> Unit) {
    val ink = MaterialTheme.colorScheme.onSurface
    val off = if (LocalOnGlass.current) MaterialTheme.colorScheme.onSurfaceVariant else LocalTertiary.current
    val press = remember { MutableInteractionSource() }
    val ripple = LocalIndication.current
    val action = if (switch) {
        Modifier.toggleable(value = on, interactionSource = press, indication = ripple, role = Role.Switch, onValueChange = { onClick() })
    } else {
        Modifier.clickable(interactionSource = press, indication = ripple, role = Role.Button, onClick = onClick)
    }
    Box(
        Modifier
            .size(48.dp)
            .pressScale(press)
            .clip(CircleShape)
            .then(action)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        // A change of mode (on, off; repeat all, repeat one) cross-fades over 200 ms (v1.14 — motion), a cut when reduced.
        Crossfade(targetState = glyph to on, animationSpec = Motion.timed(Motion.StandardMs, rememberReducedMotion()), label = "mode") { (shown, lit) ->
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Icon(painterResource(shown), contentDescription = null, tint = if (lit) ink else off, modifier = Modifier.size(24.dp))
                if (lit) {
                    Box(
                        Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 4.dp)
                            .size(4.dp)
                            .background(ink, CircleShape),
                    )
                }
            }
        }
    }
}

@Composable
private fun PlayPauseButton(playing: Boolean, onClick: () -> Unit) {
    val view = LocalView.current
    val press = {
        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        onClick()
    }
    val description = if (playing) "Pause" else "Play"
    val touch = remember { MutableInteractionSource() }
    // The filled circle wherever it stands, on the glass band or solid (DESIGN.md › v1.9).
    Surface(
        onClick = press,
        modifier = Modifier
            .size(PLAY_SIZE)
            .pressScale(touch)
            .semantics { contentDescription = description },
        shape = CircleShape,
        color = MaterialTheme.colorScheme.onSurface,
        contentColor = MaterialTheme.colorScheme.surface,
        interactionSource = touch,
    ) {
        PlayPauseGlyph(playing, MaterialTheme.colorScheme.surface)
    }
}

/**
 * The play or pause glyph, 32 dp: the new one fades in growing from 0.85 as the old fades out shrinking, over 200 ms
 * (v1.14 — motion; a cut when motion is reduced).
 */
@Composable
private fun PlayPauseGlyph(playing: Boolean, tint: androidx.compose.ui.graphics.Color) {
    val reduced = rememberReducedMotion()
    AnimatedContent(
        targetState = playing,
        transitionSpec = {
            Motion.change(
                fadeIn(tween(Motion.StandardMs, easing = Motion.Enter)) + scaleIn(tween(Motion.StandardMs, easing = Motion.Enter), initialScale = GLYPH_FROM),
                fadeOut(tween(Motion.StandardMs, easing = Motion.Leave)) + scaleOut(tween(Motion.StandardMs, easing = Motion.Leave), targetScale = GLYPH_FROM),
                reduced,
            ) using null
        },
        label = "play-pause",
    ) { showPause ->
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Icon(
                painterResource(if (showPause) R.drawable.ic_pause else R.drawable.ic_play),
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(32.dp),
            )
        }
    }
}

private val PLAY_SIZE = 72.dp

/** How small the play and pause glyphs are as they change places. */
private const val GLYPH_FROM = 0.85f
