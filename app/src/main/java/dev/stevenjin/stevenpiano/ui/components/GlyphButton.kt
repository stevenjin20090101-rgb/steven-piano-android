// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.stevenjin.stevenpiano.ui.theme.LocalDisabledGlyph
import dev.stevenjin.stevenpiano.ui.theme.Motion
import dev.stevenjin.stevenpiano.ui.theme.rememberReducedMotion
import kotlinx.coroutines.delay

/** Held down, a repeating button waits this long, then repeats this often. */
private const val REPEAT_AFTER_MS = 400L
private const val REPEAT_EVERY_MS = 60L

/**
 * An icon-only control: at least a 48 dp target, a monochrome glyph, and always a
 * [contentDescription]. Disabled glyphs use the disabled-glyph token. With [repeatWhileHeld]
 * (a stepper's − and + over a wide range), holding it down repeats [onClick] until it is let go
 * or disabled; the release after a repeat is not one more click. Pressed, it scales to 0.97
 * ([pressScale], v1.14 — motion); with [crossfade] (the mini player's play and pause) a new glyph
 * cross-fades in over 200 ms with a small scale, a cut when motion is reduced.
 */
@Composable
fun GlyphButton(
    @DrawableRes glyph: Int,
    contentDescription: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    glyphSize: Dp = 24.dp,
    repeatWhileHeld: Boolean = false,
    crossfade: Boolean = false,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val click by rememberUpdatedState(onClick)
    val repeat = remember { RepeatState() }
    if (repeatWhileHeld) {
        val pressed by interaction.collectIsPressedAsState()
        LaunchedEffect(pressed, enabled) {
            if (!pressed) return@LaunchedEffect
            repeat.repeated = false
            if (!enabled) return@LaunchedEffect
            delay(REPEAT_AFTER_MS)
            repeat.repeated = true
            while (true) {
                click()
                delay(REPEAT_EVERY_MS)
            }
        }
    }
    IconButton(
        onClick = {
            if (repeat.repeated) repeat.repeated = false else onClick()
        },
        modifier = modifier.pressScale(interaction),
        enabled = enabled,
        colors = IconButtonDefaults.iconButtonColors(
            contentColor = MaterialTheme.colorScheme.onSurface,
            disabledContentColor = LocalDisabledGlyph.current,
        ),
        interactionSource = interaction,
    ) {
        if (!crossfade) {
            Icon(painterResource(glyph), contentDescription = contentDescription, modifier = Modifier.size(glyphSize))
            return@IconButton
        }
        val reduced = rememberReducedMotion()
        AnimatedContent(
            targetState = glyph,
            modifier = Modifier.semantics { this.contentDescription = contentDescription },
            transitionSpec = {
                Motion.change(
                    fadeIn(tween(Motion.StandardMs, easing = Motion.Enter)) + scaleIn(tween(Motion.StandardMs, easing = Motion.Enter), initialScale = GLYPH_FROM),
                    fadeOut(tween(Motion.StandardMs, easing = Motion.Leave)) + scaleOut(tween(Motion.StandardMs, easing = Motion.Leave), targetScale = GLYPH_FROM),
                    reduced,
                ) using null
            },
            label = "glyph",
        ) { shown ->
            Icon(painterResource(shown), contentDescription = null, modifier = Modifier.size(glyphSize))
        }
    }
}

/** How small a cross-fading glyph is as it changes places with the next. */
private const val GLYPH_FROM = 0.85f

/** Whether the press now ending has already repeated, so its release is not a click too. */
private class RepeatState {
    var repeated = false
}
